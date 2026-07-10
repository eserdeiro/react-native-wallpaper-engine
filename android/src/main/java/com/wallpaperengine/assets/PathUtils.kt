package com.wallpaperengine.assets

import android.net.Uri

object PathUtils {
    /** JS paths often arrive as `file://...` URIs, but `BitmapFactory`/`File`/`ZipInputStream`
     * need raw filesystem paths. Normalized once at the module boundary. */
    fun toFilePath(uriOrPath: String): String {
        if (!uriOrPath.startsWith("file:")) return uriOrPath
        return Uri.parse(uriOrPath).path ?: uriOrPath
    }
}
