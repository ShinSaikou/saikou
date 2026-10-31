#!/usr/bin/env python3
"""The fixture server for the download force-stop row, with page responses it can hold open.

It is tools/bench/update_fixture_server.py, frozen by ruling R66, wrapped rather than edited: the
same twenty sources, the same paths, the same seeded latency and the same JSON request log, served by
that file's own FixtureServer and Handler. What this adds is a hold. While it is on, an image request
for page N or later of a chapter (--hold-from-page, 1 by default) is not answered: its handler waits
until the hold is released and then answers as the frozen server would. The pages before N are
served, so the chapter the download engine is working on has part of its pages on disk and the rest
in flight, which is the state the row force-stops.

A held request is logged twice: once with kind "held" when the hold ends, its t0 the moment it
arrived, and once as the frozen server logs every response. Two control paths, answered on any host
with no latency and logged with kind "hold":

    GET /hold          {"holding": <bool>, "held": <requests waiting now>,
                        "heldTotal": <requests ever held>, "holdFromPage": <N>}
    GET /hold/release  releases the hold for good and answers the same body. Every waiting request is
                       answered, and no later request is held.

GateRowsTest's part one asks /hold before it enqueues anything, so a server that is not holding ends
the run instead of letting the downloads finish before the force-stop; it records "held" once the
engine is waiting on a page. Part two asks /hold/release once it has compared the queue with part
one's, so the resumed downloads can finish.

A held request waits at most --hold-limit seconds (600 by default), then is answered anyway, so a run
that never releases cannot leave threads waiting for ever; no run of the row comes near it.

Usage: download_hold_server.py --log <file> [--port N] [--bind ADDRESS] [--hold-from-page N]
                               [--hold-limit SECONDS]
It prints "ready <port>" on stdout once it listens, and stops on SIGTERM or SIGINT, releasing
whatever it holds. Exit codes: 0 stopped; 2 usage; 3 the port could not be bound.
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
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_fixture as fx  # noqa: E402
import update_fixture_server as frozen  # noqa: E402

STATUS_PATH = "/hold"
RELEASE_PATH = "/hold/release"
DEFAULT_HOLD_FROM_PAGE = 1
DEFAULT_HOLD_LIMIT_S = 600.0


class Hold:
    """Which image requests wait, and the count of those that do."""

    def __init__(self, from_page: int = DEFAULT_HOLD_FROM_PAGE, limit_s: float = DEFAULT_HOLD_LIMIT_S):
        self.from_page = from_page
        self.limit_s = limit_s
        self._released = threading.Event()
        self._lock = threading.Lock()
        self._held = 0
        self._held_total = 0

    def applies(self, host: str, path: str) -> bool:
        """An image request of a fixture source for a page at or past the first held one, while holding."""
        if self._released.is_set() or fx.source_number_for_host(host) is None:
            return False
        match = frozen.IMAGE.match(path)
        return bool(match) and int(match.group(3)) >= self.from_page

    def wait(self) -> None:
        with self._lock:
            self._held += 1
            self._held_total += 1
        try:
            self._released.wait(self.limit_s)
        finally:
            with self._lock:
                self._held -= 1

    def release(self) -> None:
        self._released.set()

    def status(self) -> dict:
        with self._lock:
            return {"holding": not self._released.is_set(), "held": self._held, "heldTotal": self._held_total,
                    "holdFromPage": self.from_page}


class HoldingHandler(frozen.Handler):
    server: HoldServer

    def do_GET(self) -> None:  # noqa: N802, the name BaseHTTPRequestHandler dispatches on
        path = urlsplit(self.path).path
        if path in (STATUS_PATH, RELEASE_PATH):
            self.control(path)
            return
        host = self.headers.get("Host", "")
        if self.server.hold.applies(host, path):
            # Logged when the hold ends, from when the request arrived, so the log shows how long it
            # was held; the frozen handler then logs the response as it always does.
            t0 = time.monotonic()
            self.server.hold.wait()
            source = fx.source_number_for_host(host)
            self.server.log.write({
                "t0": t0, "t1": time.monotonic(), "source": source, "source_id": str(fx.source_id(source)),
                "kind": "held", "entry": int(frozen.IMAGE.match(path).group(1)), "status": None, "aborted": False,
                "path": self.path, "latency_ms": 0.0,
            })
        super().do_GET()

    def control(self, path: str) -> None:
        t0 = time.monotonic()
        if path == RELEASE_PATH:
            self.server.hold.release()
        body = json.dumps(self.server.hold.status()).encode()
        aborted = False
        try:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
        except OSError:
            aborted = True
        self.server.log.write({
            "t0": t0, "t1": time.monotonic(), "source": None, "source_id": None, "kind": "hold", "entry": None,
            "status": 200, "aborted": aborted, "path": self.path, "latency_ms": 0.0,
        })


class HoldServer(frozen.FixtureServer):
    """The frozen FixtureServer with no repository, answering through HoldingHandler."""

    def __init__(self, address: tuple[str, int], log: frozen.RequestLog, hold: Hold,
                 latency: Callable[[str, str], float], sleep: Callable[[float], None] = time.sleep):
        super().__init__(address, log, frozen.Repository(None), latency, sleep)
        self.RequestHandlerClass = HoldingHandler
        self.hold = hold


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--port", type=int, default=fx.SERVER_PORT)
    # Loopback, as the frozen server binds: the emulator reaches it as 10.0.2.2.
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--hold-from-page", type=int, default=DEFAULT_HOLD_FROM_PAGE)
    parser.add_argument("--hold-limit", type=float, default=DEFAULT_HOLD_LIMIT_S)
    args = parser.parse_args(argv)
    if not 0 <= args.hold_from_page < fx.PAGES_PER_CHAPTER:
        print(f"--hold-from-page must be a page of a chapter, 0 to {fx.PAGES_PER_CHAPTER - 1}", file=sys.stderr)
        return 2
    if args.hold_limit <= 0:
        print("--hold-limit must be a positive number of seconds", file=sys.stderr)
        return 2

    hold = Hold(args.hold_from_page, args.hold_limit)
    args.log.parent.mkdir(parents=True, exist_ok=True)
    log = frozen.RequestLog(args.log)
    try:
        server = HoldServer((args.bind, args.port), log, hold, frozen.Latency().draw)
    except OSError as e:
        print(f"cannot listen on {args.bind}:{args.port}: {e}; is another run holding it?", file=sys.stderr)
        log.close()
        return 3

    def stop(*_):
        hold.release()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log.write({**frozen.header(args.port), "hold_from_page": args.hold_from_page, "hold_limit_s": args.hold_limit})
    print(f"ready {args.port}", flush=True)
    server.serve_forever(poll_interval=0.2)
    server.server_close()
    log.write({"event": "stop", "monotonic": time.monotonic(), "hold": hold.status()})
    log.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
