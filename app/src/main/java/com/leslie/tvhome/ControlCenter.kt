package com.leslie.tvhome

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Independent refracting glass controls floating over the home screen. */
class ControlCenter(private val home: HomeActivity, private val host: FrameLayout) {
    private val ctx = home
    private val prefs = Prefs(ctx)
    private val gap = ctx.dp(12)
    private val cell = ctx.dp(47)
    private val width = cell * 4 + gap * 3
    private val useLiveGlass = Build.VERSION.SDK_INT >= 31
    private val surfaces = ArrayList<GlassSurface>()
    @Volatile private var glassGeneration = 0
    private var destroyed = false
    private var refreshing = false
    private var glassReady = false
    private val glassIo = Executors.newSingleThreadExecutor()
    private val prepareGlass = Runnable { prepareGlassNow() }
    private val scrim = View(ctx).apply {
        setBackgroundColor(Color.argb(32, 0, 0, 0))
        alpha = 0f
        translationZ = ctx.dp(100).toFloat()
    }
    // Layout only: the gaps between controls show the actual wallpaper, with no enclosing panel.
    private val panel = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        translationZ = ctx.dp(101).toFloat()
        visibility = View.INVISIBLE
        alpha = 1f
    }
    // One pass draws the shared blur through all button shapes. Individual buttons draw only
    // their tint/rim, so the TV does not composite the same blurred scene ten times per frame.
    private val liveLayer = object : View(ctx) {
        private val mask = android.graphics.Path()
        private val box = RectF()
        private val toWindow = android.graphics.Matrix()
        private val fromWindow = android.graphics.Matrix()
        private val lightMatrix = android.graphics.Matrix()
        // Unit-radius light, moved and sized with a local matrix instead of a new shader per frame.
        private val pool = android.graphics.RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(60, 255, 255, 255), Color.argb(95, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.32f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
        private val edge = android.graphics.RadialGradient(0f, 0f, 1f,
            intArrayOf(Color.argb(230, 255, 255, 255), Color.TRANSPARENT),
            null, android.graphics.Shader.TileMode.CLAMP)
        // The View is also constructed on API 28, even though live glass is disabled there.
        // Preserve the original PLUS effect on API 29+, and use API 28-safe additive blending below.
        private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                blendMode = android.graphics.BlendMode.PLUS
            } else {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
            }
        }
        init { visibility = View.INVISIBLE; translationZ = ctx.dp(100.5f).toFloat() }
        override fun onDraw(canvas: Canvas) {
            mask.reset()
            // Shapes are mapped into this layer's own coordinates, so they line up with the controls exactly.
            fromWindow.reset()
            transformMatrixToGlobal(fromWindow)
            fromWindow.invert(fromWindow)
            for (surface in surfaces) {
                val v = surface.owner
                if (!v.isShown || v.width == 0) continue
                // The real on-screen shape, scale included and not cropped to the panel's bounds.
                box.set(0f, 0f, v.width.toFloat(), v.height.toFloat())
                toWindow.reset()
                v.transformMatrixToGlobal(toWindow)
                toWindow.postConcat(fromWindow)
                toWindow.mapRect(box)
                val radius = minOf(surface.radius * v.scaleX * panel.scaleX, box.width() / 2, box.height() / 2)
                mask.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW)
            }
            val save = canvas.save()
            canvas.clipPath(mask)
            home.drawGlassScene(canvas)
            if (glowA > 0f) {
                // The focused control lights the glass around it: a pool of light spilling
                // into its neighbours, and their rims catching it.
                // The light is kept in panel coordinates, so it rides along with the panel's own motion.
                toWindow.reset()
                panel.transformMatrixToGlobal(toWindow)
                toWindow.postConcat(fromWindow)
                lightMatrix.setScale(glowR, glowR)
                lightMatrix.postTranslate(glowX, glowY)
                lightMatrix.postConcat(toWindow)
                lightPaint.alpha = (255 * glowA).toInt()
                lightPaint.style = Paint.Style.FILL
                pool.setLocalMatrix(lightMatrix)
                lightPaint.shader = pool
                canvas.drawPaint(lightPaint)
                lightMatrix.setScale(glowR * 2.2f, glowR * 2.2f)
                lightMatrix.postTranslate(glowX, glowY)
                lightMatrix.postConcat(toWindow)
                edge.setLocalMatrix(lightMatrix)
                lightPaint.shader = edge
                lightPaint.style = Paint.Style.STROKE
                lightPaint.strokeWidth = ctx.dp(2.5f).toFloat()
                canvas.drawPath(mask, lightPaint)
            }
            canvas.restoreToCount(save)
        }
    }
    private var glowX = 0f
    private var glowY = 0f
    private var glowR = 0f
    private var glowA = 0f
    private var glowAnim: android.animation.ValueAnimator? = null

    /** Glides the light to the newly focused control. */
    private fun glowAt(v: View, strength: Float = if (v is Slider) 0.5f else 1f) {
        if (!useLiveGlass) return
        if (v.width == 0) { v.post { if (v.isFocused) glowAt(v) }; return }
        val r = android.graphics.Rect(0, 0, v.width, v.height)
        panel.offsetDescendantRectToMyCoords(v, r)
        val tx = r.exactCenterX()
        val ty = r.exactCenterY()
        val tr = maxOf(v.width, v.height) * 0.5f + ctx.dp(110)
        val fromX = glowX; val fromY = glowY; val fromR = glowR; val fromA = glowA
        glowAnim?.cancel()
        glowAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = Motion.ease
            addUpdateListener {
                val f = it.animatedFraction
                val fresh = fromA == 0f
                glowX = if (fresh) tx else fromX + (tx - fromX) * f
                glowY = if (fresh) ty else fromY + (ty - fromY) * f
                glowR = if (fresh) tr else fromR + (tr - fromR) * f
                glowA = fromA + (strength - fromA) * f
                liveLayer.invalidate()
            }
            start()
        }
    }

    private val memButton: GlassButton
    private val brightness: Slider
    private val volume: Slider
    private val inputModule: GlassButton
    private val networkModule: GlassButton
    private var inputs: List<Bravia.Input> = emptyList()
    private var volumeTarget = "speaker"
    private val status = ctx.text(11f, Color.argb(190, 255, 255, 255))
    private val focusables = ArrayList<View>()
    private val io = Executors.newSingleThreadExecutor()
    private val tv = TvControls(ctx)
    private val ticker = object : Runnable {
        override fun run() {
            followVolume()
            host.postDelayed(this, 2_000)
        }
    }

    private fun followVolume() {
        if (!isOpen) return
        io.execute {
            val v = tv.volume() ?: return@execute
            host.post { if (isOpen && !volume.hasFocus()) volume.set(v) }
        }
    }

    var isOpen = false
        private set

    init {
        // tvOS layout: a square power tile beside two capsules, then a row of round buttons.
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val power = GlassButton("关机", R.drawable.ic_power, Shape.TILE) {
            confirm("关机", "电视会彻底断电关闭，用遥控器电源键开机。") { Cleaner.shutdown(ctx) }
        }
        top.addView(power, LinearLayout.LayoutParams(0, cell * 2 + gap, 1f).apply { marginEnd = gap })
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        networkModule = GlassButton("网络", R.drawable.ic_wifi, Shape.CAPSULE, accent = Color.rgb(48, 222, 239)) {
            close()
            runCatching { ctx.startActivity(tv.wifiIntent()) }
        }
        right.addView(networkModule, LinearLayout.LayoutParams(-1, cell))
        right.addView(GlassButton("待机", R.drawable.ic_moon, Shape.CAPSULE) { needAdb { close(); Cleaner.sleep(ctx) } },
            LinearLayout.LayoutParams(-1, cell).apply { topMargin = gap })
        top.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(top)

        val utilities = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        fun utility(label: String, icon: Int, action: () -> Unit): GlassButton =
            GlassButton(label, icon, Shape.ROUND, action = action).also {
                utilities.addView(it, LinearLayout.LayoutParams(cell, cell).apply {
                    if (utilities.childCount > 0) marginStart = (this@ControlCenter.width - cell * 4) / 3
                })
            }
        memButton = utility("清理内存", R.drawable.ic_memory) { cleanNow() }
        utility("相框", R.drawable.ic_photo) {
            close()
            ctx.openFrame()
        }
        utility("重启", R.drawable.ic_restart) {
            confirm("重启", "电视会重新启动，大约需要一分钟。") { Cleaner.reboot(ctx) }
        }
        utility("设置", R.drawable.ic_gear) {
            close()
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
        }
        panel.addView(utilities, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })

        brightness = Slider(R.drawable.ic_sun, "亮度") { v -> tv.setBrightness(v) }.also { focusables += it }
        panel.addView(brightness, LinearLayout.LayoutParams(-1, ctx.dp(38)).apply { topMargin = gap })
        volume = Slider(R.drawable.ic_volume, "音量", steps = 50) { v -> tv.setVolume(volumeTarget, v) }.also { focusables += it }
        panel.addView(volume, LinearLayout.LayoutParams(-1, ctx.dp(38)).apply { topMargin = gap })
        inputModule = GlassButton("输入源", R.drawable.ic_input, Shape.CAPSULE) { pickInput() }
        panel.addView(inputModule, LinearLayout.LayoutParams(-1, cell).apply { topMargin = gap })
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
        // Keep the invisible layout measured so its glass can be prepared before opening.
        if (useLiveGlass) host.addView(liveLayer, FrameLayout.LayoutParams(-1, -1))
        // The host clips the panel to its bounds; the padding is room for focused controls to grow into.
        val room = ctx.dp(16)
        panel.setPadding(room, room, room, room)
        host.addView(panel, FrameLayout.LayoutParams(width + room * 2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = ctx.dp(66) - room; marginEnd = ctx.dp(32) - room
        })
        panel.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) refreshGlass()
        }
        panel.post { refresh() }
        host.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (isOpen && now != null && focusables.contains(now)) glowAt(now)
        }
        // Report cleanup without expanding the button and moving every control below it.
        status.setShadowLayer(ctx.dp(3).toFloat(), 0f, 0f, Color.BLACK)
    }

    /** Each control owns its own background, refraction, rounded rim and focus light. */
    private inner class GlassSurface(val owner: View, val radius: Float) : Drawable() {
        /** How much white the focused glass takes on; sliders keep it low so their fill reads. */
        var focusWash = 140
        var bitmap: Bitmap? = null
            set(value) { field?.recycle(); field = value; invalidateSelf() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val rect = RectF()
        init { surfaces += this }

        /** 0 = resting, 1 = focused; animated so the light swells in rather than switching. */
        var lit = 0f
            set(value) { field = value; invalidateSelf() }
        private var litAnim: android.animation.ValueAnimator? = null
        private var sheen: android.graphics.Shader? = null
        private var rim: android.graphics.Shader? = null
        private var spot: android.graphics.Shader? = null
        private val r get() = minOf(radius, bounds.width() / 2f, bounds.height() / 2f)

        fun light(on: Boolean) {
            litAnim?.cancel()
            litAnim = android.animation.ValueAnimator.ofFloat(lit, if (on) 1f else 0f).apply {
                duration = if (on) 280 else 200
                interpolator = Motion.ease
                addUpdateListener { lit = it.animatedValue as Float }
                start()
            }
        }

        override fun onBoundsChange(b: android.graphics.Rect) {
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            // A sheen across the upper half, as light falling on a curved top surface.
            sheen = android.graphics.LinearGradient(0f, 0f, 0f, h,
                intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.55f), android.graphics.Shader.TileMode.CLAMP)
            // Rim: bright where it faces the light (top left), a softer reflection bottom right.
            rim = android.graphics.LinearGradient(0f, 0f, w, h,
                intArrayOf(Color.WHITE, Color.argb(40, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(170, 255, 255, 255)),
                floatArrayOf(0f, 0.35f, 0.65f, 1f), android.graphics.Shader.TileMode.CLAMP)
            // The focus light: a soft glow pooled under the top left of the glass.
            spot = android.graphics.RadialGradient(w * 0.3f, h * 0.1f, maxOf(w, h) * 0.95f,
                intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                null, android.graphics.Shader.TileMode.CLAMP)
        }

        override fun draw(c: Canvas) {
            rect.set(bounds)
            val r = r
            if (!useLiveGlass) bitmap?.let { c.drawBitmap(it, null, rect, paint) }
            // Milky body: a light wash over the blurred scene, much brighter when focused.
            paint.color = if (!useLiveGlass && bitmap == null) Color.argb(90, 48, 52, 58)
                else Color.argb((34 * (1f - lit)).toInt(), 0, 0, 0)
            c.drawRoundRect(rect, r, r, paint)
            paint.color = Color.argb((10 + focusWash * lit).toInt(), 255, 255, 255)
            c.drawRoundRect(rect, r, r, paint)
            paint.color = Color.WHITE
            paint.shader = sheen
            paint.alpha = (110 + 145 * lit).toInt()
            c.drawRoundRect(rect, r, r, paint)
            if (lit > 0f) {
                paint.shader = spot
                paint.alpha = (255 * lit).toInt()
                c.drawRoundRect(rect, r, r, paint)
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ctx.dp(1f + 0.6f * lit).toFloat()
            paint.shader = rim
            paint.alpha = (150 + 105 * lit).toInt()
            rect.inset(paint.strokeWidth / 2, paint.strokeWidth / 2)
            c.drawRoundRect(rect, r, r, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }
        override fun getOutline(outline: android.graphics.Outline) = outline.setRoundRect(bounds, r)
        fun invalidateScene() { if (isOpen) invalidateSelf() }
        fun dispose() { bitmap = null }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun glassFor(view: View, radius: Float): GlassSurface = GlassSurface(view, radius).also {
        view.background = it
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
        // No elevation: through translucent glass the platform shadow shows a hard-edged hole.
    }

    private fun focus(view: View, glass: GlassSurface, on: Boolean, grow: Float = 1.08f) {
        glass.light(on)
        // The focused control lifts towards the viewer; the shared blur layer follows its shape.
        view.animate().cancel()
        view.animate().scaleX(if (on) grow else 1f).scaleY(if (on) grow else 1f)
            .setDuration(if (on) 260 else 200).setInterpolator(if (on) Motion.spring else Motion.ease)
            .setUpdateListener { if (useLiveGlass) liveLayer.invalidate() }
            .start()
    }

    /** Merge layout/scene changes; slider fill animations do not invalidate the glass scene. */
    fun refreshGlass() {
        if (destroyed) return
        if (useLiveGlass) { invalidateLiveScene(); return }
        host.removeCallbacks(prepareGlass)
        host.postDelayed(prepareGlass, 80)
    }

    /** Capture without overlays, then bake the real scene through every separate glass shape. */
    private fun prepareGlassNow() {
        if (useLiveGlass) return
        if (destroyed || panel.width == 0 || host.width == 0) return
        val generation = ++glassGeneration
        val source = snapshotBehind() ?: return
        val jobs = surfaces.filter { it.owner.visibility == View.VISIBLE && it.owner.width > 0 && it.owner.height > 0 }.map { surface ->
            val view = surface.owner
            var x = 0
            var y = 0
            var child: View = view
            while (child !== host) {
                x += child.left
                y += child.top
                child = child.parent as? View ?: break
            }
            surface to android.graphics.Rect(x, y, x + view.width, y + view.height)
        }
        val screenW = host.width
        val screenH = host.height
        // A correctly aligned scene sample is available immediately, even on a cold first open.
        // The refined glass replaces it on its own worker, never behind slow TV HTTP requests.
        if (!glassReady) {
            jobs.forEach { (surface, rect) ->
                val sample = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                Canvas(sample).drawBitmap(source, null,
                    RectF(-rect.left.toFloat(), -rect.top.toFloat(), (screenW - rect.left).toFloat(), (screenH - rect.top).toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG))
                surface.bitmap = sample
            }
            glassReady = true
        }
        glassIo.execute {
            if (glassGeneration != generation) { source.recycle(); return@execute }
            val baked = try {
                jobs.map { (surface, rect) -> surface to LiquidGlass.bake(source, screenW, screenH, rect, surface.radius) }
            } finally { source.recycle() }
            host.post {
                if (!destroyed && glassGeneration == generation) {
                    baked.forEach { (surface, bitmap) -> surface.bitmap = bitmap }
                }
                else baked.forEach { (_, bitmap) -> bitmap.recycle() }
            }
        }
    }

    fun invalidateLiveScene() {
        if (!useLiveGlass || destroyed) return
        home.invalidateGlassSource()
        if (isOpen) liveLayer.invalidate()
    }

    private fun updateLiveLayer() {
        if (!useLiveGlass) return
        liveLayer.alpha = panel.alpha
        liveLayer.invalidate()
    }

    private fun snapshotBehind(): Bitmap? {
        val oldPanel = panel.alpha
        val oldScrim = scrim.alpha
        return try {
            panel.alpha = 0f
            scrim.alpha = 0f
            // The result is blurred anyway: a quarter-size, clipped capture avoids rasterizing
            // the full home screen on the UI thread, especially its large Top Shelf image.
            val scale = 0.25f
            Bitmap.createBitmap((host.width * scale).toInt(), (host.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    scale(scale, scale)
                    val margin = ctx.dp(64)
                    clipRect(panel.left - margin, panel.top - margin, panel.right + margin, panel.bottom + margin)
                    host.draw(this)
                }
            }
        } catch (e: RuntimeException) {
            null
        } finally {
            panel.alpha = oldPanel
            scrim.alpha = oldScrim
        }
    }

    private enum class Shape { TILE, CAPSULE, ROUND }

    private inner class GlassButton(
        label: String,
        icon: Int,
        shape: Shape,
        accent: Int = Color.WHITE,
        action: () -> Unit,
    ) : LinearLayout(ctx) {
        // Capsules and circles clamp this to half their height.
        private val glass = glassFor(this, ctx.dp(if (shape == Shape.TILE) 24 else 100).toFloat())
        private val glyph = ImageView(ctx)
        val title = ctx.text(12.5f, weight = 600).apply { text = label; maxLines = 1 }
        val sub = ctx.text(10.5f, Color.argb(205, 255, 255, 255), weight = 500).apply {
            visibility = GONE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        init {
            id = View.generateViewId()
            contentDescription = label
            focusables += this
            orientation = if (shape == Shape.CAPSULE) HORIZONTAL else VERTICAL
            gravity = if (shape == Shape.CAPSULE) Gravity.CENTER_VERTICAL else Gravity.CENTER
            isFocusable = true
            isClickable = true
            if (shape == Shape.CAPSULE) setPadding(ctx.dp(13), 0, ctx.dp(10), 0)
            val size = ctx.dp(when (shape) { Shape.TILE -> 40; Shape.CAPSULE -> 20; Shape.ROUND -> 21 })
            addView(glyph.apply { setImageResource(icon); setColorFilter(accent) },
                LayoutParams(size, size).apply { if (shape == Shape.CAPSULE) marginEnd = ctx.dp(9) })
            // Round buttons are icon-only, as on tvOS; the label stays for accessibility.
            when (shape) {
                Shape.TILE -> addView(title, LayoutParams(-2, -2).apply { topMargin = ctx.dp(8) })
                Shape.CAPSULE -> addView(LinearLayout(ctx).apply {
                    orientation = VERTICAL
                    addView(title)
                    addView(sub, LayoutParams(-1, -2).apply { topMargin = ctx.dp(1) })
                }, LayoutParams(0, -2, 1f))
                Shape.ROUND -> {}
            }
            setOnClickListener { action() }
            setOnFocusChangeListener { _, on ->
                focus(this, glass, on, when (shape) { Shape.TILE -> 1.06f; Shape.CAPSULE -> 1.05f; Shape.ROUND -> 1.1f })
                // Lit glass is bright: dark glyphs keep the focused control readable on any wallpaper.
                val ink = Color.argb(225, 0, 0, 0)
                glyph.setColorFilter(if (on) ink else accent)
                title.setTextColor(if (on) ink else Color.WHITE)
                sub.setTextColor(if (on) Color.argb(170, 0, 0, 0) else Color.argb(205, 255, 255, 255))
            }
        }
    }

    /**
     * iOS-style capsule slider: the white fill is the value. Left/right change it,
     * and the TV follows while the key is held.
     */
    private inner class Slider(
        icon: Int,
        label: String,
        private val steps: Int = 25,
        private val send: (Int) -> Unit,
    ) : FrameLayout(ctx) {
        private val corner = ctx.dp(19).toFloat()
        private val glass = glassFor(this, corner).apply { focusWash = 0 }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillClip = android.graphics.Path()
        private var darkIcon = false
        private var lastInteraction = 0L
        private val img = ImageView(ctx).apply { setImageResource(icon) }
        private val nameText = ctx.text(12.5f, weight = 600).apply { text = label }
        private val valueText = ctx.text(12.5f, weight = 600)
        private var value = 0
        private var min = 0
        private var max = 100
        private var ready = false
        // Logical target drives hardware writes; displayedValue is visual-only and animates between targets.
        private var displayedValue = 0f
        private var valueAnimator: android.animation.ValueAnimator? = null
        // One write in flight at a time; presses that land meanwhile collapse into the latest value,
        // so holding the key changes the TV as fast as it can take it (BRAVIA: ~45 ms per write).
        private var sending = false
        private var sent = Int.MIN_VALUE

        private fun push() {
            if (sending || value == sent) return
            sending = true
            val v = value
            sent = v
            io.execute {
                send(v)
                host.post { sending = false; push() }
            }
        }

        init {
            isFocusable = true
            clipRounded(corner)
            setWillNotDraw(false)
            contentDescription = label
            addView(img, LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(14) })
            addView(nameText, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = ctx.dp(41) })
            addView(valueText, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = ctx.dp(15) })
            setOnFocusChangeListener { _, f ->
                focus(this, glass, f, 1.03f)
                draw()
            }
            setOnKeyListener { _, code, e ->
                if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                if (e.action == KeyEvent.ACTION_DOWN && ready) {
                    val dir = if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    val step = ((max - min) / steps).coerceAtLeast(1)
                    val nv = (value + dir * step).coerceIn(min, max)
                    if (nv == value) {
                        if (e.repeatCount == 0) Motion.bump(this, dir, 0)
                    } else {
                        lastInteraction = android.os.SystemClock.uptimeMillis()
                        value = nv
                        valueText.text = value.toString()
                        animateValueTo(value)
                        push()
                    }
                }
                true
            }
            set(null)
        }

        fun set(level: Bravia.Level?) {
            // An asynchronous read started before a key press must not overwrite its newer target.
            if (ready && (sending || android.os.SystemClock.uptimeMillis() - lastInteraction < 600)) return
            val wasReady = ready
            val oldTarget = value
            ready = level != null
            if (level != null) {
                min = level.min
                max = level.max
                value = level.value.coerceIn(min, max)
                sent = value
                valueText.text = value.toString()
                if (!wasReady) {
                    displayedValue = value.toFloat()
                    draw()
                } else if (value != oldTarget) animateValueTo(value) else draw()
            } else {
                valueAnimator?.cancel()
                valueAnimator = null
                displayedValue = min.toFloat()
                valueText.text = "—"
                draw()
            }
            alpha = if (ready) 1f else 0.45f
        }

        private fun animateValueTo(target: Int) {
            valueAnimator?.cancel()
            val end = target.toFloat()
            if (displayedValue == end) {
                draw()
                return
            }
            valueAnimator = android.animation.ValueAnimator.ofFloat(displayedValue, end).apply {
                duration = 100L
                interpolator = Motion.ease
                addUpdateListener {
                    displayedValue = it.animatedValue as Float
                    draw()
                }
                start()
            }
        }

        fun finishAnimation() {
            valueAnimator?.cancel()
            valueAnimator = null
            displayedValue = value.toFloat()
            draw()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            draw()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fraction = if (ready && max > min)
                ((displayedValue - min) / (max - min)).coerceIn(0f, 1f) else 0f
            fillPaint.color = Color.argb(if (isFocused) 255 else 190, 255, 255, 255)
            val save = canvas.save()
            fillClip.reset()
            fillClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, android.graphics.Path.Direction.CW)
            canvas.clipPath(fillClip)
            canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), fillPaint)
            canvas.restoreToCount(save)
        }

        private fun draw() {
            if (width <= 0) return
            val fraction = if (ready && max > min)
                ((displayedValue - min) / (max - min)).coerceIn(0f, 1f) else 0f
            val filled = width * fraction
            val dark = Color.argb(230, 0, 0, 0)
            val iconIsDark = filled > ctx.dp(32)
            if (darkIcon != iconIsDark) {
                darkIcon = iconIsDark
                img.setColorFilter(if (darkIcon) dark else Color.WHITE)
            }
            val nameColor = if (filled > ctx.dp(41) + nameText.width) dark else Color.WHITE
            if (nameText.currentTextColor != nameColor) nameText.setTextColor(nameColor)
            val valueColor = if (filled > width - ctx.dp(15)) dark else Color.WHITE
            if (valueText.currentTextColor != valueColor) valueText.setTextColor(valueColor)
            // Only drawing changes. No animated child width or per-frame text relayout.
            invalidate()
        }
    }

    // ---- open / close -------------------------------------------------------------

    fun open() {
        if (isOpen) return
        isOpen = true
        glowAnim?.cancel()
        glowA = 0f
        home.pauseShelfRotation()
        panel.animate().cancel()
        scrim.animate().cancel()
        refresh()
        home.setGridBlocked(true)
        if (scrim.parent == null) host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        panel.bringToFront()
        if (useLiveGlass) invalidateLiveScene() else if (!glassReady) prepareGlassNow()
        panel.visibility = View.VISIBLE
        if (useLiveGlass) liveLayer.visibility = View.VISIBLE
        panel.alpha = 0f
        if (useLiveGlass) liveLayer.alpha = 0f
        panel.translationX = ctx.dp(24).toFloat()
        panel.scaleX = 0.97f
        panel.scaleY = 0.97f
        panel.pivotX = panel.width - panel.paddingRight.toFloat()
        panel.pivotY = panel.paddingTop.toFloat()
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(320)
            .setInterpolator(Motion.ease).setUpdateListener { updateLiveLayer() }.start()
        memButton.requestFocus()
        host.removeCallbacks(ticker)
        host.post(ticker)
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        host.removeCallbacks(ticker)
        brightness.finishAnimation()
        volume.finishAnimation()
        scrim.animate().alpha(0f).setDuration(180).start()
        panel.animate().alpha(0f).translationX(ctx.dp(24).toFloat()).scaleX(0.97f).scaleY(0.97f).setDuration(200)
            .setUpdateListener { updateLiveLayer() }
            .withEndAction {
                host.removeView(scrim)
                panel.visibility = View.INVISIBLE
                liveLayer.visibility = View.INVISIBLE
            }.start()
        home.restoreGridFocus()
        home.resumeShelfRotation()
    }

    fun destroy() {
        destroyed = true
        isOpen = false
        ++glassGeneration
        host.removeCallbacks(ticker)
        host.removeCallbacks(prepareGlass)
        panel.animate().cancel()
        scrim.animate().cancel()
        surfaces.forEach { it.dispose() }
        io.shutdown()
        glassIo.shutdown()
    }

    fun refocus() {
        (focusables.firstOrNull { it.isFocused } ?: memButton).requestFocus()
    }

    fun refresh() {
        if (destroyed) return
        status.text = if (prefs.adbAuthorized) "" else "本机调试未授权：关机、待机、彻底清理不可用"
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val wired = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
        networkModule.title.text = if (wired) "有线网络" else "Wi-Fi"
        networkModule.sub.text = if (caps == null) "未连接" else "已连接"
        networkModule.sub.visibility = View.VISIBLE
        if (refreshing) return
        refreshing = true
        io.execute {
            val b = tv.brightness()
            val v = tv.volume()
            val list = tv.inputs()
            val cur = tv.currentInput()
            host.post {
                refreshing = false
                if (destroyed) return@post
                brightness.set(b)
                brightness.visibility = if (b == null) View.GONE else View.VISIBLE
                v?.target?.takeIf { it.isNotEmpty() }?.let { volumeTarget = it }
                volume.set(v)
                inputs = list
                inputModule.sub.visibility = View.VISIBLE
                inputModule.sub.text = list.firstOrNull { it.uri == cur }?.name
                    ?: when {
                        list.isEmpty() -> "无法读取电视的输入源"
                        list.first().uri.startsWith("tcl-input://") -> "TCL 信号源选择器"
                        list.first().uri.startsWith("extInput:") -> "当前：主屏幕"
                        else -> "${list.size} 个 HDMI 输入"  // Android can't tell which one is showing
                    }
            }
        }
    }

    private fun pickInput() {
        if (inputs.isEmpty()) return
        // The list replaces the panel instead of stacking on top of it.
        close()
        val sheet = Sheet(host).title("输入源").message(null)
        for (i in inputs) sheet.button(i.name, R.drawable.ic_input) {
            io.execute { tv.setInput(i) }
        }
        sheet.button("在主屏幕上显示…") { pinInputs() }
        sheet.button("取消") {}
        home.showSheet(sheet)
    }

    /** Inputs shown as tiles on the home grid; one press there switches to them. */
    private fun pinInputs() {
        val sheet = Sheet(host).title("在主屏幕上显示")
            .message("选中的输入源会成为主屏幕上的一个图标，按一下就切换过去。")
        for (i in inputs) {
            val on = home.isPinned(i)
            sheet.button(if (on) "✓  ${i.name}" else i.name, R.drawable.ic_input) { home.pinInput(i, !on) }
        }
        sheet.button("完成") {}
        home.showSheet(sheet)
    }

    private fun cleanNow() {
        status.text = "正在释放内存…"
        status.visibility = View.VISIBLE
        Thread {
            val r = Cleaner.clean(ctx.applicationContext)
            host.post {
                status.text = "已释放 ${r.freedMb} MB" + if (r.viaAdb) "" else "（仅缓存）"
            }
        }.start()
    }

    private fun needAdb(action: () -> Unit) {
        if (prefs.adbAuthorized) action() else home.showSheet(Sheet(host).title("需要先授权本机调试")
            .message("点“开始”后，电视会弹出“允许调试吗？”，请勾选“始终允许”，再点“允许”。")
            .button("开始") { home.authorizeAdb() }
            .button("取消") {})
    }

    private fun confirm(title: String, msg: String, action: () -> Unit) = needAdb {
        home.showSheet(Sheet(host).title("$title？").message(msg)
            .button(title) { action() }
            .button("取消") {})
    }
}
