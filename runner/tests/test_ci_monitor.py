"""Offline tests for migration and meaningful CI Watchdog verdicts."""
import json
import sqlite3
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, mock

from runner.ci_monitor import RUN_URL, probe_ci, validate_ci_url
from runner.watchdog import InvalidCheck, WatchdogStore


class FakeResponse:
    status = 200

    def __init__(self, body):
        self.body = json.dumps(body).encode("utf-8")

    def read(self, limit):
        return self.body[:limit]

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class CIProbeTests(TestCase):
    def test_only_public_github_actions_urls(self):
        self.assertEqual(validate_ci_url(RUN_URL), RUN_URL)
        for url in (
            "http://api.github.com/repos/x/y/actions/runs?per_page=1",
            "https://localhost/repos/x/y/actions/runs?per_page=1",
            "https://example.com/repos/x/y/actions/runs?per_page=1",
            "https://api.github.com/repos/x/y/actions/runs?per_page=100",
            "https://api.github.com/repos/x/y/issues?per_page=1",
        ):
            with self.subTest(url=url), self.assertRaises(InvalidCheck):
                validate_ci_url(url)

    def check_outcome(self, status, conclusion, expected):
        response = FakeResponse({
            "workflow_runs": [{
                "name": "Android debug APK",
                "status": status,
                "conclusion": conclusion,
            }]
        })
        with mock.patch("runner.ci_monitor.checked_url", return_value=RUN_URL):
            with mock.patch("runner.ci_monitor.urllib.request.build_opener") as build:
                build.return_value.open.return_value = response
                outcome, code, elapsed, detail = probe_ci(RUN_URL)
        self.assertIs(outcome, expected)
        self.assertEqual(code, 200)
        self.assertGreaterEqual(elapsed, 0)
        self.assertIn("Android", detail)

    def test_success_failure_and_running_builds(self):
        self.check_outcome("completed", "success", True)
        self.check_outcome("completed", "failure", False)
        self.check_outcome("in_progress", None, None)
        self.check_outcome("completed", "cancelled", None)


class CIMigrationTests(TestCase):
    def setUp(self):
        self.tmp = TemporaryDirectory()
        self.home = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_new_install_and_delete_are_idempotent(self):
        store = WatchdogStore(self.home)
        self.assertEqual({c["id"] for c in store.list_checks()},
                         {"github-api", "pocketdock-ci"})
        self.assertEqual(store.get_check("pocketdock-ci")["kind"], "github_actions")
        self.assertTrue(store.remove("github-api"))
        self.assertTrue(store.remove("pocketdock-ci"))
        store = WatchdogStore(self.home)
        self.assertEqual(store.list_checks(), [])

    def test_previous_database_migrates_without_resetting_existing_history(self):
        path = self.home / "watchdog.sqlite3"
        with sqlite3.connect(path) as conn:
            conn.execute("""CREATE TABLE checks (
                id TEXT PRIMARY KEY, name TEXT, url TEXT, interval_minutes INTEGER,
                enabled INTEGER, last_state TEXT, last_checked REAL,
                last_http INTEGER, last_latency_ms INTEGER, last_error TEXT)""")
            conn.execute("""CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, check_id TEXT,
                timestamp REAL, state TEXT, message TEXT)""")
            conn.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)")
            conn.execute(
                "INSERT INTO checks VALUES (?,?,?,?,?,?,?,?,?,?)",
                ("github-api", "GitHub API", "https://api.github.com/", 15,
                 1, "up", 100.0, 200, 11, ""),
            )
            conn.execute("INSERT INTO events (check_id,timestamp,state,message) VALUES (?,?,?,?)",
                         ("github-api", 100.0, "up", "HTTP 200"))
        store = WatchdogStore(self.home)
        self.assertEqual(store.get_check("github-api")["last_state"], "up")
        self.assertEqual(store.get_check("github-api")["kind"], "http")
        self.assertEqual(len(store.history()), 1)
        self.assertIsNotNone(store.get_check("pocketdock-ci"))

    def test_pending_build_does_not_reset_last_good_or_bad_state(self):
        store = WatchdogStore(self.home)
        with mock.patch("runner.ci_monitor.probe_ci",
                        side_effect=[
                            (True, 200, 40, "CI: success"),
                            (None, 200, 48, "CI: in_progress"),
                            (False, 200, 50, "CI: failure"),
                            (None, 403, 55, "GitHub API HTTP 403"),
                        ]):
            self.assertEqual(store.run_check("pocketdock-ci")["state"], "up")
            self.assertTrue(store.run_check("pocketdock-ci")["pending"])
            self.assertEqual(store.get_check("pocketdock-ci")["last_state"], "up")
            self.assertEqual(store.run_check("pocketdock-ci")["state"], "down")
            self.assertTrue(store.run_check("pocketdock-ci")["pending"])
        self.assertEqual(store.get_check("pocketdock-ci")["last_state"], "down")
        self.assertEqual(len(store.history()), 2)
