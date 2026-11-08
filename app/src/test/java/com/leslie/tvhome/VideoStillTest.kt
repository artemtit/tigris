package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStillTest {
    @Test fun stillOfSameSourceFits() = assertTrue(VideoWallpaper.stillFits(VideoWallpaper.AERIAL, "video_aerial:a.mov"))

    @Test fun stillOfOtherSourceDoesNot() = assertFalse(VideoWallpaper.stillFits(VideoWallpaper.AERIAL, "video_local:1:2"))

    @Test fun photoThemeNeverUsesStill() = assertFalse(VideoWallpaper.stillFits("wallhaven", "wallhaven:x"))

    @Test fun noStillSaved() = assertFalse(VideoWallpaper.stillFits(VideoWallpaper.LOCAL, ""))

    @Test fun sameFilmResumes() = assertEquals(42_000L, VideoWallpaper.resumeAt("video_local:1:2", "video_local:1:2", 42_000L))

    @Test fun bigStillShrinksKeepingShape() = assertEquals(960 to 540, VideoWallpaper.stillSize(1920, 1080))

    @Test fun croppedSurfaceKeepsItsShape() = assertEquals(960 to 720, VideoWallpaper.stillSize(1440, 1080))

    @Test fun smallStillStays() = assertEquals(800 to 600, VideoWallpaper.stillSize(800, 600))

    @Test fun otherFilmStartsOver() = assertEquals(0L, VideoWallpaper.resumeAt("video_local:3:4", "video_local:1:2", 42_000L))
}
