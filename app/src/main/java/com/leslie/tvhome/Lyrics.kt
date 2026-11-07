package com.leslie.tvhome

import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs

/** Song lyrics from LRCLIB (lrclib.net, public, no key): time-synced when available, else plain. */
object Lyrics {
    data class Line(val ms: Long, val text: String)
    /** [synced]: lines with times; otherwise [plain] lines (times unknown, scrolled by position). */
    data class Text(val synced: List<Line>?, val plain: List<String>)

    private val STAMP = Regex("""^\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * LRC text to lines sorted by time. A line may carry several timestamps (a repeated chorus);
     * metadata tags ([ar:], [offset:]...), untimed and empty lines are dropped.
     */
    fun parse(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in lrc.lineSequence()) {
            var rest = raw.trim()
            val times = ArrayList<Long>()
            while (true) {
                val m = STAMP.find(rest) ?: break
                val (min, sec, frac) = m.destructured
                times += min.toLong() * 60_000 + sec.toLong() * 1000 + (if (frac.isEmpty()) 0 else frac.padEnd(3, '0').toLong())
                rest = rest.substring(m.range.last + 1).trimStart()
            }
            if (rest.isEmpty()) continue
            times.forEach { out += Line(it, rest) }
        }
        return out.sortedBy { it.ms }
    }

    /** The line sung at [ms]: the last one started; -1 before the first. */
    fun index(lines: List<Line>, ms: Long): Int = lines.indexOfLast { it.ms <= ms }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TigrisLyrics").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<String, Text?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Text?>) = size > 20
    }

    /** Cached lyrics for [key], including a known "none" (null); [hit] false when never looked up. */
    @Synchronized fun cached(key: String): Pair<Boolean, Text?> = (key in cache) to cache[key]

    /**
     * Looks the song up off the main thread; [done] gets the result there (null: none). Network
     * errors are not cached, so the next play of the song tries again.
     */
    fun fetch(key: String, artist: String, title: String, album: String?, durationSec: Long, done: (Text?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        worker.execute {
            val hit = cached(key)
            val result = if (hit.first) hit.second else runCatching { lookup(artist, title, album, durationSec) }
                .onSuccess { synchronized(this) { cache[key] = it } }.getOrNull()
            main.post { done(result) }
        }
    }

    private fun lookup(artist: String, title: String, album: String?, durationSec: Long): Text? {
        fun q(s: String) = URLEncoder.encode(s, "UTF-8")
        val get = "https://lrclib.net/api/get?artist_name=${q(artist)}&track_name=${q(title)}" +
            (album?.takeIf { it.isNotBlank() }?.let { "&album_name=${q(it)}" } ?: "") +
            (if (durationSec > 0) "&duration=$durationSec" else "")
        request(get)?.let { return text(org.json.JSONObject(it)) }
        val found = request("https://lrclib.net/api/search?track_name=${q(title)}&artist_name=${q(artist)}") ?: return null
        val list = org.json.JSONArray(found).let { a -> (0 until a.length()).map(a::getJSONObject) }
        val synced = list.filter { it.str("syncedLyrics").isNotBlank() }
        val best = synced.firstOrNull { durationSec > 0 && abs(it.optDouble("duration", 0.0) - durationSec) <= 3 }
            ?: synced.firstOrNull() ?: list.firstOrNull() ?: return null
        return text(best)
    }

    private fun text(o: org.json.JSONObject): Text? {
        val synced = parse(o.str("syncedLyrics")).takeIf { it.isNotEmpty() }
        val plain = o.str("plainLyrics").lines().map(String::trim).filter(String::isNotEmpty)
        return if (synced == null && plain.isEmpty()) null else Text(synced, plain)
    }

    private fun org.json.JSONObject.str(name: String) = if (isNull(name)) "" else optString(name)

    /** The body, or null on 404 (not found); other failures throw, so they are not cached. */
    private fun request(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setRequestProperty("User-Agent", "Tigris (https://github.com/artemtit/tigris)")
        return try {
            if (c.responseCode == 404) null
            else c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        } finally {
            c.disconnect()
        }
    }
}
