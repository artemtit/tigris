package com.leslie.tvhome

import com.leslie.tvhome.Lyrics.Line
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    @Test fun parsesTimesSortsAndSkipsTagsAndEmptyLines() {
        val lrc = "[ar:Someone]\r\n[ti:Song]\n[00:12.50] Second\n[00:01.2]First\n\n[00:20.00]\nplain words\n[01:02.345]Third"
        assertEquals(listOf(Line(1_200, "First"), Line(12_500, "Second"), Line(62_345, "Third")), Lyrics.parse(lrc))
    }

    @Test fun aLineWithSeveralTimesRepeats() {
        assertEquals(listOf(Line(5_000, "Chorus"), Line(10_000, "Verse"), Line(30_000, "Chorus")),
            Lyrics.parse("[00:30.00][00:05]Chorus\n[00:10.00]Verse"))
    }

    @Test fun currentLineForPosition() {
        val lines = Lyrics.parse("[00:05.00]a\n[00:10.00]b\n[00:15.00]c")
        assertEquals(-1, Lyrics.index(lines, 0))
        assertEquals(-1, Lyrics.index(lines, 4_999))
        assertEquals(0, Lyrics.index(lines, 5_000))
        assertEquals(1, Lyrics.index(lines, 14_999))
        assertEquals(2, Lyrics.index(lines, 600_000))
        assertEquals(-1, Lyrics.index(emptyList(), 1_000))
    }

    @Test fun positionAdvancesOnlyWhilePlaying() {
        assertEquals(13_000L, NowPlaying.position(10_000, 1_000, 1f, playing = true, now = 4_000, duration = 200_000))
        assertEquals(10_000L, NowPlaying.position(10_000, 1_000, 1f, playing = false, now = 4_000, duration = 200_000))
        assertEquals(12_000L, NowPlaying.position(10_000, 1_000, 1f, playing = true, now = 9_000, duration = 12_000))
        assertEquals("3:07", NowPlaying.clock(187_900))
        assertEquals("0:05", NowPlaying.clock(5_000))
    }
}
