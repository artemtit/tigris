package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherMetNoTest {
    @Test
    fun symbolsMapOntoWmoCodes() {
        assertEquals(0, Weather.metNoCode("clearsky_day"))
        assertEquals(2, Weather.metNoCode("partlycloudy_night"))
        assertEquals(3, Weather.metNoCode("cloudy"))
        assertEquals(45, Weather.metNoCode("fog"))
        assertEquals(61, Weather.metNoCode("lightrainshowers_day"))
        assertEquals(71, Weather.metNoCode("heavysleet"))
        assertEquals(95, Weather.metNoCode("rainandthunder"))
    }
}
