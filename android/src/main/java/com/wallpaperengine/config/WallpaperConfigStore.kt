package com.wallpaperengine.config

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persists the active live wallpaper config as JSON in `SharedPreferences` (survives reboot).
 * `KEY_VERSION` increments on every save; `HeroEngine` listens to it for hot config swaps.
 */
object WallpaperConfigStore {
    private const val PREFS_NAME = "expo_wallpaper_engine_config"
    private const val KEY_CONFIG = "config_json"
    const val KEY_VERSION = "config_version"
    /** Same counter, embedded inside the persisted JSON so it reaches
     * `WallpaperRenderer.onConfigUpdated` — `ParallaxRenderer` needs it to identify a new
     * apply (see that class). */
    const val KEY_CONFIG_VERSION_FIELD = "configVersion"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, config: JSONObject) {
        val prefs = prefs(context)
        val nextVersion = prefs.getInt(KEY_VERSION, 0) + 1
        config.put(KEY_CONFIG_VERSION_FIELD, nextVersion)
        prefs.edit()
            .putString(KEY_CONFIG, config.toString())
            .putInt(KEY_VERSION, nextVersion)
            .apply()
    }

    fun load(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_CONFIG, null)
        if (raw == null) {
            return null
        }
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }
}
