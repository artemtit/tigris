# Phone companion page localization

The QR-code page built into **Android Tigris** is rendered by
`app/src/main/assets/web/index.html` and served by `PhoneServer.kt`.
It now supports both Chinese and English.

- The **phone browser** chooses the language from `navigator.languages[0]`,
  with `navigator.language` as fallback; Chinese starts with `zh`,
  and every other locale uses English.
- The language is **independent of the television's language**. An English
  phone connected to a Chinese TV shows English. No extra API service,
  remote translation, or login is required.
- `app/src/main/assets/web/i18n.js` is a dedicated translation module. It
  owns static headings, hints, buttons, upload feedback, error messages,
  dynamic delete/restore links, wallpaper source/theme labels and
  `textarea` placeholder copy.
- The `/i18n.js` resource is served **under the existing randomly generated
  QR-code token path**, so it does not expose a new public endpoint.
- API identifiers, photo filenames, URL links and payloads stay unchanged.
  The client maps known source IDs into the browser language, even if the
  Android TV's JSON source labels are in a different language.

To add translations, maintain corresponding keys in the `en` and `zh`
dictionaries in `i18n.js`. Do not embed fixed Chinese strings in HTML or
client-side message handlers.

Run `node tools/test_phone_i18n.cjs` to validate locale selection, copy keys,
interpolation, token-scoped routing and unknown-source fallbacks.

This is **not** a separate public website or the future LG webOS app;
the feature is bundled in Android APK assets. No published APK is generated
just by merging the source change.
