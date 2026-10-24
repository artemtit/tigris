# Guard and cleanup boundaries

The Android services collect events and execute actions. Decisions that do not need Android live in three small modules:

- `HomeTakeoverPolicy`: fresh HOME evidence, settling, cooldown and a bounded takeover budget. Unknown windows never authorize a launch. Every non-HOME window cancels pending evidence, including vendor dialogs and input screens.
- `ServiceRecovery`: one outstanding rebind, cancellation when the user changes guard settings, and a fresh read of other services before delayed restoration.
- `CleanupPolicy`: package validation, foreground parsing across Android versions and explicit shell-result markers. Automatic runs never force-stop applications.

`HomeKeyFilterPolicy` remains the separate device-specific key-filter decision. The #15 exception is retained.

## Verification

Run both flavor unit-test suites, both release builds, both release lint tasks and `bash tools/test-install.sh`.

For a TV report, record model, firmware, API level, APK flavor and version. With the owner's agreement to each power operation, check cold boot and standby/wake separately. During the first minutes, open a video app and an HDMI source; neither should be taken over. Check physical Home and held volume keys, intentional visits to the stock launcher, and turning the guard off while service recovery is pending.

Collect `adb logcat -d -v time -s TigrisGuard ActivityTaskManager ActivityManager` promptly after reproducing. Do not equate a registered service with a bound service or a successful HOME command with visible takeover.

No Android-version floor was lowered. No new package-disable lists or DNS settings are applied by this release. Those require separate model-specific validation.
