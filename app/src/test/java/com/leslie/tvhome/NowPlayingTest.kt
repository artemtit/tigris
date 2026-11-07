package com.leslie.tvhome

import android.media.session.PlaybackState
import android.view.KeyEvent
import com.leslie.tvhome.NowPlaying.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingTest {
    private val own = "com.leslie.tvhome"

    @Test fun playingBeatsAMoreRecentPause() {
        val spotify = Session("com.spotify.tv.android", PlaybackState.STATE_PLAYING, 1)
        assertEquals(spotify, NowPlaying.pick(listOf(Session("com.soundcloud.android", PlaybackState.STATE_PAUSED, 9), spotify), own))
    }

    @Test fun bufferingBetweenTracksStillCountsAsPlaying() {
        val cast = Session("com.google.android.apps.mediashell", PlaybackState.STATE_BUFFERING, 1)
        assertEquals(cast, NowPlaying.pick(listOf(Session("a", PlaybackState.STATE_PAUSED, 9), cast), own))
    }

    @Test fun otherwiseTheMostRecentPause() {
        val recent = Session("b", PlaybackState.STATE_PAUSED, 9)
        assertEquals(recent, NowPlaying.pick(listOf(Session("a", PlaybackState.STATE_PAUSED, 1), recent), own))
    }

    @Test fun stoppedOrOwnSessionsShowNothing() {
        assertNull(NowPlaying.pick(listOf(Session("a", PlaybackState.STATE_STOPPED, 9), Session(own, PlaybackState.STATE_PLAYING, 9)), own))
        assertNull(NowPlaying.pick(emptyList(), own))
    }

    @Test fun musicModeOpensAfterAMinuteOfRestWhilePlaying() {
        assertEquals(60_000L, NowPlaying.openIn(playing = true, blocked = false, idleMs = 0))
        assertEquals(15_000L, NowPlaying.openIn(playing = true, blocked = false, idleMs = 45_000))
        assertEquals(0L, NowPlaying.openIn(playing = true, blocked = false, idleMs = 90_000))
    }

    @Test fun musicModeWaitsForMusicAndForOverlaysToClose() {
        assertNull(NowPlaying.openIn(playing = false, blocked = false, idleMs = 90_000))
        assertNull(NowPlaying.openIn(playing = true, blocked = true, idleMs = 90_000))
    }

    @Test fun musicModeClosesWhenTheMusicGoes() {
        assertEquals(0L, NowPlaying.closeIn(session = false, playing = false))
        assertEquals(NowPlaying.PAUSE_MS, NowPlaying.closeIn(session = true, playing = false))
        assertNull(NowPlaying.closeIn(session = true, playing = true))
    }

    @Test fun onlyMediaKeysPassThroughTheMusicMode() {
        assertTrue(NowPlaying.passesThrough(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(NowPlaying.passesThrough(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(NowPlaying.passesThrough(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertFalse(NowPlaying.passesThrough(KeyEvent.KEYCODE_DPAD_CENTER))
        assertFalse(NowPlaying.passesThrough(KeyEvent.KEYCODE_BACK))
    }

    private fun luma(c: Int) = (2126 * (c shr 16 and 255) + 7152 * (c shr 8 and 255) + 722 * (c and 255)) / 10_000

    @Test fun accentFollowsTheSaturatedPixels() {
        // Mostly white with a red quarter: the red wins, dimmed for white text.
        val px = IntArray(100) { if (it < 25) 0xFFE02020.toInt() else 0xFFFFFFFF.toInt() }
        val a = NowPlaying.accent(px)
        assertTrue((a shr 16 and 255) > 2 * (a shr 8 and 255))
        assertTrue(luma(a) <= 110)
    }

    @Test fun greyArtworkGivesADimGrey() {
        val a = NowPlaying.accent(IntArray(64) { 0xFFF0F0F0.toInt() })
        assertEquals(a shr 16 and 255, a and 255)
        assertTrue(luma(a) <= 110)
        assertEquals(NowPlaying.BASE, NowPlaying.accent(IntArray(0)))
    }
}
