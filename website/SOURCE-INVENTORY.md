# Website media provenance — 2.0.4

Production: https://tigris-tv.pages.dev (Cloudflare Pages project `tigris-tv`).

| Asset | Origin |
| --- | --- |
| home.mp4, cc.mp4, shelf.mp4, updates.mp4 | Sony BRAVIA / Android 12 recordings, 2026-10-24; silent H.264 1280×720 |
| aerial.mp4 | Same TV playing the Apple-hosted aerial over New Zealand; real motion, clock and location caption |
| intro.mp4 | Owner-supplied default startup MP4 bundled in Tigris 2.0.4; muted website preview, third-party rights retained |
| domestic-launcher.mp4 | Gentle pan/zoom of the owner-supplied Chinese-market TV screenshot; explicitly labelled, not a recording |
| tigris-ad.mp4 | Existing owner-supplied promo film, unchanged; Bensound attribution retained |
| *-poster.webp | Frame extracted from the corresponding video |

Comparison source: owner attachment `codex-clipboard-7847cc08-9539-4157-bce9-67fd7fee07cd.png`, 2026-10-24.

Logos, favicons, social cards and video fallback posters remain static. All
product showcase panels play motion media, pause offscreen and have a shared
pause control. `/agent.md` and `/install.sh` are generated directly from the
repository source at build time. Deployment metadata is recorded in the release
verification report, not in a stale source-tree hash table.
