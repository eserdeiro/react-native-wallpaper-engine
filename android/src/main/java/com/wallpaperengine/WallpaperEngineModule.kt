package com.wallpaperengine

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.wallpaperengine.apply.StaticWallpaperApplier
import com.wallpaperengine.assets.RemoteAssetDownloader
import com.wallpaperengine.autochanger.AutoChanger
import com.wallpaperengine.autochanger.AutoChangerEventBridge
import com.wallpaperengine.config.DayNightSchedule
import com.wallpaperengine.config.LiveWallpaperConfigParser
import com.wallpaperengine.config.WallpaperConfigStore
import java.io.File

/**
 * Native Android wallpaper engine: applies static wallpapers (`WallpaperManager`) and live ones
 * (`HeroWallpaperService`) for the 7 API types plus a client autochanger. Android only — see
 * `WallpaperEnginePackage` for registration; there is no iOS implementation.
 */
class WallpaperEngineModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {
  companion object {
    const val NAME = "WallpaperEngine"
  }

  override fun getName() = NAME

  init {
    AutoChangerEventBridge.attach { name, body -> runCatching { emit(name, body) } }

    // `onWallpaperChanged`: fires once Android has actually bound HeroWallpaperService as
    // the real wallpaper (not the system picker's own preview) after
    // ACTION_CHANGE_LIVE_WALLPAPER — see LiveWallpaperEngineBridge in HeroWallpaperService.kt.
    LiveWallpaperEngineBridge.attach {
      val active = isLiveWallpaperActive(reactApplicationContext)
      val body = Arguments.createMap().apply { putBoolean("isLiveWallpaperActive", active) }
      runCatching { emit("onWallpaperChanged", body) }
    }
  }

  override fun invalidate() {
    super.invalidate()
    AutoChangerEventBridge.detach()
    LiveWallpaperEngineBridge.detach()
  }

  /** Required by `NativeEventEmitter` on the JS side even though events are always-on here —
   * no-op, there's nothing to start/stop per listener count. */
  @ReactMethod
  fun addListener(eventName: String) {}

  @ReactMethod
  fun removeListeners(count: Int) {}

  private fun emit(name: String, body: Any?) {
    reactApplicationContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(name, body)
  }

  private fun emitDownloadProgress(progress: Float) {
    val body = Arguments.createMap().apply { putDouble("progress", progress.toDouble()) }
    runCatching { emit("onWallpaperDownloadProgress", body) }
  }

  // static/double/matching — double/matching resolve as 2 calls from JS (home + lock).
  //
  // `path` accepts a local path or a direct remote URL — if remote, it's downloaded to
  // cacheDir (temporary: only the decoded bitmap needs to survive) reporting progress via
  // onWallpaperDownloadProgress, then deleted whether it succeeds or fails.
  @ReactMethod
  fun setStaticWallpaper(path: String, target: String, promise: Promise) {
    val context = reactApplicationContext
    val wasRemote = RemoteAssetDownloader.isRemoteUrl(path)
    try {
      val resolvedPath = RemoteAssetDownloader.resolveLocalPath(context, path, persistent = false, fallbackExt = "jpg", ::emitDownloadProgress)
      try {
        StaticWallpaperApplier.apply(context, resolvedPath, target)
      } finally {
        if (wasRemote) File(resolvedPath).delete()
      }
      promise.resolve(null)
    } catch (e: Exception) {
      promise.reject("setStaticWallpaper", e.message, e)
    }
  }

  // video/daynight/parallax/ripple. Each config path accepts local or remote — see
  // LiveWallpaperConfigParser/RemoteAssetDownloader for the per-field persistence rule.
  //
  // Always opens the native preview (ACTION_CHANGE_LIVE_WALLPAPER), never applies "directly"
  // even if HeroWallpaperService is already active: only that system screen can offer the
  // home/lock/both choice for a live wallpaper — the API that would apply it silently
  // (`setWallpaperComponentWithFlags`) requires the `SET_WALLPAPER_COMPONENT` signature
  // permission, which a third-party app can never hold.
  @ReactMethod
  fun setLiveWallpaper(rawConfig: ReadableMap, promise: Promise) {
    val context = reactApplicationContext
    try {
      val config = LiveWallpaperConfigParser.parse(context, rawConfig.toHashMap(), ::emitDownloadProgress)
      WallpaperConfigStore.save(context, config)
      openSystemPreview(context)
      promise.resolve("preview_opened")
    } catch (e: Exception) {
      promise.reject("setLiveWallpaper", e.message, e)
    }
  }

  @ReactMethod(isBlockingSynchronousMethod = true)
  fun isLiveWallpaperActive(): Boolean = isLiveWallpaperActive(reactApplicationContext)

  // Single source of truth for the day/night cutoff — shared by DayNightRenderer (applied
  // wallpaper) and the viewer preview so they never diverge. Synchronous on purpose: the
  // viewer calls it on every render.
  @ReactMethod(isBlockingSynchronousMethod = true)
  fun resolveDayNightSlot(): String = if (DayNightSchedule.isDaytimeNow()) "day" else "night"

  // Autochanger: not an API type — client feature, see AutoChanger.kt.
  @ReactMethod
  fun startAutoChanger(imageUrls: ReadableArray, intervalMinutes: Int, promise: Promise) {
    val urls = (0 until imageUrls.size()).mapNotNull { imageUrls.getString(it) }
    AutoChanger.start(reactApplicationContext, urls, intervalMinutes)
    promise.resolve(null)
  }

  @ReactMethod
  fun stopAutoChanger(promise: Promise) {
    AutoChanger.stop(reactApplicationContext)
    promise.resolve(null)
  }

  // Real state read from SharedPreferences (what WorkManager actually applied), not a
  // client-side approximation. Used to resync on mount and as a safety net for missed events.
  @ReactMethod
  fun getAutoChangerState(promise: Promise) {
    val state = AutoChanger.readState(reactApplicationContext)
    val result: WritableMap = Arguments.createMap().apply {
      putBoolean("isRunning", state.isRunning)
      putInt("currentIndex", state.currentIndex)
      putInt("intervalMinutes", state.intervalMinutes)
      putArray("imageUrls", Arguments.createArray().apply { state.imageUrls.forEach { pushString(it) } })
    }
    promise.resolve(result)
  }

  /** Two signals, OR'd — `WallpaperManager.getInstance(context).wallpaperInfo` proved to return
   * `null` persistently on some OEMs even with the real Engine alive and rendering.
   * `LiveWallpaperEngineState.isActive()` is the reliable fallback, updated directly by
   * `HeroEngine`. Both are checked so devices where `WallpaperManager` does work correctly
   * aren't regressed. */
  private fun isLiveWallpaperActive(context: Context): Boolean {
    val info = WallpaperManager.getInstance(context).wallpaperInfo
    val expectedPackage = context.packageName
    val expectedService = HeroWallpaperService::class.java.name
    val viaWallpaperManager = info != null && info.packageName == expectedPackage && info.serviceName == expectedService
    val viaEngineState = LiveWallpaperEngineState.isActive()
    return viaWallpaperManager || viaEngineState
  }

  /** Android has no silent way to apply a live wallpaper: if our service isn't active yet, the
   * user must confirm via the system preview. JS must re-check `isLiveWallpaperActive()` on
   * foreground (the preview can be cancelled). */
  private fun openSystemPreview(context: Context) {
    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
      putExtra(
        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
        ComponentName(context, HeroWallpaperService::class.java)
      )
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
  }
}
