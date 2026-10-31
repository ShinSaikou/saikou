#!/usr/bin/env python3
"""The fixture store and sources for the Browse device tests (phase 5 unit TG2), on the host.

It is tools/bench/update_fixture_server.py, frozen by ruling R66, wrapped rather than edited: the
same twenty sources, the same synthetic repository holding the fixture extension (served from
--repo-dir, the directory tools/fixtures/update-extension/build.sh writes), the same seeded latency
and the same JSON request log, served by that file's own FixtureServer and Handler. What this adds,
for a device test that cannot read a file on the host:

  - the log, readable over HTTP. Every record the frozen handler writes is also kept in memory, and
    GET /control/log answers {"requests": [<record>, ...]} with every one so far, in arrival order,
    the control requests left out. A test compares what the app should have asked of the store with
    what the server saw, so a request the app sent anywhere else shows as one missing here.
  - a listing page that fails. After GET /control/fail?from=N, a request for page N or later of any
    fixture source's listing (/popular?page=N, a search included) is answered 500 at once and logged
    with kind "failed"; the pages before N are served as the frozen server serves them. GET
    /control/answer ends it, and every later request is served again.

The control paths are answered on any host, with no latency, and logged with kind "control":

    GET /control/log                {"requests": [...]}
    GET /control/fail?from=N        {"failFromPage": N, "failed": <requests failed so far>}
    GET /control/answer             {"failFromPage": null, "failed": <requests failed so far>}

The fixture extension only ever calls port 18794 (R66), so --port defaults to it, and whoever starts
this takes the port 18794 lock first (the Phase 5 common brief).

Usage: browse_fixture_server.py --log <file> --repo-dir <dir> [--port N] [--bind ADDRESS]
It prints "ready <port>" on stdout once it listens, and stops on SIGTERM or SIGINT. Exit codes: 0
stopped; 2 usage or an unreadable repo directory; 3 the port could not be bound.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import signal
import sys
import threading
import time
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_fixture as fx  # noqa: E402
import update_fixture_server as frozen  # noqa: E402

CONTROL_PREFIX = "/control/"
LOG_PATH = "/control/log"
FAIL_PATH = "/control/fail"
ANSWER_PATH = "/control/answer"
LISTING_PATH = "/popular"
FAILED_STATUS = 500


class KeptLog(frozen.RequestLog):
    """The frozen log, with every request record kept in memory as well, for /control/log."""

    def __init__(self, path: Path):
        super().__init__(path)
        self._records: list[dict] = []
        self._kept = threading.Lock()

    def write(self, record: dict) -> None:
        super().write(record)
        if "kind" in record and record["kind"] != "control":
            with self._kept:
                self._records.append(record)

    def requests(self) -> list[dict]:
        with self._kept:
            return list(self._records)


class PageFailure:
    """Which listing pages fail, and how many requests have."""

    def __init__(self):
        self._lock = threading.Lock()
        self._from_page: int | None = None
        self._failed = 0

    def fail_from(self, page: int) -> None:
        with self._lock:
            self._from_page = page

    def answer(self) -> None:
        with self._lock:
            self._from_page = None

    def take(self, page: int) -> bool:
        """Whether a request for listing [page] fails now, counted when it does."""
        with self._lock:
            if self._from_page is None or page < self._from_page:
                return False
            self._failed += 1
            return True

    def status(self) -> dict:
        with self._lock:
            return {"failFromPage": self._from_page, "failed": self._failed}


class BrowseHandler(frozen.Handler):
    server: BrowseFixtureServer

    def do_GET(self) -> None:  # noqa: N802, the name BaseHTTPRequestHandler dispatches on
        split = urlsplit(self.path)
        if split.path.startswith(CONTROL_PREFIX):
            self.control(split.path, parse_qs(split.query))
            return
        host = self.headers.get("Host", "")
        source = fx.source_number_for_host(host)
        if source is not None and split.path == LISTING_PATH:
            page = listing_page(parse_qs(split.query))
            if page is not None and self.server.failure.take(page):
                self.fail(source)
                return
        super().do_GET()

    def fail(self, source: int) -> None:
        t0 = time.monotonic()
        aborted = not self.answer(FAILED_STATUS, "text/plain", b"this page fails until /control/answer\n")
        self.server.log.write({
            "t0": t0, "t1": time.monotonic(), "source": source, "source_id": str(fx.source_id(source)),
            "kind": "failed", "entry": None, "status": FAILED_STATUS, "aborted": aborted, "path": self.path,
            "latency_ms": 0.0,
        })

    def control(self, path: str, query: dict[str, list[str]]) -> None:
        t0 = time.monotonic()
        status = 200
        if path == LOG_PATH:
            body = {"requests": self.server.log.requests()}
        elif path == FAIL_PATH:
            page = listing_page(query, key="from")
            if page is None:
                status, body = 400, {"error": "from must be a page number, 1 or more"}
            else:
                self.server.failure.fail_from(page)
                body = self.server.failure.status()
        elif path == ANSWER_PATH:
            self.server.failure.answer()
            body = self.server.failure.status()
        else:
            status, body = 404, {"error": "no such control path"}
        aborted = not self.answer(status, "application/json", json.dumps(body).encode())
        self.server.log.write({
            "t0": t0, "t1": time.monotonic(), "source": None, "source_id": None, "kind": "control", "entry": None,
            "status": status, "aborted": aborted, "path": self.path, "latency_ms": 0.0,
        })

    def answer(self, status: int, content_type: str, body: bytes) -> bool:
        """Writes one whole response; False when the client went away first."""
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
        except OSError:
            return False
        return True


def listing_page(query: dict[str, list[str]], key: str = "page") -> int | None:
    """The page number [key] names, 1 when a listing request names none, or None when unreadable."""
    raw = query.get(key, ["1" if key == "page" else ""])[0]
    try:
        page = int(raw)
    except ValueError:
        return None
    return page if page >= 1 else None


class BrowseFixtureServer(frozen.FixtureServer):
    """The frozen FixtureServer with its repository, answering through BrowseHandler."""

    log: KeptLog

    def __init__(self, address: tuple[str, int], log: KeptLog, repository: frozen.Repository,
                 latency: Callable[[str, str], float], sleep: Callable[[float], None] = time.sleep):
        super().__init__(address, log, repository, latency, sleep)
        self.RequestHandlerClass = BrowseHandler
        self.failure = PageFailure()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--log", required=True, type=Path)
    # The store is what this server is for, so the repository is not optional as it is in the
    # frozen server.
    parser.add_argument("--repo-dir", required=True, type=Path)
    parser.add_argument("--port", type=int, default=fx.SERVER_PORT)
    # Loopback, as the frozen server binds: the emulator reaches it as 10.0.2.2.
    parser.add_argument("--bind", default="127.0.0.1")
    args = parser.parse_args(argv)

    try:
        repository = frozen.Repository(args.repo_dir)
    except FileNotFoundError as e:
        print(e, file=sys.stderr)
        return 2
    args.log.parent.mkdir(parents=True, exist_ok=True)
    log = KeptLog(args.log)
    try:
        server = BrowseFixtureServer((args.bind, args.port), log, repository, frozen.Latency().draw)
    except OSError as e:
        print(f"cannot listen on {args.bind}:{args.port}: {e}; is another run holding it?", file=sys.stderr)
        log.close()
        return 3

    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log.write(frozen.header(args.port))
    print(f"ready {args.port}", flush=True)
    server.serve_forever(poll_interval=0.2)
    server.server_close()
    log.write({"event": "stop", "monotonic": time.monotonic(), "failure": server.failure.status()})
    log.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
