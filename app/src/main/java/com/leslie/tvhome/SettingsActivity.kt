package com.leslie.tvhome

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import java.util.concurrent.Executors
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.leslie.tvhome.tv.TvControls

/**
 * tvOS-style settings: context and a glass icon on the left, grouped rows on the right.
 * Sub-pages (keep list, hidden apps) reuse the same list.
 */
class SettingsActivity : Activity() {
    companion object {
        const val SONY_DREAM = "com.sony.dtv.braviashow/com.sony.dtv.braviashow.BraviaDayDreamService"
        const val AVATAR_FILE = "profile_avatar.png"
        fun avatarFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, AVATAR_FILE).takeIf { it.isFile }
        const val ICON_FILE = "settings_icon.png"
        const val BACKDROP_FILE = "settings_bg.jpg"

        /**
         * A picture of the user's own behind the whole settings page (Apple TV's, a TV on a blue haze,
         * say), with the TV where the big icon would be; uploaded from the phone page or pushed over adb.
         */
        fun backdropFile(ctx: android.content.Context): java.io.File? =
            listOfNotNull(java.io.File(ctx.filesDir, BACKDROP_FILE), ctx.getExternalFilesDir(null)?.let { java.io.File(it, BACKDROP_FILE) })
                .firstOrNull { it.isFile && it.length() > 0 }

        /**
         * A picture of the user's own for the settings icon (Apple TV's, say), uploaded from the phone
         * page or pushed over adb to Android/data/com.leslie.tvhome/files; null for the built-in gear.
         * Like the boot video, it is not part of the app.
         */
        fun customIcon(ctx: android.content.Context): java.io.File? = if (Prefs(ctx).ownIcon) iconFile(ctx) else null

        /** The uploaded picture whether or not it is in use ([Prefs.ownIcon]). */
        fun iconFile(ctx: android.content.Context): java.io.File? =
            listOfNotNull(java.io.File(ctx.filesDir, ICON_FILE), ctx.getExternalFilesDir(null)?.let { java.io.File(it, ICON_FILE) })
                .firstOrNull { it.isFile && it.length() > 0 }

        /** The icon decoded at no more than about [width] px wide: a 1280 px picture shown at 600 needn't take 6 MB. */
        fun decodeIcon(f: java.io.File, width: Int): android.graphics.Bitmap? {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var sample = 1
            while (o.outWidth / (sample * 2) >= width) sample *= 2
            return android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }

        private val IDLE_TEXT = Color.argb(235, 255, 255, 255)
        private val IDLE_VALUE = Color.argb(153, 255, 255, 255)
        private val IDLE_CHEVRON = Color.argb(102, 255, 255, 255)
        private val FOCUS_TEXT = Color.BLACK
        private val FOCUS_VALUE = Color.rgb(99, 99, 102)
        private val FOCUS_CHEVRON = Color.rgb(142, 142, 147)
        /** Focus growth, about the row's centre; the list's side padding leaves room for it. */
        private const val FOCUS_SCALE = 1.025f

        /** Opens straight on a sub-page (the Home and Control Center network buttons); Back then leaves. */
        const val EXTRA_PAGE = "page"
        const val PAGE_NETWORK = "network"
        const val PAGE_DEVICE = "device"
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepo
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView
    private lateinit var about: TextView
    private lateinit var icon: ImageView
    private lateinit var backdrop: ImageView
    private lateinit var pageShade: View
    private lateinit var scroll: ScrollView
    private lateinit var root: FrameLayout
    private lateinit var glass: UpdateGlass
    private lateinit var focusedTitle: TextView
    /** The category glyph on show; null while the user's own icon is in use. */
    private var glyph = 0
    private var pageGlyph = R.drawable.ic_cat_system
    private val artwork = Executors.newSingleThreadExecutor()
    private var scene: Bitmap? = null
    private var backdropKey: String? = null
    private var backdropRevision = 0
    private var activeDialog: SettingsDialog? = null
    private var page = Page.MAIN
    /** Per page, the row that opened a deeper page: focused again on the way back. */
    private val openers = HashMap<Page, Int>()
    private var entry = Page.MAIN
    /** onCreate has just shown the page; showing it again started a second Wi-Fi probe. */
    private var firstResume = true

    private enum class Page { MAIN, LOOK, HOMESCREEN, SAVER, REMOTE, APPS, MEMORY, NETWORK, DEVICE, KEEP, HIDDEN, REMOVED, SHELF_APP, REMAP }

    /** Where Back goes: pickers return to their category, categories to the main list. */
    private fun parent(p: Page) = when (p) {
        Page.KEEP -> Page.MEMORY
        Page.HIDDEN, Page.REMOVED -> Page.APPS
        Page.SHELF_APP -> Page.HOMESCREEN
        Page.REMAP -> Page.REMOTE
        else -> Page.MAIN
    }

    private fun up() {
        if (page == Page.MAIN || page == entry) finish()
        else parent(page).let { show(it, openers[it] ?: 0) }
    }

    private fun focusedRow() = list.indexOfChild(list.focusedChild).coerceAtLeast(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepo(this)
        root = FrameLayout(this).apply {
            // tvOS dark: a neutral near-black fall, no hue (the bitmap below paints the same gradient).
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(SettingsBackdrop.TOP, SettingsBackdrop.BOTTOM))
            clipChildren = false; clipToPadding = false
        }
        glass = UpdateGlass(root, moving = true)
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        pageShade = View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x28000000, 0x00000000, 0xb0000000.toInt()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(pageShade, FrameLayout.LayoutParams(-1, -1))
        // Safe area ~90 x 60 px at 1080p (Android TV is 960 x 540 dp at any density); list ~52 % wide.
        val sideMargin = dp(45)
        val safeTop = dp(30)
        val listWidth = (resources.displayMetrics.widthPixels * 0.52f).toInt()
        val leftWidth = resources.displayMetrics.widthPixels - listWidth - sideMargin * 2 - dp(24)
        // One line, shrinking for long category names so it never runs into the glyph.
        heading = text(40f, weight = 700).apply {
            maxLines = 1; letterSpacing = -0.02f
            setAutoSizeTextTypeUniformWithConfiguration(26, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        root.addView(heading, FrameLayout.LayoutParams(leftWidth, dp(52)).apply {
            leftMargin = sideMargin; topMargin = safeTop
        })
        // tvOS: a large bare glyph for the focused category, its title and description beneath, one left column.
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
        }
        icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            showIcon(this)
        }
        left.addView(icon, LinearLayout.LayoutParams(dp(220), dp(220)))
        focusedTitle = text(28f, weight = 700).apply {
            letterSpacing = -0.01f
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        left.addView(focusedTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        about = text(16f, Color.argb(153, 255, 255, 255)).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        left.addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(left, FrameLayout.LayoutParams(leftWidth, -1).apply {
            leftMargin = sideMargin; topMargin = dp(100); bottomMargin = safeTop
        })
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            // Room for the focused row to grow into: rows scale 1.025 about their centre, ~6 dp per side at 500 dp.
            setPadding(dp(12), dp(10), dp(12), dp(14))
        }
        scroll = object : ScrollView(this) {
            override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean) = false
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = true; clipChildren = true
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(list)
            setOnScrollChangeListener { _, _, _, _, _ -> glass.refresh() }
        }
        val viewport = FrameLayout(this).apply {
            clipChildren = true; clipToPadding = true
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
        }
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.clipBounds = Rect(0, 0, v.width, v.height)
        }
        root.addView(viewport, FrameLayout.LayoutParams(listWidth + dp(24), -1, Gravity.END).apply {
            rightMargin = sideMargin - dp(12); topMargin = safeTop - dp(10); bottomMargin = safeTop - dp(14)
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> glass.refresh() }
        setContentView(root)
        showBackdrop()
        entry = when (intent.getStringExtra(EXTRA_PAGE)) {
            PAGE_NETWORK -> Page.NETWORK
            PAGE_DEVICE -> Page.DEVICE
            else -> Page.MAIN
        }
        show(entry)
    }

    private fun show(p: Page, focusIndex: Int = 0) {
        page = p
        pageGlyph = glyphFor(if (parent(p) == Page.MAIN) p else parent(p))
        setGlyph(pageGlyph)
        glass.removeWithin(list)
        list.removeAllViews()
        scroll.scrollTo(0, 0)
        when (p) {
            Page.MAIN -> main()
            Page.LOOK -> look()
            Page.HOMESCREEN -> homeScreen()
            Page.SAVER -> saver()
            Page.REMOTE -> remote()
            Page.APPS -> apps()
            Page.MEMORY -> memory()
            Page.REMAP -> remapPicker()
            Page.KEEP -> appPicker(I18n.text(R.string.i18n_settingsactivity_001), prefs.keep) { pkg, on ->
                prefs.keep = if (on) prefs.keep + pkg else prefs.keep - pkg
            }
            Page.HIDDEN -> appPicker(I18n.text(R.string.i18n_settingsactivity_002), prefs.hidden, includeHidden = true) { pkg, on ->
                prefs.hidden = if (on) prefs.hidden + pkg else prefs.hidden - pkg
            }
            Page.REMOVED -> removedApps()
            Page.SHELF_APP -> shelfAppPicker()
            Page.NETWORK -> network()
            Page.DEVICE -> device()
        }
        list.post {
            val target = list.getChildAt(focusIndex.coerceIn(0, maxOf(list.childCount - 1, 0)))
            val focus = target?.takeIf { it.isFocusable } ?: (0 until list.childCount)
                .map { list.getChildAt(it) }.firstOrNull { it.isFocusable }
            focus?.requestFocus()
            focus?.let { reveal(it, immediate = true) }
        }
    }

    /** The uploaded background, whose TV stands in for the big icon; or the plain dark page. RGB_565: half the memory. */
    private val downloadingAssets = mutableSetOf<String>()

    override fun onResume() {
        super.onResume()
        backdropKey = null
        showBackdrop()
        if (firstResume) firstResume = false
        // Values may have changed in another activity (wallpaper, updates); pickers keep their state.
        else if (page == Page.MAIN || parent(page) == Page.MAIN) show(page, focusedRow())
    }

    private fun optionalAssetRow(asset: OptionalAssets.Asset, title: Int) {
        row(I18n.text(title), I18n.text(R.string.optional_asset_help), {
            I18n.text(if (asset.id in downloadingAssets) R.string.asset_downloading
                else if (OptionalAssets.file(this, asset) != null) R.string.asset_remove else R.string.asset_download)
        }) { value ->
            if (asset.id !in downloadingAssets) {
                downloadingAssets.add(asset.id)
                value.text = I18n.text(R.string.asset_downloading)
                val app = applicationContext
                Thread {
                    val result = runCatching {
                        if (OptionalAssets.file(app, asset) != null) OptionalAssets.delete(app, asset)
                        else OptionalAssets.download(app, asset)
                    }
                    runOnUiThread {
                        downloadingAssets.remove(asset.id)
                        if (!isDestroyed && !isFinishing) {
                            Toast.makeText(this, I18n.text(if (result.isSuccess) R.string.asset_done else R.string.asset_failed), Toast.LENGTH_LONG).show()
                            showBackdrop()
                            if (page == Page.SAVER) show(Page.SAVER, focusedRow())
                        }
                    }
                }.start()
            }
        }
    }

    private fun showBackdrop() {
        val file = if (prefs.settingsBg) backdropFile(this) else null
        val selected = file
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val key = "${selected?.path}:${selected?.lastModified()}:${prefs.settingsBg}:${prefs.background}:$sw:$sh"
        if (key == backdropKey) return
        backdropKey = key
        val revision = ++backdropRevision
        artwork.execute {
            val original = selected?.let { runCatching { decodeIcon(it, 1440) }.getOrNull() }
            val source = original ?: SettingsBackdrop.load(this)
            val width = 720
            val height = (width.toLong() * sh / sw).toInt().coerceAtLeast(1)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(image)
            val scale = maxOf(width.toFloat() / source.width, height.toFloat() / source.height) * if (original != null) 1.08f else 1f
            val matrix = android.graphics.Matrix().apply {
                setScale(scale, scale)
                postTranslate(if (original != null) width - source.width * scale else (width - source.width * scale) / 2f,
                    (height - source.height * scale) / 2f)
            }
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            // The user's own picture stays, but well behind the text, as tvOS keeps its page dark.
            if (original != null) canvas.drawColor(Color.argb(165, 0, 0, 0))
            // Match the actual crop and page scrim; never re-center the original artwork in the glass.
            val shade = Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0x28000000, 0x00000000, 0xb0000000.toInt()), null,
                    android.graphics.Shader.TileMode.CLAMP)
            }
            val material = image.copy(Bitmap.Config.ARGB_8888, true)
            if (original != null) Canvas(material).drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
            runOnUiThread {
                if (!isFinishing && !isDestroyed && revision == backdropRevision) {
                    backdrop.setImageBitmap(image)
                    pageShade.visibility = if (original != null) View.VISIBLE else View.GONE
                    icon.visibility = if (original != null || resources.configuration.fontScale > 1.15f) View.INVISIBLE else View.VISIBLE
                    scene = material
                    glass.texture(material)
                }
            }
        }
    }

    override fun onPause() {
        activeDialog?.dismiss()
        activeDialog = null
        super.onPause()
    }

    override fun onDestroy() {
        backdropRevision++
        activeDialog?.dismiss()
        glass.close()
        artwork.shutdownNow()
        super.onDestroy()
    }

    /** The user's own settings picture, bare; otherwise the focused category's glyph. */
    private fun showIcon(v: ImageView) {
        val own = customIcon(this)?.let { decodeIcon(it, dp(380)) }
        (v.getTag(R.id.motion_focus) as? Tween)?.cancel()
        v.alpha = 1f
        if (own != null) {
            glyph = 0
            v.clearColorFilter()
            v.setImageBitmap(own)
        } else {
            glyph = pageGlyph
            v.setColorFilter(Color.argb(217, 255, 255, 255))
            v.setImageResource(glyph)
        }
    }

    private fun glyphFor(p: Page) = when (p) {
        Page.LOOK -> R.drawable.ic_cat_look
        Page.HOMESCREEN -> R.drawable.ic_cat_home
        Page.SAVER -> R.drawable.ic_cat_saver
        Page.REMOTE -> R.drawable.ic_cat_remote
        Page.APPS -> R.drawable.ic_cat_apps
        Page.MEMORY -> R.drawable.ic_cat_memory
        Page.NETWORK -> R.drawable.ic_cat_network
        Page.DEVICE -> R.drawable.ic_cat_device
        else -> R.drawable.ic_cat_system
    }

    /** Swaps the glyph with a quick critically damped crossfade (~200 ms); a frame per change, nothing continuous. */
    private fun setGlyph(res: Int) {
        if (glyph == 0 || glyph == res) return
        glyph = res
        val t = icon.getTag(R.id.motion_focus) as? Tween ?: Tween().also { icon.setTag(R.id.motion_focus, it) }
        val from = icon.alpha
        t.start(90, Motion.ease, end = {
            icon.setImageResource(glyph)
            t.start(130, Motion.ease) { f -> icon.alpha = f }
        }) { f -> icon.alpha = from * (1 - f) }
    }

    /** This build runs on one HEC TV: install guides for other brands' firmware stay hidden here. */
    private val hec = android.os.Build.MANUFACTURER.equals("HEC", true) || android.os.Build.BRAND.equals("HEC", true)

    /** 4K wallpaper / Aerial quality choices only matter on a panel that can show 4K. */
    private val uhdPanel by lazy {
        @Suppress("DEPRECATION") windowManager.defaultDisplay.supportedModes.any { it.physicalHeight >= 2160 }
    }

    /** tvOS: a short list of categories; each opens its own page. */
    private fun main() {
        heading.text = I18n.text(R.string.i18n_settingsactivity_003)
        about.text = ""
        row(I18n.text(R.string.cat_look), I18n.text(R.string.cat_look_help), { backgroundLabel() }, chevron = true, glyph = R.drawable.ic_cat_look) { show(Page.LOOK) }
        row(I18n.text(R.string.cat_home), I18n.text(R.string.cat_home_help), { "" }, chevron = true, glyph = R.drawable.ic_cat_home) { show(Page.HOMESCREEN) }
        row(I18n.text(R.string.cat_saver), I18n.text(R.string.cat_saver_help), { onOff(prefs.frameScreensaver) }, chevron = true, glyph = R.drawable.ic_cat_saver) { show(Page.SAVER) }
        row(I18n.text(R.string.cat_remote), I18n.text(R.string.cat_remote_help), { "" }, chevron = true, glyph = R.drawable.ic_cat_remote) { show(Page.REMOTE) }
        spacer()
        row(I18n.text(R.string.cat_apps), I18n.text(R.string.cat_apps_help), { "" }, chevron = true, glyph = R.drawable.ic_cat_apps) { show(Page.APPS) }
        row(I18n.text(R.string.cat_memory), I18n.text(R.string.cat_memory_help),
            { I18n.text(R.string.i18n_settingsactivity_096, Cleaner.availMb(this)) }, chevron = true, glyph = R.drawable.ic_cat_memory) { show(Page.MEMORY) }
        row(I18n.text(R.string.settings_network), I18n.text(R.string.settings_network_help), { networkLabel() }, chevron = true, glyph = R.drawable.ic_cat_network) {
            show(Page.NETWORK)
        }
        row(I18n.text(R.string.settings_device), I18n.text(R.string.settings_device_help),
            { android.os.Build.MODEL }, chevron = true, glyph = R.drawable.ic_cat_device) { show(Page.DEVICE) }
        spacer()
        // Everything that needs system permissions (Bluetooth, picture, sound, joining networks):
        // one clearly labelled way out instead of grey jumps scattered through the list.
        row(I18n.text(R.string.i18n_settingsactivity_136), I18n.text(R.string.cat_system_help), { "" }, chevron = true) {
            runCatching { startActivity(TvControls(this).settingsIntent()) }
        }
    }

    private fun backgroundLabel() = Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background)).let {
        if (prefs.background == Wallpapers.MINE) it else I18n.text(R.string.i18n_settingsactivity_064, it)
    }

    private fun look() {
        heading.text = I18n.text(R.string.cat_look)
        about.text = I18n.text(R.string.cat_look_help)
        section(I18n.text(R.string.section_wallpaper))
        row(I18n.text(R.string.i18n_settingsactivity_062), I18n.text(R.string.i18n_settingsactivity_063), { backgroundLabel() },
            chevron = true) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        row(I18n.text(R.string.i18n_settingsactivity_065), I18n.text(R.string.i18n_settingsactivity_066), { minutesLabel(prefs.wallMinutes) }) {
            val opts = listOf(5, 15, 30, 60, 180, 1440)
            prefs.wallMinutes = opts[(opts.indexOf(prefs.wallMinutes) + 1) % opts.size]
        }
        row(I18n.text(R.string.i18n_settingsactivity_067), I18n.text(R.string.i18n_settingsactivity_068),
            { I18n.text(R.string.i18n_settingsactivity_069, Wallpapers(this, Wallpapers.sourceFor(prefs.background)).files().size) }) { v ->
            v.text = I18n.text(R.string.i18n_settingsactivity_070)
            Thread {
                val w = Wallpapers(applicationContext, Wallpapers.sourceFor(prefs.background))
                w.refresh()
                w.advance(prefs.wallMinutes, force = true)
                runOnUiThread { v.text = I18n.text(R.string.i18n_settingsactivity_071, w.files().size) }
            }.start()
        }
        if (uhdPanel) row(I18n.text(R.string.media_quality_wall_title), I18n.text(R.string.media_quality_wall_desc), {
            when (prefs.wallQuality) {
                MediaQuality.UHD -> I18n.text(R.string.media_quality_4k)
                MediaQuality.COMPAT -> I18n.text(R.string.media_quality_1080)
                else -> I18n.text(R.string.media_quality_auto)
            }
        }) {
            prefs.wallQuality = when (prefs.wallQuality) {
                MediaQuality.AUTO -> MediaQuality.UHD
                MediaQuality.UHD -> MediaQuality.COMPAT
                else -> MediaQuality.AUTO
            }
            Thread {
                runCatching { Wallpapers(applicationContext, Wallpapers.sourceFor(prefs.background)).refresh(force = true) }
            }.start()
        }

        section(I18n.text(R.string.section_clock))
        row(I18n.text(R.string.i18n_settingsactivity_009), I18n.text(R.string.i18n_settingsactivity_010),
            { if (prefs.clock24) I18n.text(R.string.i18n_settingsactivity_011) else I18n.text(R.string.i18n_settingsactivity_012) }) { prefs.clock24 = !prefs.clock24 }
        row(I18n.text(R.string.i18n_settingsactivity_013), I18n.text(R.string.i18n_settingsactivity_014), {
            when (prefs.clockStyle) { Prefs.CLOCK_LARGE -> I18n.text(R.string.i18n_settingsactivity_015); Prefs.CLOCK_OFF -> I18n.text(R.string.i18n_settingsactivity_016); else -> I18n.text(R.string.i18n_settingsactivity_017) }
        }) {
            val opts = listOf(Prefs.CLOCK_CORNER, Prefs.CLOCK_LARGE, Prefs.CLOCK_OFF)
            prefs.clockStyle = opts[(opts.indexOf(prefs.clockStyle) + 1) % opts.size]
        }

        section(I18n.text(R.string.section_interface))
        row(I18n.text(R.string.dock_columns_title), I18n.text(R.string.dock_columns_description),
            { prefs.dockColumns.toString() }) { prefs.dockColumns = DockLayout.next(prefs.dockColumns) }
        row(I18n.text(R.string.i18n_settingsactivity_031), I18n.text(R.string.i18n_settingsactivity_032) +
            I18n.text(R.string.i18n_settingsactivity_033), {
            auto(prefs.focusStyle, prefs.focusTvos(this), "tvOS", I18n.text(R.string.i18n_settingsactivity_034))
        }) {
            prefs.focusStyle = next(prefs.focusStyle, Prefs.FOCUS_TVOS, Prefs.FOCUS_SIMPLE)
        }
        row(I18n.text(R.string.i18n_settingsactivity_035), I18n.text(R.string.i18n_settingsactivity_036) +
            I18n.text(R.string.i18n_settingsactivity_037), {
            auto(prefs.ccStyle, prefs.ccGlass(this), I18n.text(R.string.i18n_settingsactivity_038), I18n.text(R.string.i18n_settingsactivity_039))
        }) {
            prefs.ccStyle = next(prefs.ccStyle, Prefs.CC_GLASS, Prefs.CC_PANEL)
        }
        row(I18n.text(R.string.i18n_settingsactivity_029), I18n.text(R.string.i18n_settingsactivity_030), { onOff(prefs.showAvatar) }, checked = { prefs.showAvatar }) { prefs.showAvatar = !prefs.showAvatar }
        row(I18n.text(R.string.i18n_settingsactivity_026), I18n.text(R.string.i18n_settingsactivity_027), { "" }, chevron = true) {
            startActivity(Intent(this, WallpaperActivity::class.java).putExtra("upload_avatar", true))
        }
        if (backdropFile(this) != null) row(I18n.text(R.string.i18n_settingsactivity_018), I18n.text(R.string.i18n_settingsactivity_019), {
            if (prefs.settingsBg) I18n.text(R.string.i18n_settingsactivity_020) else I18n.text(R.string.i18n_settingsactivity_021)
        }) {
            prefs.settingsBg = !prefs.settingsBg
            showBackdrop()
        }
        if (iconFile(this) != null) row(I18n.text(R.string.i18n_settingsactivity_022), I18n.text(R.string.i18n_settingsactivity_023), {
            if (prefs.ownIcon) I18n.text(R.string.i18n_settingsactivity_024) else I18n.text(R.string.i18n_settingsactivity_025)
        }) {
            prefs.ownIcon = !prefs.ownIcon
            showIcon(icon)
        }
    }

    private fun homeScreen() {
        heading.text = I18n.text(R.string.cat_home)
        about.text = I18n.text(R.string.cat_home_help)
        // Weather is a self-contained optional card. No network traffic until opt-in.
        section(I18n.text(R.string.weather_settings))
        row(I18n.text(R.string.weather_show), I18n.text(R.string.weather_settings_help),
            { onOff(prefs.weatherEnabled) }, checked = { prefs.weatherEnabled }) { prefs.weatherEnabled = !prefs.weatherEnabled }
        row(I18n.text(R.string.weather_city_settings), I18n.text(R.string.weather_city_help),
            { prefs.weatherCity.ifBlank { I18n.text(R.string.weather_city_unknown) } }, chevron = true) { value ->
            selectWeatherCity { city ->
                prefs.weatherCity = city.name
                prefs.weatherLatitude = city.latitude
                prefs.weatherLongitude = city.longitude
                value.text = city.name
            }
        }
        row(I18n.text(R.string.weather_unit_settings), I18n.text(R.string.weather_unit_help),
            { if (prefs.weatherFahrenheit) "°F" else "°C" }) {
            prefs.weatherFahrenheit = !prefs.weatherFahrenheit
        }

        section(I18n.text(R.string.shelf_custom_section))
        row(I18n.text(R.string.shelf_custom_url), I18n.text(R.string.shelf_custom_url_help),
            { shelfUrlLabel() }, chevron = true) { value ->
            editShelfUrl { value.text = shelfUrlLabel() }
        }
        row(I18n.text(R.string.shelf_custom_app), I18n.text(R.string.shelf_custom_app_help),
            { shelfAppLabel() }, chevron = true) { show(Page.SHELF_APP) }
        row(I18n.text(R.string.shelf_custom_clear), I18n.text(R.string.shelf_custom_clear_help), { "" }) {
            prefs.shelfCustomUrl = ""
            prefs.shelfCustomPkg = ""
            show(page, openers[page] ?: 0)
        }
    }

    private fun saver() {
        heading.text = I18n.text(R.string.cat_saver)
        about.text = I18n.text(R.string.cat_saver_help)
        section(I18n.text(R.string.cat_saver))
        row(I18n.text(R.string.i18n_settingsactivity_072), I18n.text(R.string.i18n_settingsactivity_073),
            { onOff(prefs.frameScreensaver) }, checked = { prefs.frameScreensaver }) { v ->
            val on = !prefs.frameScreensaver
            val current = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()
            val mine = "$packageName/${PhotoDream::class.java.name}"
            // Whatever the TV had before goes back when the frame is turned off (Sony's own if unknown).
            if (on && !current.startsWith(packageName)) prefs.prevScreensaver = current
            val comp = if (on) mine else prefs.prevScreensaver.ifEmpty { SONY_DREAM }
            v.text = I18n.text(R.string.i18n_settingsactivity_074)
            Thread {
                val ok = putSecure("screensaver_components", comp) && putSecure("screensaver_enabled", "1")
                if (ok) prefs.frameScreensaver = on
                runOnUiThread { v.text = if (ok) onOff(prefs.frameScreensaver) else I18n.text(R.string.i18n_settingsactivity_075) }
            }.start()
        }
        row(I18n.text(R.string.i18n_settingsactivity_076), I18n.text(R.string.i18n_settingsactivity_077),
            { if (prefs.saverMode == Prefs.SAVER_AERIAL) I18n.text(R.string.i18n_settingsactivity_078) else I18n.text(R.string.i18n_settingsactivity_079) }) {
            prefs.saverMode = if (prefs.saverMode == Prefs.SAVER_AERIAL) Prefs.SAVER_PHOTOS else Prefs.SAVER_AERIAL
        }
        row(I18n.text(R.string.i18n_settingsactivity_080), I18n.text(R.string.i18n_settingsactivity_081), { minutesLabel(idleMinutes()) }) { v ->
            val opts = listOf(1, 3, 5, 10, 15, 30)
            val next = opts[(opts.indexOf(idleMinutes()) + 1) % opts.size]
            v.text = I18n.text(R.string.i18n_settingsactivity_082)
            Thread {
                val ok = LocalAdb.shell(applicationContext, "settings put system screen_off_timeout ${next * 60_000}") != null
                runOnUiThread { v.text = if (ok) minutesLabel(next) else I18n.text(R.string.i18n_settingsactivity_083) }
            }.start()
        }
        row(I18n.text(R.string.i18n_settingsactivity_084), I18n.text(R.string.i18n_settingsactivity_085), { I18n.text(R.string.i18n_settingsactivity_086, prefs.frameSeconds) }) {
            val opts = listOf(15, 30, 60, 120)
            prefs.frameSeconds = opts[(opts.indexOf(prefs.frameSeconds) + 1) % opts.size]
        }
        if (uhdPanel) row(I18n.text(R.string.media_quality_aerial_title), I18n.text(R.string.media_quality_aerial_desc), {
            when (prefs.aerialQuality) {
                MediaQuality.UHD -> I18n.text(R.string.media_quality_4k)
                MediaQuality.COMPAT -> I18n.text(R.string.media_quality_compat)
                else -> I18n.text(R.string.media_quality_auto)
            }
        }) {
            prefs.aerialQuality = when (prefs.aerialQuality) {
                MediaQuality.AUTO -> MediaQuality.UHD
                MediaQuality.UHD -> MediaQuality.COMPAT
                else -> MediaQuality.AUTO
            }
        }

        section(I18n.text(R.string.section_intro))
        val video = Intro.videoFile(this) != null || OptionalAssets.file(this, OptionalAssets.intro) != null
        row(I18n.text(R.string.i18n_settingsactivity_041), I18n.text(R.string.i18n_settingsactivity_042) +
            if (video) "" else I18n.text(R.string.i18n_settingsactivity_043), {
            when (prefs.intro) { Prefs.INTRO_BOOT -> I18n.text(R.string.i18n_settingsactivity_044); Prefs.INTRO_WAKE -> I18n.text(R.string.i18n_settingsactivity_045); else -> I18n.text(R.string.i18n_settingsactivity_046) }
        }) {
            val opts = listOf(Prefs.INTRO_OFF, Prefs.INTRO_BOOT, Prefs.INTRO_WAKE)
            prefs.intro = opts[(opts.indexOf(prefs.intro) + 1) % opts.size]
            // This boot counts as played: turning it on doesn't start it on the next return home.
            prefs.introBoot = Intro.bootStamp(this)
        }
        row(I18n.text(R.string.i18n_settingsactivity_047), I18n.text(R.string.i18n_settingsactivity_048) +
            if (video) "" else I18n.text(R.string.i18n_settingsactivity_049), {
            if (prefs.introSource == Prefs.INTRO_WORDMARK) I18n.text(R.string.i18n_settingsactivity_050) else if (video) I18n.text(R.string.i18n_settingsactivity_051) else I18n.text(R.string.i18n_settingsactivity_052)
        }) {
            prefs.introSource = if (prefs.introSource == Prefs.INTRO_WORDMARK) Prefs.INTRO_VIDEO else Prefs.INTRO_WORDMARK
        }
        optionalAssetRow(OptionalAssets.intro, R.string.download_default_intro)
        row(I18n.text(R.string.i18n_settingsactivity_053), I18n.text(R.string.i18n_settingsactivity_054), {
            if (prefs.wakeDelay == 0) I18n.text(R.string.i18n_settingsactivity_055) else I18n.text(R.string.i18n_settingsactivity_056, prefs.wakeDelay / 1000.0).replace(".0 ", " ")
        }) {
            val opts = listOf(0, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 10_000)
            prefs.wakeDelay = opts.firstOrNull { it > prefs.wakeDelay } ?: 0
        }
        row(I18n.text(R.string.i18n_settingsactivity_057), I18n.text(R.string.i18n_settingsactivity_058), { onOff(prefs.introSound) }, checked = { prefs.introSound }) { prefs.introSound = !prefs.introSound }
        row(I18n.text(R.string.i18n_settingsactivity_059), I18n.text(R.string.i18n_settingsactivity_060), { "" }, chevron = true) {
            startActivity(Intent(this, HomeActivity::class.java)
                .putExtra(Intro.EXTRA_PREVIEW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        row(I18n.text(R.string.welcome_preview), I18n.text(R.string.welcome_preview_help), { "" }, chevron = true) {
            startActivity(Intent(this, HomeActivity::class.java).putExtra(Welcome.EXTRA_PREVIEW, true))
        }
    }

    private fun remote() {
        heading.text = I18n.text(R.string.cat_remote)
        about.text = I18n.text(R.string.cat_remote_help)
        section(I18n.text(R.string.section_buttons))
        row(I18n.text(R.string.i18n_settingsactivity_101), I18n.text(R.string.i18n_settingsactivity_102), {
            if (prefs.shutdownOnPowerKey) I18n.text(R.string.i18n_settingsactivity_103) else I18n.text(R.string.i18n_settingsactivity_104)
        }) {
            prefs.shutdownOnPowerKey = !prefs.shutdownOnPowerKey
            if (prefs.shutdownOnPowerKey && !prefs.adbAuthorized)
                Toast.makeText(this, I18n.text(R.string.i18n_settingsactivity_105), Toast.LENGTH_LONG).show()
        }
        for ((key, title) in listOf("remap_netflix" to R.string.remap_netflix, "remap_evo" to R.string.remap_evo)) {
            row(I18n.text(title), I18n.text(R.string.remap_help), { remapLabel(key) }, chevron = true) {
                remapKey = key
                show(Page.REMAP)
            }
        }
    }

    /** The remote button the REMAP page picks a target for ([RemoteRemap] pref key). */
    private var remapKey = "remap_netflix"

    private fun remapLabel(key: String) = when (val target = prefs.remap(key)) {
        "" -> I18n.text(R.string.remap_none)
        RemoteRemap.INPUTS -> I18n.text(R.string.remap_inputs)
        else -> appLabel(target)
    }

    private fun remapPicker() {
        val key = remapKey
        heading.text = I18n.text(if (key == "remap_evo") R.string.remap_evo else R.string.remap_netflix)
        val help = I18n.text(R.string.remap_help)
        about.text = help
        fun choice(label: String, target: String, drawable: Drawable?) {
            row(label, help, { if (prefs.remap(key) == target) "✓" else "" }, checked = { prefs.remap(key) == target },
                checkbox = true, appIcon = drawable) {
                prefs.setRemap(key, target)
                up()
            }
        }
        choice(I18n.text(R.string.remap_inputs), RemoteRemap.INPUTS, null)
        choice(I18n.text(R.string.remap_none), "", null)
        for (a in repo.allApps().filter { !repo.isOwn(it.pkg) }.distinctBy { it.pkg }.sortedBy { it.label }) {
            choice(a.label, a.pkg, runCatching { packageManager.getActivityIcon(a.component) }.getOrNull())
        }
    }

    private fun apps() {
        heading.text = I18n.text(R.string.cat_apps)
        about.text = I18n.text(R.string.cat_apps_help)
        row(I18n.text(R.string.i18n_settingsactivity_005), I18n.text(R.string.i18n_settingsactivity_006),
            { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }, chevron = true) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        section(I18n.text(R.string.settings_apps_group))
        row(I18n.text(R.string.i18n_settingsactivity_106), I18n.text(R.string.i18n_settingsactivity_107),
            { onOff(prefs.recentFirst) }, checked = { prefs.recentFirst }) { prefs.recentFirst = !prefs.recentFirst }
        row(I18n.text(R.string.i18n_settingsactivity_108), I18n.text(R.string.i18n_settingsactivity_109), { I18n.text(R.string.i18n_settingsactivity_110, prefs.hidden.size) }, chevron = true) { show(Page.HIDDEN) }
        row(I18n.text(R.string.i18n_settingsactivity_111), I18n.text(R.string.i18n_settingsactivity_112), { I18n.text(R.string.i18n_settingsactivity_113, prefs.removed.size) }, chevron = true) {
            show(Page.REMOVED)
        }
    }

    private fun memory() {
        heading.text = I18n.text(R.string.cat_memory)
        about.text = I18n.text(R.string.cat_memory_help)
        row(I18n.text(R.string.i18n_settingsactivity_094), I18n.text(R.string.i18n_settingsactivity_095), { I18n.text(R.string.i18n_settingsactivity_096, Cleaner.availMb(this)) }) { v ->
            v.text = I18n.text(R.string.i18n_settingsactivity_097)
            Thread {
                val r = Cleaner.clean(applicationContext)
                runOnUiThread { v.text = r.description() }
            }.start()
        }
        row(I18n.text(R.string.i18n_settingsactivity_088), I18n.text(R.string.i18n_settingsactivity_089), { onOff(prefs.cleanOnStandby) }, checked = { prefs.cleanOnStandby }) {
            prefs.cleanOnStandby = !prefs.cleanOnStandby
        }
        row(I18n.text(R.string.i18n_settingsactivity_090), I18n.text(R.string.i18n_settingsactivity_091), {
            if (prefs.cleanIntervalHours == 0) I18n.text(R.string.i18n_settingsactivity_092) else I18n.text(R.string.i18n_settingsactivity_093, prefs.cleanIntervalHours)
        }) {
            val opts = listOf(0, 2, 4, 8)
            prefs.cleanIntervalHours = opts[(opts.indexOf(prefs.cleanIntervalHours) + 1) % opts.size]
            KeeperService.start(this, KeeperService.ACTION_RESCHEDULE)
        }
        row(I18n.text(R.string.i18n_settingsactivity_098), I18n.text(R.string.i18n_settingsactivity_099), { I18n.text(R.string.i18n_settingsactivity_100, prefs.keep.size) }, chevron = true) {
            show(Page.KEEP)
        }
    }

    /** Tigris's own system rows, under About this TV. */
    private fun tigrisRows() {
        section(I18n.text(R.string.section_tigris))
        row(I18n.text(R.string.i18n_settingsactivity_138), I18n.text(R.string.i18n_settingsactivity_139),
            { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }, chevron = true) {
            dialog(I18n.text(R.string.i18n_settingsactivity_138),
                I18n.text(R.string.settings_about, packageManager.getPackageInfo(packageName, 0).versionName ?: ""))
        }
        row(I18n.text(R.string.i18n_settingsactivity_115), I18n.text(R.string.i18n_settingsactivity_116),
            { if (prefs.adbAuthorized) I18n.text(R.string.i18n_settingsactivity_117) else I18n.text(R.string.i18n_settingsactivity_118) }) { v ->
            v.text = I18n.text(R.string.i18n_settingsactivity_119)
            Thread {
                val ok = LocalAdb.shell(applicationContext, "echo ok", 60_000) != null
                runOnUiThread { v.text = if (ok) I18n.text(R.string.i18n_settingsactivity_120) else I18n.text(R.string.i18n_settingsactivity_121) }
            }.start()
        }
        row(I18n.text(R.string.i18n_settingsactivity_122), I18n.text(R.string.i18n_settingsactivity_123),
            { if (!prefs.guard) I18n.text(R.string.i18n_settingsactivity_124) else if (HomeGuardService.isRunning) I18n.text(R.string.i18n_settingsactivity_125) else I18n.text(R.string.i18n_settingsactivity_126) }, checked = { prefs.guard }) { v ->
            val on = !prefs.guard
            v.text = I18n.text(R.string.i18n_settingsactivity_127)
            Thread {
                val ok = (Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)) && Guard.setEnabled(applicationContext, on)
                runOnUiThread {
                    v.text = if (!ok) I18n.text(R.string.i18n_settingsactivity_128) else if (on) I18n.text(R.string.i18n_settingsactivity_129) else I18n.text(R.string.i18n_settingsactivity_130)
                    if (ok && on) Toast.makeText(this, I18n.text(R.string.i18n_settingsactivity_131), Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !Guard.isXiaomi()) {
            row(I18n.text(R.string.home_filter_title), I18n.text(R.string.home_filter_warning),
                { if (prefs.persistentHomeFilter) I18n.text(R.string.home_filter_on) else I18n.text(R.string.home_filter_off) },
                checked = { prefs.persistentHomeFilter }) {
                val on = !prefs.persistentHomeFilter
                prefs.persistentHomeFilter = on && prefs.guard
                if (on && !prefs.guard) prefs.persistentHomeFilter = false
                HomeGuardService.onFilterChanged()
                if (on) Toast.makeText(this, I18n.text(R.string.home_filter_warning), Toast.LENGTH_LONG).show()
            }
        }
        if (Guard.isXiaomi() || prefs.keepAdb) row(I18n.text(R.string.i18n_settingsactivity_132), I18n.text(R.string.i18n_settingsactivity_133), { onOff(prefs.keepAdb) }, checked = { prefs.keepAdb }) { v ->
            val on = !prefs.keepAdb
            v.text = I18n.text(R.string.i18n_settingsactivity_134)
            Thread {
                val ok = !on || Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)
                if (ok) { prefs.keepAdb = on; Guard.ensureAdb(applicationContext) }
                runOnUiThread { v.text = if (ok) onOff(prefs.keepAdb) else I18n.text(R.string.i18n_settingsactivity_135) }
            }.start()
        }
        // A brand install guide with an outside link: no use on the HEC TV this build is for.
        // Resolver success is not proof that the physical HOME key reaches us.
        if (!hec) row(I18n.text(R.string.install_home_check_title), I18n.text(R.string.install_home_check_help), { "" }) {
            val native = Guard.isDefaultHome(this)
            val bound = HomeGuardService.isRunning
            val secure = Guard.hasSecureSettings(this)
            val next = HomeInstallGuide.nextStep(
                android.os.Build.MANUFACTURER, android.os.Build.BRAND, native, bound, secure)
            dialog(I18n.text(R.string.install_home_check_title),
                I18n.text(R.string.install_home_check_status,
                    native.toString(), bound.toString(), secure.toString()) + "\n\n" + I18n.text(next),
                secondary = I18n.text(R.string.install_home_guide_open), secondaryAction = {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://tigris-tv.pages.dev/#install")))
                    }
                })
        }
    }

    /** Read-only connection status; Android only lets apps read, not change, without system rights. */
    private fun network() {
        heading.text = I18n.text(R.string.settings_network)
        about.text = I18n.text(R.string.settings_network_help)
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val net = runCatching { cm?.activeNetwork }.getOrNull()
        val caps = runCatching { cm?.getNetworkCapabilities(net) }.getOrNull()
        val wired = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val ip = runCatching { cm?.getLinkProperties(net)?.linkAddresses }.getOrNull()
            ?.map { it.address }?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
        val help = I18n.text(R.string.settings_network_help)
        section(I18n.text(R.string.settings_network_status))
        row(I18n.text(R.string.settings_network_type), help, {
            when { caps == null -> I18n.text(R.string.settings_network_none); wired -> I18n.text(R.string.settings_network_ethernet); else -> "Wi‑Fi" }
        }) {}
        if (caps != null && !wired) {
            val name = row(I18n.text(R.string.settings_network_name), I18n.text(R.string.settings_network_name_help),
                { I18n.text(R.string.settings_unavailable) }) {}
            // Without location permission Android hides the SSID; local adb (if allowed) can still read it.
            if (prefs.adbAuthorized) Thread {
                val out = LocalAdb.shell(applicationContext, "dumpsys wifi | grep -m1 mWifiInfo")
                val ssid = out?.let { Regex("SSID: \"?([^\",]+)\"?,").find(it)?.groupValues?.get(1) }
                    ?.takeIf { it.isNotBlank() && !it.contains("unknown ssid") }
                if (ssid != null) runOnUiThread { if (!isDestroyed && page == Page.NETWORK) name.text = ssid }
            }.start()
        }
        row(I18n.text(R.string.settings_network_internet), help, {
            I18n.text(if (online) R.string.settings_network_online else R.string.settings_network_offline)
        }) {}
        row(I18n.text(R.string.settings_network_ip), help, { ip ?: "—" }) {}
        section(I18n.text(R.string.settings_android_section))
        row(I18n.text(R.string.settings_network_change), I18n.text(R.string.settings_network_change_help), { "" }, chevron = true) {
            runCatching { startActivity(TvControls(this).wifiIntent()) }
        }
    }

    /** About this TV: what any app may read, shown here instead of in the grey system page. */
    private fun device() {
        heading.text = I18n.text(R.string.settings_device)
        val help = I18n.text(R.string.settings_device_help)
        about.text = help
        val size = { bytes: Long -> android.text.format.Formatter.formatShortFileSize(this, bytes) }
        val metrics = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it)
        }
        val mem = android.app.ActivityManager.MemoryInfo().also {
            getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it)
        }
        val stat = runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path) }.getOrNull()
        section(I18n.text(R.string.settings_device))
        row(I18n.text(R.string.settings_device_model), help, {
            "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}"
        }) {}
        row(I18n.text(R.string.settings_device_android), help, {
            "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        }) {}
        row(I18n.text(R.string.settings_device_screen), help, { "${metrics.widthPixels} × ${metrics.heightPixels}" }) {}
        row(I18n.text(R.string.settings_device_memory), help, {
            I18n.text(R.string.settings_free_of, size(mem.availMem), size(mem.totalMem))
        }) {}
        row(I18n.text(R.string.settings_device_storage), help, {
            stat?.let { I18n.text(R.string.settings_free_of, size(it.availableBytes), size(it.totalBytes)) } ?: "—"
        }) {}
        row(I18n.text(R.string.settings_device_time), help, {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT).format(java.util.Date())
        }) {}
        row(I18n.text(R.string.settings_device_timezone), help, {
            java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.LONG)
        }) {}
        tigrisRows()
    }

    /** One dock app. The shelf appears when that tile is focused in the first row. */
    private fun shelfAppPicker() {
        heading.text = I18n.text(R.string.shelf_custom_app)
        about.text = I18n.text(R.string.shelf_custom_app_help)
        val apps = repo.allApps().filter { !repo.isOwn(it.pkg) }.sortedBy { it.label }
        if (apps.isEmpty()) {
            information(I18n.text(R.string.shelf_custom_app), I18n.text(R.string.shelf_custom_none))
            return
        }
        for (a in apps) {
            val drawable = runCatching { packageManager.getActivityIcon(a.component) }.getOrNull()
            row(a.label, a.pkg, { if (a.pkg == prefs.shelfCustomPkg) "✓" else "" },
                checked = { a.pkg == prefs.shelfCustomPkg }, checkbox = true, appIcon = drawable) {
                prefs.shelfCustomPkg = a.pkg
                up()
            }
        }
    }

    private fun shelfUrlLabel(): String {
        val url = prefs.shelfCustomUrl
        if (url.isBlank()) return I18n.text(R.string.shelf_custom_unset)
        return runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
    }

    private fun networkLabel(): String {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        return when {
            caps == null -> I18n.text(R.string.settings_network_none)
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> I18n.text(R.string.settings_network_ethernet)
            else -> "Wi‑Fi"
        }
    }

    private fun shelfAppLabel(): String {
        val pkg = prefs.shelfCustomPkg
        return if (pkg.isBlank()) I18n.text(R.string.shelf_custom_unset) else appLabel(pkg)
    }

    private fun editShelfUrl(onSaved: () -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            hint = I18n.text(R.string.shelf_custom_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(IDLE_VALUE)
            background = rounded(Color.argb(45, 255, 255, 255), dp(12).toFloat())
            setText(prefs.shelfCustomUrl)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(48)
        }
        dialog(I18n.text(R.string.shelf_custom_url), I18n.text(R.string.shelf_custom_url_dialog),
            body = input, primary = I18n.text(R.string.shelf_custom_save), confirm = {
                val raw = input.text.toString().trim()
                if (!ShelfLimits.isHttpUrl(raw)) {
                    input.error = I18n.text(R.string.shelf_custom_url_invalid)
                    input.requestFocus()
                    false
                } else {
                    prefs.shelfCustomUrl = raw
                    onSaved()
                    true
                }
            })
        input.post { input.requestFocus() }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg }

    private fun appPicker(title: String, selected: Set<String>, includeHidden: Boolean = false, toggle: (String, Boolean) -> Unit) {
        heading.text = title
        about.text = if (page == Page.KEEP) I18n.text(R.string.i18n_settingsactivity_141) else I18n.text(R.string.i18n_settingsactivity_142)
        val apps = if (includeHidden) repo.allApps().sortedBy { it.label } else repo.allApps().filter { !repo.isSystem(it.pkg) }.sortedBy { it.label }
        val sel = selected.toMutableSet()
        for (a in apps) {
            val drawable = runCatching { packageManager.getActivityIcon(a.component) }.getOrNull()
            row(a.label, null, { if (a.pkg in sel) "✓" else "" }, checked = { a.pkg in sel },
                checkbox = true, appIcon = drawable) { v ->
                val on = a.pkg !in sel
                if (on) sel += a.pkg else sel -= a.pkg
                toggle(a.pkg, on)
                v.text = if (on) "✓" else ""
            }
        }
    }

    /** Preinstalled apps deleted from the long-press menu; selecting one reinstalls it from the system partition. */
    private fun removedApps() {
        heading.text = I18n.text(R.string.i18n_settingsactivity_143)
        about.text = I18n.text(R.string.i18n_settingsactivity_144)
        if (prefs.removed.isEmpty()) {
            information(I18n.text(R.string.i18n_settingsactivity_145), I18n.text(R.string.settings_removed_empty))
            about.text = I18n.text(R.string.settings_removed_empty)
        }
        for (pkg in prefs.removed.sorted()) {
            val label = try {
                packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(packageManager).toString()
            } catch (e: Exception) { pkg }
            row(label, null, { I18n.text(R.string.i18n_settingsactivity_146) },
                appIcon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()) { v ->
                v.text = I18n.text(R.string.i18n_settingsactivity_147)
                Thread {
                    val ok = LocalAdb.shell(applicationContext, "cmd package install-existing $pkg")?.contains("installed") == true
                    if (ok) prefs.removed = prefs.removed - pkg
                    runOnUiThread { v.text = if (ok) I18n.text(R.string.i18n_settingsactivity_148) else I18n.text(R.string.i18n_settingsactivity_149) }
                }.start()
            }
        }
    }

    private fun dialog(title: String, message: String? = null, body: View? = null,
                       compact: Boolean = false, primary: String = getString(android.R.string.ok),
                       secondary: String = getString(android.R.string.cancel),
                       secondaryAction: (() -> Unit)? = null, confirm: () -> Boolean = { true }) {
        activeDialog?.dismiss()
        activeDialog = SettingsDialog(this, scene ?: Backgrounds.render("graphite"), title,
            message, body, compact, primary, confirm, secondary, secondaryAction).also { it.show() }
    }

    /** Search only after the user selects Search; the platform owns the TV keyboard. */
    private fun selectWeatherCity(onChosen: (Weather.City) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            hint = I18n.text(R.string.settings_city_short)
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(IDLE_VALUE)
            background = rounded(Color.argb(45,255,255,255), dp(12).toFloat())
            setText(prefs.weatherCity)
            setSelection(text.length)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        dialog(I18n.text(R.string.weather_choose_city), body = input, compact = true,
            primary = I18n.text(R.string.settings_search), confirm = {
                val query = input.text.toString().trim()
                if (query.length < 2) {
                    input.error = I18n.text(R.string.settings_city_minimum)
                    input.requestFocus()
                    false
                } else {
                    val app = applicationContext
                    Thread {
                        val cities = runCatching { Weather.search(query) }.getOrNull()
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) when {
                                cities == null -> Toast.makeText(app, I18n.text(R.string.weather_city_error), Toast.LENGTH_LONG).show()
                                cities.isEmpty() -> Toast.makeText(app, I18n.text(R.string.weather_city_none), Toast.LENGTH_LONG).show()
                                else -> {
                                    val choices = LinearLayout(this).apply {
                                        orientation = LinearLayout.VERTICAL
                                        clipChildren = false
                                    }
                                    cities.forEach { city ->
                                        val option = text(17f, weight = 500).apply {
                                            text = city.name
                                            gravity = Gravity.CENTER_VERTICAL
                                            setPadding(dp(16), 0, dp(16), 0)
                                            isFocusable = true; isClickable = true
                                            background = rounded(Color.argb(28,255,255,255), dp(14).toFloat())
                                            setOnFocusChangeListener { v, on ->
                                                v.background = rounded(Color.argb(if (on) 75 else 28,255,255,255), dp(14).toFloat())
                                                v.tweenScale(if (on) 1.025f else 1f, 200)
                                            }
                                            setOnClickListener {
                                                onChosen(city)
                                                activeDialog?.dismiss()
                                            }
                                        }
                                        choices.addView(option, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
                                    }
                                    dialog(I18n.text(R.string.weather_choose_city), body = choices)
                                }
                            }
                        }
                    }.start()
                    true
                }
            })
    }

    /** One header style for every page: small, dimmed, on the 8 dp grid, aligned with the row text. */
    private fun section(title: String) {
        val first = list.childCount == 0
        list.addView(text(14f, Color.argb(128, 255, 255, 255), weight = 500).apply {
            text = title; letterSpacing = 0.01f
        }, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(20); topMargin = if (first) 0 else dp(24); bottomMargin = dp(8)
        })
    }

    /** A gap between groups of category rows, as tvOS separates its main list. */
    private fun spacer() {
        list.addView(View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(-1, dp(24)))
    }

    /** Reveal the entire scaled row with a safety inset on all four sides. */
    private fun reveal(row: View, immediate: Boolean = false) {
        scroll.post {
            if (isFinishing || isDestroyed || row.parent !== list) return@post
            val rect = Rect(0, 0, row.width, row.height)
            list.offsetDescendantRectToMyCoords(row, rect)
            val edge = dp(16)
            val target = when {
                rect.top < scroll.scrollY + edge -> rect.top - edge
                rect.bottom > scroll.scrollY + scroll.height - edge -> rect.bottom - scroll.height + edge
                else -> scroll.scrollY
            }.coerceIn(0, (list.height - scroll.height).coerceAtLeast(0))
            if (immediate) scroll.scrollTo(0, target) else scroll.smoothScrollTo(0, target)
        }
    }

    /** One focus target per row; explicit switch/checkbox semantics and bounded value width. */
    private fun row(label: String, desc: String?, value: () -> String, chevron: Boolean = false,
                    checked: (() -> Boolean)? = null, checkbox: Boolean = false,
                    appIcon: Drawable? = null, glyph: Int? = null, onClick: (TextView) -> Unit): TextView {
        val r = object : LinearLayout(this) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                if (checked != null) {
                    info.className = if (checkbox) "android.widget.CheckBox" else "android.widget.Switch"
                    info.isCheckable = true
                    info.isChecked = checked()
                }
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; isClickable = true
            clipChildren = false; clipToPadding = false
            // tvOS row: ~116 px at 1080p (58 dp), transparent until focused. One padding for both states,
            // so value and chevron sit in the same column whether the row is focused or not.
            setPadding(dp(20), dp(8), dp(20), dp(8))
            minimumHeight = dp(58)
        }
        appIcon?.let { drawable ->
            r.addView(ImageView(this).apply {
                setImageDrawable(drawable); scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        }
        val l = text(19f, IDLE_TEXT, weight = 500).apply {
            text = label; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        val v = text(17f, IDLE_VALUE).apply {
            text = value(); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = dp(220)
        }
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        // tvOS shows On/Off as the value instead of a switch; checkmarks stay for multi-select lists.
        val check = if (checkbox) text(18f, weight = 600).apply {
            gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else null
        check?.let { r.addView(it, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) }) }
        val arrow = if (chevron) text(24f, IDLE_CHEVRON, weight = 400).apply {
            text = "›"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else null
        arrow?.let { r.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }) }
        fun sync() {
            val on = checked?.invoke()
            if (on != null) {
                check?.text = if (on) "✓" else ""
                v.visibility = if (checkbox) View.GONE else View.VISIBLE
            }
            r.contentDescription = I18n.text(R.string.settings_row_value, label,
                if (on != null) onOff(on) else v.text.toString())
        }
        v.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { sync() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val corner = dp(12).toFloat()
        r.setOnFocusChangeListener { _, on ->
            // Focus = a solid white plate with dark ink, a soft shadow and a short decelerating lift, as on tvOS.
            r.background = if (on) rounded(Color.WHITE, corner) else null
            l.setTextColor(if (on) FOCUS_TEXT else IDLE_TEXT)
            v.setTextColor(if (on) FOCUS_VALUE else IDLE_VALUE)
            arrow?.setTextColor(if (on) FOCUS_CHEVRON else IDLE_CHEVRON)
            check?.setTextColor(if (on) FOCUS_TEXT else Color.WHITE)
            // Grown about its centre, so the plate stays inside the list's padding on both sides.
            r.pivotX = r.width / 2f
            r.pivotY = r.height / 2f
            r.tweenScale(if (on) FOCUS_SCALE else 1f, if (on) 180 else 150, Motion.ease, z = if (on) dp(6).toFloat() else 0f)
            if (on) {
                setGlyph(glyph ?: pageGlyph)
                focusedTitle.text = label
                if (desc != null) about.text = desc
                reveal(r)
            }
        }
        r.setOnClickListener {
            val from = page
            openers[from] = list.indexOfChild(r)
            onClick(v)
            if (page == from && from != Page.REMOVED && !v.text.startsWith(I18n.text(R.string.i18n_settingsactivity_150)) &&
                !v.text.startsWith(I18n.text(R.string.i18n_settingsactivity_151))) v.text = value()
            sync()
        }
        list.addView(r, LinearLayout.LayoutParams(-1, -2))
        sync()
        return v
    }

    private fun information(title: String, message: String) {
        list.addView(text(15f, weight = 600).apply { text = title },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(24), dp(16), dp(8)) })
        list.addView(text(14f, IDLE_VALUE).apply {
            text = message; setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(16); marginEnd = dp(16) })
        focusedTitle.text = title
    }

    private fun onOff(b: Boolean) = if (b) I18n.text(R.string.i18n_settingsactivity_152) else I18n.text(R.string.i18n_settingsactivity_153)

    /** I18n.text(R.string.i18n_settingsactivity_154) with what auto picks on this TV, or the forced choice. */
    private fun auto(pref: String, on: Boolean, yes: String, no: String) =
        if (pref == Prefs.AUTO) I18n.text(R.string.i18n_settingsactivity_155, if (on) yes else no) else if (on) yes else no

    /** The next of 自动 → [a] → [b]. */
    private fun next(pref: String, a: String, b: String) = when (pref) { Prefs.AUTO -> a; a -> b; else -> Prefs.AUTO }

    /** A secure setting, written directly once WRITE_SECURE_SETTINGS is granted, otherwise through local adb. Blocking. */
    private fun putSecure(key: String, value: String): Boolean =
        if (Guard.hasSecureSettings(this)) runCatching { Settings.Secure.putString(contentResolver, key, value) }.getOrDefault(false)
        else LocalAdb.shell(applicationContext, "settings put secure $key $value") != null

    private fun minutesLabel(m: Int) = when {
        m >= 1440 -> I18n.text(R.string.i18n_settingsactivity_156)
        m >= 60 -> I18n.text(R.string.i18n_settingsactivity_157, m / 60)
        else -> I18n.text(R.string.i18n_settingsactivity_158, m)
    }

    /** On Android TV the screensaver starts after the "screen off" timeout. */
    private fun idleMinutes() = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) {
            up()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) true else super.onKeyDown(keyCode, event)
}
