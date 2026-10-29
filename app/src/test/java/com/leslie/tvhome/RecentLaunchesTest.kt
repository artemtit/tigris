package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentLaunchesTest {
    @Test fun newestFirstWithoutDuplicatesAndCapped() {
        var saved = emptyList<String>()
        for ((i, p) in listOf("a", "b", "c", "d", "e", "f", "g", "b").withIndex()) saved = RecentOrder.recordLaunch(p, saved, i.toLong())
        assertEquals(listOf("b@7", "g@6", "f@5", "e@4", "d@3", "c@2"), saved)
        assertEquals(listOf("b", "g", "f", "e", "d", "c"), RecentOrder.recentPackages(saved))
    }
}
