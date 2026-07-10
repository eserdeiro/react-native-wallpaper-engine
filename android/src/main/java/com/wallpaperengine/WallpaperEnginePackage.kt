package com.wallpaperengine

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager
import com.wallpaperengine.view.WallpaperEnginePreviewViewManager

/** Registers `WallpaperEngineModule` (native module) and `WallpaperEnginePreviewViewManager`
 * (in-app interactive preview) for classic React Native autolinking. Extends `BaseReactPackage`
 * (lazy per-name module lookup) rather than the older `ReactPackage.createNativeModules` list,
 * which is deprecated. */
class WallpaperEnginePackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == WallpaperEngineModule.NAME) WallpaperEngineModule(reactContext) else null

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
    listOf(WallpaperEnginePreviewViewManager())

  override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
    ReactModuleInfoProvider {
      mapOf(
        WallpaperEngineModule.NAME to ReactModuleInfo(
          WallpaperEngineModule.NAME,
          WallpaperEngineModule.NAME,
          false, // canOverrideExistingModule
          false, // needsEagerInit
          false, // isCxxModule
          false, // isTurboModule
        )
      )
    }
}
