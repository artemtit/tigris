package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class HomeTakeoverPolicyTest {
    private fun HomeTakeoverPolicy.request(now: Long) = request(now, true, true, false)

    @Test fun bootAndWakeWithoutWindowEvidenceNeverTakeOver() {
        val policy = HomeTakeoverPolicy()
        assertFalse(policy.request(1500))
        assertFalse(policy.request(180000))
    }

    @Test fun appOrInputOpenedDuringDelayCancelsTakeover() {
        val policy = HomeTakeoverPolicy()
        policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, 1000)
        policy.observe(HomeTakeoverPolicy.Window.OTHER, 1200)
        assertFalse(policy.request(1500))
    }

    @Test fun onlyFreshSettledHomeCanTakeOverOnce() {
        val policy = HomeTakeoverPolicy()
        policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, 1000)
        assertFalse(policy.request(1100))
        assertTrue(policy.request(1350))
        assertFalse(policy.request(1700))
        policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, 5000)
        assertFalse(policy.request(8000))
    }

    @Test fun userChoiceSleepAndDisabledGuardWin() {
        val policy = HomeTakeoverPolicy()
        policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, 1000)
        assertFalse(policy.request(1400, false, true, false))
        assertFalse(policy.request(1400, true, false, false))
        assertFalse(policy.request(1400, true, true, true))
    }

    @Test fun repeatedVendorTakeoversPauseInsteadOfFightingForever() {
        val policy = HomeTakeoverPolicy()
        for (time in listOf(1000L, 4000L, 7000L)) {
            policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, time)
            assertTrue(policy.request(time + 350))
        }
        policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, 10000)
        assertFalse(policy.request(10350))
        policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, 20000)
        assertFalse(policy.request(20350))
        policy.observe(HomeTakeoverPolicy.Window.STOCK_HOME, 80000)
        assertTrue(policy.request(80350))
    }
}
