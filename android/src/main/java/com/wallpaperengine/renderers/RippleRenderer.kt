package com.wallpaperengine.renderers

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperengine.assets.BitmapUtils
import com.wallpaperengine.gl.EglCore
import com.wallpaperengine.gl.OesVideoDecoder
import com.wallpaperengine.gl.buildProgram
import com.wallpaperengine.gl.buildQuadBuffer
import com.wallpaperengine.gl.component1
import com.wallpaperengine.gl.component2
import com.wallpaperengine.gl.component3
import com.wallpaperengine.gl.component4
import com.wallpaperengine.gl.computeCoverCropRect
import com.wallpaperengine.gl.floatBufferOf
import com.wallpaperengine.gl.queryMaxTextureSize
import com.wallpaperengine.gl.uploadBitmapAsTexture
import org.json.JSONObject
import java.nio.FloatBuffer

private const val MAX_RIPPLES = 4
private const val FRAME_INTERVAL_MS = 16L
/** Image mode idle-poll interval while no ripple is active: the base image never changes on
 * its own, so there's no need to redraw/swapBuffers at 60fps with nothing animating.
 * `addRipple` interrupts the thread to wake it instantly on touch instead of waiting out this
 * interval. Video mode doesn't need this — a new decoded frame is already the redraw trigger. */
private const val IDLE_POLL_INTERVAL_MS = 200L
private const val RIPPLE_LIFETIME_SECONDS = 1.5f

// ---- Image mode: shader over a plain 2D texture (static bitmap uploaded once) ----
private const val IMAGE_VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
"""

private const val IMAGE_FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uRippleCenters[$MAX_RIPPLES];
uniform float uRippleTimes[$MAX_RIPPLES];
uniform int uRippleCount;
varying vec2 vTexCoord;

void main() {
    vec2 uv = vTexCoord;
    for (int i = 0; i < $MAX_RIPPLES; i++) {
        if (i >= uRippleCount) break;
        float t = uRippleTimes[i];
        if (t < 0.0 || t > $RIPPLE_LIFETIME_SECONDS) continue;
        vec2 center = uRippleCenters[i];
        vec2 delta = uv - center;
        float dist = length(delta);
        float radius = t * 0.6;
        float ringWidth = 0.12;
        float ring = smoothstep(radius - ringWidth, radius, dist) - smoothstep(radius, radius + ringWidth, dist);
        float amplitude = ring * (1.0 - t / $RIPPLE_LIFETIME_SECONDS) * 0.025;
        vec2 dir = delta / max(dist, 0.0001);
        uv += dir * amplitude;
    }
    gl_FragColor = texture2D(uTexture, uv);
}
"""

// ---- Video mode: same external OES texture + uSTMatrix as OesVideoDecoder/VideoRenderer, with
// the same ripple distortion applied to the already-transformed vTexCoord. ----
private const val VIDEO_VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uSTMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

private const val VIDEO_FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform vec2 uRippleCenters[$MAX_RIPPLES];
uniform float uRippleTimes[$MAX_RIPPLES];
uniform int uRippleCount;
varying vec2 vTexCoord;

void main() {
    vec2 uv = vTexCoord;
    for (int i = 0; i < $MAX_RIPPLES; i++) {
        if (i >= uRippleCount) break;
        float t = uRippleTimes[i];
        if (t < 0.0 || t > $RIPPLE_LIFETIME_SECONDS) continue;
        vec2 center = uRippleCenters[i];
        vec2 delta = uv - center;
        float dist = length(delta);
        float radius = t * 0.6;
        float ringWidth = 0.12;
        float ring = smoothstep(radius - ringWidth, radius, dist) - smoothstep(radius, radius + ringWidth, dist);
        float amplitude = ring * (1.0 - t / $RIPPLE_LIFETIME_SECONDS) * 0.025;
        vec2 dir = delta / max(dist, 0.0001);
        uv += dir * amplitude;
    }
    gl_FragColor = texture2D(uTexture, uv);
}
"""

/** Active ripple centers/times, shared between `RippleRenderer`'s two modes (image: written on
 * the main thread in `onTouchEvent`, aged/read on its own `GLRenderThread`; video: everything
 * runs on the main thread, but the lock is kept anyway — negligible cost uncontended). */
private class RippleState {
    private val lock = Object()
    private val centers = FloatArray(MAX_RIPPLES * 2)
    private val startTimes = FloatArray(MAX_RIPPLES) { -1f }
    private var nextSlot = 0

    fun add(u: Float, v: Float) {
        synchronized(lock) {
            val slot = nextSlot
            centers[slot * 2] = u
            centers[slot * 2 + 1] = v
            startTimes[slot] = 0f
            nextSlot = (nextSlot + 1) % MAX_RIPPLES
        }
    }

    fun hasActive(): Boolean = synchronized(lock) { startTimes.any { it >= 0f } }

    fun age(dt: Float) {
        synchronized(lock) {
            for (i in 0 until MAX_RIPPLES) {
                if (startTimes[i] < 0f) continue
                startTimes[i] += dt
                if (startTimes[i] > RIPPLE_LIFETIME_SECONDS) startTimes[i] = -1f
            }
        }
    }

    fun uploadUniforms(centersLoc: Int, timesLoc: Int, countLoc: Int) {
        synchronized(lock) {
            GLES20.glUniform2fv(centersLoc, MAX_RIPPLES, centers, 0)
            GLES20.glUniform1fv(timesLoc, MAX_RIPPLES, startTimes, 0)
            GLES20.glUniform1i(countLoc, MAX_RIPPLES)
        }
    }
}

/**
 * `ripple`: GLES2 water-ripple shader driven by `onTouchEvent`. Two modes, chosen in
 * `onConfigUpdated` by which field the config carries (video wins if both arrive):
 * - **Image** (`imagePath`): static 2D texture uploaded once, on its own `GLRenderThread` that
 *   only redraws when a ripple is active or the bitmap changes (idle-poll).
 * - **Video** (`videoPath`): same pipeline as `VideoRenderer.kt` (`OesVideoDecoder`, main
 *   thread) with the ripple fragment shader instead of a passthrough.
 */
class RippleRenderer(private val context: Context) : WallpaperRenderer {
    private var imagePath: String? = null
    private var videoPath: String? = null
    private var width = 0
    private var height = 0
    private var holder: SurfaceHolder? = null
    private var isVisible = false

    private val rippleState = RippleState()

    // ---- Image mode ----
    private var glThread: GLRenderThread? = null

    // ---- Video mode ----
    private var videoEgl: EglCore? = null
    private var videoProgramId = 0
    private var videoDecoder: OesVideoDecoder? = null
    /** Current cover-crop UV rect (u0, v0, u1, v1) — reused in `onTouchEvent` to map the touch
     * to the same visible sub-rect. */
    private var videoCropRect = floatArrayOf(0f, 0f, 1f, 1f)
    private var videoVertexBuffer: FloatBuffer = buildQuadBuffer(videoCropRect)
    private var vAPositionLoc = -1
    private var vATexCoordLoc = -1
    private var vUTextureLoc = -1
    private var vUSTMatrixLoc = -1
    private var vURippleCentersLoc = -1
    private var vURippleTimesLoc = -1
    private var vURippleCountLoc = -1
    private var lastVideoFrameTime = SystemClock.elapsedRealtime()

    private fun useVideo(): Boolean = videoPath != null

    override fun onSurfaceCreated(holder: SurfaceHolder, width: Int, height: Int) {
        this.holder = holder
        this.width = width
        this.height = height
        if (useVideo()) {
            ensureVideoGlInitialized(holder)
            updateVideoVertexBuffer()
            startVideoPlaybackIfReady()
        } else {
            startImageThreadIfReady()
        }
    }

    override fun onConfigUpdated(config: JSONObject) {
        val newVideoPath = config.optString("videoPath").ifEmpty { null }
        val newImagePath = config.optString("imagePath").ifEmpty { null }

        if (newVideoPath == null && newImagePath == null) {
            return
        }

        // Priority: video (real motion) over image if both arrive.
        if (newVideoPath != null) {
            if (newVideoPath == videoPath) {
                return
            }
            stopImageMode()
            imagePath = null
            videoPath = newVideoPath
            val currentHolder = holder
            if (currentHolder != null) {
                ensureVideoGlInitialized(currentHolder)
                // startVideoPlaybackIfReady resets the known video size; call it before
                // updateVideoVertexBuffer so the buffer doesn't keep the previous video's crop.
                startVideoPlaybackIfReady()
                updateVideoVertexBuffer()
            }
            return
        }

        // Image only.
        stopVideoMode()
        videoPath = null
        if (newImagePath == imagePath && glThread != null) {
            return
        }
        imagePath = newImagePath
        val existingThread = glThread
        if (existingThread != null) {
            val bitmap = loadBitmap()
            if (bitmap != null) existingThread.setBitmap(bitmap)
        } else {
            startImageThreadIfReady()
        }
    }

    override fun onTouchEvent(event: MotionEvent) {
        if ((event.action != MotionEvent.ACTION_DOWN && event.action != MotionEvent.ACTION_MOVE) || width <= 0 || height <= 0) {
            return
        }
        val uScreen = (event.x / width).coerceIn(0f, 1f)
        val vScreen = (event.y / height).coerceIn(0f, 1f)

        if (useVideo()) {
            // Map the touch (screen space, top-left origin) to the same cover-crop sub-rect
            // currently on screen (videoCropRect) — otherwise the ripple center would drift
            // from the finger on any video whose aspect ratio doesn't match the screen's.
            val (u0, v0, u1, v1) = videoCropRect
            val cropU = u0 + uScreen * (u1 - u0)
            val cropV = v1 - vScreen * (v1 - v0)

            // The fragment shader compares ripples against vTexCoord, which is
            // `uSTMatrix * aTexCoord` (post-transform), not the pre-transform cover-crop space
            // above — so the touch point needs the same uSTMatrix applied.
            // `SurfaceTexture.getTransformMatrix()` isn't guaranteed to be identity (decoder
            // padding varies by device/vendor).
            val dec = videoDecoder
            val (u, v) = if (dec != null) transformByStMatrix(dec.stMatrix, cropU, cropV) else cropU to cropV
            rippleState.add(u, v)
        } else {
            // Image mode: the bitmap is already cover-cropped on upload, so screen and texture
            // are 1:1 — only the Y flip convention applies (see GLRenderThread.uploadTexture).
            val v = 1f - vScreen
            glThread?.addRipple(uScreen, v)
        }
    }

    /** Mirrors `vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy` from
     * VIDEO_VERTEX_SHADER on the CPU, so the ripple center lives in the same space the fragment
     * shader compares against. `matrix` is column-major (same layout `glUniformMatrix4fv`
     * expects), hence `m[0]/m[4]/m[12]` for the X row. */
    private fun transformByStMatrix(matrix: FloatArray, u: Float, v: Float): Pair<Float, Float> {
        val x = matrix[0] * u + matrix[4] * v + matrix[12]
        val y = matrix[1] * u + matrix[5] * v + matrix[13]
        return x to y
    }

    override fun onVisibilityChanged(visible: Boolean) {
        isVisible = visible
        glThread?.setPaused(!visible)
        videoDecoder?.setVisible(visible)
        if (visible && videoDecoder != null) {
            drawVideoFrame()
        }
    }

    override fun onSurfaceDestroyed() {
        stopImageMode()
        stopVideoMode()
    }

    override fun onDestroy() {
        stopImageMode()
        stopVideoMode()
        holder = null
    }

    // ==================== Image mode ====================

    private fun loadBitmap(): Bitmap? {
        val path = imagePath ?: return null
        if (width <= 0 || height <= 0) {
            return null
        }
        val bitmap = BitmapUtils.decodeCoverBitmap(path, width, height)
        return bitmap
    }

    /** Idempotent no-op if the thread is already running. Called from both `onSurfaceCreated`
     * and `onConfigUpdated` since their arrival order isn't guaranteed — the thread starts as
     * soon as whichever one arrives second is ready. */
    private fun startImageThreadIfReady() {
        if (glThread != null) return
        val currentHolder = holder
        if (currentHolder == null) {
            return
        }
        val bitmap = loadBitmap()
        if (bitmap == null) {
            return
        }
        glThread = GLRenderThread(currentHolder, width, height, rippleState).also {
            it.setBitmap(bitmap)
            it.setPaused(!isVisible)
            it.start()
        }
    }

    private fun stopImageMode() {
        glThread?.shutdown()
        glThread = null
    }

    // ==================== Video mode ====================

    private fun ensureVideoGlInitialized(holder: SurfaceHolder) {
        if (videoEgl != null) return
        val core = EglCore()
        if (!core.init(holder)) {
            return
        }
        val program = buildProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        if (program == 0) {
            core.release()
            return
        }
        videoEgl = core
        videoProgramId = program
        vAPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        vATexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        vUTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        vUSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
        vURippleCentersLoc = GLES20.glGetUniformLocation(program, "uRippleCenters")
        vURippleTimesLoc = GLES20.glGetUniformLocation(program, "uRippleTimes")
        vURippleCountLoc = GLES20.glGetUniformLocation(program, "uRippleCount")

        videoDecoder = OesVideoDecoder(
            onVideoSizeChanged = { updateVideoVertexBuffer() },
            onFrameAvailable = { drawVideoFrame() },
        )
    }

    private fun startVideoPlaybackIfReady() {
        videoDecoder?.startPlaybackIfReady(videoPath)
    }

    private fun updateVideoVertexBuffer() {
        val dec = videoDecoder
        videoCropRect = if (dec != null && dec.videoWidth > 0 && dec.videoHeight > 0 && width > 0 && height > 0) {
            computeCoverCropRect(dec.videoWidth, dec.videoHeight, width, height)
        } else {
            floatArrayOf(0f, 0f, 1f, 1f)
        }
        videoVertexBuffer = buildQuadBuffer(videoCropRect)
    }

    private fun drawVideoFrame() {
        val core = videoEgl ?: return
        val dec = videoDecoder ?: return
        if (!isVisible) return

        if (!core.makeCurrent()) {
            return
        }

        dec.updateTexImage()

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastVideoFrameTime) / 1000f).coerceIn(0f, 0.1f)
        lastVideoFrameTime = now
        rippleState.age(dt)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(videoProgramId)

        videoVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(vAPositionLoc)
        GLES20.glVertexAttribPointer(vAPositionLoc, 2, GLES20.GL_FLOAT, false, 16, videoVertexBuffer)
        videoVertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(vATexCoordLoc)
        GLES20.glVertexAttribPointer(vATexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, videoVertexBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, dec.oesTextureId)
        GLES20.glUniform1i(vUTextureLoc, 0)
        GLES20.glUniformMatrix4fv(vUSTMatrixLoc, 1, false, dec.stMatrix, 0)
        rippleState.uploadUniforms(vURippleCentersLoc, vURippleTimesLoc, vURippleCountLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(vAPositionLoc)
        GLES20.glDisableVertexAttribArray(vATexCoordLoc)
        core.swapBuffers()
    }

    private fun stopVideoMode() {
        videoEgl?.makeCurrent()
        videoDecoder?.release()
        videoDecoder = null
        if (videoProgramId != 0) {
            GLES20.glDeleteProgram(videoProgramId)
            videoProgramId = 0
        }
        videoEgl?.release()
        videoEgl = null
    }
}

/** Dedicated thread with its own EGL context, bound to the SurfaceHolder's Surface — image mode
 * only (video mode runs on the main thread, see `RippleRenderer.drawVideoFrame`). */
private class GLRenderThread(
    private val holder: SurfaceHolder,
    private val width: Int,
    private val height: Int,
    private val rippleState: RippleState,
) : Thread("WallpaperEngine-GL") {
    @Volatile private var running = true
    @Volatile private var paused = false
    @Volatile private var pendingBitmap: Bitmap? = null

    private var maxTextureSize = 0

    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap != null) pendingBitmap = bitmap
    }

    fun addRipple(u: Float, v: Float) {
        rippleState.add(u, v)
        // Wakes the thread instantly if it's sleeping in idle-poll mode.
        interrupt()
    }

    fun setPaused(value: Boolean) {
        paused = value
    }

    fun shutdown() {
        running = false
        interrupt()
        try {
            join(500)
        } catch (_: InterruptedException) {
        }
    }

    override fun run() {
        val egl = EglCore()
        if (!egl.init(holder)) {
            return
        }

        GLES20.glViewport(0, 0, width, height)
        val program = buildProgram(IMAGE_VERTEX_SHADER, IMAGE_FRAGMENT_SHADER)
        if (program == 0) {
            egl.release()
            return
        }
        GLES20.glUseProgram(program)

        val vertexBuffer = floatBufferOf(
            floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            )
        )
        val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uRippleCenters = GLES20.glGetUniformLocation(program, "uRippleCenters")
        val uRippleTimes = GLES20.glGetUniformLocation(program, "uRippleTimes")
        val uRippleCount = GLES20.glGetUniformLocation(program, "uRippleCount")

        maxTextureSize = queryMaxTextureSize()

        var textureId = 0
        var lastFrameTime = SystemClock.elapsedRealtime()
        // Starts true so the first frame (base image, no ripple yet) draws as soon as the
        // texture is ready.
        var needsDraw = true

        while (running) {
            val frameStart = SystemClock.elapsedRealtime()
            val dt = ((frameStart - lastFrameTime) / 1000f).coerceIn(0f, 0.1f)
            lastFrameTime = frameStart

            pendingBitmap?.let { bitmap ->
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                textureId = uploadBitmapAsTexture(bitmap, maxTextureSize)
                pendingBitmap = null
                needsDraw = true
            }

            val hasActiveRipple = rippleState.hasActive()

            if (!paused && textureId != 0 && (needsDraw || hasActiveRipple)) {
                rippleState.age(dt)

                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                GLES20.glEnableVertexAttribArray(aPosition)
                GLES20.glEnableVertexAttribArray(aTexCoord)
                vertexBuffer.position(0)
                GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
                vertexBuffer.position(2)
                GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLES20.glUniform1i(uTexture, 0)
                rippleState.uploadUniforms(uRippleCenters, uRippleTimes, uRippleCount)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GLES20.glDisableVertexAttribArray(aPosition)
                GLES20.glDisableVertexAttribArray(aTexCoord)

                egl.swapBuffers()
                needsDraw = false
            }

            val elapsed = SystemClock.elapsedRealtime() - frameStart
            val sleepTime = (if (hasActiveRipple) FRAME_INTERVAL_MS else IDLE_POLL_INTERVAL_MS) - elapsed
            if (sleepTime > 0) {
                try {
                    sleep(sleepTime)
                } catch (_: InterruptedException) {
                    // Woken by addRipple/setPaused — the loop reevaluates state next iteration.
                }
            }
        }

        if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        GLES20.glDeleteProgram(program)
        egl.release()
    }
}
