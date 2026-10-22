package com.leslie.tvhome.tv

import android.content.Context

/**
 * Brightness, volume, HDMI inputs and the system settings shortcuts for the Control Center.
 *
 * The first adapter in [brands] whose [TvAdapter.matches] is true handles this TV; when none
 * does, [AndroidAdapter] (standard Android APIs) does. Brand adapters extend AndroidAdapter, so
 * whatever a brand doesn't override falls back to standard Android.
 *
 * To support another brand: add `<Brand>Adapter.kt` to this package (see [CoocaaAdapter] for a
 * small one) and list it in [brands]. Every call except the two intents may block
 * (HTTP, settings, PackageManager), so call them off the main thread.
 */
class TvControls private constructor(adapter: TvAdapter) : TvAdapter by adapter {
    constructor(ctx: Context) : this(pick(ctx))

    companion object {
        /** Tried in order. Keep the ones that detect by brand before any that detect by installed packages. */
        private fun brands(ctx: Context): List<TvAdapter> = listOf(
            SonyAdapter(ctx),
            XiaomiAdapter(ctx),
            TclAdapter(ctx),
            CoocaaAdapter(ctx),
        )

        private fun pick(ctx: Context): TvAdapter = brands(ctx).firstOrNull { it.matches() } ?: AndroidAdapter(ctx)
    }
}
