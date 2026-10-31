#!/usr/bin/env python3
"""Unit tests for tools/bench/download_hold_server.py, the download force-stop row's fixture server.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The server is the frozen update_fixture_server.py (ruling R66) with a hold added in a subclass, so
these run it in process on a free port with no latency, as test_update_fixture.py runs the frozen
one, and ask it what the device asks: the first page of a chapter, a later page, the two control
paths. A held request is made on a thread of its own and must still be waiting after the others
have been answered.

The cases that matter, and why each one is here:
  - a chapter's first page is served while its later pages wait, which is what leaves a chapter
    part-way through its pages when the row force-stops it;
  - /hold counts what is waiting, and /hold/release answers every waiting request and holds nothing
    after it, since part two's resumed downloads would otherwise never finish;
  - only image requests of a fixture source are held: a page list, and a host that is not a source,
    are answered as the frozen server answers them;
  - a held request is logged when the hold ends, from when it arrived, beside the frozen server's
    own line for its response;
  - a hold nothing releases ends at its limit rather than keeping a thread for ever;
  - a taken port is exit 3 and a page outside a chapter is a usage error, before anything is served.
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
_spec = importlib.util.spec_from_file_location("download_hold_server", BENCH / "download_hold_server.py")
hold_server = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = hold_server
_spec.loader.exec_module(hold_server)
fx = hold_server.fx
frozen = hold_server.frozen

HOST = f"{fx.source_host(2)}:{fx.SERVER_PORT}"


class HoldServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.start(hold_server.Hold())

    def start(self, hold):
        self.log_path = Path(self.tmp.name, "requests.jsonl")
        self.log = frozen.RequestLog(self.log_path)
        self.hold = hold
        self.server = hold_server.HoldServer(("127.0.0.1", 0), self.log, hold, lambda host, path: 0.0,
                                             sleep=lambda _: None)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.close)

    def close(self):
        self.hold.release()
        self.server.shutdown()
        self.server.server_close()
        self.log.close()

    def get(self, path: str, host: str = HOST, timeout: float = 5) -> tuple[int, bytes]:
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=timeout)
        connection.request("GET", path, headers={"Host": host})
        response = connection.getresponse()
        body = response.read()
        connection.close()
        return response.status, body

    def status(self, path: str = "/hold") -> dict:
        code, body = self.get(path, host="10.0.2.2")
        self.assertEqual(code, 200)
        return json.loads(body)

    def in_background(self, path: str) -> tuple[threading.Thread, dict]:
        result: dict = {}

        def fetch():
            result["response"] = self.get(path, timeout=10)

        thread = threading.Thread(target=fetch, daemon=True)
        thread.start()
        return thread, result

    def await_held(self, count: int) -> None:
        deadline = time.monotonic() + 5
        while self.status()["held"] < count:
            self.assertLess(time.monotonic(), deadline, f"never {count} held")
            time.sleep(0.02)

    def records(self) -> list[dict]:
        return [json.loads(line) for line in self.log_path.read_text().splitlines()]

    def test_the_first_page_is_served_and_the_later_ones_wait_until_the_release(self):
        self.assertEqual(self.status(), {"holding": True, "held": 0, "heldTotal": 0, "holdFromPage": 1})
        status, body = self.get("/manga/5/chapter/1/page/0.png")
        self.assertEqual((status, body[:4]), (200, b"\x89PNG"))

        waiting = [self.in_background(f"/manga/5/chapter/1/page/{page}.png") for page in (1, 2)]
        self.await_held(2)
        time.sleep(0.2)
        self.assertTrue(all(thread.is_alive() for thread, _ in waiting), "a held page was answered")
        self.assertEqual(self.status()["heldTotal"], 2)

        released = self.status("/hold/release")
        self.assertEqual((released["holding"], released["heldTotal"]), (False, 2))
        for thread, result in waiting:
            thread.join(5)
            self.assertFalse(thread.is_alive())
            self.assertEqual(result["response"][0], 200)
        self.assertEqual(self.status()["held"], 0)
        # Nothing is held after the release.
        self.assertEqual(self.get("/manga/5/chapter/2/page/2.png")[0], 200)
        self.assertEqual(self.status()["heldTotal"], 2)

    def test_only_later_pages_of_a_fixture_source_are_held(self):
        self.assertEqual(self.get("/manga/5/chapter/1/pages")[0], 200)
        self.assertEqual(self.get("/manga/5/chapter/1/page/2.png", host="10.0.2.2")[0], 404)
        self.assertEqual(self.get("/manga/5/chapter/1/page/2.png", host="s21.update-fixture.test")[0], 400)
        self.assertEqual(self.status()["heldTotal"], 0)

    def test_a_held_request_is_logged_from_its_arrival_to_its_release(self):
        thread, _ = self.in_background("/manga/9/chapter/3/page/1.png")
        self.await_held(1)
        time.sleep(0.3)
        self.status("/hold/release")
        thread.join(5)
        records = [r for r in self.records() if r["kind"] in ("held", "image")]
        self.assertEqual([r["kind"] for r in records], ["held", "image"])
        held = records[0]
        self.assertEqual((held["source"], held["entry"], held["path"]), (2, 9, "/manga/9/chapter/3/page/1.png"))
        self.assertEqual(held["source_id"], str(fx.source_id(2)))
        self.assertGreaterEqual(held["t1"] - held["t0"], 0.3)
        self.assertEqual((records[1]["status"], records[1]["aborted"]), (200, False))
        self.assertTrue(all(r["kind"] == "hold" for r in self.records() if r["path"].startswith("/hold")))

    def test_a_hold_nothing_releases_ends_at_its_limit(self):
        self.close()
        self.start(hold_server.Hold(from_page=1, limit_s=0.3))
        started = time.monotonic()
        self.assertEqual(self.get("/manga/1/chapter/1/page/1.png")[0], 200)
        self.assertGreaterEqual(time.monotonic() - started, 0.3)
        self.assertTrue(self.status()["holding"])


class MainTest(unittest.TestCase):
    def run_main(self, *argv: str) -> tuple[int, str]:
        err = io.StringIO()
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(err):
            code = hold_server.main(["--log", str(Path(tmp, "log.jsonl")), *argv])
        return code, err.getvalue()

    def test_a_page_outside_a_chapter_is_a_usage_error(self):
        code, err = self.run_main("--hold-from-page", str(fx.PAGES_PER_CHAPTER))
        self.assertEqual(code, 2)
        self.assertIn("--hold-from-page", err)

    def test_a_taken_port_is_exit_3(self):
        with socket.socket() as taken:
            taken.bind(("127.0.0.1", 0))
            taken.listen()
            code, err = self.run_main("--port", str(taken.getsockname()[1]))
        self.assertEqual(code, 3)
        self.assertIn("is another run holding it?", err)


if __name__ == "__main__":
    unittest.main()
