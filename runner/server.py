"""Minimal, dependency-free PocketDock runner proof of concept.

Runs on the Android phone in Termux. Intentionally loopback-only.
No authentication is needed while bound to localhost, and no actions
other than returning a health response are available.
"""

from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json

HOST = "127.0.0.1"
PORT = 8799


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path != "/health":
            self._send(404, {"error": "not found"})
            return

        self._send(
            200,
            {
                "service": "pocketdock-runner",
                "status": "ok",
                "timestamp": datetime.now(timezone.utc).isoformat(),
            },
        )

    def _send(self, status: int, data: dict) -> None:
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"PocketDock Runner: http://{HOST}:{PORT}/health")
    print("Running on this device. Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
