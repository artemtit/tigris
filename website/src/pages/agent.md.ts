import { readFileSync } from "node:fs";
export function GET() {
  return new Response(readFileSync(new URL("../../../AGENTS.md", import.meta.url), "utf8"), {
    headers: { "Content-Type": "text/markdown; charset=utf-8" },
  });
}
