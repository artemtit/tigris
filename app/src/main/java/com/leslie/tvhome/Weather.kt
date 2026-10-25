package com.leslie.tvhome

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Optional weather data engine. No access to location permission or network until the
 * user enables the card or explicitly searches for a city. No timer/service of its own.
 * Open-Meteo is the only provider; preserve its attribution and license conditions.
 */
object Weather {
    const val FRESH_MS = 30L * 60_000
    const val MAX_CACHE_MS = 6L * 60 * 60_000

    data class City(val name: String, val latitude: Double, val longitude: Double)
    data class Reading(val city: String, val latitude: Double, val longitude: Double,
                       val temperature: Int, val high: Int?, val low: Int?,
                       val code: Int, val timestamp: Long)

    private fun request(url: String): JSONObject {
        require(url.startsWith("https://api.open-meteo.com/") ||
                url.startsWith("https://geocoding-api.open-meteo.com/"))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode !in 200..299) error("Weather HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }

    /** Called only after a user opens the City picker. Returns at most 8 matching cities. */
    fun search(query: String): List<City> {
        val search = query.trim().take(60)
        if (search.length < 2) return emptyList()
        val json = request("https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(search, "UTF-8") + "&count=8&language=" +
            (if (I18n.isChinese()) "zh" else "en") + "&format=json")
        val rows = json.optJSONArray("results") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val o = rows.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("latitude", Double.NaN)
            val lon = o.optDouble("longitude", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
            else City(listOf(o.optString("name"), o.optString("admin1"), o.optString("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", "), lat, lon)
        }
    }

    private fun decode(text: String, city: City, now: Long, maxAge: Long): Reading? = runCatching {
        val j = JSONObject(text)
        if (j.optString("city") != city.name) return null
        if (j.optDouble("lat") != city.latitude || j.optDouble("lon") != city.longitude) return null
        val at = j.optLong("at")
        if (at <= 0 || now < at || now - at >= maxAge) return null
        Reading(city.name, city.latitude, city.longitude, j.getInt("temp"),
            if (j.isNull("high")) null else j.getInt("high"),
            if (j.isNull("low")) null else j.getInt("low"),
            j.getInt("code"), at)
    }.getOrNull()

    fun cached(ctx: Context, city: City, maxAge: Long = MAX_CACHE_MS): Reading? {
        val saved = ctx.getSharedPreferences("weather-data", Context.MODE_PRIVATE)
            .getString("forecast", null) ?: return null
        return decode(saved, city, System.currentTimeMillis(), maxAge)
    }

    /** Run from a background executor. Returns a fresh or usable cached observation. */
    fun load(ctx: Context, city: City): Reading? {
        cached(ctx, city, FRESH_MS)?.let { return it }
        if (!city.latitude.isFinite() || !city.longitude.isFinite() ||
            city.latitude !in -90.0..90.0 || city.longitude !in -180.0..180.0) return null
        return try {
            val query = "https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}" +
                "&longitude=${city.longitude}&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
            val json = request(query)
            val current = json.getJSONObject("current")
            val temp = current.getDouble("temperature_2m")
            val code = current.getInt("weather_code")
            if (!temp.isFinite()) error("Missing temperature")
            val daily = json.optJSONObject("daily")
            val high = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN)
                ?.takeIf { it.isFinite() }?.let { it.roundToInt() }
            val low = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN)
                ?.takeIf { it.isFinite() }?.let { it.roundToInt() }
            val at = System.currentTimeMillis()
            val data = Reading(city.name, city.latitude, city.longitude,
                temp.roundToInt(), high, low, code, at)
            val serialized = JSONObject().put("city", city.name).put("lat", city.latitude)
                .put("lon", city.longitude).put("temp", data.temperature).put("code", code)
                .put("high", high ?: JSONObject.NULL).put("low", low ?: JSONObject.NULL)
                .put("at", at).toString()
            ctx.getSharedPreferences("weather-data", Context.MODE_PRIVATE)
                .edit().putString("forecast", serialized).apply()
            data
        } catch (_: Exception) { cached(ctx, city) }
    }

    fun glyph(code: Int) = when {
        code == 0 -> "☀"
        code <= 3 -> "⛅"
        code <= 48 -> "☁"
        code <= 67 || code in 80..82 -> "☂"
        code <= 77 || code in 85..86 -> "❄"
        code >= 95 -> "⚡"
        else -> "☁"
    }

    fun label(code: Int): String = I18n.text(when {
        code == 0 -> R.string.weather_clear
        code in 1..3 -> R.string.weather_partly
        code in 45..48 -> R.string.weather_fog
        code in 51..67 || code in 80..82 -> R.string.weather_rain
        code in 71..77 || code in 85..86 -> R.string.weather_snow
        code >= 95 -> R.string.weather_storm
        else -> R.string.weather_cloud
    })

    fun format(data: Reading, fahrenheit: Boolean): String {
        fun value(celsius: Int?): String {
            val n = celsius ?: return "—"
            return (if (fahrenheit) (n * 9.0 / 5.0 + 32.0).roundToInt() else n).toString()
        }
        return I18n.text(R.string.weather_card_text, glyph(data.code), data.city,
            value(data.temperature), value(data.high), value(data.low), label(data.code),
            if (fahrenheit) "F" else "C")
    }
}
