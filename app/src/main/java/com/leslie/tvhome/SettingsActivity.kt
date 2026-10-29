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
        private val IDLE_VALUE = Color.argb(170, 255, 255, 255)
        private val FOCUS_TEXT = Color.rgb(28, 28, 30)
        private val FOCUS_VALUE = Color.argb(140, 0, 0, 0)

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
    private lateinit var back: TextView
    private val artwork = Executors.newSingleThreadExecutor()
    private var scene: Bitmap? = null
    private var backdropKey: String? = null
    private var backdropRevision = 0
    private var activeDialog: SettingsDialog? = null
    private var page = Page.MAIN
    /** The main-page row that opened the current sub-page, focused again on the way back. */
    private var opener = 0
    private var entry = Page.MAIN
    /** onCreate has just shown the page; showing it again started a second Wi-Fi probe. */
    private var firstResume = true

    private enum class Page { MAIN, KEEP, HIDDEN, REMOVED, SHELF_APP, NETWORK, DEVICE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepo(this)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(18, 24, 34))
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
                intArrayOf(0x28030a14, 0x00030a14, 0xb0030a14.toInt()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(pageShade, FrameLayout.LayoutParams(-1, -1))
        // tvOS: rows float over a dimmed picture, no slabs; the dim deepens towards the list.
        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x33000000, 0x59000000, 0x8c000000.toInt()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1, -1))
        // Safe area ~90 x 60 px at 1080p (Android TV is 960 x 540 dp at any density); list ~52 % wide.
        val sideMargin = dp(45)
        val safeTop = dp(30)
        val listWidth = (resources.displayMetrics.widthPixels * 0.52f).toInt()
        val leftWidth = resources.displayMetrics.widthPixels - listWidth - sideMargin * 2 - dp(24)
        heading = text(22f, weight = 600).apply { maxLines = 2; letterSpacing = -0.01f }
        root.addView(heading, FrameLayout.LayoutParams(leftWidth, -2).apply {
            leftMargin = sideMargin; topMargin = safeTop
        })
        val left = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        icon = ImageView(this).apply {
            showIcon(this)
            scaleType = ImageView.ScaleType.FIT_CENTER
            clipRounded(dp(18).toFloat())
        }
        left.addView(icon, FrameLayout.LayoutParams(dp(132), dp(132), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(28)
        })
        val description = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        focusedTitle = text(15f, weight = 600).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        description.addView(focusedTitle, LinearLayout.LayoutParams(-1, -2))
        about = text(12.5f, Color.argb(200, 255, 255, 255)).apply {
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        description.addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        left.addView(description, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(52) })
        back = text(13f, weight = 500).apply {
            text = I18n.text(R.string.i18n_updateactivity_016); gravity = Gravity.CENTER
            isFocusable = true; isClickable = true
            setOnClickListener { if (page == Page.MAIN || page == entry) finish() else show(Page.MAIN, opener) }
        }
        back.background = rounded(Color.argb(40, 255, 255, 255), dp(16).toFloat())
        back.setOnFocusChangeListener { _, on ->
            back.background = rounded(if (on) Color.WHITE else Color.argb(40, 255, 255, 255), dp(16).toFloat())
            back.setTextColor(if (on) FOCUS_TEXT else Color.WHITE)
            back.tweenScale(if (on) 1.04f else 1f, if (on) 180 else 150)
        }
        left.addView(back, FrameLayout.LayoutParams(dp(96), dp(32), Gravity.BOTTOM))
        root.addView(left, FrameLayout.LayoutParams(leftWidth, -1).apply {
            leftMargin = sideMargin; topMargin = dp(84); bottomMargin = safeTop
        })
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            // Room for the focused row to grow into (scale 1.04 of ~480 dp is ~10 dp per side).
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
        glass.removeWithin(list)
        list.removeAllViews()
        scroll.scrollTo(0, 0)
        when (p) {
            Page.MAIN -> main()
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
            (focus ?: back).requestFocus()
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
        else if (page == Page.MAIN) show(Page.MAIN, opener)
        else if (page == Page.NETWORK || page == Page.DEVICE) show(page)
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
                            show(Page.MAIN)
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
            // Match the actual crop and page scrim; never re-center the original artwork in the glass.
            val shade = Paint().apply {
                shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0x28030a14, 0x00030a14, 0xb0030a14.toInt()), null,
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

    private fun showIcon(v: ImageView) {
        val own = customIcon(this)?.let { decodeIcon(it, dp(380)) }
        glass.removeWithin(v)
        if (own != null) {
            v.setImageBitmap(own)
            v.setPadding(0, 0, 0, 0)
            v.background = null
        } else {
            glass.control(v, 32, content = true)
            v.setImageResource(R.drawable.ic_gear)
            v.setPadding(dp(42), dp(42), dp(42), dp(42))
        }
    }

    private fun main() {
        heading.text = I18n.text(R.string.i18n_settingsactivity_003)
        about.text = ""

        section(I18n.text(R.string.i18n_settingsactivity_004))
        row(I18n.text(R.string.i18n_settingsactivity_005), I18n.text(R.string.i18n_settingsactivity_006), { "" }, chevron = true) {
            startActivity(Intent(this, UpdateActivity::class.java))
        }
        section(I18n.text(R.string.i18n_settingsactivity_008))
        row(I18n.text(R.string.dock_columns_title), I18n.text(R.string.dock_columns_description),
            { prefs.dockColumns.toString() }) { prefs.dockColumns = DockLayout.next(prefs.dockColumns) }
        row(I18n.text(R.string.i18n_settingsactivity_009), I18n.text(R.string.i18n_settingsactivity_010),
            { if (prefs.clock24) I18n.text(R.string.i18n_settingsactivity_011) else I18n.text(R.string.i18n_settingsactivity_012) }) { prefs.clock24 = !prefs.clock24 }
        row(I18n.text(R.string.i18n_settingsactivity_013), I18n.text(R.string.i18n_settingsactivity_014), {
            when (prefs.clockStyle) { Prefs.CLOCK_LARGE -> I18n.text(R.string.i18n_settingsactivity_015); Prefs.CLOCK_OFF -> I18n.text(R.string.i18n_settingsactivity_016); else -> I18n.text(R.string.i18n_settingsactivity_017) }
        }) {
            val opts = listOf(Prefs.CLOCK_CORNER, Prefs.CLOCK_LARGE, Prefs.CLOCK_OFF)
            prefs.clockStyle = opts[(opts.indexOf(prefs.clockStyle) + 1) % opts.size]
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
        row(I18n.text(R.string.i18n_settingsactivity_026), I18n.text(R.string.i18n_settingsactivity_027), { "" }, chevron = true) {
            startActivity(Intent(this, WallpaperActivity::class.java).putExtra("upload_avatar", true))
        }
        row(I18n.text(R.string.i18n_settingsactivity_029), I18n.text(R.string.i18n_settingsactivity_030), { onOff(prefs.showAvatar) }, checked = { prefs.showAvatar }) { prefs.showAvatar = !prefs.showAvatar }
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
            show(Page.MAIN, opener)
        }

        // Weather is a self-contained optional card. No network traffic until opt-in.
        section(I18n.text(R.string.weather_settings))
        row(I18n.text(R.string.weather_settings), I18n.text(R.string.weather_settings_help),
            { onOff(prefs.weatherEnabled) }, checked = { prefs.weatherEnabled }) { prefs.weatherEnabled = !prefs.weatherEnabled }
        row(I18n.text(R.string.weather_unit_settings), I18n.text(R.string.weather_unit_help),
            { if (prefs.weatherFahrenheit) "°F" else "°C" }) {
            prefs.weatherFahrenheit = !prefs.weatherFahrenheit
        }
        row(I18n.text(R.string.weather_city_settings), I18n.text(R.string.weather_city_help),
            { prefs.weatherCity.ifBlank { I18n.text(R.string.weather_city_unknown) } }, chevron = true) { value ->
            selectWeatherCity { city ->
                prefs.weatherCity = city.name
                prefs.weatherLatitude = city.latitude
                prefs.weatherLongitude = city.longitude
                value.text = city.name
            }
        }

        section(I18n.text(R.string.i18n_settingsactivity_040))
        optionalAssetRow(OptionalAssets.intro, R.string.download_default_intro)
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

        section(I18n.text(R.string.i18n_settingsactivity_061))
        row(I18n.text(R.string.i18n_settingsactivity_062), I18n.text(R.string.i18n_settingsactivity_063), {
            Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background)).let {
                if (prefs.background == Wallpapers.MINE) it else I18n.text(R.string.i18n_settingsactivity_064, it)
            }
        }, chevron = true) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        row(I18n.text(R.string.media_quality_wall_title), I18n.text(R.string.media_quality_wall_desc), {
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
        row(I18n.text(R.string.media_quality_aerial_title), I18n.text(R.string.media_quality_aerial_desc), {
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

        section(I18n.text(R.string.i18n_settingsactivity_087))
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
        row(I18n.text(R.string.i18n_settingsactivity_094), I18n.text(R.string.i18n_settingsactivity_095), { I18n.text(R.string.i18n_settingsactivity_096, Cleaner.availMb(this)) }) { v ->
            v.text = I18n.text(R.string.i18n_settingsactivity_097)
            Thread {
                val r = Cleaner.clean(applicationContext)
                runOnUiThread { v.text = r.description() }
            }.start()
        }
        row(I18n.text(R.string.i18n_settingsactivity_098), I18n.text(R.string.i18n_settingsactivity_099), { I18n.text(R.string.i18n_settingsactivity_100, prefs.keep.size) }, chevron = true) {
            show(Page.KEEP)
        }
        row(I18n.text(R.string.i18n_settingsactivity_101), I18n.text(R.string.i18n_settingsactivity_102), {
            if (prefs.shutdownOnPowerKey) I18n.text(R.string.i18n_settingsactivity_103) else I18n.text(R.string.i18n_settingsactivity_104)
        }) {
            prefs.shutdownOnPowerKey = !prefs.shutdownOnPowerKey
            if (prefs.shutdownOnPowerKey && !prefs.adbAuthorized)
                Toast.makeText(this, I18n.text(R.string.i18n_settingsactivity_105), Toast.LENGTH_LONG).show()
        }

        section(I18n.text(R.string.settings_apps_group))
        row(I18n.text(R.string.i18n_settingsactivity_106), I18n.text(R.string.i18n_settingsactivity_107),
            { onOff(prefs.recentFirst) }, checked = { prefs.recentFirst }) { prefs.recentFirst = !prefs.recentFirst }
        row(I18n.text(R.string.i18n_settingsactivity_108), I18n.text(R.string.i18n_settingsactivity_109), { I18n.text(R.string.i18n_settingsactivity_110, prefs.hidden.size) }, chevron = true) { show(Page.HIDDEN) }
        row(I18n.text(R.string.i18n_settingsactivity_111), I18n.text(R.string.i18n_settingsactivity_112), { I18n.text(R.string.i18n_settingsactivity_113, prefs.removed.size) }, chevron = true) {
            show(Page.REMOVED)
        }

        section(I18n.text(R.string.i18n_settingsactivity_114))
        row(I18n.text(R.string.settings_network), I18n.text(R.string.settings_network_help), { networkLabel() }, chevron = true) {
            show(Page.NETWORK)
        }
        row(I18n.text(R.string.settings_device), I18n.text(R.string.settings_device_help),
            { android.os.Build.MODEL }, chevron = true) { show(Page.DEVICE) }
        row(I18n.text(R.string.i18n_settingsactivity_115), I18n.text(R.string.i18n_settingsactivity_116),
            { if (prefs.adbAuthorized) I18n.text(R.string.i18n_settingsactivity_117) else I18n.text(R.string.i18n_settingsactivity_118) }) { v ->
            v.text = I18n.text(R.string.i18n_settingsactivity_119)
            Thread {
                val ok = LocalAdb.shell(applicationContext, "echo ok", 60_000) != null
                runOnUiThread { v.text = if (ok) I18n.text(R.string.i18n_settingsactivity_120) else I18n.text(R.string.i18n_settingsactivity_121) }
            }.start()
        }
        // Resolver success is not proof that the physical HOME key reaches us.
        row(I18n.text(R.string.install_home_check_title), I18n.text(R.string.install_home_check_help), { "" }) {
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
        row(I18n.text(R.string.i18n_settingsactivity_138), I18n.text(R.string.i18n_settingsactivity_139),
            { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }, chevron = true) {
            dialog(I18n.text(R.string.i18n_settingsactivity_138),
                I18n.text(R.string.settings_about, packageManager.getPackageInfo(packageName, 0).versionName ?: ""))
        }

        // Everything that needs system permissions (Bluetooth, picture, sound, joining networks):
        // one clearly labelled way out instead of grey jumps scattered through the list.
        section(I18n.text(R.string.settings_android_section))
        row(I18n.text(R.string.i18n_settingsactivity_136), I18n.text(R.string.i18n_settingsactivity_137), { "" }, chevron = true) { runCatching { startActivity(TvControls(this).settingsIntent()) } }
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
        row(I18n.text(R.string.settings_device_tigris), help, { packageManager.getPackageInfo(packageName, 0).versionName ?: "" }) {}
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
                show(Page.MAIN, opener)
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

    private fun section(title: String) {
        val first = list.childCount == 0
        list.addView(text(11.5f, Color.argb(150, 255, 255, 255), weight = 600).apply { text = title },
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16); topMargin = if (first) dp(2) else dp(18); bottomMargin = dp(6)
            })
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
                    appIcon: Drawable? = null, onClick: (TextView) -> Unit): TextView {
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
            // tvOS row: ~70 px at 1080p (35 dp), transparent until focused.
            setPadding(dp(16), dp(6), dp(16), dp(6))
            minimumHeight = dp(35)
        }
        appIcon?.let { drawable ->
            r.addView(ImageView(this).apply {
                setImageDrawable(drawable); scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
        }
        val l = text(14f, IDLE_TEXT, weight = 500).apply {
            text = label; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        val v = text(13f, IDLE_VALUE).apply {
            text = value(); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            maxWidth = dp(190)
        }
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        // tvOS shows On/Off as the value instead of a switch; checkmarks stay for multi-select lists.
        val check = if (checkbox) text(15f, weight = 600).apply {
            gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else null
        check?.let { r.addView(it, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(10) }) }
        val arrow = if (chevron) text(17f, IDLE_VALUE, weight = 400).apply {
            text = "›"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else null
        arrow?.let { r.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) }) }
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
        val corner = dp(9).toFloat()
        r.setOnFocusChangeListener { _, on ->
            // Focus = a solid white plate with dark ink and a short decelerating lift, as on tvOS.
            r.background = if (on) rounded(Color.WHITE, corner) else null
            l.setTextColor(if (on) FOCUS_TEXT else IDLE_TEXT)
            v.setTextColor(if (on) FOCUS_VALUE else IDLE_VALUE)
            arrow?.setTextColor(if (on) FOCUS_VALUE else IDLE_VALUE)
            check?.setTextColor(if (on) FOCUS_TEXT else Color.WHITE)
            // Grown from the label's left edge, so the text keeps the column and only the plate reaches past it.
            r.pivotX = l.left.toFloat()
            r.tweenScale(if (on) 1.04f else 1f, if (on) 180 else 150, Motion.ease)
            if (on) {
                focusedTitle.text = label
                if (desc != null) about.text = desc
                reveal(r)
            }
        }
        r.setOnClickListener {
            if (page == Page.MAIN) opener = list.indexOfChild(r)
            onClick(v)
            if (page == Page.MAIN && !v.text.startsWith(I18n.text(R.string.i18n_settingsactivity_150)) &&
                !v.text.startsWith(I18n.text(R.string.i18n_settingsactivity_151))) v.text = value()
            sync()
        }
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        sync()
        return v
    }

    private fun information(title: String, message: String) {
        list.addView(text(15f, weight = 600).apply { text = title },
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(24), dp(16), dp(8)) })
        list.addView(text(13f, IDLE_VALUE).apply {
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
            if (page == entry) finish() else show(Page.MAIN, opener)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) true else super.onKeyDown(keyCode, event)
}
