#!/usr/bin/env python3
"""The host-side fixture server for the library update harness, ruling R66.

It serves the twenty sources of the fixture extension (tools/fixtures/update-extension) and, for
the one-time setup of an AVD, a synthetic extension repository holding that extension. Every source
request is answered after a latency drawn uniformly between 400 and 800 ms, and every request, source
or repository, is logged as one JSON line:

    {"t0": <server monotonic seconds at receipt>, "t1": <after the response was written>,
     "source": <1..20 or null>, "source_id": "<id>" or null, "kind": "<kind>", "entry": <n or null>,
     "status": <HTTP status>, "aborted": <true when the client went away before the response>,
     "path": "<path and query>", "latency_ms": <the drawn latency>}

The first line of the log is {"event": "start", ...} with the parameters and the clocks, the last is
{"event": "stop", ...}. The times are time.monotonic(), the clock the driver reads on the same host,
so the server's window and the driver's device-side window are on one clock.

The latency is seeded per request: the generator for a request is keyed on the seed, the host, the
path and how many times this server has already seen that host and path. So the same request gets
the same latency in every run whatever order the requests arrive in, and two apps that fetch the
same entries are served the same latencies.

Request kinds, by host and path:
    popular   GET /popular?page=N[&q=...]      a page of 50 entries of that source
    details   GET /manga/<e>                    an entry's details
    chapters  GET /manga/<e>/chapters           an entry's 50 chapters, newest first
    pages     GET /manga/<e>/chapter/<k>/pages  a chapter's page image addresses
    image     GET /manga/<e>/chapter/<k>/page/<p>.png
    repo      GET /repo/index.min.json, /repo/repo.json, /repo/apk/<file>, /repo/icon/<pkg>.png
    unknown   anything else, answered 404; a source host that names no source is answered 400

Repository requests carry no latency; they are setup, never part of a run. The repository serves
the APK and signer digest tools/fixtures/update-extension/build.sh wrote into --repo-dir, and
answers 404 for all of it when no directory is given.

Usage: update_fixture_server.py --log <file> [--port N] [--repo-dir <dir>] [--bind ADDRESS]
It prints "ready <port>" on stdout once it listens, and stops on SIGTERM or SIGINT.
Exit codes: 0 stopped; 2 usage or an unreadable repo directory; 3 the port could not be bound.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import datetime
import json
import random
import re
import signal
import struct
import sys
import threading
import time
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_fixture as fx  # noqa: E402

DETAILS = re.compile(r"^/manga/(\d+)$")
CHAPTERS = re.compile(r"^/manga/(\d+)/chapters$")
PAGES = re.compile(r"^/manga/(\d+)/chapter/(\d+)/pages$")
IMAGE = re.compile(r"^/manga/(\d+)/chapter/(\d+)/page/(\d+)\.png$")


def png(width: int = 8, height: int = 8, grey: int = 200) -> bytes:
    """A flat grey PNG, drawn from nothing, for the page and icon requests."""
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    rows = b"".join(b"\x00" + bytes([grey]) * width for _ in range(height))
    header = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")


class Latency:
    """The seeded latency of each request, keyed so arrival order does not change it."""

    def __init__(self, seed: str = fx.LATENCY_SEED, low: float = fx.LATENCY_MIN_S, high: float = fx.LATENCY_MAX_S):
        self.seed, self.low, self.high = seed, low, high
        self._seen: dict[str, int] = {}
        self._lock = threading.Lock()

    def draw(self, host: str, path: str) -> float:
        key = f"{host}|{path}"
        with self._lock:
            occurrence = self._seen.get(key, 0)
            self._seen[key] = occurrence + 1
        return random.Random(f"{self.seed}|{key}|{occurrence}").uniform(self.low, self.high)


class Repository:
    """The synthetic extension repository: one extension, served from build.sh's output."""

    def __init__(self, directory: Path | None):
        self.apk: bytes | None = None
        self.fingerprint: str | None = None
        if directory is None:
            return
        apk, signer = directory / "update-fixture.apk", directory / "signer.sha256"
        if not apk.is_file() or not signer.is_file():
            raise FileNotFoundError(f"{directory} holds no update-fixture.apk and signer.sha256; run build.sh")
        self.apk = apk.read_bytes()
        self.fingerprint = signer.read_text().strip()

    def respond(self, path: str) -> tuple[int, str, bytes]:
        if self.apk is None:
            return 404, "text/plain", b"no repository directory was given\n"
        if path == "/repo/repo.json":
            meta = {"name": fx.REPO_NAME, "shortName": fx.REPO_SHORT_NAME, "website": "http://localhost/",
                    "signingKeyFingerprint": self.fingerprint}
            return 200, "application/json", json.dumps({"index_v2": None, "meta": meta}).encode()
        if path == "/repo/index.min.json":
            # No "sources" list, which the format allows: it only labels the install screen, and it
            # keeps the whole index inside one TCP segment. Upstream v0.20.4 peeks one byte of this
            # body, fetches repo.json, then closes this body on the main thread, and a close that has
            # to read a body still in flight dies of NetworkOnMainThreadException ("unknown error").
            entry = {"name": fx.EXTENSION_NAME, "pkg": fx.EXTENSION_PACKAGE, "apk": "update-fixture.apk",
                     "lang": fx.SOURCE_LANG, "code": 1, "version": "1.4.1", "nsfw": 0}
            return 200, "application/json", json.dumps([entry]).encode()
        if path == "/repo/apk/update-fixture.apk":
            return 200, "application/vnd.android.package-archive", self.apk
        if path == f"/repo/icon/{fx.EXTENSION_PACKAGE}.png":
            return 200, "image/png", png(96, 96, 128)
        return 404, "text/plain", b"not in the repository\n"


def source_response(source: int, path: str, query: dict[str, list[str]]) -> tuple[int, str, str, int | None, bytes]:
    """(status, kind, content type, entry, body) for a request to one fixture source."""
    if path == "/popular":
        page = int(query.get("page", ["1"])[0] or 1)
        needle = query.get("q", [""])[0].lower()
        titles = [(e, fx.entry_title(source, e)) for e in range(1, fx.MAX_ENTRIES_PER_SOURCE + 1)]
        titles = [t for t in titles if needle in t[1].lower()]
        start = (page - 1) * fx.POPULAR_PAGE_SIZE
        chunk = titles[start:start + fx.POPULAR_PAGE_SIZE]
        body = {"entries": [{"url": fx.entry_url(e), "title": t} for e, t in chunk],
                "hasNextPage": start + fx.POPULAR_PAGE_SIZE < len(titles)}
        return 200, "popular", "application/json", None, json.dumps(body).encode()

    for pattern, kind in ((DETAILS, "details"), (CHAPTERS, "chapters"), (PAGES, "pages"), (IMAGE, "image")):
        match = pattern.match(path)
        if not match:
            continue
        entry = int(match.group(1))
        if not 1 <= entry <= fx.MAX_ENTRIES_PER_SOURCE:
            return 404, kind, "text/plain", entry, b"no such entry\n"
        if kind == "details":
            body = {"url": fx.entry_url(entry), "title": fx.entry_title(source, entry),
                    "description": f"Synthetic entry {entry} of {fx.source_name(source)}."}
            return 200, kind, "application/json", entry, json.dumps(body).encode()
        if kind == "chapters":
            chapters = [{"url": fx.chapter_url(entry, k), "name": fx.chapter_name(k), "number": k,
                         "uploaded": fx.chapter_uploaded_ms(k)}
                        for k in range(fx.CHAPTERS_PER_LIST, 0, -1)]
            return 200, kind, "application/json", entry, json.dumps({"chapters": chapters}).encode()
        chapter = int(match.group(2))
        if not 1 <= chapter <= fx.CHAPTERS_PER_LIST:
            return 404, kind, "text/plain", entry, b"no such chapter\n"
        if kind == "pages":
            base = f"http://{fx.source_host(source)}:{fx.SERVER_PORT}{fx.chapter_url(entry, chapter)}"
            pages = [f"{base}/page/{p}.png" for p in range(fx.PAGES_PER_CHAPTER)]
            return 200, kind, "application/json", entry, json.dumps({"pages": pages}).encode()
        page = int(match.group(3))
        if not 0 <= page < fx.PAGES_PER_CHAPTER:
            return 404, kind, "text/plain", entry, b"no such page\n"
        return 200, kind, "image/png", entry, png()
    return 404, "unknown", "text/plain", None, b"no such path\n"


class RequestLog:
    """Appends one JSON line per request, whole lines only, under a lock."""

    def __init__(self, path: Path):
        self._file = path.open("a", encoding="utf-8")
        self._lock = threading.Lock()

    def write(self, record: dict) -> None:
        line = json.dumps(record, sort_keys=True) + "\n"
        with self._lock:
            self._file.write(line)
            self._file.flush()

    def close(self) -> None:
        with self._lock:
            self._file.close()


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True
    # A 2,000 entry run from a concurrent client opens many connections at once.
    request_queue_size = 128

    def __init__(self, address: tuple[str, int], log: RequestLog, repository: Repository,
                 latency: Callable[[str, str], float], sleep: Callable[[float], None] = time.sleep):
        super().__init__(address, Handler)
        self.log, self.repository, self.latency, self.sleep = log, repository, latency, sleep


class Handler(BaseHTTPRequestHandler):
    server: FixtureServer
    protocol_version = "HTTP/1.1"
    # Buffered, so the status line, the headers and the body leave in one write (see Repository).
    wbufsize = 1 << 16

    def do_GET(self) -> None:  # noqa: N802, the name BaseHTTPRequestHandler dispatches on
        t0 = time.monotonic()
        split = urlsplit(self.path)
        host = self.headers.get("Host", "")
        source = fx.source_number_for_host(host)
        latency = 0.0
        entry = None
        if split.path == "/probe":
            # library_update.sh asks this from the device's shell until the emulator's network
            # answers, before the app is launched. It names no source, so the guard never counts it.
            kind, status, content_type, body = "probe", 200, "text/plain", b"ok\n"
        elif split.path.startswith("/repo/"):
            kind = "repo"
            status, content_type, body = self.server.repository.respond(split.path)
        elif source is None and host.split(":", 1)[0].lower().endswith("." + fx.HOST_SUFFIX):
            kind, status, content_type, body = "unknown", 400, "text/plain", b"no such source\n"
        elif source is None:
            kind, status, content_type, body = "unknown", 404, "text/plain", b"not a fixture host\n"
        else:
            status, kind, content_type, entry, body = source_response(source, split.path, parse_qs(split.query))
            latency = self.server.latency(host.split(":", 1)[0].lower(), self.path)
            self.server.sleep(latency)

        aborted = False
        try:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
        except OSError:
            aborted = True
        self.server.log.write({
            "t0": t0, "t1": time.monotonic(), "source": source,
            "source_id": str(fx.source_id(source)) if source else None, "kind": kind, "entry": entry,
            "status": status, "aborted": aborted, "path": self.path, "latency_ms": round(latency * 1000, 3),
        })

    def log_message(self, format: str, *args) -> None:  # noqa: A002, the base class's signature
        pass  # the JSON log is the record; stderr stays quiet


def header(port: int) -> dict:
    return {
        "event": "start", "monotonic": time.monotonic(),
        "wall": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "port": port, "seed": fx.LATENCY_SEED, "latency_s": [fx.LATENCY_MIN_S, fx.LATENCY_MAX_S],
        "rate_limit": [fx.RATE_LIMIT_PERMITS, fx.RATE_LIMIT_PERIOD_SECONDS],
        "chapters_per_list": fx.CHAPTERS_PER_LIST, "sources": fx.SOURCE_COUNT,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--port", type=int, default=fx.SERVER_PORT)
    parser.add_argument("--repo-dir", type=Path)
    # The emulator reaches the host's loopback as 10.0.2.2, so loopback is enough and nothing else
    # on the network can reach the fixture.
    parser.add_argument("--bind", default="127.0.0.1")
    args = parser.parse_args(argv)

    try:
        repository = Repository(args.repo_dir)
    except FileNotFoundError as e:
        print(e, file=sys.stderr)
        return 2
    args.log.parent.mkdir(parents=True, exist_ok=True)
    log = RequestLog(args.log)
    try:
        server = FixtureServer((args.bind, args.port), log, repository, Latency().draw)
    except OSError as e:
        print(f"cannot listen on {args.bind}:{args.port}: {e}; is another run holding it?", file=sys.stderr)
        log.close()
        return 3

    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    log.write(header(args.port))
    print(f"ready {args.port}", flush=True)
    server.serve_forever(poll_interval=0.2)
    server.server_close()
    log.write({"event": "stop", "monotonic": time.monotonic()})
    log.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
