package com.wallpaperengine.assets

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a JS-supplied path: local values (raw or `file://`) are normalized, remote
 * `http(s)://` URLs are downloaded with real progress. Assumes a single wallpaper apply in
 * flight at a time (the progress event carries no download identifier).
 */
object RemoteAssetDownloader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** Throttle progress notifications so large downloads don't flood the JS event bridge. */
    private const val PROGRESS_MIN_INTERVAL_MS = 100L

    private const val CACHE_DIR_NAME = "wallpaper_engine_remote"
    /** Same dir name (and same slug algorithm in `fileNameFor`) as the JS side
     * (`use-wallpaper-actions.ts`) — keep in sync or the download dedupe breaks. */
    private const val PERSISTENT_DIR_NAME = "wallpaper-engine-assets"

    fun isRemoteUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    /**
     * `persistent = true` -> `filesDir` (the path is persisted in `WallpaperConfigStore` and
     * re-read after a reboot: video/daynight/ripple). `false` -> `cacheDir` (static images and
     * parallax zips are discarded right after use). Idempotent per URL: an existing download
     * with the same deterministic name is reused.
     */
    fun resolveLocalPath(
        context: Context,
        value: String,
        persistent: Boolean,
        fallbackExt: String,
        onProgress: ((Float) -> Unit)?,
    ): String {
        if (!isRemoteUrl(value)) return PathUtils.toFilePath(value)

        val directory = if (persistent) persistentDirectory(context) else cacheDirectory(context)
        val fileName = fileNameFor(value, fallbackExt)
        val destination = File(directory, fileName)

        if (destination.exists() && destination.length() > 0) {
            onProgress?.invoke(1f)
            return destination.absolutePath
        }

        return download(value, directory, fileName, onProgress).absolutePath
    }

    private fun download(url: String, directory: File, fileName: String, onProgress: ((Float) -> Unit)?): File {
        directory.mkdirs()
        val outFile = File(directory, fileName)

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            connection.connect()
            val totalBytes = connection.contentLengthLong
            var bytesRead = 0L
            var lastReportAt = 0L

            connection.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                        bytesRead += read

                        if (onProgress != null && totalBytes > 0) {
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= PROGRESS_MIN_INTERVAL_MS) {
                                lastReportAt = now
                                onProgress(bytesRead.toFloat() / totalBytes)
                            }
                        }
                    }
                }
            }

            onProgress?.invoke(1f)
            return outFile
        } catch (e: Exception) {
            outFile.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Deterministic per-URL file name (mirrors `slugFromUrl` on the JS side) so a finished
     * download is reused instead of repeated. */
    private fun fileNameFor(url: String, fallbackExt: String): String {
        val lastSegment = url.substringBefore('?').substringAfterLast('/')
        val ext = if (lastSegment.contains('.')) lastSegment.substringAfterLast('.') else fallbackExt
        val slug = url.replace(Regex("^https?://"), "").replace(Regex("[^a-zA-Z0-9]+"), "_").takeLast(100)
        return "$slug.$ext"
    }

    private fun cacheDirectory(context: Context): File = File(context.cacheDir, CACHE_DIR_NAME)

    private fun persistentDirectory(context: Context): File = File(context.filesDir, PERSISTENT_DIR_NAME)
}
