package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class ReleaseAssetNamesTest {
    @Test fun versionedAssetsPreferredInBothFlavors() {
        assertEquals("Tigris-v2.0.6.apk",
            ReleaseAssetNames.select("v2.0.6", false, listOf("Tigris.apk", "Tigris-v2.0.6.apk")))
        assertEquals("Tigris-guard-v2.0.6.apk",
            ReleaseAssetNames.select("v2.0.6", true, listOf("Tigris-guard.apk", "Tigris-guard-v2.0.6.apk")))
    }

    @Test fun historicalReleaseAssetsStillWork() {
        assertEquals("Tigris.apk", ReleaseAssetNames.select("v2.0.5", false, listOf("Tigris.apk")))
        assertEquals("Tigris-guard.apk", ReleaseAssetNames.select("v2.0.5", true, listOf("Tigris-guard.apk")))
    }

    @Test fun neverSelectWrongFlavorOrWrongVersion() {
        assertNull(ReleaseAssetNames.select("v2.0.6", true, listOf("Tigris-v2.0.6.apk")))
        assertNull(ReleaseAssetNames.select("v2.0.6", false, listOf("Tigris-v2.0.5.apk")))
    }
}
