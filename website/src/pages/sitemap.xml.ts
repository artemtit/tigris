import type { APIRoute } from "astro";
import { brand } from "../data/site";

export const GET: APIRoute = () =>
  new Response(
    `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">
  <url>
    <loc>${brand.site}/</loc>
    <lastmod>${new Date().toISOString().slice(0, 10)}</lastmod>
    <changefreq>weekly</changefreq>
    <priority>1.0</priority>
    <xhtml:link rel="alternate" hreflang="zh-CN" href="${brand.site}/"/>
    <xhtml:link rel="alternate" hreflang="en" href="${brand.site}/en/"/>
  </url>
  <url>
    <loc>${brand.site}/en/</loc>
    <lastmod>${new Date().toISOString().slice(0, 10)}</lastmod>
    <changefreq>weekly</changefreq>
    <priority>0.8</priority>
    <xhtml:link rel="alternate" hreflang="zh-CN" href="${brand.site}/"/>
    <xhtml:link rel="alternate" hreflang="en" href="${brand.site}/en/"/>
  </url>
</urlset>
`,
    { headers: { "content-type": "application/xml; charset=utf-8" } }
  );
