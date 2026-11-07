package com.leslie.tvhome

import android.media.session.PlaybackState
import com.leslie.tvhome.NowPlaying.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
