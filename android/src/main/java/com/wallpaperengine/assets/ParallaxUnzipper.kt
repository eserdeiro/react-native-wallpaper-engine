package com.wallpaperengine.assets

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Native unzip + layer parser for parallax zips. Three real naming variants exist, none with a
 * manifest: (1) leading digit = z-order; (2) same, plus `<n>_mask.<ext>` companions;
 * (3) no leading digit (single layer) — assigned a sequential fallback z-order.
 * Any zip subfolders are flattened for robustness against unseen variants.
 */
object ParallaxUnzipper {
    data class Layer(val fileName: String, val zOrder: Int)

    private val MASK_SUFFIX_REGEX = Regex("_mask$", RegexOption.IGNORE_CASE)

    /** Fixed dir, cleaned before each extract — only one applied parallax exists at a time. */
    const val APPLIED_DIR_NAME = "parallax_current"

    private const val PREVIEW_DIR_PREFIX = "parallax_preview_"

    /** Preview extractions are namespaced per source zip: multiple previews can be mounted at
     * once (viewer FlatList) and must never share a dir — nor touch the applied wallpaper's. */
    fun previewDirNameFor(zipPath: String): String {
        val stem = File(zipPath).nameWithoutExtension
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(80)
        val safeName = stem.ifBlank { zipPath.hashCode().toString() }
        return "$PREVIEW_DIR_PREFIX$safeName"
    }

    /** Cleans and recreates `filesDir/wallpaper_engine/<dirName>` — shared with the loose
     * `layers: string[]` path of `LiveWallpaperConfigParser`. */
    fun prepareDir(context: Context, dirName: String): File {
        val outDir = File(context.filesDir, "wallpaper_engine/$dirName")
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()
        return outDir
    }

    fun extract(context: Context, zipPath: String, dirName: String = APPLIED_DIR_NAME): File {
        val outDir = prepareDir(context, dirName)

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(zipPath))).use { zis ->
                val buffer = ByteArray(8192)
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = File(entry.name).name
                        if (name.isNotBlank()) {
                            FileOutputStream(File(outDir, name)).use { fos ->
                                var read: Int
                                while (zis.read(buffer).also { read = it } > 0) {
                                    fos.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            throw e
        }

        return outDir
    }

    /** `N_mask.ext` companions are grayscale depth maps, not drawable layers — excluded. */
    fun isRenderableLayer(fileName: String): Boolean {
        val base = fileName.substringBeforeLast('.')
        return !MASK_SUFFIX_REGEX.containsMatchIn(base)
    }

    /** Leading digit of the file name is the z-order; `null` if it doesn't start with one. */
    fun deriveZOrder(fileName: String): Int? = fileName.getOrNull(0)?.digitToIntOrNull()

    /** Excludes mask companions, derives z-order per name (alphabetical index as fallback),
     * sorted ascending (background first). */
    fun parseLayers(dir: File): List<Layer> {
        val allFiles = dir.listFiles { f -> f.isFile } ?: emptyArray()
        val files = allFiles
            .filter { isRenderableLayer(it.name) }
            .sortedBy { it.name }

        return files
            .mapIndexed { index, f -> Layer(f.name, deriveZOrder(f.name) ?: index) }
            .sortedBy { it.zOrder }
    }
}
