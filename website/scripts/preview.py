"""Local preview server that mirrors Cloudflare Pages behaviour.

- binds 127.0.0.1 only
- serves .webp / .svg / .js with correct MIME types
- unknown paths fall back to 404.html with HTTP 404 (like Pages)
- adds no-store so every reload shows the current files
"""
import functools
import http.server
import mimetypes
import socketserver
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "dist"
PORT = 8789

mimetypes.add_type("image/webp", ".webp")
mimetypes.add_type("image/svg+xml", ".svg")
mimetypes.add_type("application/javascript", ".js")
mimetypes.add_type("text/css", ".css")


class PreviewHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def send_head(self):
        path = self.translate_path(self.path)
        if not Path(path).exists() and not path.endswith("/404.html"):
            self.send_response(404)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            return open(ROOT / "404.html", "rb")
        return super().send_head()

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    handler = functools.partial(PreviewHandler)
    with Server(("127.0.0.1", PORT), handler) as httpd:
        print(f"preview: http://127.0.0.1:{PORT}/  (root={ROOT})", flush=True)
        httpd.serve_forever()

