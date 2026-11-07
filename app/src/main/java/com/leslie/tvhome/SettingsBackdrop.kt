package com.leslie.tvhome

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View

/**
 * tvOS 26 Settings over the user's own Home wallpaper, blurred once. Shared by Settings and Software Update.
 * Worker-thread only: [load] may decode a photo.
 */
internal object SettingsBackdrop {
    /** Neutral graphite, never a hue: the page colour before (or without) a wallpaper. */
    const val TOP = 0xFF3A3A3E.toInt()
    const val BOTTOM = 0xFF161618.toInt()

    /**
     * Home's own frosted frame and the background it was baked from: the blurred photo, or one still
     * of the Aerial film. Home publishes it as it bakes its dock glass, so Settings costs no decode.
     */
    @Volatile var home: Pair<String, Bitmap>? = null

    /** A small blurred picture, about 22 % darkened (the same as Home's frost). */
    fun load(context: Context): Bitmap {
        val key = Prefs(context).background
        home?.takeIf { it.first == key }?.let { return it.second }
        // Home not baked yet (Settings opened from the app row): decode the photo small, blur it here.
        if (Wallpapers.isPhoto(key)) runCatching {
            Wallpapers(context, Wallpapers.sourceFor(key)).current()
                ?.let { SettingsActivity.decodeIcon(it, 480) }
                ?.let { Wallpapers.blurred(it, darken = 0.22f) }
        }.getOrNull()?.let { return it }
        // A film with no frame yet, a gradient theme, or no photo cached: plain graphite.
        val image = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        Canvas(image).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 18f, TOP, BOTTOM, Shader.TileMode.CLAMP)
        })
        return image
    }

    /**
     * The page as drawn: [source] cropped to the screen, darkened to ~36 % overall and a little more
     * behind the left column, so white text holds over snow or cloud. Drawn once per open.
     */
    fun compose(source: Bitmap, sw: Int, sh: Int): Bitmap {
        val width = 480
        val height = (width.toLong() * sh / sw).toInt().coerceAtLeast(1)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height)
        canvas.drawBitmap(source, Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
        }, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.drawColor(Color.argb(18, 0, 0, 0))
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, width * 0.5f, 0f,
                Color.argb(72, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        return image
    }
}

/**
 * The tvOS 26 material, baked: a 28 dp round rect of 11 % white over the blurred page, a lit 1 px rim
 * and a soft fall-off shadow outside its edge (the parent must not clip children). With [scene], the
 * panel first paints its own share of the blurred page, so it hides sharp content behind (dialogs).
 * Static: no live blur, nothing animates.
 */
internal class GlassPanel(private val owner: View, private val scene: Bitmap? = null, radiusDp: Int = 28) : Drawable() {
    private val density = owner.resources.displayMetrics.density
    private val radius = radiusDp * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private val location = IntArray(2)
    private val texture = scene?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    private var rim: Shader? = null

    override fun onBoundsChange(bounds: Rect) {
        rim = LinearGradient(0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            Color.argb(64, 255, 255, 255), Color.argb(14, 255, 255, 255), Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        // Shadow: thin rings outside the edge, fading out and falling slightly; never under the glass.
        paint.style = Paint.Style.STROKE
        val step = 2 * density
        paint.strokeWidth = step
        for (i in 0 until 8) {
            val grow = step * i + step / 2
            rect.set(bounds); rect.inset(-grow, -grow); rect.offset(0f, 3 * density)
            paint.color = Color.argb(16 - i * 2, 0, 0, 0)
            canvas.drawRoundRect(rect, radius + grow, radius + grow, paint)
        }
        paint.style = Paint.Style.FILL
        rect.set(bounds)
        paint.color = Color.WHITE
        texture?.let { t ->
            val b = scene!!
            owner.getLocationInWindow(location)
            val screen = owner.rootView
            val scale = maxOf(screen.width.toFloat() / b.width, screen.height.toFloat() / b.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((screen.width - b.width * scale) / 2 - location[0], (screen.height - b.height * scale) / 2 - location[1])
            t.setLocalMatrix(matrix)
            paint.shader = t
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
        }
        paint.color = Color.argb(28, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.WHITE
        paint.shader = rim
        rect.inset(0.5f, 0.5f)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
