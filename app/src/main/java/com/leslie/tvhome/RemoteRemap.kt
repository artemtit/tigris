package com.leslie.tvhome

/** Remote buttons whose firmware target is missing (Netflix, evo); Tigris answers them instead. */
object RemoteRemap {
    const val INPUTS = "inputs"
    const val KEYCODE_NETFLIX = 265

    fun prefKey(keyCode: Int): String? = when (keyCode) {
        KEYCODE_NETFLIX -> "remap_netflix"
        android.view.KeyEvent.KEYCODE_TV_SATELLITE_SERVICE -> "remap_evo"
        else -> null
    }

    fun default(key: String) = if (key == "remap_netflix") "ru.kinopoisk.tv" else INPUTS
}
