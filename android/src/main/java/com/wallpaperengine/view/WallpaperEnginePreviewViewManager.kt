package com.wallpaperengine.view

import com.facebook.react.bridge.ReadableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.annotations.ReactProp

/** Registers `WallpaperEnginePreviewView` as `<WallpaperEnginePreviewView config={...} />`. */
class WallpaperEnginePreviewViewManager : ViewGroupManager<WallpaperEnginePreviewView>() {
  override fun getName() = "WallpaperEnginePreviewView"

  override fun createViewInstance(reactContext: ThemedReactContext): WallpaperEnginePreviewView =
    WallpaperEnginePreviewView(reactContext)

  @ReactProp(name = "config")
  fun setConfig(view: WallpaperEnginePreviewView, config: ReadableMap?) {
    view.setConfig(config?.toHashMap())
  }

  override fun onDropViewInstance(view: WallpaperEnginePreviewView) {
    view.release()
    super.onDropViewInstance(view)
  }
}
