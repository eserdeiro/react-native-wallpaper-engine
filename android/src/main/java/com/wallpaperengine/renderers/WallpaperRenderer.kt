package com.wallpaperengine.renderers

import android.view.MotionEvent
import android.view.SurfaceHolder
import org.json.JSONObject

/**
 * Per-type render contract for live wallpapers (video/daynight/parallax/ripple). `HeroEngine`
 * and `WallpaperEnginePreviewView` forward surface/visibility/touch callbacks to the active
 * renderer and hot-swap it when the config `type` changes.
 */
interface WallpaperRenderer {
    fun onSurfaceCreated(holder: SurfaceHolder, width: Int, height: Int)
    fun onSurfaceDestroyed()
    fun onVisibilityChanged(visible: Boolean)
    fun onTouchEvent(event: MotionEvent) {}
    fun onConfigUpdated(config: JSONObject) {}
    fun onDestroy() {}
}
