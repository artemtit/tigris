# Third-party notices

- **Inter** typeface (`app/src/main/assets/inter.ttf`) — Copyright (c) 2016 The Inter Project Authors, SIL Open Font License 1.1. Full text: `app/src/main/assets/inter-OFL.txt`.
- **Material Icons** (vector paths in `app/src/main/res/drawable/ic_*.xml`) — Copyright Google LLC, Apache License 2.0, https://github.com/google/material-design-icons.
- **dadb** (build dependency) — Copyright mobile.dev, Apache License 2.0.
- **AndroidX Media3 ExoPlayer** (build dependency) — Copyright The Android Open Source Project, Apache License 2.0.
- **NanoHTTPD** (build dependency) — Copyright (c) 2012-2013 by Paul S. Hawke, 2001,2005-2013 by Jarno Elonen, 2010 by Konstantinos Togias, BSD 3-Clause License.
- **ZXing core** (build dependency) — Copyright ZXing authors, Apache License 2.0.
- **Aerial** (https://github.com/pengchujin/aerial-tv) — MIT License, Copyright (c) 2026 pengchujin. Tigris ports its home guard and revival (`Guard.kt`), Xiaomi device details (`tv/XiaomiAdapter.kt`, `AppRepo.kt`), the phone page (`PhoneServer.kt`, `assets/web/index.html`, restyled), `tools/tcast-push.py`, and ships its aerial film catalogue `app/src/main/assets/aerials.json` (addresses and place names from Apple's public tvOS aerial resources; the films themselves stream from Apple and are not included).
- **Apple Root CA** (`app/src/main/res/raw/apple_root_ca.pem`) — Apple's public root certificate, trusted only for `sylvan.apple.com`, which serves the aerial films.


## 2.0.4 bundled presentation assets

The project owner supplied `app/src/main/res/raw/default_intro.mp4` (Apple TV
startup animation, 1080p) and `app/src/main/res/drawable-nodpi/default_settings_bg.png`
(Apple-branded settings artwork) for bundling. Apple names and logos remain
their owners' trademarks; these files are not covered by Tigris's source-code
license. Tigris is independent and not endorsed by Apple.

The website comparison's `patchwall.mp4` animates a cropped Xiaomi official
PatchWall marketing image; it is not an operation recording or a same-device
before/after test. Source: https://in.event.mi.com/in/PatchWall ; original image:
https://i03.appmifile.com/113_operator_in/16/04/2024/738607ef96ffbbe88e80a800c851085d.png .
Xiaomi and depicted media retain their respective rights.

The website aerial preview is a short recording of the owner's television
playing the existing Apple-hosted screensaver, with the Tigris clock and place
caption. It does not change the app's streaming-only aerial implementation.
