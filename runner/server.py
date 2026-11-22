"""PocketDock Runner: a small read-only service executing on Android in Termux.

Only loopback access is permitted. Endpoints:
- GET /health         Basic liveness and compatibility with PocketDock 0.1
- GET /status         Runner uptime and Termux-home free space
- GET /checks/github  Perform one outbound network check on the *phone*

No remote shell, no arbitrary URLs, no file writes, no startup automation.
"""

from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import shutil
import time
import urllib.error
import urllib.request

HOST = "127.0.0.1"
PORT = 8799
STARTED = time.monotonic()


def timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def phone_network_check() -> dict:
    req = urllib.request.Request(
        "https://api.github.com/",
        headers={"User-Agent": "PocketDock-Android-Runner/0.2"},
    )
    try:
        with urllib.request.urlopen(req, timeout=4) as response:
            return {"reachable": 200 <= response.status < 500, "checked_at": timestamp()}
    except urllib.error.HTTPError as error:
        return {"reachable": error.code < 500, "checked_at": timestamp()}
    except (urllib.error.URLError, TimeoutError, OSError):
        return {"reachable": False, "checked_at": timestamp()}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self.send_json(200, {
                "service": "pocketdock-runner",
                "status": "ok",
                "timestamp": timestamp(),
            })
        elif path == "/status":
            free_megabytes = shutil.disk_usage(Path.home()).free // (1024 * 1024)
            self.send_json(200, {
                "service": "pocketdock-runner",
                "status": "ok",
                "uptime_seconds": int(time.monotonic() - STARTED),
                "free_storage_mb": free_megabytes,
                "timestamp": timestamp(),
            })
        elif path == "/checks/github":
            self.send_json(200, phone_network_check())
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self) -> None:
        self.send_json(405, {"error": "read-only runner"})

    def send_json(self, status: int, data: dict) -> None:
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: object) -> None:
        # Avoid constantly printing to the Termux terminal on app refreshes.
        return


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"PocketDock Runner: http://{HOST}:{PORT}/health")
    print("Read-only localhost service. Ctrl+C stops it.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
