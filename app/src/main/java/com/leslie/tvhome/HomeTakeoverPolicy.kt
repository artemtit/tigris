package com.leslie.tvhome

/** Only a confirmed HOME activity may trigger automatic takeover. Time is monotonic. */
internal class HomeTakeoverPolicy {
    enum class Window { UNKNOWN, STOCK_HOME, OTHER }
    private var window = Window.UNKNOWN
    private var observedAt = 0L
    private var lastTakeover = Long.MIN_VALUE / 2
    private val attempts = ArrayDeque<Long>()
    private var pausedUntil = 0L

    fun observe(window: Window, now: Long) {
        this.window = window
        observedAt = now
    }

    fun request(now: Long, enabled: Boolean, awake: Boolean, visitingStock: Boolean): Boolean {
        if (!enabled || !awake || visitingStock || window != Window.STOCK_HOME) return false
        if (now - observedAt !in SETTLE_MS..MAX_AGE_MS || now - lastTakeover < COOLDOWN_MS || now < pausedUntil) return false
        while (attempts.isNotEmpty() && now - attempts.first() >= WINDOW_MS) attempts.removeFirst()
        if (attempts.size >= MAX_ATTEMPTS) {
            pausedUntil = now + PAUSE_MS
            return false
        }
        attempts.addLast(now)
        lastTakeover = now
        window = Window.UNKNOWN
        return true
    }

    companion object {
        const val SETTLE_MS = 350L
        private const val MAX_AGE_MS = 2_000L
        private const val COOLDOWN_MS = 2_000L
        private const val WINDOW_MS = 30_000L
        private const val PAUSE_MS = 60_000L
        private const val MAX_ATTEMPTS = 3
    }
}
