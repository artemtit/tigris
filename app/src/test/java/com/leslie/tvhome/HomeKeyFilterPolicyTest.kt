package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class HomeKeyFilterPolicyTest {
    @Test fun coocaaAndroid11NeverFiltersRemoteKeys() {
        assertFalse(HomeKeyFilterPolicy.shouldRequestFilter(30, true, false))
        assertFalse(HomeKeyFilterPolicy.shouldRequestFilter(30, true, true))
    }

    @Test fun otherAndroid11DevicesKeepExistingBehavior() {
        assertTrue(HomeKeyFilterPolicy.shouldRequestFilter(30, false, false))
        assertTrue(HomeKeyFilterPolicy.shouldRequestFilter(30, false, true))
    }

    @Test fun coocaaAndroid9And10PreserveEarlierPolicy() {
        assertTrue(HomeKeyFilterPolicy.shouldRequestFilter(28, true, false))
        assertTrue(HomeKeyFilterPolicy.shouldRequestFilter(29, true, false))
    }

    @Test fun newerAndroidVersionsPreserveOnDemandFiltering() {
        assertFalse(HomeKeyFilterPolicy.shouldRequestFilter(31, true, false))
        assertTrue(HomeKeyFilterPolicy.shouldRequestFilter(31, true, true))
        assertFalse(HomeKeyFilterPolicy.shouldRequestFilter(31, false, false))
        assertTrue(HomeKeyFilterPolicy.shouldRequestFilter(31, false, true))
    }
}
