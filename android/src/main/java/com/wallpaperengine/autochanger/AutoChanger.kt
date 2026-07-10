package com.wallpaperengine.autochanger

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.wallpaperengine.apply.StaticWallpaperApplier
import com.wallpaperengine.assets.RemoteAssetDownloader
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "expo_wallpaper_engine_autochanger"
private const val PREFS_NAME = "expo_wallpaper_engine_autochanger"
private const val KEY_URLS = "urls"
private const val KEY_INTERVAL = "interval_minutes"
private const val KEY_INDEX = "current_index"
private const val KEY_IS_RUNNING = "is_running"

private const val EVENT_TICK = "onAutoChangerTick"
private const val EVENT_STATE_CHANGE = "onAutoChangerStateChange"

/** Real autochanger state as persisted in `SharedPreferences` — the single source of truth
 * consumed by both `AutoChangerWorker` and `getAutoChangerState()`. */
internal data class AutoChangerState(
    val imageUrls: List<String>,
    val intervalMinutes: Int,
    val currentIndex: Int,
    val isRunning: Boolean,
)

/** Client-side rotation of static wallpapers via self-chaining `OneTimeWorkRequest`s
 * (`PeriodicWorkRequest` enforces a 15-minute minimum interval). */
object AutoChanger {
    fun start(context: Context, imageUrls: List<String>, intervalMinutes: Int) {
        val safeInterval = intervalMinutes.coerceAtLeast(1)
        prefs(context).edit()
            .putString(KEY_URLS, JSONArray(imageUrls).toString())
            .putInt(KEY_INTERVAL, safeInterval)
            .putInt(KEY_INDEX, 0)
            .putBoolean(KEY_IS_RUNNING, true)
            .apply()

        scheduleNext(context, 0)
        emitStateChange(isRunning = true)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        prefs(context).edit().clear().apply()
        emitStateChange(isRunning = false)
    }

    internal fun scheduleNext(context: Context, delayMinutes: Int) {
        val request = OneTimeWorkRequestBuilder<AutoChangerWorker>()
            .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    internal fun readState(context: Context): AutoChangerState {
        val prefsInstance = prefs(context)
        val urlsJson = prefsInstance.getString(KEY_URLS, null)
        val urls = mutableListOf<String>()
        if (urlsJson != null) {
            val array = JSONArray(urlsJson)
            for (i in 0 until array.length()) urls.add(array.getString(i))
        }
        return AutoChangerState(
            imageUrls = urls,
            intervalMinutes = prefsInstance.getInt(KEY_INTERVAL, 60),
            currentIndex = prefsInstance.getInt(KEY_INDEX, 0),
            isRunning = prefsInstance.getBoolean(KEY_IS_RUNNING, false),
        )
    }

    internal fun advanceIndex(context: Context, nextIndex: Int) {
        prefs(context).edit().putInt(KEY_INDEX, nextIndex).apply()
    }

    /** Defensive: the persisted playlist is empty, so the worker has nothing to rotate and
     * doesn't re-chain — without this flag `isRunning` would stay `true` incorrectly. */
    internal fun markStoppedFromWorker(context: Context) {
        prefs(context).edit().putBoolean(KEY_IS_RUNNING, false).apply()
        emitStateChange(isRunning = false)
    }

    internal fun emitTick(index: Int, url: String) {
        AutoChangerEventBridge.emit(EVENT_TICK, mapOf("index" to index, "url" to url))
    }

    private fun emitStateChange(isRunning: Boolean) {
        AutoChangerEventBridge.emit(EVENT_STATE_CHANGE, mapOf("isRunning" to isRunning))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Bridge between `AutoChangerWorker` (instantiated by WorkManager, unrelated to the live Expo
 * `Module` instance) and `ExpoWallpaperEngineModule.sendEvent`. Best-effort: with no JS
 * runtime attached the events are dropped — `getAutoChangerState()` is the resync source of
 * truth.
 */
internal object AutoChangerEventBridge {
    @Volatile
    private var emitter: ((String, Map<String, Any?>) -> Unit)? = null

    fun attach(block: (String, Map<String, Any?>) -> Unit) {
        emitter = block
    }

    fun detach() {
        emitter = null
    }

    fun emit(name: String, body: Map<String, Any?>) {
        emitter?.invoke(name, body)
    }
}

class AutoChangerWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val state = AutoChanger.readState(applicationContext)
        if (state.imageUrls.isEmpty()) {
            AutoChanger.markStoppedFromWorker(applicationContext)
            return Result.success()
        }

        val safeIndex = state.currentIndex % state.imageUrls.size
        val url = state.imageUrls[safeIndex]
        var tempPath: String? = null
        try {
            // Temp file is only needed until applied; deleted in the finally below.
            tempPath = RemoteAssetDownloader.resolveLocalPath(applicationContext, url, persistent = false, fallbackExt = "img", onProgress = null)
            StaticWallpaperApplier.apply(applicationContext, tempPath, "both")
            AutoChanger.emitTick(safeIndex, url)
        } catch (e: Exception) {
        } finally {
            tempPath?.let { File(it).delete() }
        }

        // A stop()/restart during the download above cancels this unique work and sets
        // isStopped — without this check, a long-running download can still finish, apply,
        // and re-schedule with its stale interval, clobbering the fresh chain the restart
        // already enqueued.
        if (isStopped) return Result.success()

        AutoChanger.advanceIndex(applicationContext, (safeIndex + 1) % state.imageUrls.size)
        AutoChanger.scheduleNext(applicationContext, state.intervalMinutes)
        return Result.success()
    }
}
