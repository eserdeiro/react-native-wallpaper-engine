package com.wallpaperengine.renderers

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.SurfaceHolder
import com.wallpaperengine.gl.EglCore
import com.wallpaperengine.gl.OesVideoDecoder
import com.wallpaperengine.gl.buildProgram
import com.wallpaperengine.gl.buildQuadBuffer
import com.wallpaperengine.gl.computeCoverCropRect
import org.json.JSONObject
import java.nio.FloatBuffer

private const val VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
uniform mat4 uSTMatrix;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
}
"""

private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
varying vec2 vTexCoord;
void main() {
    gl_FragColor = texture2D(uTexture, vTexCoord);
}
"""

/**
 * `video`: MediaPlayer decodes into an OES texture (`OesVideoDecoder`) drawn as a
 * cover-cropped GL quad. `MediaPlayer.setSurface` on the raw engine Surface would stretch the
 * video — there is no platform cover-crop API for a raw `SurfaceHolder` outside an Activity.
 * No dedicated thread: MediaPlayer decodes on its own and requests redraws per frame.
 */
class VideoRenderer(private val context: Context) : WallpaperRenderer {
    private var videoPath: String? = null
    private var isVisible = false

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private var egl: EglCore? = null
    private var programId = 0
    private var decoder: OesVideoDecoder? = null
    private var vertexBuffer: FloatBuffer = buildQuadBuffer(floatArrayOf(0f, 0f, 1f, 1f))

    private var aPositionLoc = -1
    private var aTexCoordLoc = -1
    private var uTextureLoc = -1
    private var uSTMatrixLoc = -1

    override fun onSurfaceCreated(holder: SurfaceHolder, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        ensureGlInitialized(holder)
        updateVertexBuffer()
        decoder?.startPlaybackIfReady(videoPath)
    }

    override fun onConfigUpdated(config: JSONObject) {
        val newPath = config.optString("videoPath").ifEmpty { null }
        if (newPath == null || newPath == videoPath) return
        videoPath = newPath
        // startPlaybackIfReady resets the known video size; call it before updateVertexBuffer
        // so the buffer falls back to no-crop instead of keeping the previous video's crop.
        decoder?.startPlaybackIfReady(newPath)
        updateVertexBuffer()
    }

    private fun ensureGlInitialized(holder: SurfaceHolder) {
        if (egl != null) return
        val core = EglCore()
        if (!core.init(holder)) {
            return
        }
        val program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            core.release()
            return
        }
        egl = core
        programId = program
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uSTMatrixLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")

        decoder = OesVideoDecoder(
            onVideoSizeChanged = { updateVertexBuffer() },
            onFrameAvailable = { drawFrame() },
        )
    }

    private fun updateVertexBuffer() {
        val dec = decoder
        val cropRect = if (dec != null && dec.videoWidth > 0 && dec.videoHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0) {
            computeCoverCropRect(dec.videoWidth, dec.videoHeight, surfaceWidth, surfaceHeight)
        } else {
            floatArrayOf(0f, 0f, 1f, 1f)
        }
        vertexBuffer = buildQuadBuffer(cropRect)
    }

    private fun drawFrame() {
        val core = egl ?: return
        val dec = decoder ?: return
        if (!isVisible) return

        if (!core.makeCurrent()) {
            return
        }

        dec.updateTexImage()

        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(programId)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, dec.oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, dec.stMatrix, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
        core.swapBuffers()
    }

    override fun onVisibilityChanged(visible: Boolean) {
        isVisible = visible
        decoder?.setVisible(visible)
        if (visible) {
            // Redraw the last known frame; the compositor may have dropped it in background.
            drawFrame()
        }
    }

    override fun onSurfaceDestroyed() {
        releaseAll()
    }

    override fun onDestroy() {
        releaseAll()
    }

    private fun releaseAll() {
        // Reassert our EGL context before deleting: with two engines alive on this thread the
        // deletes could land in the other engine's context (ids collide across contexts).
        egl?.makeCurrent()
        decoder?.release()
        decoder = null
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        egl?.release()
        egl = null
    }
}
