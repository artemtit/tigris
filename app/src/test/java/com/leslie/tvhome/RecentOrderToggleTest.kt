package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

/** Manual ordering must never change just because a tile was launched. */
class RecentOrderToggleTest {
    @Test fun manuallySortedGridIsUnaffectedWhenRecentFirstIsDisabled() {
        val original = listOf("com.app.videos", "folder:music", "com.app.photos")
        assertEquals(original, RecentOrder.afterLaunch("com.app.photos", original, original, false))
        assertEquals(original, RecentOrder.afterLaunch("folder:music", original, original, false))
    }

    @Test fun enabledModeKeepsExistingUpgradeBehavior() {
        val current = listOf("video", "music", "photo")
        assertEquals(listOf("photo", "video", "music"),
            RecentOrder.afterLaunch("photo", current, current, true))
    }
}
