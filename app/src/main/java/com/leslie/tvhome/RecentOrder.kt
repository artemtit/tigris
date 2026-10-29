package com.leslie.tvhome

/** Move the last opened app to the front, keeping every other app's relative order. */
object RecentOrder {
    fun promote(opened: String, visible: List<String>, saved: List<String>): List<String> =
        (listOf(opened) + visible + saved).distinct()

    fun afterLaunch(opened: String, visible: List<String>, saved: List<String>, recentFirst: Boolean): List<String> =
        if (recentFirst) promote(opened, visible, saved) else saved
}
