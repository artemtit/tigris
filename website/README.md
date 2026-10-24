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
CLOUDFLARE_ACCOUNT_ID=1b14747dd35d66e9f7941beaf412c3fe wrangler pages deploy . --project-name tigris-tv --branch main
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

- **Product media** now uses muted video loops. Home, Top Shelf, Control Center,
  update center and aerial clips were captured on the owner's Sony BRAVIA with
  `adb screenrecord` on 2026-10-24. Posters are frames from each recording.
- **Startup preview** uses the owner-supplied default clip bundled in 2.0.4. The
  gallery labels it as third-party material; do not republish it without permission.
- **Comparison** uses the owner-supplied Chinese-market TV screenshot with gentle
  pan/zoom, labelled as an image rather than a recording. The two sides are different TVs.
- **Aerial** shows actual TV playback, not an illustration; Apple retains the
  rights to its footage. The APK contains stream addresses, not aerial films.
- **Bundled settings artwork** is an owner-supplied third-party
  asset, not original Tigris artwork; see the repository's asset provenance.

- **Commands have two OS variants.** The install section has one
  PowerShell / macOS·Linux switch that swaps every block (including what the
  copy button copies). The installer captures the actual versioned APK filename
  in `$apk` or `APK` before installing; users must copy the complete code block.
  Sony BRAVIA registration also uses a platform-specific `BODY` variable.
  `com.leslie.tvhome` is always spelled out; `192.168.x.x` and `PIN` are
  documented placeholders.
- **Never suggest disabling the Xiaomi stock launcher** (`com.mitv.tvhome`).
  Both languages say so explicitly and offer the home guard instead.
- **Client-side strings** come from `data-*` attributes written by the layout,
  never from literals inside `main.ts`, so `/en/` cannot fall back to Chinese.
- **Download buttons** open the full GitHub Releases list, where both the Standard and Guard APKs are available. This is deliberately version-agnostic: never hardcode a release tag, a versioned asset URL, or the retired unversioned `releases/latest/download/Tigris.apk` URLs. Installer snippets use `tools/download-release.py` to resolve and verify the current release.
- **Evergreen copy**: no "tonight"-style lines; the CTA promises the experience,
  not a single evening.
- Adding a UI string means adding it to `zh.ts`, `en.ts` and, if the client
  script uses it, `strings.ts`; `astro check` fails on drift.


Production account: `1b14747dd35d66e9f7941beaf412c3fe` (migrated 2026-10-24).
Production URL: https://tigris-tv.pages.dev . Set the account explicitly; old Wrangler caches may point to the previous account.
