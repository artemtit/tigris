package com.leslie.tvhome.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.tv.TvInputInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.leslie.tvhome.Guard

/**
 * Xiaomi / Redmi China firmware (PatchWall, HyperOS TV). From Aerial's device notes
 * (github.com/pengchujin/aerial-tv, docs/devices/xiaomi-*.md):
 *
 * - Backlight: Settings.Global "picture_backlight", 0–100, applied by the firmware's TV menu
 *   service as soon as it changes. Writing it needs WRITE_SECURE_SETTINGS (granted with the guard).
 * - Inputs: the generic passthrough intent has no handler; Xiaomi's player switches with
 *   EXTSRC_PLAY, where MediaTek hardware ids HW5.. map onto source ids 23... MiTV-MFTR0 also
 *   registers an HDMI input HW8 with no connector behind it.
 * - Settings and Wi-Fi: Xiaomi's own settings app.
 */
class XiaomiAdapter(ctx: Context) : AndroidAdapter(ctx) {
    override fun matches() = isXiaomi() || installed(PLAYER)

    override fun brightness(): Level? {
        val r = ctx.contentResolver
        if (Settings.Global.getString(r, BACKLIGHT) == null || !Guard.hasSecureSettings(ctx)) return null
        return Level(Settings.Global.getInt(r, BACKLIGHT, 50), 0, 100)
    }

    override fun setBrightness(v: Int) =
        runCatching { Settings.Global.putInt(ctx.contentResolver, BACKLIGHT, v.coerceIn(0, 100)) }.isSuccess

    override fun hidden(input: TvInputInfo) = Build.MODEL in setOf("MiTV_MFTR0", "MiTV-MFTR0") && hw(input.id) == 8

    override fun inputUri(input: TvInputInfo): String =
        (if (installed(PLAYER)) hw(input.id)?.let { "$URI_PREFIX${it + 18}" } else null) ?: super.inputUri(input)

    override fun setInput(i: Input) {
        if (!i.uri.startsWith(URI_PREFIX)) return super.setInput(i)
        val n = i.uri.removePrefix(URI_PREFIX).toIntOrNull() ?: return
        runCatching {
            ctx.startActivity(Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(PLAYER, "$PLAYER.ExternalSourceActivity"))
                .putExtra("input", n)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "Xiaomi input $n failed", it) }
    }

    override fun settingsIntent() = resolvable(Intent("com.xiaomi.mitv.settings.SETTINGS")
        .setComponent(ComponentName(SETTINGS, "$SETTINGS.entry.MainActivity"))) ?: super.settingsIntent()

    override fun wifiIntent() = resolvable(Intent("com.xiaomi.mitv.settings.WIFI_SETTINGS")
        .setComponent(ComponentName(SETTINGS, "$SETTINGS.wifi.MitvWifiActivity"))) ?: super.wifiIntent()

    private fun hw(id: String): Int? = Regex("""/HW(\d+)$""").find(id)?.groupValues?.get(1)?.toIntOrNull()

    companion object {
        private const val URI_PREFIX = "xiaomi-input:"
        private const val PLAYER = "com.xiaomi.mitv.tvplayer"
        private const val SETTINGS = "com.xiaomi.mitv.settings"
        private const val BACKLIGHT = "picture_backlight"

        fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.equals("Xiaomi", true) || Build.BRAND.equals("Redmi", true)
    }
}
