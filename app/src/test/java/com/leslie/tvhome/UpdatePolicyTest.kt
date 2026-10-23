package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun numericOrdering() {
        assertTrue(UpdatePolicy.newer("v1.2.10", "1.2.9"))
        assertFalse(UpdatePolicy.newer("v1.2.7", "1.2.7"))
        assertFalse(UpdatePolicy.newer("v2.0.2", "2.0.3"))
        assertTrue(UpdatePolicy.newer("v2.0.4", "2.0.3-local"))
    }
    @Test fun rejectsPreviewAndMalformedTags() {
        assertFalse(UpdatePolicy.newer("v2.1.0-rc1", "2.0.3"))
        assertFalse(UpdatePolicy.newer("nightly", "2.0.3"))
        assertFalse(UpdatePolicy.newer("v99999999999999999.0", "2.0.3"))
        assertFalse(UpdatePolicy.newer("v2.0.0", "unknown"))
    }
}
