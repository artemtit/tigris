package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Shared tvOS aerial/local video wallpaper. Video decoding stays in the hardware
 * layer. Glass receives bounded, *asynchronous* PixelCopy snapshots; unlike
 * TextureView.getBitmap(), the GPU readback never blocks the remote-control UI.
 *
 * The extra source Surface is only borrowed for a single PixelCopy and released.
 * PixelCopy can fail on vendor codecs: video keeps playing and the static glass
 * remains in place until a good frame arrives.
 *
 * Aerials never stream: each film plays from a finished download while the next one in the
 * shuffled [AerialQueue] downloads; the current film loops until the next is on disk.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class VideoWallpaper(
    private val ctx: Context,
    host: FrameLayout,
    private val canSample: () -> Boolean,
    private val onFrame: (Bitmap?) -> Unit,
    /** True while the film covers the screen, so the picture under it need not be drawn. */
    private val onCover: (Boolean) -> Unit = {},
) {
    // Films fill the screen (see crop): opaque, so the GPU composites them without blending.
    private val texture = TextureView(ctx).apply { alpha = 0f; isOpaque = true }
    var frame: Bitmap? = null
        private set

    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val layer = object : FrameLayout(ctx) {
        override fun dispatchDraw(canvas: Canvas) {
            if (canvas.isHardwareAccelerated) super.dispatchDraw(canvas)
            else if (texture.alpha > 0f) frame?.let {
                canvas.drawBitmap(it, null, Rect(0, 0, width, height), framePaint)
            }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val copyThread = HandlerThread("TigrisVideoGlassCopy").apply { start() }
    private val copyHandler = Handler(copyThread.looper)
    private var player: ExoPlayer? = null
    private var key: String? = null
    private var aerial = false
    private var failures = 0
    private var failedKey: String? = null // survives stop(): don't restart a source that just failed
    // Background priority: a download never competes with the UI or an app's cold start.
    private val dlThread = HandlerThread("TigrisAerialDownload", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val dl = Handler(dlThread.looper)
    @Volatile private var dlGeneration = 0L
    private var fetching = false
    private var current: File? = null
    private var ready: File? = null
    /** The local video loops in place of the aerials until the first film is on disk. */
    private var filler = false
    private val hevc by lazy { MediaQuality.hevcDecoderPresent() }
    private var aspect = 16f / 9f
    private var captureGeneration = 0L
    private var pendingCapture = false
    private var captureSurface: Surface? = null
    private var captureStartedAt = 0L
    private var failedCopies = 0
    private var nextCaptureAt = 0L
    private val lowEnd by lazy { Device.isLowEnd(ctx) }
    private val timeout = Runnable { nextAfterError() }

    private val sample = object : Runnable {
        override fun run() {
            if (player == null) return
            val now = SystemClock.uptimeMillis()
            // Vendor PixelCopy can swallow callbacks. Never hold the busy flag forever.
            if (pendingCapture && now - captureStartedAt > 3_000L) {
                captureSurface?.release()
                captureSurface = null
                captureGeneration++
                pendingCapture = false
                failedCopies++
                nextCaptureAt = now + 5_000L
            }
            if (!pendingCapture && now >= nextCaptureAt && texture.alpha > 0f &&
                texture.isAvailable && canSample() && texture.width > 0 && texture.height > 0
            ) {
                capture(now)
            }
            main.postDelayed(this, if (lowEnd) 350L else 200L)
        }
    }

    init {
        layer.addView(texture, FrameLayout.LayoutParams(-1, -1))
        host.addView(layer, FrameLayout.LayoutParams(-1, -1))
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop() }
        // Films used to live in the cache, which the system wipes; the old folder goes once.
        dl.post { File(ctx.cacheDir, "aerials").deleteRecursively() }
    }

    private fun show(on: Boolean) {
        texture.alpha = if (on) 1f else 0f
        onCover(on)
    }

    /** Called from the UI thread; only enqueues an asynchronous PixelCopy. */
    private fun capture(now: Long) {
        val surfaceTexture = texture.surfaceTexture ?: return
        val width = minOf(if (lowEnd) 320 else 480, texture.width)
        val height = (width.toLong() * texture.height / texture.width).toInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: run {
                nextCaptureAt = now + 10_000L
                return
            }
        val generation = captureGeneration
        pendingCapture = true
        captureStartedAt = now
        nextCaptureAt = now + if (lowEnd) 1_000L else 500L
        val surface = Surface(surfaceTexture)
        captureSurface = surface
        try {
            PixelCopy.request(surface, bitmap, { result ->
                surface.release()
                main.post {
                    if (captureSurface === surface) captureSurface = null
                    if (generation != captureGeneration || player == null) {
                        bitmap.recycle()
                        return@post
                    }
                    pendingCapture = false
                    if (result == PixelCopy.SUCCESS) {
                        failedCopies = 0
                        frame = bitmap
                        onFrame(bitmap)
                    } else {
                        bitmap.recycle()
                        failedCopies++
                        // Some vendor codecs do not support copying their video layer.
                        // Back off rather than spin and interfere with remote input.
                        nextCaptureAt = SystemClock.uptimeMillis() +
                            if (failedCopies >= 3) 10_000L else 1_500L
                    }
                }
            }, copyHandler)
        } catch (_: Exception) {
            surface.release()
            if (captureSurface === surface) captureSurface = null
            bitmap.recycle()
            pendingCapture = false
            failedCopies++
            nextCaptureAt = now + if (failedCopies >= 3) 10_000L else 1_500L
        }
    }

    fun start(source: String) {
        val file = MediaFiles.wallpaper(ctx)
        val nextKey = if (source == LOCAL) "$source:${file?.lastModified()}:${file?.length()}" else source
        if (key == nextKey || failedKey == nextKey) return
        failedKey = null
        stop()
        key = nextKey
        if (source == LOCAL && file == null) { fail(); return }
        aerial = source == AERIAL
        // Both sources play local files only, so small buffers never starve.
        val buffers = DefaultLoadControl.Builder()
            .setBufferDurationsMs(5000, 15000, 500, 1000)
            .setTargetBufferBytes(24 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false).build()
        val p = ExoPlayer.Builder(ctx).setLoadControl(buffers).build()
        player = p
        p.volume = 0f
        // Aerials loop too: the film repeats until the next download is ready.
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.setVideoTextureView(texture)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player !== p) return
                main.removeCallbacks(timeout)
                failures = 0
                show(true)
                main.removeCallbacks(sample)
                sample.run()
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
                crop()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player === p) nextAfterError(error.errorCode)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (player !== p) return
                if (state == Player.STATE_BUFFERING) armTimeout()
                if (state == Player.STATE_READY) main.removeCallbacks(timeout)
            }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                // End of a loop: switch films here if the next one has finished downloading.
                if (player === p && aerial && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                    main.post { if (player === p && ready != null) showNext() }
            }
        })
        if (!aerial) { play(android.net.Uri.fromFile(file!!).toString()); return }
        // The newest finished film was downloaded ahead and not shown yet; older ones have played.
        val done = aerialDir(ctx).listFiles { f -> f.name.endsWith(".mov") }.orEmpty()
            .sortedByDescending { it.lastModified() }
        done.drop(1).let { old -> dl.post { old.forEach { it.delete() } } }
        // First run: the local video (or else the static background) stays until the first film is fully downloaded.
        ready = done.firstOrNull()
        if (ready != null) { showNext(); return }
        MediaFiles.wallpaper(ctx)?.let { filler = true; play(android.net.Uri.fromFile(it).toString()) }
        fetchNext()
    }

    private fun showNext() {
        val next = ready ?: return
        ready = null
        val old = current
        current = next
        filler = false
        play(android.net.Uri.fromFile(next).toString())
        if (old != null && old != next) dl.post { old.delete() }
        fetchNext()
    }

    /** Downloads the next queued film on [dl]; the result comes back to [onDownloaded]. */
    private fun fetchNext() {
        if (fetching || player == null || !aerial || ready != null) return
        fetching = true
        val gen = dlGeneration
        val keep = current?.name
        dl.post { download(gen, keep, 0) }
    }

    private fun onDownloaded(gen: Long, file: File) {
        if (gen != dlGeneration || player == null) return
        fetching = false
        ready = file
        // The local filler hands over at the end of its loop, like one film to the next.
        if (current == null && !filler) showNext()
    }

    /** No network or Apple unreachable: keep looping the current film and retry with backoff. */
    private fun download(gen: Long, keep: String?, attempt: Int) {
        if (gen != dlGeneration) return
        val file = runCatching { fetchOne(gen, keep) }.getOrNull()
        if (gen != dlGeneration) return
        if (file != null) main.post { onDownloaded(gen, file) }
        else dl.postDelayed({ download(gen, keep, attempt + 1) }, minOf(600_000L, 15_000L shl minOf(attempt, 6)))
    }

    /** Next film of the persisted shuffle that fits the disk budget, or null to retry later. */
    private fun fetchOne(gen: Long, keep: String?): File? {
        val prefs = Prefs(ctx)
        val films = Aerials.all(ctx).associateBy { it.id }
        var order = AerialQueue.order(films.keys.toList(), prefs.aerialQueue, Random)
        var pos = prefs.aerialQueuePos
        val dir = aerialDir(ctx)
        // ponytail: at most 10 films per attempt, so an all-too-big catalogue backs off instead of spinning.
        repeat(minOf(10, order.size)) {
            AerialQueue.at(order, pos, Random).let { order = it.first; pos = it.second }
            val film = films.getValue(order[pos])
            val file = File(dir, film.id + ".mov")
            if (file.name != keep) {
                // 4K films (500 MB+) never fit the budget: 2K HEVC first, AVC if HEVC isn't decodable.
                for (url in MediaQuality.aerialCandidates(film.url, film.hevc, false, hevc)) {
                    val part = File(dir, url.substringAfterLast('/') + ".part")
                    if (gen != dlGeneration) throw InterruptedIOException("stopped")
                    dir.listFiles().orEmpty().filter { it.name != keep && it != part }.forEach { it.delete() }
                    if (fetch(gen, url, part, dir)) {
                        if (!part.renameTo(file)) throw IOException("rename")
                        prefs.aerialQueue = order.joinToString(",")
                        prefs.aerialQueuePos = pos + 1
                        return file
                    }
                }
            }
            pos++
            prefs.aerialQueue = order.joinToString(",")
            prefs.aerialQueuePos = pos
        }
        return null
    }

    /** True once [part] holds the whole film; false if it is missing or doesn't fit; throws on network errors. */
    private fun fetch(gen: Long, url: String, part: File, dir: File): Boolean {
        val have = part.length()
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false // stay on the catalogue host
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val code = c.responseCode
            if (AerialQueue.skip(code)) return false
            if (code == 416) { part.delete(); throw IOException("HTTP 416") }
            if (code != 200 && code != 206) throw IOException("HTTP $code")
            if (code == 206 && c.getHeaderField("Content-Range")?.startsWith("bytes $have-") != true) throw IOException("bad range")
            val resumed = if (code == 206) have else 0L
            val length = c.contentLengthLong
            if (length <= 0) return false
            val total = resumed + length
            val others = dir.listFiles().orEmpty().filter { it != part }.sumOf { it.length() }
            if (!AerialQueue.fits(total, resumed, others, android.os.StatFs(dir.path).availableBytes)) {
                part.delete()
                return false
            }
            c.inputStream.use { input ->
                FileOutputStream(part, code == 206).use { out ->
                    val buf = ByteArray(64 * 1024)
                    val started = SystemClock.elapsedRealtime()
                    var got = 0L
                    while (true) {
                        if (gen != dlGeneration) throw InterruptedIOException("stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        // ponytail: average-rate cap, so eMMC and CPU stay free for the UI and app launches.
                        got += n
                        val ahead = got * 1000 / MAX_BYTES_PER_SECOND - (SystemClock.elapsedRealtime() - started)
                        if (ahead > 0) Thread.sleep(ahead)
                    }
                }
            }
            if (part.length() != total) throw IOException("short download")
            return true
        } finally {
            c.disconnect()
        }
    }

    private fun crop() {
        if (texture.width <= 0 || texture.height <= 0) return
        val viewAspect = texture.width.toFloat() / texture.height
        val sx = maxOf(1f, aspect / viewAspect)
        val sy = maxOf(1f, viewAspect / aspect)
        texture.setTransform(Matrix().apply { setScale(sx, sy, texture.width / 2f, texture.height / 2f) })
    }

    private fun armTimeout() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 20_000L)
    }

    private fun play(uri: String) {
        val p = player ?: return
        show(false)
        clearFrame()
        p.setMediaItem(MediaItem.fromUri(uri))
        armTimeout()
        p.prepare()
        p.play()
    }

    /** A film that can't be read (IO/parsing) is deleted; the next finished film (or the next download) takes over. */
    private fun nextAfterError(errorCode: Int = 0) {
        val p = player ?: return
        if (!aerial || ++failures >= 3) { fail(); return }
        main.removeCallbacks(timeout)
        p.stop()
        // Decoder errors and the timeout say nothing about the file: keep it.
        if (errorCode in 2000..3999) current?.let { f -> dl.post { f.delete() } }
        current = null
        filler = false
        show(false)
        clearFrame()
        if (ready != null) showNext() else fetchNext()
    }

    private fun fail() {
        val k = key
        stop()
        failedKey = k
        Toast.makeText(ctx, I18n.text(R.string.live_wallpaper_failed), Toast.LENGTH_LONG).show()
    }

    private fun clearFrame() {
        captureGeneration++
        captureSurface?.release()
        captureSurface = null
        pendingCapture = false
        frame = null
        onFrame(null)
    }

    fun stop() {
        freeze()
        show(false)
        clearFrame()
    }

    /**
     * Frees the decoder and the download but leaves the last frame on screen, for an app
     * launch: the cold start gets the memory, and the launch animation shows no change.
     * [start] plays again as after [stop].
     */
    fun freeze() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(sample)
        player?.release()
        player = null
        captureGeneration++
        captureSurface?.release()
        captureSurface = null
        pendingCapture = false
        key = null
        failures = 0
        // Cancels the download in flight (its .part resumes next time) and any pending retry.
        dlGeneration++
        dl.removeCallbacksAndMessages(null)
        fetching = false
        current = null
        ready = null
        filler = false
    }

    fun destroy() {
        stop()
        copyThread.quitSafely()
        dlThread.quitSafely()
    }

    companion object {
        const val LOCAL = "video_local"
        const val AERIAL = "video_aerial"
        private const val MAX_BYTES_PER_SECOND = 2L * 1024 * 1024
        val SOURCES = listOf(LOCAL, AERIAL)
        fun label(source: String) = I18n.text(if (source == LOCAL) R.string.live_wallpaper_local else R.string.live_wallpaper_aerial)
        /** Holds at most the film on screen and the one downloaded ahead. */
        fun aerialDir(ctx: Context) = File(ctx.noBackupFilesDir, "aerials").apply { mkdirs() }
    }
}
