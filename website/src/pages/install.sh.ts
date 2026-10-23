import { readFileSync } from "node:fs";
export function GET() {
  return new Response(readFileSync(new URL("../../../install.sh", import.meta.url), "utf8"), {
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
}
