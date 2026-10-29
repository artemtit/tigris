package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AerialQueueTest {
    private val ids = (1..20).map { "film$it" }

    @Test fun everyFilmPlaysOnceBeforeAnyRepeats() {
        val random = Random(7)
        var order = AerialQueue.order(ids, "", random)
        var pos = 0
        val played = mutableListOf<String>()
        repeat(ids.size * 3) {
            AerialQueue.at(order, pos, random).let { order = it.first; pos = it.second }
            played += order[pos++]
        }
        played.chunked(ids.size).forEach { assertEquals(ids.toSet(), it.toSet()) }
        played.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun savedOrderSurvivesARestartUnlessTheCatalogueChanged() {
        val order = AerialQueue.order(ids, "", Random(1))
        val saved = order.joinToString(",")
        assertEquals(order, AerialQueue.order(ids, saved, Random(2)))
        assertEquals(ids.toSet(), AerialQueue.order(ids + "new", saved, Random(2)).toSet() - "new")
        assertEquals(21, AerialQueue.order(ids + "new", saved, Random(2)).size)
    }

    @Test fun cacheBudgetKeepsCapAndFreeSpace() {
        val mb = 1L shl 20
        assertTrue(AerialQueue.fits(170 * mb, 0, 170 * mb, 560 * mb))
        assertFalse(AerialQueue.fits(250 * mb, 0, 170 * mb, 560 * mb)) // over 400 MB together
        assertFalse(AerialQueue.fits(170 * mb, 0, 0, 300 * mb)) // would leave < 150 MB free
        assertTrue(AerialQueue.fits(170 * mb, 100 * mb, 0, 250 * mb)) // resuming needs only the rest
    }
}
