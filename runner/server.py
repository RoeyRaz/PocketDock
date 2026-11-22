"""PocketDock Android Runner v0.3, localhost-only and backwards compatible.

GET /health, /status and /checks/github remain available to 0.1/0.2 clients.
The Watchdog control API requires a random, per-device pairing token.
"""
from __future__ import annotations

import argparse
import fcntl
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import shutil
import threading
import time
from urllib.parse import urlsplit

try:
    from .watchdog import InvalidCheck, WatchdogStore, probe
except ImportError:
    from watchdog import InvalidCheck, WatchdogStore, probe

HOST = "127.0.0.1"
PORT = 8799
STARTED = time.monotonic()


def timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def phone_network_check() -> dict:
    ok, _, _, _ = probe("https://api.github.com/")
    return {"reachable": ok, "checked_at": timestamp()}


class Handler(BaseHTTPRequestHandler):
    store: WatchdogStore | None = None

    def _path(self):
        return urlsplit(self.path).path

    def _valid_host(self):
        host = self.headers.get("Host", "")
        return host in (f"127.0.0.1:{PORT}", f"localhost:{PORT}",
                        f"127.0.0.1:{self.server.server_port}",
                        f"localhost:{self.server.server_port}")

    def _authorized(self):
        if self.headers.get("Origin"):
            return False
        supplied = self.headers.get("Authorization", "")
        if not supplied.startswith("Bearer ") or self.store is None:
            return False
        return self.store.authorized(supplied[7:])

    def _require_auth(self):
        if self._authorized():
            return True
        self.send_json(401, {"error": "Pair the phone with the Runner token"})
        return False

    def do_GET(self):
        if not self._valid_host():
            self.send_json(403, {"error": "Invalid Host"})
            return
        path = self._path()
        if path == "/health":
            self.send_json(200, {"service": "pocketdock-runner", "status": "ok",
                                 "timestamp": timestamp()})
        elif path == "/status":
            self.send_json(200, {"service": "pocketdock-runner", "status": "ok",
                                 "uptime_seconds": int(time.monotonic() - STARTED),
                                 "free_storage_mb": shutil.disk_usage(Path.home()).free // (1024 * 1024),
                                 "timestamp": timestamp()})
        elif path == "/checks/github":
            self.send_json(200, phone_network_check())
        elif path == "/api/overview":
            if self._require_auth():
                self.send_json(200, self.store.overview())
        elif path == "/api/history":
            if self._require_auth():
                self.send_json(200, {"events": self.store.history(50)})
        else:
            self.send_json(404, {"error": "Not found"})

    def _json_body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 2 or length > 4096:
                raise ValueError("Invalid body size")
            value = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(value, dict):
                raise ValueError("JSON object expected")
            return value
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError):
            raise ValueError("Invalid JSON or request body")

    def do_POST(self):
        if not self._valid_host():
            self.send_json(403, {"error": "Invalid Host"})
            return
        path = self._path()
        if path in ("/health", "/status", "/checks/github"):
            self.send_json(405, {"error": "Read-only endpoint"})
            return
        if not self._require_auth():
            return
        try:
            body = self._json_body()
            if path == "/api/checks":
                if set(body) - {"name", "url", "interval_minutes"}:
                    raise InvalidCheck("Unsupported check property")
                check = self.store.add_check(body.get("name"), body.get("url"), body.get("interval_minutes"))
                self.send_json(201, check)
                return
            if path == "/api/settings":
                if type(body.get("notifications")) is not bool or set(body) != {"notifications"}:
                    raise ValueError("notifications must be a boolean")
                self.store.set_notifications(body["notifications"])
                self.send_json(200, {"settings": self.store.settings()})
                return
            parts = path.split("/")
            if len(parts) == 5 and parts[:3] == ["", "api", "checks"]:
                identifier = parts[3]
                if len(identifier) > 40 or not identifier.replace("-", "").isalnum():
                    raise ValueError("Invalid check ID")
                if parts[4] == "run" and body == {}:
                    result = self.store.run_check(identifier)
                    if result is None:
                        self.send_json(404, {"error": "Check not found"})
                    else:
                        self.send_json(200, result)
                    return
                if parts[4] == "toggle" and set(body) == {"enabled"} and type(body["enabled"]) is bool:
                    found = self.store.set_enabled(identifier, body["enabled"])
                    self.send_json(200 if found else 404, {"enabled": body["enabled"]} if found else {"error": "Check not found"})
                    return
            self.send_json(404, {"error": "Unknown operation"})
        except (ValueError, InvalidCheck) as exc:
            self.send_json(400, {"error": str(exc)[:180]})

    def do_DELETE(self):
        if not self._valid_host():
            self.send_json(403, {"error": "Invalid Host"})
            return
        if not self._require_auth():
            return
        path = self._path()
        parts = path.split("/")
        if len(parts) == 4 and parts[:3] == ["", "api", "checks"]:
            if self.store.remove(parts[3]):
                self.send_json(200, {"deleted": True})
            else:
                self.send_json(404, {"error": "Check not found"})
        else:
            self.send_json(404, {"error": "Unknown operation"})

    def send_json(self, code, data):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # Never log pairing tokens or request contents.
        return


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--show-token", action="store_true",
                        help="Print the local pairing token in this Termux session only")
    args = parser.parse_args()
    store = WatchdogStore()
    if args.show_token:
        print(store.token())
        return

    lock_file = store.home / "runner.lock"
    with lock_file.open("a+") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("PocketDock Runner is already running in another Termux session.")
            return
        Handler.store = store
        server = ThreadingHTTPServer((HOST, PORT), Handler)
        server.daemon_threads = True
        stop = threading.Event()
        worker = threading.Thread(target=store.run_scheduler, args=(stop,), daemon=True)
        worker.start()
        print(f"PocketDock Runner: http://{HOST}:{PORT}/health")
        print("Watchdog active on this phone. Pair via --show-token. Ctrl+C stops it.")
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            stop.set()
            server.server_close()
            worker.join(timeout=6)
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


if __name__ == "__main__":
    main()
