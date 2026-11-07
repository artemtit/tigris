package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCoverTest {
    @Test fun sameAspectFillsExactly() = assertEquals(1920 to 1080, VideoWallpaper.cover(1920, 1080, 16f / 9f))

    @Test fun narrowerVideoGrowsTall() = assertEquals(1920 to 1440, VideoWallpaper.cover(1920, 1080, 4f / 3f))

    @Test fun widerVideoGrowsWide() = assertEquals(2520 to 1080, VideoWallpaper.cover(1920, 1080, 21f / 9f))

    @Test fun noSizeBeforeLayout() = assertNull(VideoWallpaper.cover(0, 1080, 16f / 9f))
}
