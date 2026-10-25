package com.leslie.tvhome

/**
 * Fail-closed APK signer selection. Android 9/10 may omit signingInfo when parsing an
 * APK archive from disk. Only those releases may retry the legacy GET_SIGNATURES parser.
 *
 * The actual platform queries live in Updates.install(); this policy is pure Kotlin so
 * the retry and trust gates have JVM regression coverage on both APK flavors.
 */
internal object UpdateSignerPolicy {
    fun withLegacyFallback(
        modern: Set<String>,
        legacyAllowed: Boolean,
        legacyRead: () -> Set<String>,
    ): Set<String> = if (modern.isNotEmpty() || !legacyAllowed) modern else legacyRead()

    fun sameSigner(archive: Set<String>, installed: Set<String>): Boolean =
        archive.isNotEmpty() && installed.isNotEmpty() && archive == installed

    fun matchesPinned(archive: Set<String>, allowedProduct: Boolean, trustedFingerprint: String): Boolean =
        allowedProduct && archive == setOf(trustedFingerprint)
}
