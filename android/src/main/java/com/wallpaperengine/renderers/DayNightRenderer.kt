package com.wallpaperengine.renderers

import android.content.Context
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.SurfaceHolder
import com.wallpaperengine.assets.BitmapUtils
import com.wallpaperengine.config.DayNightSchedule
import com.wallpaperengine.gl.EglCore
import com.wallpaperengine.gl.STATIC_TEXTURE_FRAGMENT_SHADER
import com.wallpaperengine.gl.STATIC_TEXTURE_VERTEX_SHADER
import com.wallpaperengine.gl.buildProgram
import com.wallpaperengine.gl.floatBufferOf
import com.wallpaperengine.gl.queryMaxTextureSize
import com.wallpaperengine.gl.uploadBitmapAsTexture
import org.json.JSONObject
import java.nio.FloatBuffer

private const val CHECK_INTERVAL_MS = 60_000L
private const val CROSSFADE_DURATION_MS = 1200L
private const val CROSSFADE_STEP_MS = 32L

/**
 * `daynight`: two bitmaps crossfaded when crossing the local-time cutoff (see
 * `DayNightSchedule`). Always centered cover-crop. Draws on-demand via GL on its own thread
 * (never Canvas — see StaticTextureQuad.kt).
 */
class DayNightRenderer(private val context: Context) : WallpaperRenderer {
    // Written on the main thread, read on the render thread.
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var width = 0
    @Volatile private var height = 0

    // Render-thread only.
    private var dayTextureId = 0
    private var nightTextureId = 0
    private var isCurrentlyDay = true
    /** 0 = no crossfade in progress. */
    private var crossfadeFromTextureId = 0
    private var crossfadeProgress = 1f

    private var dayImagePath: String? = null
    private var nightImagePath: String? = null

    private var egl: EglCore? = null
    private var programId = 0
    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTextureLoc = -1
    private var uAlphaLoc = -1
    private var maxTextureSize = 0

    private val renderThread = HandlerThread("WallpaperEngine-DayNight").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    private val fullscreenQuad: FloatBuffer = floatBufferOf(
        floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
    )

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkDayNightSwitch()
            renderHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    private val crossfadeRunnable = object : Runnable {
        override fun run() {
            crossfadeProgress = (crossfadeProgress + CROSSFADE_STEP_MS.toFloat() / CROSSFADE_DURATION_MS).coerceAtMost(1f)
            drawFrame()
            if (crossfadeProgress < 1f) {
                renderHandler.postDelayed(this, CROSSFADE_STEP_MS)
            } else {
                crossfadeFromTextureId = 0
            }
        }
    }

    override fun onSurfaceCreated(holder: SurfaceHolder, width: Int, height: Int) {
        this.holder = holder
        this.width = width
        this.height = height
        renderHandler.post {
            ensureGlInitialized(holder)
            decodeTexturesIfNeeded()
            drawFrame()
        }
    }

    private fun ensureGlInitialized(surfaceHolder: SurfaceHolder) {
        if (egl != null) return
        val core = EglCore()
        if (!core.init(surfaceHolder)) {
            return
        }
        val program = buildProgram(STATIC_TEXTURE_VERTEX_SHADER, STATIC_TEXTURE_FRAGMENT_SHADER)
        if (program == 0) {
            core.release()
            return
        }
        egl = core
        programId = program
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uAlphaLoc = GLES20.glGetUniformLocation(program, "uAlpha")
        maxTextureSize = queryMaxTextureSize()
    }

    override fun onConfigUpdated(config: JSONObject) {
        val newDayPath = config.optString("dayImagePath").ifEmpty { null }
        val newNightPath = config.optString("nightImagePath").ifEmpty { null }
        if (newDayPath == dayImagePath && newNightPath == nightImagePath) {
            return
        }
        dayImagePath = newDayPath
        nightImagePath = newNightPath
        renderHandler.post {
            decodeTexturesIfNeeded(force = true)
            drawFrame()
        }
    }

    private fun decodeTexturesIfNeeded(force: Boolean = false) {
        val core = egl
        if (core == null) {
            return
        }
        if (width == 0 || height == 0) {
            return
        }
        if (!force && dayTextureId != 0 && nightTextureId != 0) return
        if (!core.makeCurrent()) {
            return
        }
        deleteDayNightTextures()
        val dayBitmap = dayImagePath?.let { BitmapUtils.decodeCoverBitmap(it, width, height) }
        val nightBitmap = nightImagePath?.let { BitmapUtils.decodeCoverBitmap(it, width, height) }
        dayTextureId = dayBitmap?.let { uploadBitmapAsTexture(it, maxTextureSize) } ?: 0
        nightTextureId = nightBitmap?.let { uploadBitmapAsTexture(it, maxTextureSize) } ?: 0
        isCurrentlyDay = DayNightSchedule.isDaytimeNow()
    }

    private fun deleteDayNightTextures() {
        val ids = buildList {
            if (dayTextureId != 0) add(dayTextureId)
            if (nightTextureId != 0) add(nightTextureId)
        }
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids.toIntArray(), 0)
        dayTextureId = 0
        nightTextureId = 0
    }

    private fun checkDayNightSwitch() {
        val shouldBeDay = DayNightSchedule.isDaytimeNow()
        if (shouldBeDay != isCurrentlyDay) {
            crossfadeFromTextureId = currentTargetTextureId()
            isCurrentlyDay = shouldBeDay
            crossfadeProgress = 0f
            renderHandler.removeCallbacks(crossfadeRunnable)
            renderHandler.post(crossfadeRunnable)
        }
    }

    private fun currentTargetTextureId(): Int = if (isCurrentlyDay) dayTextureId else nightTextureId

    private fun drawFrame() {
        val core = egl ?: return
        val surfaceHolder = holder ?: return
        if (!surfaceHolder.surface.isValid) return

        if (!core.makeCurrent()) {
            return
        }

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(programId)

        val targetId = currentTargetTextureId()
        val fromId = crossfadeFromTextureId

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        // Draw order matches the original Canvas/Paint.alpha crossfade: "from" at 1-progress,
        // then "target" on top at progress.
        if (fromId != 0 && crossfadeProgress < 1f) {
            drawTexturedQuad(fullscreenQuad, fromId, 1f - crossfadeProgress)
            if (targetId != 0) drawTexturedQuad(fullscreenQuad, targetId, crossfadeProgress)
        } else if (targetId != 0) {
            drawTexturedQuad(fullscreenQuad, targetId, 1f)
        }
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        core.swapBuffers()
    }

    private fun drawTexturedQuad(vertexBuffer: FloatBuffer, textureId: Int, alpha: Float) {
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniform1f(uAlphaLoc, alpha)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun onVisibilityChanged(visible: Boolean) {
        renderHandler.removeCallbacks(checkRunnable)
        if (visible) {
            renderHandler.post {
                decodeTexturesIfNeeded()
                drawFrame()
            }
            renderHandler.post(checkRunnable)
        }
    }

    override fun onSurfaceDestroyed() {
        renderHandler.removeCallbacksAndMessages(null)
        // Posted to the render thread so an in-flight drawFrame finishes before teardown.
        renderHandler.post { releaseGl() }
    }

    override fun onDestroy() {
        renderHandler.removeCallbacksAndMessages(null)
        // Posted to the render thread so an in-flight drawFrame finishes before resources are
        // torn down; quitSafely runs after so the queued release still executes.
        renderHandler.post {
            releaseGl()
        }
        renderThread.quitSafely()
    }

    private fun releaseGl() {
        deleteDayNightTextures()
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        egl?.release()
        egl = null
    }
}
