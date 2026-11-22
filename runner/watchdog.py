"""Persistent, bounded, opt-in Watchdog engine running on the Android phone.

No root, third-party Python packages, Windows agent or remote shell.
Storage: SQLite in ~/.local/share/pocketdock (override for tests).
All HTTP checks are HTTPS-only, have timeouts, and reject local/private destinations.
"""
from __future__ import annotations

import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import subprocess
import threading
import time
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from urllib.error import HTTPError, URLError

DEFAULT_URL = "https://api.github.com/"
MIN_INTERVAL = 5
MAX_INTERVAL = 1440
MAX_CHECKS = 12


class InvalidCheck(ValueError):
    pass


def checked_url(url: str, *, resolve: bool = True) -> str:
    """Forbid local addresses, credentials, redirects and non-HTTPS checks."""
    if not isinstance(url, str) or len(url) > 500:
        raise InvalidCheck("HTTPS URL must be at most 500 characters")
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise InvalidCheck("Invalid URL") from exc
    if (
        parsed.scheme != "https" or not host or parsed.username or parsed.password or
        parsed.fragment or port not in (None, 443) or
        not re.fullmatch(r"[a-zA-Z0-9.-]+", host) or "." not in host or
        host.startswith(".") or host.endswith(".") or ".." in host
    ):
        raise InvalidCheck("Only public HTTPS websites on port 443 are supported")
    try:
        ipaddress.ip_address(host)
        raise InvalidCheck("Direct IP addresses are not supported")
    except ValueError as exc:
        if isinstance(exc, InvalidCheck):
            raise
    if host.lower().endswith((".local", ".internal", ".test", ".localhost")):
        raise InvalidCheck("Local hostnames are not supported")
    if resolve:
        try:
            addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
            if not addresses:
                raise InvalidCheck("Domain could not be resolved")
            for family, _, _, _, address in addresses:
                ip = ipaddress.ip_address(address[0])
                if not ip.is_global:
                    raise InvalidCheck("Private or special network destinations are blocked")
        except (socket.gaierror, OSError) as exc:
            raise InvalidCheck("Domain could not be resolved") from exc
    return url


class DenyRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def probe(url: str) -> tuple[bool, int | None, int, str]:
    start = time.monotonic()
    try:
        checked_url(url)
        opener = build_opener(DenyRedirects)
        request = Request(url, headers={"User-Agent": "PocketDock-Watchdog/0.3"})
        with opener.open(request, timeout=5) as response:
            status = response.status
            ok = 200 <= status < 300
            return ok, status, int((time.monotonic() - start) * 1000), "" if ok else f"HTTP {status}"
    except HTTPError as exc:
        status = exc.code
        return False, status, int((time.monotonic() - start) * 1000), f"HTTP {status}"
    except (InvalidCheck, URLError, TimeoutError, OSError, ValueError) as exc:
        return False, None, int((time.monotonic() - start) * 1000), str(exc)[:160]


class WatchdogStore:
    """Thread-safe via short per-operation SQLite connections and a single lock."""

    def __init__(self, home: Path | None = None):
        self.home = Path(home) if home else Path(
            os.environ.get("POCKETDOCK_DATA_DIR", str(Path.home() / ".local/share/pocketdock"))
        )
        self.home.mkdir(parents=True, exist_ok=True)
        try:
            self.home.chmod(0o700)
        except OSError:
            pass
        self.db = self.home / "watchdog.sqlite3"
        self.keyfile = self.home / "pairing-token"
        self.lock = threading.RLock()
        self._initialize()
        self._ensure_token()

    def _connect(self):
        conn = sqlite3.connect(self.db, timeout=10)
        conn.row_factory = sqlite3.Row
        return conn

    def _initialize(self):
        with self._connect() as conn:
            conn.execute("""CREATE TABLE IF NOT EXISTS checks (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL,
                interval_minutes INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
                last_state TEXT, last_checked REAL, last_http INTEGER,
                last_latency_ms INTEGER, last_error TEXT, kind TEXT NOT NULL DEFAULT 'http')""")
            conn.execute("""CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, check_id TEXT,
                timestamp REAL NOT NULL, state TEXT NOT NULL, message TEXT NOT NULL)""")
            conn.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)")
            # Existing 0.3 databases upgrade in-place without resetting tokens or history.
            columns = {row[1] for row in conn.execute("PRAGMA table_info(checks)")}
            if "kind" not in columns:
                conn.execute("ALTER TABLE checks ADD COLUMN kind TEXT NOT NULL DEFAULT 'http'")
            # Initialize built-in checks only on first boot. A user's removal
            # must not be undone on every restart.
            if conn.execute("SELECT value FROM settings WHERE key='health_defaults_seeded'").fetchone() is None:
                n = conn.execute("SELECT COUNT(*) AS n FROM checks").fetchone()["n"]
                if n == 0:
                    conn.execute(
                        "INSERT INTO checks (id,name,url,interval_minutes,enabled) VALUES (?,?,?,?,?)",
                        ("github-api", "GitHub API", DEFAULT_URL, 15, 1),
                    )
                conn.execute("INSERT INTO settings (key,value) VALUES ('health_defaults_seeded','true')")
            # Seed once only, so deleting this monitor later is respected.
            if conn.execute("SELECT value FROM settings WHERE key='ci_defaults_seeded'").fetchone() is None:
                current_count = conn.execute("SELECT COUNT(*) FROM checks").fetchone()[0]
                if current_count < MAX_CHECKS:
                    conn.execute(
                        "INSERT OR IGNORE INTO checks (id,name,url,interval_minutes,enabled,kind) "
                        "VALUES (?,?,?,?,?,?)",
                        ("pocketdock-ci", "PocketDock CI",
                         "https://api.github.com/repos/RoeyRaz/PocketDock/actions/runs?per_page=1",
                         30, 1, "github_actions"),
                    )
                conn.execute("INSERT INTO settings (key,value) VALUES ('ci_defaults_seeded','true')")
            if conn.execute("SELECT value FROM settings WHERE key='notifications'").fetchone() is None:
                conn.execute("INSERT INTO settings (key,value) VALUES ('notifications','false')")
        try:
            self.db.chmod(0o600)
        except OSError:
            pass

    def _ensure_token(self):
        with self.lock:
            if self.keyfile.exists():
                return
            token = secrets.token_urlsafe(32)
            fd = os.open(str(self.keyfile), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as file:
                    file.write(token + "\n")
            except Exception:
                self.keyfile.unlink(missing_ok=True)
                raise

    def token(self) -> str:
        return self.keyfile.read_text(encoding="utf-8").strip()

    def authorized(self, supplied: str) -> bool:
        return bool(supplied) and hmac.compare_digest(self.token(), supplied)

    def settings(self) -> dict:
        with self.lock, self._connect() as conn:
            values = conn.execute("SELECT key,value FROM settings").fetchall()
            return {r["key"]: r["value"] == "true" for r in values}

    def set_notifications(self, enabled: bool):
        with self.lock, self._connect() as conn:
            conn.execute(
                "INSERT INTO settings (key,value) VALUES ('notifications',?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                ("true" if enabled else "false",),
            )

    def list_checks(self) -> list[dict]:
        with self.lock, self._connect() as conn:
            rows = conn.execute("SELECT * FROM checks ORDER BY name COLLATE NOCASE").fetchall()
            return [dict(row) | {"enabled": bool(row["enabled"])} for row in rows]

    def add_check(self, name: str, url: str, interval: int) -> dict:
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 60:
            raise InvalidCheck("Name must be 1 to 60 characters")
        if type(interval) is not int or not MIN_INTERVAL <= interval <= MAX_INTERVAL:
            raise InvalidCheck("Interval must be 5 to 1440 minutes")
        checked_url(url, resolve=False)
        # Probes validate public DNS again at execution time.
        with self.lock, self._connect() as conn:
            count = conn.execute("SELECT COUNT(*) FROM checks").fetchone()[0]
            if count >= MAX_CHECKS:
                raise InvalidCheck("At most 12 checks are supported")
            slug = secrets.token_hex(8)
            conn.execute(
                "INSERT INTO checks (id,name,url,interval_minutes,enabled) VALUES (?,?,?,?,1)",
                (slug, name.strip(), url, interval),
            )
            return {"id": slug, "name": name.strip(), "url": url, "interval_minutes": interval}

    def get_check(self, identifier: str) -> dict | None:
        with self.lock, self._connect() as conn:
            row = conn.execute("SELECT * FROM checks WHERE id=?", (identifier,)).fetchone()
            return dict(row) if row else None

    def set_enabled(self, identifier: str, enabled: bool) -> bool:
        with self.lock, self._connect() as conn:
            result = conn.execute("UPDATE checks SET enabled=? WHERE id=?", (int(enabled), identifier))
            return result.rowcount == 1

    def remove(self, identifier: str) -> bool:
        with self.lock, self._connect() as conn:
            result = conn.execute("DELETE FROM checks WHERE id=?", (identifier,))
            return result.rowcount == 1

    def history(self, limit=30) -> list[dict]:
        limit = max(1, min(int(limit), 100))
        with self.lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT e.*, COALESCE(c.name, e.check_id) AS name FROM events e "
                "LEFT JOIN checks c ON c.id=e.check_id ORDER BY e.id DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [dict(row) for row in rows]

    def _notify(self, name: str, state: str):
        """Optional Termux:API integration, no shell and no notification spam."""
        if not self.settings().get("notifications"):
            return
        if not shutil.which("termux-notification"):
            return
        try:
            subprocess.run(
                ["termux-notification", "--id", "8033", "--title", "PocketDock",
                 "--content", (name + ": " + state)[:140]],
                check=False, timeout=3, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
        except (OSError, subprocess.TimeoutExpired):
            pass

    def run_check(self, identifier: str, checker=probe) -> dict | None:
        check = self.get_check(identifier)
        if check is None:
            return None
        if check.get("kind") == "github_actions" and checker is probe:
            try:
                from .ci_monitor import probe_ci
            except ImportError:
                from ci_monitor import probe_ci
            ok, code, latency, error = probe_ci(check["url"])
        else:
            ok, code, latency, error = checker(check["url"])
        # In-progress/cancelled builds and transient API errors are not verdicts.
        # Keep the last completed build state to avoid false recovery alerts.
        if ok is None:
            now = time.time()
            with self.lock, self._connect() as conn:
                conn.execute(
                    "UPDATE checks SET last_checked=?, last_http=?, last_latency_ms=?, "
                    "last_error=? WHERE id=?",
                    (now, code, latency, error[:160], identifier),
                )
            return {"id": identifier, "state": check["last_state"],
                    "pending": True, "http": code,
                    "latency_ms": latency, "error": error}
        current = "up" if ok else "down"
        changed = check["last_state"] is not None and check["last_state"] != current
        now = time.time()
        with self.lock, self._connect() as conn:
            conn.execute(
                "UPDATE checks SET last_state=?, last_checked=?, last_http=?, last_latency_ms=?, "
                "last_error=? WHERE id=?",
                (current, now, code, latency, error[:160], identifier),
            )
            # Store first reading and state transitions. Avoid logging unchanged checks.
            if changed or check["last_state"] is None:
                message = (error if check.get("kind") == "github_actions" else (f"HTTP {code}" if ok else error))[:160]
                conn.execute(
                    "INSERT INTO events (check_id,timestamp,state,message) VALUES (?,?,?,?)",
                    (identifier, now, current, message),
                )
                conn.execute(
                    "DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 300)"
                )
        if changed:
            self._notify(check["name"], current.upper())
        return {"id": identifier, "state": current, "http": code, "latency_ms": latency, "error": error}

    def due_ids(self, now: float | None = None) -> list[str]:
        now = time.time() if now is None else now
        return [
            item["id"] for item in self.list_checks()
            if item["enabled"] and (
                item["last_checked"] is None or
                now - float(item["last_checked"]) >= item["interval_minutes"] * 60
            )
        ]

    def run_scheduler(self, stop: threading.Event):
        while not stop.wait(5):
            for identifier in self.due_ids():
                if stop.is_set():
                    return
                try:
                    self.run_check(identifier)
                except Exception:
                    # Keep the scheduler alive; a bad check must not stop the service.
                    pass

    def overview(self) -> dict:
        checks = self.list_checks()
        return {
            "checks": checks,
            "history": self.history(20),
            "settings": self.settings(),
            "totals": {
                "all": len(checks),
                "enabled": sum(bool(c["enabled"]) for c in checks),
                "down": sum(c["last_state"] == "down" for c in checks if c["enabled"]),
            },
        }
