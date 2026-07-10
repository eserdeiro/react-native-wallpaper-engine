package com.wallpaperengine.apply

import android.app.WallpaperManager
import android.content.Context
import com.wallpaperengine.assets.BitmapUtils
import com.wallpaperengine.assets.PathUtils

/**
 * static/double/matching: decodes the file (cover-cropped to the real device screen size)
 * and applies it via `WallpaperManager.setBitmap`.
 */
object StaticWallpaperApplier {
    fun apply(context: Context, path: String, target: String) {
        val filePath = PathUtils.toFilePath(path)
        val metrics = context.resources.displayMetrics
        val bitmap = BitmapUtils.decodeCoverBitmap(filePath, metrics.widthPixels, metrics.heightPixels)
            ?: throw IllegalStateException("No se pudo decodificar el archivo: $filePath")

        val flag = when (target) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> throw IllegalArgumentException("Unsupported apply target: $target")
        }

        try {
            WallpaperManager.getInstance(context).setBitmap(bitmap, null, true, flag)
        } finally {
            bitmap.recycle()
        }
    }
}
