/**
 * Minimal Node.js typing for the two Astro static endpoints which embed
 * repository text files. Avoid an unpinned dependency just for readFileSync.
 * Both call sites pass a URL and explicitly request UTF-8 text.
 */
declare module "node:fs" {
  export function readFileSync(path: URL, encoding: "utf8"): string;
}
