package com.wallpaperengine.gl

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import kotlin.math.max

/**
 * Shared shader for drawing one static bitmap as a textured quad (`uAlpha` for crossfades;
 * 1.0 is a no-op). All renderers must draw via GL, never `Canvas`: locking the wallpaper
 * Surface for Canvas connects it as a CPU client and Android then permanently rejects any
 * EGL connection on that same Surface.
 */
internal const val STATIC_TEXTURE_VERTEX_SHADER = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = aPosition;
    vTexCoord = aTexCoord;
}
"""

internal const val STATIC_TEXTURE_FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexture;
uniform float uAlpha;
varying vec2 vTexCoord;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    gl_FragColor = vec4(color.rgb, color.a * uAlpha);
}
"""

/** Query once with an active EGL context — low-end GPUs may support less than the full
 * screen resolution. */
internal fun queryMaxTextureSize(): Int {
    val buf = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, buf, 0)
    return buf[0]
}

/** Uploads a `Bitmap` as a `GL_TEXTURE_2D`. Recycles `bitmap` — the caller must not touch it
 * afterwards. Bitmap row 0 is the top row; GL v=0 is the bottom edge — flip before upload. */
internal fun uploadBitmapAsTexture(bitmap: Bitmap, maxTextureSize: Int): Int {
    val ids = IntArray(1)
    GLES20.glGenTextures(1, ids, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

    val flipped = flipBitmapVertically(bitmap)
    bitmap.recycle()
    val toUpload = capBitmapToMaxTextureSize(flipped, maxTextureSize)
    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, toUpload, 0)
    if (toUpload !== flipped) flipped.recycle()
    toUpload.recycle()
    return ids[0]
}

internal fun capBitmapToMaxTextureSize(bitmap: Bitmap, maxTextureSize: Int): Bitmap {
    if (maxTextureSize <= 0 || (bitmap.width <= maxTextureSize && bitmap.height <= maxTextureSize)) return bitmap
    val scale = maxTextureSize.toFloat() / max(bitmap.width, bitmap.height)
    val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}

internal fun flipBitmapVertically(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { preScale(1f, -1f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
