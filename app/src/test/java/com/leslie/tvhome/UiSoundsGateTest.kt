package com.leslie.tvhome

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSoundsGateTest {
    @Test fun debouncesCloseCalls() {
        val g = UiSounds.Gate(45)
        assertTrue(g.allow(1000, 0))
        assertFalse(g.allow(1030, 0))
        assertTrue(g.allow(1045, 0))
    }

    @Test fun skipsEveryOtherRepeat() {
        val g = UiSounds.Gate(45)
        assertFalse(g.allow(1000, 1))
        assertTrue(g.allow(1000, 2))
    }
}
