"""Smoke tests for the read-only phone Runner (no external network needed)."""

from http.server import ThreadingHTTPServer
from threading import Thread
from unittest import TestCase, mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import json

from runner.server import Handler


class RunnerTests(TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = "http://127.0.0.1:" + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=3)

    def get(self, path):
        with urlopen(self.base + path, timeout=3) as response:
            self.assertEqual(response.status, 200)
            return json.load(response)

    def test_health_compatible_with_version_01(self):
        data = self.get("/health")
        self.assertEqual(data["service"], "pocketdock-runner")
        self.assertEqual(data["status"], "ok")

    def test_status_provides_phone_runtime_metrics(self):
        data = self.get("/status")
        self.assertEqual(data["status"], "ok")
        self.assertGreaterEqual(data["uptime_seconds"], 0)
        self.assertGreaterEqual(data["free_storage_mb"], 0)

    def test_phone_network_check_is_fixed_and_read_only(self):
        with mock.patch("runner.server.phone_network_check", return_value={"reachable": True}):
            self.assertTrue(self.get("/checks/github")["reachable"])

    def test_unknown_endpoint_is_rejected(self):
        with self.assertRaises(HTTPError) as error:
            urlopen(self.base + "/execute?command=rm", timeout=3)
        self.assertEqual(error.exception.code, 404)

    def test_post_is_rejected(self):
        with self.assertRaises(HTTPError) as error:
            urlopen(Request(self.base + "/status", data=b"{}", method="POST"), timeout=3)
        self.assertEqual(error.exception.code, 405)
