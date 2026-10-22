import { defineConfig } from "astro/config";

// Static build for Cloudflare Pages. The 404 page is emitted as 404.html by
// dist/[...].astro; headers and redirects are copied from public/.
export default defineConfig({
  site: "https://tigris-tv.pages.dev",
  output: "static",
  trailingSlash: "ignore",
  build: {
    format: "directory",
    inlineStylesheets: "auto",
  },
  compressHTML: true,
  devToolbar: { enabled: false },
});
