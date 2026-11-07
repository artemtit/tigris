package com.leslie.tvhome

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
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

    /** The music mode opens after this long without a key on the home screen while something plays. */
    const val IDLE_MS = 60_000L
    /** An open music mode closes once playback has stayed paused this long. */
    const val PAUSE_MS = 10_000L

    /** Milliseconds until the music mode opens (0: now); null while nothing plays or something else is open. */
    fun openIn(playing: Boolean, blocked: Boolean, idleMs: Long): Long? =
        if (!playing || blocked) null else (IDLE_MS - idleMs).coerceAtLeast(0)

    /** Milliseconds until an open music mode closes: at once without a session, after [PAUSE_MS] paused, never while playing. */
    fun closeIn(session: Boolean, playing: Boolean): Long? = when {
        !session -> 0
        playing -> null
        else -> PAUSE_MS
    }

    /** Remote keys that go on to the session and leave the music mode open; any other key closes it. */
    fun passesThrough(code: Int) = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY ||
        code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS

    /** The card's dark glass as opaque RGB (the card adds its alpha); also the accent of colourless artwork. */
    const val BASE = 0xFF1E1E22.toInt()
    /** Accent luminance cap (0..255): a tint never gets bright enough to wash out white text. */
    private const val MAX_LUMA = 110

    /**
     * One accent colour from an artwork's pixels (a tiny downscale is plenty): the mean of the
     * saturated ones, or of all of them when too few are, dimmed to [MAX_LUMA]. Opaque ARGB.
     */
    fun accent(px: IntArray): Int {
        fun saturated(c: Int): Boolean {
            val max = maxOf(c shr 16 and 255, c shr 8 and 255, c and 255)
            val min = minOf(c shr 16 and 255, c shr 8 and 255, c and 255)
            return max > 40 && (max - min) * 4 > max
        }
        val use = px.filter(::saturated).takeIf { it.isNotEmpty() && it.size * 10 >= px.size } ?: px.toList()
        if (use.isEmpty()) return BASE
        var r = 0L; var g = 0L; var b = 0L
        use.forEach { r += it shr 16 and 255; g += it shr 8 and 255; b += it and 255 }
        r /= use.size; g /= use.size; b /= use.size
        val luma = (2126 * r + 7152 * g + 722 * b) / 10_000
        if (luma > MAX_LUMA) { r = r * MAX_LUMA / luma; g = g * MAX_LUMA / luma; b = b * MAX_LUMA / luma }
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    /** [a] moved [f] of the way to [b], per RGB channel, with alpha [alpha]. */
    fun mix(a: Int, b: Int, f: Float, alpha: Int): Int {
        fun ch(s: Int) = ((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * f).toInt()
        return (alpha shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Cross-fades to [d] over [ms], then keeps only [d], so the previous picture can be collected. */
private fun ImageView.crossfade(d: Drawable?, ms: Int) {
    val t = TransitionDrawable(arrayOf(drawable ?: ColorDrawable(Color.TRANSPARENT), d ?: ColorDrawable(Color.TRANSPARENT)))
    t.isCrossFadeEnabled = true
    setImageDrawable(t)
    t.startTransition(ms)
    postDelayed({ if (drawable === t) setImageDrawable(d) }, ms.toLong())
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
    private val glass = rounded(NowPlaying.mix(NowPlaying.BASE, NowPlaying.BASE, 0f, 150), ctx.dp(18).toFloat())
    private val tint = Tween()
    private var tinted = NowPlaying.BASE

    /** A session worth showing; false while the card fades out, so the dock no longer leads here. */
    val active get() = current != null
    val playing get() = current?.let(::isPlaying) == true
    /** Identifies the shown track and its artwork; null with no session. */
    val track get() = artKey
    val titleText: CharSequence get() = title.text
    val artistText: CharSequence get() = artist.text
    /** The artwork's accent colour, [NowPlaying.BASE] without artwork. */
    var accent = NowPlaying.BASE
        private set
    /** After every change of the shown session (track, state, artwork) and its going away. */
    var onChange: (() -> Unit)? = null

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
        // The artwork's colour tints it a little (see [tintTo]).
        background = glass
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
        // Nothing kept while another app is in front: the next start loads the artwork again.
        art.setImageDrawable(null)
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
            onChange?.invoke()
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
        // Apps often send the title first and the artwork a moment later, hence its size in the key.
        val key = "$pkg|${title.text}|${artist.text}|" +
            (ART_KEYS.firstNotNullOfOrNull { meta?.getBitmap(it) }?.let { "${it.width}x${it.height}" } ?: artUri(meta))
        if (key != artKey) {
            artKey = key
            val size = context.dp(44)
            io.execute {
                val small = runCatching { cover(meta, size) }.getOrNull()
                val d: Drawable? = small?.let { BitmapDrawable(resources, it) }
                    ?: runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
                // A 12x12 copy is plenty for one colour.
                val color = small?.let { s ->
                    val tiny = Bitmap.createScaledBitmap(s, 12, 12, true)
                    IntArray(144).also { tiny.getPixels(it, 0, 12, 0, 0, 12, 12); tiny.recycle() }.let(NowPlaying::accent)
                } ?: NowPlaying.BASE
                main.post {
                    if (artKey != key) return@post
                    art.crossfade(d, Motion.glideMs(size))
                    tintTo(color)
                    onChange?.invoke()
                }
            }
        }
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f
        }
        animate().cancel()
        animate().alpha(1f).setDuration(250).start()
        onChange?.invoke()
    }

    /** Glides the glass a little towards [color]: only the drawable's colour changes, nothing is baked. */
    private fun tintTo(color: Int) {
        if (color == accent) return
        accent = color
        val from = tinted
        val to = NowPlaying.mix(NowPlaying.BASE, color, 0.45f, 255)
        tint.start(Motion.glideMs(dp(120)).toLong(), Motion.glide) { f ->
            tinted = NowPlaying.mix(from, to, f, 255)
            glass.setColor(NowPlaying.mix(tinted, tinted, 0f, 150))
        }
    }

    /** A [size]-square copy of the current artwork, scaled off the main thread; null without artwork. */
    fun cover(size: Int, done: (Bitmap?) -> Unit) {
        val meta = current?.metadata
        val key = artKey
        io.execute {
            val b = runCatching { cover(meta, size) }.getOrNull()
            main.post { if (artKey == key) done(b) }
        }
    }

    /**
     * [meta]'s artwork centre-cropped into a new [size]-square bitmap: always a copy, the session's
     * own bitmap is left alone. A URI is read through the content resolver, decoded near [size].
     */
    private fun cover(meta: MediaMetadata?, size: Int): Bitmap? {
        val src = ART_KEYS.firstNotNullOfOrNull { meta?.getBitmap(it) } ?: artUri(meta)?.let { uri ->
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = size.toFloat() / minOf(info.size.width, info.size.height)
                    if (s < 1f) d.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
                }
            }.getOrNull()
        } ?: return null
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(src, Rect(x, y, x + side, y + side), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun artUri(meta: MediaMetadata?) = ART_URIS.firstNotNullOfOrNull { meta?.getString(it)?.takeIf(String::isNotBlank) }

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
        val ART_KEYS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val ART_URIS = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
    }
}

/**
 * "Music mode": while music plays and nobody touches the remote, the cover, title and artist
 * over the dimmed wallpaper, lit by the artwork's colour. One static full-screen fill (the scrim
 * is the glow); nothing animates while it is open. Keys are handled by [HomeActivity].
 */
class MusicMode(private val host: FrameLayout, private val card: NowPlayingCard) {
    private val ctx = host.context
    private val size = (ctx.resources.displayMetrics.heightPixels * 0.4f).toInt()
    private val radius = size * 0.06f
    /** Dim scrim and radial glow in one drawable. */
    private val glow = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = ctx.resources.displayMetrics.widthPixels * 0.6f
        setGradientCenter(0.5f, 0.42f)
    }
    private val glowTween = Tween()
    private var lit = card.accent
    private val root = FrameLayout(ctx).apply {
        background = glow
        alpha = 0f
        clipChildren = false
        // Above focused tiles, which lift themselves with translationZ; no shadow of its own.
        translationZ = ctx.dp(200).toFloat()
        outlineProvider = null
    }
    private val cover = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        // Without artwork the empty glass square keeps the layout where it was.
        background = rounded(Color.argb(28, 255, 255, 255), radius)
        clipRounded(radius)
        elevation = ctx.dp(18).toFloat()
    }
    private val title = ctx.text(34f, weight = 700).apply { letterSpacing = -0.02f; oneLine() }
    private val artist = ctx.text(20f, Color.argb(190, 255, 255, 255)).apply { oneLine() }
    private var key: String? = null
    var isShowing = false
        private set

    init {
        val w = (ctx.resources.displayMetrics.widthPixels * 0.6f).toInt()
        root.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            addView(cover, LinearLayout.LayoutParams(size, size))
            addView(title, LinearLayout.LayoutParams(w, -2).apply { topMargin = ctx.dp(36) })
            addView(artist, LinearLayout.LayoutParams(w, -2).apply { topMargin = ctx.dp(8) })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        paint(lit)
    }

    private fun android.widget.TextView.oneLine() {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun show() {
        if (isShowing) return
        isShowing = true
        if (root.parent == null) host.addView(root, FrameLayout.LayoutParams(-1, -1))
        bind()
        root.animate().cancel()
        root.animate().alpha(1f).setDuration(Motion.glideMs(size).toLong()).setInterpolator(Motion.glide).start()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(250).setInterpolator(Motion.ease).withEndAction { if (!isShowing) release() }.start()
    }

    /** At once, letting the cover's bitmap go (onStop, low memory). */
    fun release() {
        isShowing = false
        root.animate().cancel()
        glowTween.cancel()
        root.alpha = 0f
        host.removeView(root)
        cover.setImageDrawable(null)
        key = null
    }

    /** Follows the card: a new track cross-fades its cover in, its colour glides in once. */
    fun bind() {
        if (!isShowing) return
        title.text = card.titleText
        artist.text = card.artistText
        if (card.accent != lit) {
            val from = lit
            val to = card.accent
            lit = to
            glowTween.start(Motion.glideMs(size).toLong(), Motion.glide) { f -> paint(NowPlaying.mix(from, to, f, 255)) }
        }
        val track = card.track ?: return
        if (track == key) return
        key = track
        card.cover(size) { b -> if (isShowing && key == track) cover.crossfade(b?.let { BitmapDrawable(ctx.resources, it) }, Motion.glideMs(size)) }
    }

    /** Dark all over, with a dim breath of [accent] behind the cover. */
    private fun paint(accent: Int) {
        glow.colors = intArrayOf(NowPlaying.mix(0, accent, 0.55f, 200), NowPlaying.mix(0, accent, 0.12f, 215), Color.argb(225, 0, 0, 0))
    }
}
