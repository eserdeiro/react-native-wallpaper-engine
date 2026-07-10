package com.wallpaperengine.config

import java.util.Calendar

/**
 * Single source of truth for the day/night cutoff (day = 06:00-18:00 local time), shared by
 * `DayNightRenderer` and `resolveDayNightSlot()` so preview and applied wallpaper never
 * diverge. Mirrored in JS (`index.ts`) for the non-Android fallback — keep in sync.
 */
object DayNightSchedule {
    const val DAY_START_HOUR = 6
    const val DAY_END_HOUR_EXCLUSIVE = 18

    fun isDaytimeNow(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour in DAY_START_HOUR until DAY_END_HOUR_EXCLUSIVE
    }
}
