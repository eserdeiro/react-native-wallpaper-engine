package com.wallpaperengine.config

import android.content.Context
import com.wallpaperengine.assets.ParallaxUnzipper
import com.wallpaperengine.assets.RemoteAssetDownloader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Normalizes the raw JS config map (discriminated union by `type`) into the `JSONObject`
 * persisted by `WallpaperConfigStore` and consumed by the renderers. Resolves every asset
 * path (local or remote URL) and builds the parallax layers dir before persisting.
 */
object LiveWallpaperConfigParser {
    /** Config for a real apply (`setLiveWallpaper`) — parallax extracts to the fixed
     * [ParallaxUnzipper.APPLIED_DIR_NAME]. [onProgress] (0..1, throttled) reflects any remote
     * download. */
    fun parse(context: Context, raw: Map<String, Any?>, onProgress: ((Float) -> Unit)? = null): JSONObject =
        parseInternal(context, raw, { ParallaxUnzipper.APPLIED_DIR_NAME }, onProgress)

    /** Preview variant (`WallpaperEnginePreviewView`): parallax extracts to a per-source dir,
     * never the applied dir nor another preview's. No progress; the view runs this off the
     * UI thread. */
    fun parseForPreview(context: Context, raw: Map<String, Any?>): JSONObject =
        parseInternal(context, raw, { zipPath -> ParallaxUnzipper.previewDirNameFor(zipPath) }, onProgress = null)

    private fun parseInternal(
        context: Context,
        raw: Map<String, Any?>,
        parallaxDirNameFor: (String) -> String,
        onProgress: ((Float) -> Unit)?,
    ): JSONObject {
        val type = raw["type"] as? String ?: throw IllegalArgumentException("config.type is required")
        val json = JSONObject()
        json.put("type", type)

        when (type) {
            "video" -> {
                json.put("videoPath", requireFilePath(context, raw, "videoPath", persistent = true, fallbackExt = "mp4", onProgress))
            }

            "daynight" -> {
                // Two sequential downloads, each mapped to half of the combined 0..1 range.
                val dayPath = requireFilePath(context, raw, "dayImagePath", persistent = true, fallbackExt = "png") { p ->
                    onProgress?.invoke(p * 0.5f)
                }
                val nightPath = requireFilePath(context, raw, "nightImagePath", persistent = true, fallbackExt = "png") { p ->
                    onProgress?.invoke(0.5f + p * 0.5f)
                }
                json.put("dayImagePath", dayPath)
                json.put("nightImagePath", nightPath)
            }

            "parallax" -> {
                val rawLayers = raw["layers"] as? List<*>
                if (rawLayers != null) {
                    // Loose layers, no zip: array order defines z-order (index 0 = background).
                    if (rawLayers.isEmpty()) throw IllegalArgumentException("config.layers no puede estar vacio para parallax")
                    val layersKey = rawLayers.joinToString("|")
                    val layersDir = ParallaxUnzipper.prepareDir(context, parallaxDirNameFor(layersKey))

                    rawLayers.forEachIndexed { zOrder, rawPath ->
                        val path = rawPath as? String ?: throw IllegalArgumentException("config.layers[$zOrder] debe ser un string")
                        // Temp download is only needed until copied into layersDir (itself persistent).
                        val resolvedPath = RemoteAssetDownloader.resolveLocalPath(context, path, persistent = false, fallbackExt = "jpg") { p ->
                            onProgress?.invoke((zOrder + p) / rawLayers.size)
                        }
                        // The numeric prefix matches ParallaxUnzipper.deriveZOrder, so parseLayers works unchanged.
                        val ext = File(resolvedPath).extension.ifEmpty { "jpg" }
                        File(resolvedPath).copyTo(File(layersDir, "${zOrder}_layer.$ext"), overwrite = true)
                        if (RemoteAssetDownloader.isRemoteUrl(path)) File(resolvedPath).delete()
                    }

                    val layers = ParallaxUnzipper.parseLayers(layersDir)
                    putParallaxLayers(json, layersDir, layers)
                } else {
                    // The zip itself doesn't need to survive: what persists is the extracted layers dir.
                    val rawZipPath = raw["zipPath"] as? String ?: throw IllegalArgumentException("config.zipPath or config.layers is required for parallax")
                    val zipPath = requireFilePath(context, raw, "zipPath", persistent = false, fallbackExt = "zip", onProgress)
                    val parallaxDirName = parallaxDirNameFor(zipPath)
                    val layersDir = ParallaxUnzipper.extract(context, zipPath, parallaxDirName)
                    val layers = ParallaxUnzipper.parseLayers(layersDir)

                    if (RemoteAssetDownloader.isRemoteUrl(rawZipPath)) {
                        File(zipPath).delete()
                    }

                    putParallaxLayers(json, layersDir, layers)
                }
            }

            "ripple" -> {
                // Image or video; video wins if both arrive (same rule as RippleRenderer).
                val rawVideoPath = raw["videoPath"] as? String
                val rawImagePath = raw["imagePath"] as? String
                if (rawVideoPath == null && rawImagePath == null) {
                    throw IllegalArgumentException("config.videoPath or config.imagePath is required for ripple")
                }
                if (rawVideoPath != null) {
                    json.put("videoPath", requireFilePath(context, raw, "videoPath", persistent = true, fallbackExt = "mp4", onProgress))
                } else {
                    json.put("imagePath", requireFilePath(context, raw, "imagePath", persistent = true, fallbackExt = "webp", onProgress))
                }
            }

            else -> throw IllegalArgumentException("Unsupported live wallpaper type: $type")
        }

        return json
    }

    private fun putParallaxLayers(json: JSONObject, layersDir: File, layers: List<ParallaxUnzipper.Layer>) {
        json.put("layersDir", layersDir.absolutePath)
        json.put("layers", JSONArray().apply {
            layers.forEach { layer ->
                put(JSONObject().apply {
                    put("fileName", layer.fileName)
                    put("zOrder", layer.zOrder)
                })
            }
        })
    }

    /** Requires the field and resolves it to a real local path (downloads remote URLs — see
     * [RemoteAssetDownloader] for the persistent/cache split). */
    private fun requireFilePath(
        context: Context,
        raw: Map<String, Any?>,
        key: String,
        persistent: Boolean,
        fallbackExt: String,
        onProgress: ((Float) -> Unit)?,
    ): String {
        val value = raw[key] as? String ?: throw IllegalArgumentException("config.$key is required")
        val filePath = RemoteAssetDownloader.resolveLocalPath(context, value, persistent, fallbackExt, onProgress)
        return filePath
    }
}
