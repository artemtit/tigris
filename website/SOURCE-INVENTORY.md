# SOURCE-INVENTORY

Generated manifest of the tracked website source and the production build.
This file exists so the source tree can be verified with tools that cannot read
`.astro`, `.webp` or `.jpg` files: every path, byte size and SHA-256 below was
produced by Git and by the Astro build, not typed by hand.

Commit HEAD: `e8dfe0b` (adds `.gitattributes` only)
Deployed code: this file at HEAD, identical to `e3b6f3c` except `.gitattributes`
Production: Cloudflare Pages project `tigris-tv`,
deployment `48682f69-b91e-4f53-9613-3a1f9ab5e003`

## Routes (Astro `src/pages`)

| Source file | URL |
| --- | --- |
| `src/pages/index.astro` | `/` |
| `src/pages/en/index.astro` | `/en/` |
| `src/pages/[lang].astro` | `/zh/`, `/en/` fallback bodies |
| `src/pages/sitemap.xml.ts` | `/sitemap.xml` |

## Components (`src/components`, 10 files)

`Header.astro`, `Hero.astro`, `Aerial.astro`, `Features.astro`, `Compare.astro`,
`Gallery.astro`, `Install.astro`, `Compatibility.astro`, `Faq.astro`,
`Footer.astro` — plus `src/layouts/Base.astro`.

## Build

```sh
pnpm install --frozen-lockfile
pnpm exec astro check     # 0 errors, 0 warnings (25 files)
pnpm exec astro build     # 4 pages -> dist/, 26 files
cd dist && wrangler pages deploy . --project-name tigris-tv --branch main
```

## Production artifact (`dist/`, 26 files)

| Path | Bytes |
| --- | --- |
| `index.html` | 69702 |
| `en/index.html` | 70161 |
| `zh/index.html` | 2903 |
| `404.html` | 881 |
| `404.css` | 1799 |
| `_headers` | 724 |
| `_redirects` | 186 |
| `robots.txt` | 73 |
| `sitemap.xml` | 824 |
| `img/home-1280.webp` | 258688 |
| `img/home-640.webp` | 57664 |
| `img/shelf-1280.webp` | 52214 |
| `img/shelf-640.webp` | 21532 |
| `img/grid-1280.webp` | 203334 |
| `img/grid-640.webp` | 51574 |
| `img/cc-1280.webp` | 204952 |
| `img/cc-640.webp` | 47308 |
| `img/frame-1280.webp` | 260436 |
| `img/frame-640.webp` | 65516 |
| `img/og.jpg` | 134711 |
| `img/aerial-illustration.svg` | 5840 |
| `img/before-illustration.svg` | 3741 |
| `img/favicon.svg` | 567 |
| `_astro/_lang_.DGiHNVbq.css` | 32043 |
| `_astro/...CExepcfd.js` | 66 |
| `_astro/...SEfia8Tt.js` | 6964 |

## Verification hashes

Rebuilding `e3b6f3c` in an empty directory produced 26 files, byte-identical to
the production artifact (26/26).

| File | SHA-256 |
| --- | --- |
| `index.html` | `c970a9ea0fb496778f222c1b983fe192017f8327c390a537fbcb03b1ebfa682f` |
| `en/index.html` | `b10b30ebb7f234786b00bbe981774c54ff1697a56b0c5465c6f9892c2f8999e4` |
| `_headers` | `071d18929daca9b82c6823a2e4548c93ef23104d2edd79c8381d23f933faf0b5` |

Live copies of these files hash to the same values on
`https://tigris-tv.pages.dev/` and `/en/`.
