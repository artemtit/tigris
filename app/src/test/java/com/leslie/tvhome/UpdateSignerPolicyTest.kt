package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSignerPolicyTest {
    private val pinned = "ea7f4568203d294bd37acac966d6d707f79147549d24c2244f58a6fbc27a6291"

    @Test fun android9ArchiveMissingSigningInfoUsesLegacyParser() {
        var calls = 0
        val observed = UpdateSignerPolicy.withLegacyFallback(emptySet(), legacyAllowed = true) {
            calls++
            setOf(pinned)
        }
        assertEquals(1, calls)
        assertEquals(setOf(pinned), observed)
        assertTrue(UpdateSignerPolicy.matchesPinned(observed, allowedProduct = true, trustedFingerprint = pinned))
    }

    @Test fun modernSignerTakesPrecedenceWithoutCallingLegacyParser() {
        val observed = UpdateSignerPolicy.withLegacyFallback(setOf(pinned), legacyAllowed = true) {
            throw AssertionError("Do not downgrade when a modern signer is available")
        }
        assertEquals(setOf(pinned), observed)
    }

    @Test fun newerAndroidNeverDowngradesWhenSigningInfoIsMissing() {
        val observed = UpdateSignerPolicy.withLegacyFallback(emptySet(), legacyAllowed = false) {
            throw AssertionError("Legacy read must be blocked on newer Android")
        }
        assertTrue(observed.isEmpty())
        assertFalse(UpdateSignerPolicy.matchesPinned(observed, allowedProduct = true, trustedFingerprint = pinned))
    }

    @Test fun missingLegacySignatureDoesNotPassChecks() {
        val observed = UpdateSignerPolicy.withLegacyFallback(emptySet(), legacyAllowed = true) {
            emptySet()
        }
        assertFalse(UpdateSignerPolicy.matchesPinned(observed, allowedProduct = true, trustedFingerprint = pinned))
        assertFalse(UpdateSignerPolicy.sameSigner(observed, emptySet()))
    }

    @Test fun unknownProductAndMismatchedSignerStayRejected() {
        assertFalse(UpdateSignerPolicy.matchesPinned(setOf(pinned), allowedProduct = false, trustedFingerprint = pinned))
        assertFalse(UpdateSignerPolicy.matchesPinned(setOf("malicious"), allowedProduct = true, trustedFingerprint = pinned))
        assertFalse(UpdateSignerPolicy.matchesPinned(setOf(pinned, "other"), allowedProduct = true, trustedFingerprint = pinned))
        assertFalse(UpdateSignerPolicy.sameSigner(setOf(pinned), setOf("different")))
        assertFalse(UpdateSignerPolicy.sameSigner(setOf(pinned), emptySet()))
        assertTrue(UpdateSignerPolicy.sameSigner(setOf(pinned), setOf(pinned)))
    }

    @Test fun existingInstallSignerMayAlsoRequireLegacyReadOnAndroid9() {
        val downloaded = UpdateSignerPolicy.withLegacyFallback(emptySet(), true) { setOf(pinned) }
        val installed = UpdateSignerPolicy.withLegacyFallback(emptySet(), true) { setOf(pinned) }
        assertTrue(UpdateSignerPolicy.sameSigner(downloaded, installed))
        assertFalse(UpdateSignerPolicy.sameSigner(downloaded, setOf("other")))
    }
}
