package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimerTest {
    @Test fun cyclesOffThirtySixtyNinetyOff() {
        assertEquals(30, SleepTimer.next(0))
        assertEquals(60, SleepTimer.next(30))
        assertEquals(90, SleepTimer.next(60))
        assertEquals(0, SleepTimer.next(90))
        assertEquals(0, SleepTimer.next(45)) // unknown goes back to off
    }

    @Test fun minutesLeftRoundsUpAndStopsAtZero() {
        val now = 1_000_000L
        assertEquals(0, SleepTimer.minutesLeft(0L, now))
        assertEquals(0, SleepTimer.minutesLeft(now, now))
        assertEquals(1, SleepTimer.minutesLeft(now + 1, now))
        assertEquals(30, SleepTimer.minutesLeft(now + 30 * 60_000L, now))
        assertEquals(30, SleepTimer.minutesLeft(now + 29 * 60_000L + 1, now))
    }
}
