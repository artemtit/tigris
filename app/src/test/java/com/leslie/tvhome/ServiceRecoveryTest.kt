package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class ServiceRecoveryTest {
    private var entries = listOf("own", "voice")
    private var allowed = true
    private var running = false
    private val pending = mutableListOf<() -> Unit>()
    private fun recovery() = ServiceRecovery("own", { entries }, { entries = it }, { allowed }, { running }, { pending.add(it) })

    @Test fun turningOffBeforeCallbackDoesNotResurrectService() {
        val recovery = recovery()
        recovery.repair()
        allowed = false
        recovery.cancel()
        pending.single()()
        assertEquals(listOf("voice"), entries)
    }

    @Test fun editsToOtherServicesDuringDelayArePreserved() {
        recovery().repair()
        entries = listOf("screenreader")
        pending.single()()
        assertEquals(listOf("screenreader", "own"), entries)
    }

    @Test fun duplicateRequestsAndObsoleteCallbacksDoNotOverwriteSettings() {
        val recovery = recovery()
        recovery.repair()
        recovery.repair()
        assertEquals(1, pending.size)
        recovery.cancel()
        entries = listOf("own", "new")
        pending.single()()
        assertEquals(listOf("own", "new"), entries)
    }
}
