package com.leslie.tvhome

/** Move the last opened app to the front, keeping every other app's relative order. */
object RecentOrder {
    /** How many apps the "Recent" strip above the dock remembers and shows. */
    const val RECENT_MAX = 6

    fun promote(opened: String, visible: List<String>, saved: List<String>): List<String> =
        (listOf(opened) + visible + saved).distinct()

    fun afterLaunch(opened: String, visible: List<String>, saved: List<String>, recentFirst: Boolean): List<String> =
        if (recentFirst) promote(opened, visible, saved) else saved

    /** [saved] entries are "package@epochMillis", newest first; [opened] moves to the front. */
    fun recordLaunch(opened: String, saved: List<String>, now: Long): List<String> =
        (listOf("$opened@$now") + saved.filter { it.substringBefore('@') != opened }).take(RECENT_MAX)

    fun recentPackages(saved: List<String>): List<String> =
        saved.map { it.substringBefore('@') }.filter { it.isNotEmpty() }.distinct().take(RECENT_MAX)
}
