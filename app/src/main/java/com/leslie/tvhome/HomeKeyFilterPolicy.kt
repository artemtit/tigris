package com.leslie.tvhome

/**
 * Decide whether the home guard should request ALL remote-control key events.
 *
 * Android <= 11 normally needs Home interception to avoid a long background-activity
 * launch delay. Coocaa / Skyworth Android 11 firmware reported in issue #15 consumes
 * Home before AccessibilityService.onKeyEvent; asking for the entire key stream instead
 * risks losing a volume KEY_UP and causing runaway volume changes.
 *
 * Limit the exception to this manufacturer AND OS version until other devices have
 * been tested. Window-state-based home detection remains enabled.
 */
internal object HomeKeyFilterPolicy {
    fun shouldRequestFilter(
        apiLevel: Int,
        isCoocaa: Boolean,
        homeFilterRequested: Boolean,
    ): Boolean {
        if (isCoocaa && apiLevel == 30) return false // Android 11 / API 30
        return homeFilterRequested || apiLevel <= 30
    }
}
