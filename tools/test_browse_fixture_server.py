#!/usr/bin/env python3
"""Unit tests for tools/bench/browse_fixture_server.py, the Browse device tests' fixture store.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The server is the frozen update_fixture_server.py (ruling R66) with a readable log and a failing
listing page added in a subclass, so these run it in process on a free port with no latency, as
test_download_hold_server.py runs its wrapper, over a repository directory made here with a stand-in
APK. Nothing here binds 18794.

The cases that matter, and why each one is here:
  - the repository answers as the frozen one does, and /control/log names each of its requests in
    order without the control requests, which is how a device test reads what the app asked;
  - a listing page at or past the failing one is answered 500 and logged "failed", the pages before
    it and every other kind of request are served, and /control/answer serves the failed page again;
  - a failure is counted per request, and a control path that names no page or no such path is
    refused without changing anything;
  - no repository directory is a usage error, and a taken port is exit 3.
"""

from __future__ import annotations

import contextlib
import http.client
import importlib.util
import io
import json
import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
sys.path.insert(0, str(BENCH))
_spec = importlib.util.spec_from_file_location("browse_fixture_server", BENCH / "browse_fixture_server.py")
browse_server = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = browse_server
_spec.loader.exec_module(browse_server)
fx = browse_server.fx
frozen = browse_server.frozen

SOURCE_HOST = f"{fx.source_host(3)}:{fx.SERVER_PORT}"
DEVICE_HOST = "10.0.2.2:18794"


class BrowseFixtureServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        repo = Path(self.tmp.name, "repo")
        repo.mkdir()
        Path(repo, "update-fixture.apk").write_bytes(b"PK stand-in")
        Path(repo, "signer.sha256").write_text("ab" * 32 + "\n")
        self.log_path = Path(self.tmp.name, "requests.jsonl")
        self.log = browse_server.KeptLog(self.log_path)
        self.server = browse_server.BrowseFixtureServer(("127.0.0.1", 0), self.log, frozen.Repository(repo),
                                                        lambda host, path: 0.0, sleep=lambda _: None)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.close)

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.log.close()

    def get(self, path: str, host: str = SOURCE_HOST) -> tuple[int, bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        connection.request("GET", path, headers={"Host": host})
        response = connection.getresponse()
        body = response.read()
        connection.close()
        return response.status, body

    def control(self, path: str, expected: int = 200) -> dict:
        status, body = self.get(path, host=DEVICE_HOST)
        self.assertEqual(status, expected, body)
        return json.loads(body)

    def logged(self, count: int) -> list[dict]:
        # The frozen handler writes its line after the response is flushed (R140 item 3), so the log
        # is read until it holds the lines the requests made.
        deadline = time.monotonic() + 5
        while True:
            requests = self.control("/control/log")["requests"]
            if len(requests) >= count or time.monotonic() > deadline:
                return requests
            time.sleep(0.02)

    def test_the_log_names_each_repository_request_in_order_and_no_control_request(self):
        self.assertEqual(self.control("/control/log"), {"requests": []})
        status, body = self.get("/repo/index.min.json", host=DEVICE_HOST)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)[0]["pkg"], fx.EXTENSION_PACKAGE)
        self.assertEqual(self.get("/repo/repo.json", host=DEVICE_HOST)[0], 200)
        self.assertEqual(self.get("/repo/apk/update-fixture.apk", host=DEVICE_HOST), (200, b"PK stand-in"))
        self.assertEqual(self.get("/repo/nothing", host=DEVICE_HOST)[0], 404)

        requests = self.logged(4)
        self.assertEqual([(r["kind"], r["path"], r["status"]) for r in requests], [
            ("repo", "/repo/index.min.json", 200),
            ("repo", "/repo/repo.json", 200),
            ("repo", "/repo/apk/update-fixture.apk", 200),
            ("repo", "/repo/nothing", 404),
        ])
        # The file holds the same records and the control requests too.
        lines = [json.loads(line) for line in self.log_path.read_text().splitlines()]
        self.assertEqual([r["path"] for r in lines if r["kind"] == "repo"], [r["path"] for r in requests])
        self.assertTrue(any(r["kind"] == "control" and r["path"] == "/control/log" for r in lines))

    def test_pages_from_the_failing_one_answer_500_until_the_server_answers_again(self):
        self.assertEqual(self.control("/control/fail?from=2"), {"failFromPage": 2, "failed": 0})

        status, body = self.get("/popular?page=1")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["entries"][0]["title"], fx.entry_title(3, 1))
        self.assertEqual(self.get("/popular?page=2")[0], 500)
        self.assertEqual(self.get("/popular?page=3&q=Fixture")[0], 500)
        # Only a source's listing fails: an entry, and a listing asked of a host that is no source.
        self.assertEqual(self.get("/manga/60/chapters")[0], 200)
        self.assertEqual(self.get("/popular?page=2", host=DEVICE_HOST)[0], 404)
        self.assertEqual(self.control("/control/answer"), {"failFromPage": None, "failed": 2})

        status, body = self.get("/popular?page=2")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["entries"][0]["title"], fx.entry_title(3, fx.POPULAR_PAGE_SIZE + 1))

        requests = self.logged(6)
        self.assertEqual([(r["kind"], r["path"], r["status"]) for r in requests], [
            ("popular", "/popular?page=1", 200),
            ("failed", "/popular?page=2", 500),
            ("failed", "/popular?page=3&q=Fixture", 500),
            ("chapters", "/manga/60/chapters", 200),
            ("unknown", "/popular?page=2", 404),
            ("popular", "/popular?page=2", 200),
        ])
        failed = requests[1]
        self.assertEqual((failed["source"], failed["source_id"]), (3, str(fx.source_id(3))))

    def test_a_control_path_with_no_page_or_no_such_path_is_refused(self):
        self.assertEqual(self.control("/control/fail", expected=400)["error"], "from must be a page number, 1 or more")
        self.assertEqual(self.control("/control/fail?from=0", expected=400)["error"],
                         "from must be a page number, 1 or more")
        self.control("/control/nothing", expected=404)
        self.assertEqual(self.get("/popular?page=2")[0], 200)
        self.assertEqual(self.control("/control/answer"), {"failFromPage": None, "failed": 0})


class MainTest(unittest.TestCase):
    def run_main(self, *argv: str) -> tuple[int, str]:
        err = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(err):
            code = browse_server.main(["--log", str(Path(tmp, "log.jsonl")), *argv])
        return code, err.getvalue()

    def test_a_directory_with_no_repository_is_exit_2(self):
        with tempfile.TemporaryDirectory() as empty:
            code, err = self.run_main("--repo-dir", empty, "--port", "0")
        self.assertEqual(code, 2)
        self.assertIn("run build.sh", err)

    def test_no_repository_directory_is_a_usage_error(self):
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(io.StringIO()):
            browse_server.main(["--log", "unused.jsonl"])
        self.assertEqual(raised.exception.code, 2)

    def test_a_taken_port_is_exit_3(self):
        with tempfile.TemporaryDirectory() as repo, socket.socket() as taken:
            Path(repo, "update-fixture.apk").write_bytes(b"PK")
            Path(repo, "signer.sha256").write_text("ab\n")
            taken.bind(("127.0.0.1", 0))
            taken.listen()
            code, err = self.run_main("--repo-dir", repo, "--port", str(taken.getsockname()[1]))
        self.assertEqual(code, 3)
        self.assertIn("is another run holding it?", err)


if __name__ == "__main__":
    unittest.main()
