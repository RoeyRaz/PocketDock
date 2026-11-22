"""Offline unit tests for the Watchdog's safety, persistence and API."""
from http.server import ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread
from unittest import TestCase, mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import json

from runner.server import Handler
from runner.watchdog import InvalidCheck, WatchdogStore, checked_url


class EngineTests(TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.store = WatchdogStore(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def test_initial_check_and_secret_are_created_privately(self):
        self.assertEqual(self.store.list_checks()[0]["id"], "github-api")
        self.assertGreaterEqual(len(self.store.token()), 30)
        self.assertTrue(self.store.authorized(self.store.token()))
        self.assertFalse(self.store.authorized("invalid"))
        self.assertEqual(self.store.keyfile.stat().st_mode & 0o777, 0o600)

    def test_url_safety(self):
        for url in (
            "http://example.com/", "https://127.0.0.1/", "https://[::1]/",
            "https://localhost/", "https://example.local/", "https://user:secret@example.com/",
            "https://example.com:8080/", "file:///etc/passwd", "https://api.github.com/#frag"
        ):
            with self.subTest(url=url), self.assertRaises(InvalidCheck):
                checked_url(url, resolve=False)
        self.assertEqual(checked_url("https://api.github.com/", resolve=False), "https://api.github.com/")

    def test_persistent_events_only_when_status_changes(self):
        item = self.store.add_check("Example", "https://example.com/", 5)
        self.store.run_check(item["id"], checker=lambda _: (True, 200, 32, ""))
        self.store.run_check(item["id"], checker=lambda _: (True, 200, 22, ""))
        self.store.run_check(item["id"], checker=lambda _: (False, 503, 25, "HTTP 503"))
        self.assertEqual(len(self.store.history()), 2)
        self.assertEqual(self.store.history()[0]["state"], "down")
        reopened = WatchdogStore(Path(self.temp.name))
        self.assertEqual(reopened.get_check(item["id"])["last_state"], "down")

    def test_scheduler_limits_and_toggle(self):
        item = self.store.add_check("Example", "https://example.com/", 5)
        self.assertIn(item["id"], self.store.due_ids())
        self.assertTrue(self.store.set_enabled(item["id"], False))
        self.assertNotIn(item["id"], self.store.due_ids())
        with self.assertRaises(InvalidCheck):
            self.store.add_check("Bad", "https://example.com/", 1)

    def test_notification_is_off_by_default(self):
        self.assertFalse(self.store.settings()["notifications"])
        self.store.set_notifications(True)
        self.assertTrue(self.store.settings()["notifications"])
        with mock.patch("runner.watchdog.shutil.which", return_value=None):
            self.store._notify("Sample", "DOWN")


class ApiTests(TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = TemporaryDirectory()
        cls.store = WatchdogStore(Path(cls.temp.name))
        class TestHandler(Handler):
            store = cls.store
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        cls.thread = Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)
        cls.temp.cleanup()

    def api(self, path, method="GET", payload=None, token=True):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + self.store.token()
        data = json.dumps(payload).encode() if payload is not None else None
        request = Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def test_legacy_health_and_status(self):
        code, data = self.api("/health", token=False)
        self.assertEqual(code, 200)
        self.assertEqual(data["service"], "pocketdock-runner")

    def test_protected_overview(self):
        code, _ = self.api("/api/overview", token=False)
        self.assertEqual(code, 401)
        code, data = self.api("/api/overview")
        self.assertEqual(code, 200)
        self.assertEqual(data["totals"]["all"], 2)

    def test_crud_and_validation(self):
        code, data = self.api("/api/checks", "POST", {
            "name": "Example", "url": "https://example.com/", "interval_minutes": 10
        })
        self.assertEqual(code, 201)
        identifier = data["id"]
        code, _ = self.api(f"/api/checks/{identifier}/toggle", "POST", {"enabled": False})
        self.assertEqual(code, 200)
        code, _ = self.api("/api/checks", "POST", {
            "name": "Forbidden", "url": "http://localhost/", "interval_minutes": 10
        })
        self.assertEqual(code, 400)
        code, _ = self.api(f"/api/checks/{identifier}", "DELETE")
        self.assertEqual(code, 200)
        code, _ = self.api(f"/api/checks/{identifier}", "DELETE")
        self.assertEqual(code, 404)

    def test_unrecognized_commands_are_rejected(self):
        code, _ = self.api("/api/command", "POST", {"cmd": "ls"})
        self.assertEqual(code, 404)
