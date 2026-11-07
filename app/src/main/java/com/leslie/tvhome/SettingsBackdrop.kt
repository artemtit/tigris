package com.leslie.tvhome

import android.content.Context
import android.graphics.*

/** tvOS dark Settings: a neutral near-black fall, no hue, no wallpaper. Shared by Settings and Software Update. */
internal object SettingsBackdrop {
    const val TOP = 0xFF1C1C1E.toInt()
    const val BOTTOM = 0xFF0B0B0C.toInt()

    @Suppress("UNUSED_PARAMETER")
    fun load(context: Context): Bitmap {
        val image = Bitmap.createBitmap(64, 270, Bitmap.Config.ARGB_8888)
        Canvas(image).drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 270f, TOP, BOTTOM, Shader.TileMode.CLAMP)
        })
        return image
    }
}
