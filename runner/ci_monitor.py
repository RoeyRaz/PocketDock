"""Read-only GitHub Actions monitor for a PUBLIC repository.

This check runs on the Android phone. It distinguishes build failure from
GitHub API availability and does not require credentials or a Windows agent.
"""
from __future__ import annotations

import json
import re
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit

try:
    from .watchdog import checked_url, DenyRedirects, InvalidCheck
except ImportError:
    from watchdog import checked_url, DenyRedirects, InvalidCheck

RUN_URL = "https://api.github.com/repos/RoeyRaz/PocketDock/actions/runs?per_page=1"
RUN_PATH = re.compile(r"^/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs$")
FAILURES = {"failure", "timed_out", "action_required"}
MAX_RESPONSE = 96 * 1024


def validate_ci_url(url: str) -> str:
    checked_url(url, resolve=False)
    parsed = urlsplit(url)
    if (parsed.hostname != "api.github.com" or parsed.path.count("/") != 5 or
            not RUN_PATH.fullmatch(parsed.path) or parsed.query != "per_page=1"):
        raise InvalidCheck("Expected the public GitHub Actions latest-run API URL")
    return url


def probe_ci(url: str) -> tuple[bool | None, int | None, int, str]:
    """Return healthy/failing/pending, HTTP status, elapsed ms and short context.

    'Pending' does not generate a spurious recovery notification while a build
    is still running. The caller persists the last completed outcome.
    """
    started = time.monotonic()
    try:
        validate_ci_url(url)
        # Resolve the destination at check time, forbidding private IPs.
        checked_url(url, resolve=True)
        opener = urllib.request.build_opener(DenyRedirects)
        req = urllib.request.Request(url, headers={
            "User-Agent": "PocketDock-CI-Watchdog/0.4",
            "Accept": "application/vnd.github+json",
        })
        with opener.open(req, timeout=6) as response:
            body = response.read(MAX_RESPONSE + 1)
            code = response.status
        if len(body) > MAX_RESPONSE:
            raise ValueError("GitHub response exceeded limit")
        payload = json.loads(body.decode("utf-8"))
        runs = payload.get("workflow_runs", [])
        if not isinstance(runs, list):
            raise ValueError("Invalid GitHub response")
        if not runs:
            return None, code, int((time.monotonic() - started) * 1000), "No workflow runs yet"
        run = runs[0]
        if not isinstance(run, dict):
            raise ValueError("Invalid workflow run")
        status = run.get("status")
        conclusion = run.get("conclusion")
        title = str(run.get("name", "Workflow"))[:60]
        elapsed = int((time.monotonic() - started) * 1000)
        if status != "completed":
            return None, code, elapsed, f"{title}: {status or 'pending'}"
        if conclusion == "success":
            return True, code, elapsed, f"{title}: success"
        if conclusion in FAILURES:
            return False, code, elapsed, f"{title}: {conclusion}"
        # cancelled, skipped, neutral and anything else are not failures.
        return None, code, elapsed, f"{title}: {conclusion or 'unknown'}"
    except urllib.error.HTTPError as error:
        code = error.code
        return None, code, int((time.monotonic() - started) * 1000), f"GitHub API HTTP {code}"
    except (InvalidCheck, urllib.error.URLError, TimeoutError, OSError,
            ValueError, KeyError, TypeError, UnicodeError) as error:
        return None, None, int((time.monotonic() - started) * 1000), str(error)[:140]
