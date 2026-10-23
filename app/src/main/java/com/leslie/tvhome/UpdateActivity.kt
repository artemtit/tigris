package com.leslie.tvhome

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import android.graphics.Color
import android.view.Gravity
import android.view.View

class UpdateActivity : Activity() {
    companion object { @Volatile var visible = false }
    private lateinit var status: TextView
    private lateinit var column: LinearLayout
    private var busy = false
    private val surfaces = mutableListOf<FrostGlass>()
    override fun onCreate(state: Bundle?) { super.onCreate(state); show() }
    override fun onResume() { super.onResume(); visible = true; if (::status.isInitialized && !busy) renderStatus() }
    override fun onPause() { visible = false; super.onPause() }
    private fun button(label: String, action: () -> Unit): TextView {
        val view = text(17f, weight = 600).apply {
            text = label; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(22), 0)
            isFocusable = true; isClickable = true
            setOnClickListener { action() }
        }
        val glass = FrostGlass(view, dp(23).toFloat()); surfaces += glass; view.background = glass
        view.setOnFocusChangeListener { _, focused ->
            glass.focused = focused
            view.tweenScale(if (focused) 1.035f else 1f, 180)
        }
        column.addView(view, LinearLayout.LayoutParams(-1, dp(49)).apply { bottomMargin = dp(10) })
        return view
    }
    private fun renderStatus() {
        status.text = Updates.products.joinToString("\n\n") { p ->
            p.name + "\n" + Updates.prefs(this).getString(p.pkg + ".status", Updates.installed(this, p)?.let { "已安装 $it" } ?: "未安装")
        }
    }
    private fun show() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(20, 27, 43)) }
        val background = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(background, FrameLayout.LayoutParams(-1, -1))
        val heading = text(29f, weight = 600).apply { text = "软件更新"; gravity = Gravity.CENTER }
        root.addView(heading, FrameLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(18) })
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(dp(56), dp(98), dp(56), dp(22))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(26), dp(28), dp(26), dp(28))
        }
        val infoGlass = FrostGlass(info, dp(30).toFloat()); info.background = infoGlass; surfaces += infoGlass
        info.addView(text(36f, weight = 600).apply { text = "始终保持新鲜" })
        info.addView(text(17f, Color.argb(200,255,255,255)).apply {
            text = "桌面与投屏，一起更新。"; setPadding(0, dp(12), 0, dp(20))
        })
        status = text(16f).apply { setLineSpacing(dp(4).toFloat(), 1f) }
        info.addView(status); renderStatus()
        info.addView(text(13f, Color.argb(175,255,255,255)).apply {
            text = "自动检查正式版。需要安装确认时会通知你，更新保留应用配置。"
            setPadding(0, dp(24), 0, 0)
        })
        content.addView(info, LinearLayout.LayoutParams(0, -2, 0.46f).apply { marginEnd = dp(36) })
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply {
            clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false
            addView(column)
            setOnScrollChangeListener { _, _, _, _, _ -> surfaces.forEach { it.invalidateSelf() } }
        }
        content.addView(scroll, LinearLayout.LayoutParams(0, -1, 0.54f))
        setContentView(root)
        Thread {
            val prefs = Prefs(this)
            val source = if (Wallpapers.isPhoto(prefs.background)) {
                val walls = Wallpapers(this, Wallpapers.sourceFor(prefs.background))
                walls.current()?.let { walls.forHome(it) }
            } else null
            val image = source ?: Backgrounds.render(if (Wallpapers.isPhoto(prefs.background)) "green" else prefs.background)
            val blurred = Wallpapers.blurred(image, darken = 0.35f)
            runOnUiThread {
                if (!isDestroyed) { background.setImageBitmap(blurred); surfaces.forEach { it.texture(blurred) } }
            }
        }.start()
        val check = button("检查更新") { work { Updates.check(applicationContext) } }
        lateinit var auto: TextView
        fun autoLabel() = "自动更新    " + if (Updates.prefs(this).getBoolean("auto", false)) "已开启" else "已关闭"
        auto = button(autoLabel()) {
            val prefs = Updates.prefs(this)
            prefs.edit().putBoolean("auto", !prefs.getBoolean("auto", false)).apply()
            auto.text = autoLabel()
        }
        check.requestFocus()
        button("允许 Tigris 安装更新") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { Toast.makeText(this, "这台电视未提供安装授权页", Toast.LENGTH_LONG).show() }
        }
        for (p in Updates.products) {
            button("安装 / 更新 ${p.name}") { work { Updates.install(applicationContext, p) } }
        }
        button("打开 MiracastReceiver 投屏") {
            val pm = packageManager; val pkg = Updates.products[1].pkg
            val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (intent == null) Toast.makeText(this, "先检查更新并安装投屏组件", Toast.LENGTH_LONG).show() else startActivity(intent)
        }
        button("返回") { finish() }
    }
    private fun work(block: () -> Unit) {
        if (busy) return
        busy = true; status.text = "正在处理，请稍候…"
        Updates.executor.execute {
            val error = runCatching(block).exceptionOrNull()
            runOnUiThread {
                busy = false
                if (!isDestroyed) { renderStatus(); if (error != null) status.append("\n" + error.message) }
            }
        }
    }
}
