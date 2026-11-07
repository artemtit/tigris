package com.leslie.tvhome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.concurrent.Executor

/** Which media session the home screen shows: one playing, else the last paused; never our own. */
object NowPlaying {
    data class Session(val pkg: String, val state: Int, val updated: Long)

    /** Buffering counts as playing: a track change or a Cast stream briefly buffers between songs. */
    private fun playing(s: Session) = s.state == PlaybackState.STATE_PLAYING || s.state == PlaybackState.STATE_BUFFERING

    /** [list] in the system's order (most important first); the first playing one wins. */
    fun pick(list: List<Session>, own: String): Session? {
        val others = list.filter { it.pkg != own }
        return others.firstOrNull(::playing)
            ?: others.filter { it.state == PlaybackState.STATE_PAUSED }.maxByOrNull { it.updated }
    }
}

/**
 * tvOS "Now Playing" card for the top-right corner: artwork, title, artist and source, then
 * previous / play-pause / next. Shown only while some app has a playing or paused media session.
 * The sessions come through the notification listener ([RevivalListener]); without that access
 * the card simply never appears. Event driven: no polling.
 */
class NowPlayingCard(
    ctx: Context,
    private val io: Executor,
    /** UP from the card: the corner buttons. */
    private val onUp: () -> Unit,
    /** DOWN from the card, or the card going away while focused: back to the dock. */
    private val onDown: () -> Unit,
) : LinearLayout(ctx) {
    private val main = Handler(Looper.getMainLooper())
    private val sessions = ctx.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(ctx, RevivalListener::class.java)
    private var controllers: List<MediaController> = emptyList()
    private var current: MediaController? = null
    private var artKey: String? = null
    private var started = false
    private var label: Pair<String, String>? = null

    /** A session worth showing; false while the card fades out, so the dock no longer leads here. */
    val active get() = current != null

    private val art = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipRounded(ctx.dp(8).toFloat()) }
    private val title = line(ctx.text(15f, weight = 600))
    private val artist = line(ctx.text(13f, Color.argb(200, 255, 255, 255)))
    private val source = line(ctx.text(11f, Color.argb(150, 255, 255, 255), weight = 500))
    private val idle = rounded(Color.TRANSPARENT, ctx.dp(12).toFloat())
    private val lit = rounded(Color.WHITE, ctx.dp(12).toFloat())
    /** Artwork and text; OK opens the playing app. */
    private val body = LinearLayout(ctx).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = idle
        setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        addView(art, LayoutParams(ctx.dp(44), ctx.dp(44)))
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(source)
            addView(title, LayoutParams(-2, -2).apply { topMargin = ctx.dp(3) })
            addView(artist, LayoutParams(-2, -2).apply { topMargin = ctx.dp(2) })
        }, LayoutParams(-2, -2).apply { marginStart = ctx.dp(10) })
        contentDescription = I18n.text(R.string.now_playing)
        setOnClickListener { open() }
        setOnFocusChangeListener { _, f ->
            background = if (f) lit else idle
            title.setTextColor(if (f) Color.BLACK else Color.WHITE)
            artist.setTextColor(if (f) Color.argb(170, 0, 0, 0) else Color.argb(200, 255, 255, 255))
            source.setTextColor(if (f) Color.argb(140, 0, 0, 0) else Color.argb(150, 255, 255, 255))
            tweenScale(if (f) 1.04f else 1f, 150)
        }
    }
    private val prev = IconButton(ctx, R.drawable.ic_skip_prev) { current?.transportControls?.skipToPrevious() }
    val playPause = IconButton(ctx, R.drawable.ic_play) {
        current?.let { c -> if (isPlaying(c)) c.transportControls.pause() else c.transportControls.play() }
    }
    private val next = IconButton(ctx, R.drawable.ic_skip_next) { current?.transportControls?.skipToNext() }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
        // The dark glass of the low-end control panel, lighter so the wallpaper still shows through.
        background = rounded(Color.argb(150, 30, 30, 34), ctx.dp(18).toFloat())
        setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        addView(body, LayoutParams(-2, -2))
        prev.contentDescription = I18n.text(R.string.now_playing_previous)
        playPause.contentDescription = I18n.text(R.string.now_playing_play_pause)
        next.contentDescription = I18n.text(R.string.now_playing_next)
        listOf(prev, playPause, next).forEach { addView(it, LayoutParams(ctx.dp(32), ctx.dp(32)).apply { marginStart = ctx.dp(8) }) }
        listOf(body, prev, playPause, next).forEach { v ->
            v.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) false else when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                    // The row ends at the card's edges; don't let focus leak to the dock or the clock.
                    KeyEvent.KEYCODE_DPAD_LEFT -> v == body
                    KeyEvent.KEYCODE_DPAD_RIGHT -> v == next
                    else -> false
                }
            }
        }
    }

    private fun line(t: android.widget.TextView) = t.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = context.dp(200)
    }

    /** From onStart: needs the notification listener; without it nothing is shown. */
    fun start() {
        if (started) return
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main)
            started = true
            watch(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            stop()
        }
    }

    /** From onStop: no callbacks are left behind on the sessions. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        show(null)
    }

    /** Listens to every session, so a paused one starting to play takes the card over. */
    private fun watch(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list
        list.forEach { it.registerCallback(callback, main) }
        refresh()
    }

    private fun refresh() {
        if (!started) return show(null)
        val infos = controllers.map {
            NowPlaying.Session(it.packageName, it.playbackState?.state ?: PlaybackState.STATE_NONE, it.playbackState?.lastPositionUpdateTime ?: 0L)
        }
        show(NowPlaying.pick(infos, context.packageName)?.let { controllers[infos.indexOf(it)] })
    }

    private fun isPlaying(c: MediaController) = c.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }

    private fun show(c: MediaController?) {
        current = c
        if (c == null) {
            if (visibility == View.VISIBLE) {
                val hadFocus = hasFocus()
                animate().cancel()
                animate().alpha(0f).setDuration(200).withEndAction { if (current == null) visibility = View.GONE }.start()
                if (hadFocus) onDown()
            }
            artKey = null
            return
        }
        val meta = c.metadata
        val pkg = c.packageName
        val app = appName(pkg)
        title.text = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() } ?: app
        artist.text = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        artist.visibility = if (artist.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        source.text = app
        playPause.setIcon(if (isPlaying(c)) R.drawable.ic_pause else R.drawable.ic_play)
        // The artwork is scaled once per track, off the main thread; the app icon stands in for none.
        val key = "$pkg|${title.text}|${artist.text}"
        if (key != artKey) {
            artKey = key
            val bmp = meta?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val size = context.dp(44)
            io.execute {
                val d: Drawable? = runCatching {
                    if (bmp != null) BitmapDrawable(resources, Bitmap.createScaledBitmap(bmp, size, size, true))
                    else context.packageManager.getApplicationIcon(pkg)
                }.getOrNull()
                main.post { if (artKey == key) art.setImageDrawable(d) }
            }
        }
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f
        }
        animate().cancel()
        animate().alpha(1f).setDuration(250).start()
    }

    /** The Cast receiver carries Spotify Connect, YouTube and others; it has no name of its own worth showing. */
    private fun appName(pkg: String): String =
        if (pkg == CAST) "Google Cast"
        else label?.takeIf { it.first == pkg }?.second
            ?: runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
                .also { label = pkg to it }

    private fun open() {
        val c = current ?: return
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(c.packageName) ?: pm.getLaunchIntentForPackage(c.packageName)
        runCatching {
            if (launch != null) context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            else c.sessionActivity?.send()
        }
    }

    private companion object {
        const val CAST = "com.google.android.apps.mediashell"
    }
}
