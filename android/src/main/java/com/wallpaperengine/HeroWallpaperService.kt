package com.wallpaperengine

import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.wallpaperengine.config.WallpaperConfigStore
import com.wallpaperengine.renderers.DayNightRenderer
import com.wallpaperengine.renderers.ParallaxRenderer
import com.wallpaperengine.renderers.RippleRenderer
import com.wallpaperengine.renderers.VideoRenderer
import com.wallpaperengine.renderers.WallpaperRenderer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bridge between `HeroEngine` (decoupled from any particular `Module` instance) and
 * `ExpoWallpaperEngineModule.sendEvent` (only callable from that instance) — same pattern as
 * `AutoChangerEventBridge`. Emits exactly when `HeroEngine.onCreate()` runs with
 * `isPreview() == false`: the deterministic signal that Android has bound us as the real
 * wallpaper, not the picker's own preview (`Intent.ACTION_WALLPAPER_CHANGED` was tried and
 * discarded — it fires as soon as the bind starts, not when it finishes).
 */
internal object LiveWallpaperEngineBridge {
    @Volatile
    private var emitter: (() -> Unit)? = null

    fun attach(block: () -> Unit) {
        emitter = block
    }

    fun detach() {
        emitter = null
    }

    fun emitActivated() {
        emitter?.invoke()
    }
}

/**
 * Fallback source of truth for "is our wallpaper active now", alongside
 * `WallpaperManager.getInstance(context).wallpaperInfo` — that query has been observed to
 * return `null` persistently on at least one OEM even with the real `HeroEngine` alive and
 * rendering. Updated directly by `HeroEngine` from its own `isPreview()` (the same signal
 * `LiveWallpaperEngineBridge` uses). See `ExpoWallpaperEngineModule.isLiveWallpaperActive`,
 * which ORs both signals.
 */
internal object LiveWallpaperEngineState {
    private val active = AtomicBoolean(false)

    fun setActive(value: Boolean) {
        active.set(value)
    }

    fun isActive(): Boolean = active.get()
}

/**
 * The module's single `WallpaperService` (declared in `AndroidManifest.xml`). Its single
 * `Engine` (`HeroEngine`, below) dispatches to a `WallpaperRenderer` per type according to the
 * config JSON persisted in `WallpaperConfigStore`.
 *
 * `HeroEngine` is nested as an `inner class` on purpose: `WallpaperService.Engine` is a real
 * (non-static) inner class of `WallpaperService`, so Kotlin requires it lexically nested to
 * construct it (`Engine()` needs the implicit receiver of `HeroWallpaperService.this`).
 */
class HeroWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = HeroEngine()

    /**
     * The module's single real `WallpaperService.Engine` — "one Engine per type" is resolved at
     * the `WallpaperRenderer` level instead, because Android doesn't allow recreating the Engine
     * of an already-active live wallpaper without going through the system preview
     * (`setWallpaperComponent()`, the only API that forces that recreation, requires the
     * `SET_WALLPAPER_COMPONENT` signature permission a third-party app can't have). With 4
     * `Engine` subclasses, the "applied" hot-swap path (service already active -> rewrite config
     * -> notify) couldn't work across a type change (e.g. video -> ripple).
     */
    inner class HeroEngine : Engine() {
        private var renderer: WallpaperRenderer? = null
        private var currentType: String? = null
        private var currentHolder: SurfaceHolder? = null
        private var width = 0
        private var height = 0

        private val prefs: SharedPreferences by lazy { WallpaperConfigStore.prefs(this@HeroWallpaperService) }
        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WallpaperConfigStore.KEY_VERSION) reloadConfig()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.currentHolder = surfaceHolder
            setTouchEventsEnabled(true)
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            reloadConfig(forceRecreate = true)

            // isPreview() == false: this Engine instance is the real wallpaper, not the one the
            // system picker creates to show its own live preview.
            if (!isPreview()) {
                // Set before emitting the event on purpose: the JS listener may check
                // isLiveWallpaperActive() in the same tick it reacts to onWallpaperChanged.
                LiveWallpaperEngineState.setActive(true)
                LiveWallpaperEngineBridge.emitActivated()
            }
        }

        private fun reloadConfig(forceRecreate: Boolean = false) {
            val config = WallpaperConfigStore.load(this@HeroWallpaperService)
            if (config == null) {
                return
            }
            val type = config.optString("type").ifEmpty { null }
            if (type == null) {
                return
            }

            if (forceRecreate || type != currentType) {
                renderer?.onSurfaceDestroyed()
                renderer?.onDestroy()
                renderer = createRenderer(type)
                currentType = type
                val surfaceHolder = currentHolder
                if (surfaceHolder != null && width > 0 && height > 0) {
                    renderer?.onSurfaceCreated(surfaceHolder, width, height)
                }
            }

            renderer?.onConfigUpdated(config)
        }

        private fun createRenderer(type: String): WallpaperRenderer = when (type) {
            "video" -> VideoRenderer(this@HeroWallpaperService)
            "daynight" -> DayNightRenderer(this@HeroWallpaperService)
            "parallax" -> ParallaxRenderer(this@HeroWallpaperService)
            "ripple" -> RippleRenderer(this@HeroWallpaperService)
            else -> VideoRenderer(this@HeroWallpaperService)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.currentHolder = holder
            this.width = width
            this.height = height
            renderer?.onSurfaceCreated(holder, width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            renderer?.onSurfaceDestroyed()
            super.onSurfaceDestroyed(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            renderer?.onVisibilityChanged(visible)
        }

        override fun onTouchEvent(event: MotionEvent) {
            renderer?.onTouchEvent(event)
            super.onTouchEvent(event)
        }

        override fun onDestroy() {
            if (!isPreview()) {
                LiveWallpaperEngineState.setActive(false)
            }
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            renderer?.onSurfaceDestroyed()
            renderer?.onDestroy()
            super.onDestroy()
        }
    }
}
