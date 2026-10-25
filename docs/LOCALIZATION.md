# Tigris UI localization (TV)

Tigris follows the **Android TV system language automatically**. There is **one APK**
for both languages and no language preference, onboarding step, or new background service.

## Architecture

- `app/src/main/java/com/leslie/tvhome/I18n.kt` is the single UI-copy entry point.
- `app/src/main/res/values/` holds English strings (also the Android fallback).
- `app/src/main/res/values-zh/` holds Chinese translations.
- Each feature has its own paired `i18n_<feature>.xml` resource file in both
  directories. Android resource resolution chooses the correct language.
- `App.onCreate()` initializes `I18n` with the application context.
- To add a third language, supply another `values-<language>/` catalogue.
  Do not add a separate APK, a manual language toggle, or a custom locale parser.

## Adding UI text

1. Add a stable string resource key with the same name in both catalogues.
2. In Kotlin UI code, call `I18n.text(R.string.your_key)`.
3. For dynamic text, use numbered placeholders in **both** translations:
   `<string name="total_items">%1$s items</string>` and
   `<string name="total_items">%1$s 个项目</string>`, then call
   `I18n.text(R.string.total_items, count)`.
4. Use the current Android locale when formatting dates and times. Compact counts use 万/亿 for Chinese, and K/M for English.
5. Do not cache translated text in long-lived static values if it must
   immediately update after a system language change.
6. Never translate technical identifiers: package names, intents, ADB commands,
   JSON/API keys, event names, URLs, TV manufacturer identifiers, or user data.

This UI module is independent of Home Guard, firmware adapters, volume keys and
APK flavor selection. A locale change may recreate an Android activity; it must
not reset the user's home layout, settings, or background.

## Verification

Run `python3 tools/test_i18n.py` to check both language catalogues for matching
keys, matching numbered placeholders and all `I18n.text` resource references.
Android CI also runs this check, builds and tests both APK flavors, and runs lint.

Before merging, verify zh-CN and en-US/en-GB on an Android TV or emulator,
including dialogs, the control center, app labels, settings, photo frame dates,
notifications and long strings. The phone-based wallpaper upload web page
has its own browser language and is outside this TV system-locale module.
