package com.leslie.tvhome

/** Release filenames always include the tag; old published releases used stable names. */
internal object ReleaseAssetNames {
    fun candidates(tag: String, guard: Boolean): List<String> {
        val prefix = if (guard) "Tigris-guard" else "Tigris"
        return listOf("$prefix-$tag.apk", "$prefix.apk")
    }

    fun select(tag: String, guard: Boolean, available: List<String>): String? =
        candidates(tag, guard).firstOrNull { it in available }
}
