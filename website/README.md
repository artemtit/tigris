# Tigris website

Official site for Tigris, an independent open-source Android TV launcher.
Static Astro build, deployed to Cloudflare Pages. **Not affiliated with Apple.**

## Commands

```sh
pnpm install                     # once (pnpm 11+; allow builds for esbuild + sharp)
pnpm exec astro check            # type check, must report 0 errors
pnpm exec astro build            # -> dist/
python scripts/build-assets.py   # regenerate WebP + OG card from ../docs/screenshots
python scripts/preview.py        # serve dist/ at http://127.0.0.1:8789
```

`scripts/build-assets.py` needs Pillow and a CJK font for the OG card only. It
searches common Windows / macOS / Linux font paths and takes `TIGRIS_OG_FONT`
as an override; no font is bundled or redistributed.

## Reproducible deploy

Cloudflare Pages project **`tigris-tv`**, production branch **`main`**.
Upload the built folder, never the sources:

```sh
pnpm exec astro build
cd dist
wrangler pages deploy . --project-name tigris-tv --branch main
```

Build policy (intentionally simple, so a deploy is one command):

- No Cloudflare-side build step, no CI env vars, no secrets in the repo.
- `dist/` is the complete deploy artifact: HTML, `_astro/*.css|js`, `img/*.webp`,
  `og.jpg`, `_headers`, `_redirects`, `robots.txt`, `sitemap.xml`, `404.html`.
- `_headers` sets CSP + security headers and the cache policy; `public/404.html`
  is the static 404 served for unknown paths.
- Deployments from this machine use local `wrangler` OAuth; nothing is pushed to
  GitHub. Record the deployment id printed by wrangler.

## Layout

```
src/data/site.ts        brand facts, asset paths and every command, as
                        { posix, powershell } variants generated side by side
src/data/zh.ts          Chinese copy (source of truth for the shape)
src/data/en.ts          English copy, checked with `satisfies Copy`
src/data/lang.ts        the Copy interface both locales must satisfy
src/data/strings.ts     short UI labels used by the client script
src/data/interfaces.ts  Step / FaqItem shapes (optional fields declared once)
src/components/*.astro  Header, Hero, Aerial, Features, Compare, Gallery,
                        Install, Compatibility, Faq, Footer
src/pages/index.astro   /             (Chinese)
src/pages/en/index.astro /en/         (English)
src/pages/[lang].astro  /zh/, /en/    fallback bodies for direct links
public/img/*            optimised WebP captures, OG card, illustrations
public/404.html         static 404 served by Cloudflare Pages
```

## Content rules (do not regress)

- **Promo film**: `public/video/tigris-ad.mp4` is the unchanged
  `promo/tigris-ad.mp4` supplied by the owner. Its visible description in
  `Film.astro` must retain all three Bensound attribution lines in both locales.
  License `55KIM7QUNXBISK9H` covers this video only; do not reuse it for another video.

- **Screenshots** come from `TVHome/docs/screenshots/{home,grid,shelf,cc,frame}.jpg`
  and are converted by `scripts/build-assets.py`. Never substitute a generated
  image for a capture.
- **Recordings** in `public/video/` (home, intro, settings, cc) are `adb shell screenrecord`
  clips from the same TV, re-encoded to 1280x720 H.264 without sound; each poster in
  `public/img/*-poster.webp` is a frame of its clip. They play only while on screen and
  also with reduced motion (the hero button pauses them all). The boot animation shown is the Tigris wordmark, never a
  trademarked video.
- **The "before" pane** of the comparison is an SVG illustration and is labelled
  「安装前示意图（非同一设备实拍）」 / "illustration, not the same TV" in the UI.
- **The aerial screensaver** is shown as a labelled motion illustration. No
  fabricated aerial frame is claimed to be a real capture; the photo frame next
  to it *is* a real capture.
- **Commands have two OS variants.** `com.leslie.tvhome` is always written in
  full, so no snippet depends on a shell variable. The install section has one
  PowerShell / macOS·Linux switch that swaps every block (and what the copy
  button copies). The only variable-bearing lines are the Sony BRAVIA `BODY`
  registration, shipped separately per shell. `192.168.x.x` and `PIN` are
  documented placeholders.
- **Never suggest disabling the Xiaomi stock launcher** (`com.mitv.tvhome`).
  Both languages say so explicitly and offer the home guard instead.
- **Client-side strings** come from `data-*` attributes written by the layout,
  never from literals inside `main.ts`, so `/en/` cannot fall back to Chinese.
- **Every download link** points at GitHub `releases/latest/download/…`.
- **Evergreen copy**: no "tonight"-style lines; the CTA promises the experience,
  not a single evening.
- Adding a UI string means adding it to `zh.ts`, `en.ts` and, if the client
  script uses it, `strings.ts`; `astro check` fails on drift.

