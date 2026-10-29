package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteRemapTest {
    @Test fun onlyNetflixAndEvoAreRemapped() {
        assertEquals("remap_netflix", RemoteRemap.prefKey(265))
        assertEquals("remap_evo", RemoteRemap.prefKey(240))
        assertNull(RemoteRemap.prefKey(88)) // MEDIA_PREVIOUS
        assertNull(RemoteRemap.prefKey(3))
    }

    @Test fun defaults() {
        assertEquals("ru.kinopoisk.tv", RemoteRemap.default("remap_netflix"))
        assertEquals("inputs", RemoteRemap.default("remap_evo"))
    }
}
