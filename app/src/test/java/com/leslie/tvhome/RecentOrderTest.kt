package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentOrderTest {
    @Test fun lastOpenedWinsWithoutReversingTheOtherApps() {
        val first = RecentOrder.promote("music", listOf("video", "photos", "music"), emptyList())
        assertEquals(listOf("music", "video", "photos"), first)
        assertEquals(listOf("photos", "music", "video"), RecentOrder.promote("photos", first, first))
    }

    @Test fun reopeningFirstDoesNotDuplicateItOrLoseHiddenApps() {
        assertEquals(listOf("music", "video", "hidden"),
            RecentOrder.promote("music", listOf("music", "video"), listOf("video", "hidden", "music")))
    }

    @Test fun currentManualOrderIsPreservedBehindNewlyOpenedApp() {
        assertEquals(listOf("video", "photos", "music"),
            RecentOrder.promote("video", listOf("photos", "video", "music"), listOf("music", "video", "photos")))
    }
}
