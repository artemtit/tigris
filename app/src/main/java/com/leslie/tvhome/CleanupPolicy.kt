package com.leslie.tvhome

internal object CleanupPolicy {
    enum class Mode { SKIP, CACHE_ONLY, DEEP }
    fun mode(automatic: Boolean, foreground: String?): Mode = when {
        automatic && foreground == null -> Mode.SKIP
        automatic || foreground == null -> Mode.CACHE_ONLY
        else -> Mode.DEEP
    }
    private val packageName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    fun targets(packages: Collection<String>, protected: Set<String>): List<String> =
        packages.filter { packageName.matches(it) && it !in protected }.distinct()

    fun foreground(dump: String): String? {
        val lines = dump.lineSequence().filter {
            it.contains("topResumedActivity") || it.contains("mResumedActivity") || it.contains("ResumedActivity:")
        }
        return lines.mapNotNull { Regex("u\\d+ ([A-Za-z0-9_.]+)/").find(it)?.groupValues?.get(1) }
            .firstOrNull()
    }

    fun succeeded(output: String?, marker: String) = output?.lineSequence()?.any { it.trim() == marker } == true
}
