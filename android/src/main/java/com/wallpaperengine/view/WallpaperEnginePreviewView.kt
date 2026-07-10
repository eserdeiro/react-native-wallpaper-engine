package com.wallpaperengine.view

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.wallpaperengine.config.LiveWallpaperConfigParser
import com.wallpaperengine.renderers.ParallaxRenderer
import com.wallpaperengine.renderers.RippleRenderer
import com.wallpaperengine.renderers.WallpaperRenderer
import org.json.JSONObject

/**
 * In-app interactive preview — only `parallax` (sensors) and `ripple` (touch), the 2 types
 * that need a real surface for their interaction (`video`/`daynight` preview in plain React).
 * Any other `type` (or null config) releases the active renderer without drawing anything.
 *
 * Reuses the exact same `WallpaperRenderer` the applied wallpaper uses — this view is just
 * another `SurfaceHolder` host for it, the same role `HeroWallpaperService.HeroEngine` plays
 * for the real wallpaper.
 */
class WallpaperEnginePreviewView(context: Context) : FrameLayout(context) {
    private val surfaceView = SurfaceView(context)
    private var renderer: WallpaperRenderer? = null
    private var currentType: String? = null
    private var pendingConfig: JSONObject? = null
    private var width = 0
    private var height = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    /** Incremented on every `setConfig`/`release` — a background parse that resolves after the
     * token changed (newer config in flight, or view released) is discarded instead of
     * clobbering current state. */
    private var configToken = 0

    // Effective visibility = view-tree visibility (onVisibilityChanged) AND window visibility
    // (onWindowVisibilityChanged, fires with Activity.onStop/onStart — home button, screen off).
    private var isViewVisible = false
    private var isWindowVisible = true

    init {
        addView(
            surfaceView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                width = w
                height = h
                renderer?.onSurfaceCreated(holder, w, h)
                pendingConfig?.let { renderer?.onConfigUpdated(it) }
                if (isEffectivelyVisible()) renderer?.onVisibilityChanged(true)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                renderer?.onSurfaceDestroyed()
            }
        })
    }

    private fun isEffectivelyVisible() = isViewVisible && isWindowVisible

    /** Called from `Prop("config")` — always on the UI thread. Same `type` reuses the current
     * renderer (hot config update); a different `type` recreates it.
     *
     * `LiveWallpaperConfigParser.parseForPreview` can download and unzip — real blocking I/O,
     * so it runs on its own `Thread` and only the resolved result (local paths) comes back to
     * the UI thread to apply to the renderer. */
    fun setConfig(raw: Map<String, Any?>?) {
        val type = raw?.get("type") as? String
        if (type != "parallax" && type != "ripple") {
            release()
            return
        }

        val token = ++configToken
        Thread({
            val parsed = try {
                LiveWallpaperConfigParser.parseForPreview(context, raw)
            } catch (e: Exception) {
                return@Thread
            }
            mainHandler.post {
                if (token != configToken) {
                    return@post
                }
                applyParsedConfig(type, parsed)
            }
        }, "WallpaperEnginePreviewConfig").start()
    }

    private fun applyParsedConfig(type: String, parsed: JSONObject) {
        if (renderer == null || currentType != type) {
            releaseRendererOnly()
            renderer = if (type == "parallax") ParallaxRenderer(context) else RippleRenderer(context)
            currentType = type
        }
        pendingConfig = parsed

        val holder = surfaceView.holder
        val surfaceReady = width > 0 && height > 0 && holder.surface != null && holder.surface.isValid
        if (surfaceReady) {
            renderer?.onSurfaceCreated(holder, width, height)
            renderer?.onConfigUpdated(parsed)
            if (isEffectivelyVisible()) renderer?.onVisibilityChanged(true)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        renderer?.onTouchEvent(event)
        return true
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        isViewVisible = visibility == View.VISIBLE
        applyVisibility()
    }

    /** Window visibility (Activity.onStop/onStart — home button, screen off) is distinct from
     * `onVisibilityChanged`, which only reacts to view-tree changes. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        isWindowVisible = visibility == View.VISIBLE
        applyVisibility()
    }

    private fun applyVisibility() {
        val effective = isEffectivelyVisible()
        renderer?.onVisibilityChanged(effective)

        if (effective) {
            // Explicit re-render on regained visibility: if the Surface is still valid but the
            // renderer's loop had stopped, this restarts it without waiting for a new
            // surfaceCreated/surfaceChanged cycle (which may not come if Android only hid the
            // window without destroying the surface).
            val holder = surfaceView.holder
            val surfaceReady = width > 0 && height > 0 && holder.surface != null && holder.surface.isValid
            if (renderer != null && surfaceReady) {
                renderer?.onSurfaceCreated(holder, width, height)
                pendingConfig?.let { renderer?.onConfigUpdated(it) }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Android doesn't always fire onVisibilityChanged/onWindowVisibilityChanged with the
        // default (VISIBLE) value on attach — assume visible, like any normal RN view.
        isViewVisible = true
        isWindowVisible = true
        applyVisibility()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /** Releases renderer (sensors/GL/threads) + pending config. Called on unmount and from
     * `OnViewDestroys` in the module (cheap double safety, both idempotent). */
    fun release() {
        configToken++ // invalidates any background parse landing after this
        releaseRendererOnly()
        pendingConfig = null
        currentType = null
    }

    private fun releaseRendererOnly() {
        renderer?.onSurfaceDestroyed()
        renderer?.onDestroy()
        renderer = null
    }
}
