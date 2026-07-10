package com.wallpaperengine.assets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import java.io.File

object BitmapUtils {
    /** Decodes with a computed `inSampleSize` to avoid OOM on large images, then scales and
     * center-crops to cover exactly `targetWidth x targetHeight`. */
    fun decodeCoverBitmap(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val file = File(path)
        if (!file.exists()) {
            return null
        }

        if (targetWidth <= 0 || targetHeight <= 0) {
            return BitmapFactory.decodeFile(path)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        val decoded = BitmapFactory.decodeFile(path, decodeOptions)
        if (decoded == null) {
            return null
        }

        return coverCrop(decoded, targetWidth, targetHeight)
    }

    private fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /** Scales keeping aspect ratio to cover the target, crops the excess centered.
     * Recycles `source`. */
    fun coverCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceRatio = source.width.toFloat() / source.height.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()

        val scale = if (sourceRatio > targetRatio) {
            targetHeight.toFloat() / source.height.toFloat()
        } else {
            targetWidth.toFloat() / source.width.toFloat()
        }

        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val left = ((targetWidth - scaledWidth) / 2).toFloat()
        val top = ((targetHeight - scaledHeight) / 2).toFloat()
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(left, top)
        }
        canvas.drawBitmap(source, matrix, null)
        source.recycle()
        return output
    }
}
