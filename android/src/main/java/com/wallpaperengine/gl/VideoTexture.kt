package com.wallpaperengine.gl

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * `MediaPlayer` -> external OES GL texture (`SurfaceTexture`) pipeline, shared by
 * `VideoRenderer` and `RippleRenderer`'s video mode — only the shader used to draw the decoded
 * frame differs. Requires a current EGL context (the constructor calls `glGenTextures`).
 */
internal class OesVideoDecoder(
    private val onVideoSizeChanged: () -> Unit,
    onFrameAvailable: () -> Unit,
) {
    val oesTextureId: Int = createOesTexture()
    private val surfaceTexture = SurfaceTexture(oesTextureId).apply {
        setOnFrameAvailableListener({ onFrameAvailable() }, Handler(Looper.getMainLooper()))
    }
    private val surface = Surface(surfaceTexture)
    val stMatrix = FloatArray(16)

    var videoWidth = 0
        private set
    var videoHeight = 0
        private set

    private var mediaPlayer: MediaPlayer? = null
    private var currentPath: String? = null
    private var isVisible = false

    /** No-op if a player is already running the same `path`. Starting a new one resets
     * `videoWidth`/`videoHeight` to 0 until the video reports its size — callers must rebuild
     * their vertex buffer after calling this. */
    fun startPlaybackIfReady(path: String?) {
        if (path == null) return
        if (mediaPlayer != null && currentPath == path) return
        releaseMediaPlayer()
        currentPath = path
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)
                setOnPreparedListener { prepared ->
                    if (prepared !== mediaPlayer) {
                        return@setOnPreparedListener
                    }
                    if (prepared.videoWidth > 0 && prepared.videoHeight > 0) {
                        this@OesVideoDecoder.videoWidth = prepared.videoWidth
                        this@OesVideoDecoder.videoHeight = prepared.videoHeight
                        onVideoSizeChanged()
                    }
                    try {
                        if (isVisible) prepared.start()
                    } catch (e: IllegalStateException) {
                    }
                }
                setOnVideoSizeChangedListener { _, w, h ->
                    if (w > 0 && h > 0 && (w != this@OesVideoDecoder.videoWidth || h != this@OesVideoDecoder.videoHeight)) {
                        this@OesVideoDecoder.videoWidth = w
                        this@OesVideoDecoder.videoHeight = h
                        onVideoSizeChanged()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
        }
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        val player = mediaPlayer ?: return
        try {
            if (visible) {
                player.start()
            } else if (player.isPlaying) {
                player.pause()
            }
        } catch (e: IllegalStateException) {
        }
    }

    /** Uploads the latest frame to the OES texture and refreshes `stMatrix` — call before each
     * draw. `SurfaceTexture` can throw `IllegalStateException` during visibility transitions,
     * and the service shares the app process, so catch and skip the frame instead of crashing. */
    fun updateTexImage() {
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
        } catch (e: IllegalStateException) {
        }
    }

    fun release() {
        releaseMediaPlayer()
        surface.release()
        surfaceTexture.release()
        if (oesTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: Exception) {
            }
            player.release()
        }
        mediaPlayer = null
        currentPath = null
        videoWidth = 0
        videoHeight = 0
    }
}

private fun createOesTexture(): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    return ids[0]
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    if (status[0] == 0) {
        GLES20.glDeleteShader(shader)
        return 0
    }
    return shader
}

private fun linkProgram(vertexShader: Int, fragmentShader: Int): Int {
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertexShader)
    GLES20.glAttachShader(program, fragmentShader)
    GLES20.glLinkProgram(program)

    // Shaders are no longer needed after linking: detach + flag for deletion with the program.
    GLES20.glDetachShader(program, vertexShader)
    GLES20.glDetachShader(program, fragmentShader)
    GLES20.glDeleteShader(vertexShader)
    GLES20.glDeleteShader(fragmentShader)

    val status = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
    if (status[0] == 0) {
        GLES20.glDeleteProgram(program)
        return 0
    }
    return program
}

/** Compiles and links both shaders — returns 0 on any failure, without leaking the shader
 * that did compile when the other one failed. */
internal fun buildProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    if (vertexShader == 0 || fragmentShader == 0) {
        if (vertexShader != 0) GLES20.glDeleteShader(vertexShader)
        if (fragmentShader != 0) GLES20.glDeleteShader(fragmentShader)
        return 0
    }
    return linkProgram(vertexShader, fragmentShader)
}

/** `(u0, v0, u1, v1)` UV sub-rect to sample for cover-crop. Always symmetric around the
 * center, which makes it invariant under any flip applied later by `SurfaceTexture`'s
 * transform matrix. */
internal fun computeCoverCropRect(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): FloatArray {
    val sourceRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
    val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()

    var u0 = 0f
    var u1 = 1f
    var v0 = 0f
    var v1 = 1f

    if (sourceRatio > targetRatio) {
        val visibleFraction = targetRatio / sourceRatio
        u0 = (1f - visibleFraction) / 2f
        u1 = u0 + visibleFraction
    } else if (sourceRatio < targetRatio) {
        val visibleFraction = sourceRatio / targetRatio
        v0 = (1f - visibleFraction) / 2f
        v1 = v0 + visibleFraction
    }

    return floatArrayOf(u0, v0, u1, v1)
}

/** Direct native-order `FloatBuffer` ready for `glVertexAttribPointer`. */
internal fun floatBufferOf(data: FloatArray): FloatBuffer =
    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(data)
        position(0)
    }

/** Fullscreen quad in clip space with interleaved `x,y,u,v` (16-byte stride), UVs taken from
 * `cropRect`. */
internal fun buildQuadBuffer(cropRect: FloatArray): FloatBuffer {
    val (u0, v0, u1, v1) = cropRect
    return floatBufferOf(
        floatArrayOf(
            -1f, -1f, u0, v0,
            1f, -1f, u1, v0,
            -1f, 1f, u0, v1,
            1f, 1f, u1, v1
        )
    )
}

/** Enables `val (u0, v0, u1, v1) = rect` destructuring for `FloatArray`. */
internal operator fun FloatArray.component1() = this[0]
internal operator fun FloatArray.component2() = this[1]
internal operator fun FloatArray.component3() = this[2]
internal operator fun FloatArray.component4() = this[3]
