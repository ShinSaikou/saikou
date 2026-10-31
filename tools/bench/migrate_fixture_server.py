#!/usr/bin/env python3
"""The fixture server for the batch migration's device checks (phase 4 unit MB, design g7 section 20.1).

It is tools/bench/update_fixture_server.py, frozen by ruling R66, wrapped rather than edited, as
download_hold_server.py wraps it: the frozen FixtureServer and Handler answer every request, with
the frozen seeded latency (0.4 to 0.8 s) and the frozen JSON request log, and the request kinds and
the fixture extension's JSON shapes keep their meaning. What changes is the catalogue. The frozen
server's `source_response` is replaced by this file's own while the server runs, and it serves:

    source 1  the old source, A: entries 1 to 250
    source 2  a second old source: the overflow of the 300-entry run (entries 1 to 50) and, as
              entry 51, the partner of A's entry 1 under the same title (the duplicate pair)
    source 3  target B       source 4  target C       sources 5 to 20 list nothing

A's entries by number: 1 to 100 are found on B only, 101 to 150 on C only, 151 to 200 on both
(with 10 chapters on B and 20 on C, so matching by chapter number picks C), 201 to 230 on neither,
and 231 to 250 carry a bracketed part that only the advanced (deep) search strips, so their title
is found on B by deep search only. Source 2's entries 1 to 50 are found on B. Every title is two
made-up words that no other title shares, so a search for one title, or for one of its words,
finds that title alone; the frozen titles share a word and would all match one another. An old
entry has 12 chapters; a target entry 15 unless stated above. The search is the frozen one: a
substring of the lowercased title.

Pages and page images are answered by the frozen function, so a chapter of any entry downloads.

Two failures, set at start or at any time through the control path:
    unavailable      the next N searches of a source answer 503 (a window of requests)
    fail_chapters    the chapter lists of chosen entries of a source answer 503, so a migration's
                     re-fetch of a target's chapters fails at the write

The control path, answered on any host with no latency and logged with kind "control":
    GET /control                       the failure state, the searches, details and chapter lists
                                       each source has answered, and the last 200 searches'
                                       sources and queries, as JSON
    GET /control?unavailable=S:N       the next N searches of source S answer 503 (N 0 ends it)
    GET /control?fail_chapters=S:E[-F][,S:E[-F]...]   replaces the set; an empty value clears it
    GET /control?reset=1               clears both
    GET /control/catalogue             every entry of sources 1 to 4: source, entry, title, role,
                                       chapters, and for an old entry its entry on B and on C

Usage: migrate_fixture_server.py --log <file> [--port N] [--bind ADDRESS]
                                 [--unavailable S:N ...] [--fail-chapters SPEC]
It prints "ready <port>" on stdout once it listens, and stops on SIGTERM or SIGINT.
Exit codes: 0 stopped; 2 usage; 3 the port could not be bound.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import signal
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_fixture as fx  # noqa: E402
import update_fixture_server as frozen  # noqa: E402

# The frozen function, kept before anything replaces it: pages and images are its answers.
FROZEN_SOURCE_RESPONSE = frozen.source_response

OLD_A = 1
OLD_OVERFLOW = 2
TARGET_B = 3
TARGET_C = 4
TARGETS = (TARGET_B, TARGET_C)

B_ONLY = range(1, 101)
C_ONLY = range(101, 151)
BOTH = range(151, 201)
NONE = range(201, 231)
BRACKETED = range(231, 251)
OVERFLOW = range(1, 51)
DUPLICATE_ENTRY = 51
DUPLICATE_OF = 1

OLD_CHAPTERS = 12
TARGET_CHAPTERS = 15
BOTH_CHAPTERS = {TARGET_B: 10, TARGET_C: 20}

CONTROL_PATH = "/control"
RECENT_SEARCHES = 200
CATALOGUE_PATH = "/control/catalogue"

_CONSONANTS = "bdfgklmnprstvz"
_VOWELS = "aeiou"
_SYLLABLES = [c + v for c in _CONSONANTS for v in _VOWELS]
_WORD_SPACE = len(_SYLLABLES) ** 3


def word(index: int) -> str:
    """A made-up six-letter word, distinct for every index below 343,000."""
    # 7919 is prime and shares no factor with 70 ** 3, so the mix is a permutation.
    n = (index * 7919 + 12345) % _WORD_SPACE
    syllables = []
    for _ in range(3):
        n, rest = divmod(n, len(_SYLLABLES))
        syllables.append(_SYLLABLES[rest])
    return "".join(syllables).capitalize()


def base_title(key: int) -> str:
    return f"{word(2 * key)} {word(2 * key + 1)}"


def bracket_part(key: int) -> str:
    return f"({word(100_000 + 2 * key)} {word(100_001 + 2 * key)})"


@dataclass(frozen=True)
class Entry:
    source: int
    entry: int
    title: str
    role: str
    chapters: int
    # For an old entry: its entry number on B and on C, or None where it has none.
    on_b: int | None = None
    on_c: int | None = None

    def json(self) -> dict:
        body = {"source": self.source, "entry": self.entry, "title": self.title, "role": self.role,
                "chapters": self.chapters}
        if self.source in (OLD_A, OLD_OVERFLOW):
            body["targets"] = {str(TARGET_B): self.on_b, str(TARGET_C): self.on_c}
        return body


def build_catalogue() -> dict[int, dict[int, Entry]]:
    """Sources 1 to 4, each a map from entry number to entry, in listing order."""
    targets: dict[int, list[tuple[str, int]]] = {TARGET_B: [], TARGET_C: []}

    def on(source: int, title: str, chapters: int) -> int:
        targets[source].append((title, chapters))
        return len(targets[source])

    old_a: dict[int, Entry] = {}
    for e in range(1, fx.MAX_ENTRIES_PER_SOURCE + 1):
        title = base_title(e)
        b = c = None
        if e in B_ONLY:
            role, b = "b", on(TARGET_B, title, TARGET_CHAPTERS)
        elif e in C_ONLY:
            role, c = "c", on(TARGET_C, title, TARGET_CHAPTERS)
        elif e in BOTH:
            role = "both"
            b = on(TARGET_B, title, BOTH_CHAPTERS[TARGET_B])
            c = on(TARGET_C, title, BOTH_CHAPTERS[TARGET_C])
        elif e in NONE:
            role = "none"
        else:
            role, b = "bracketed", on(TARGET_B, title, TARGET_CHAPTERS)
            title = f"{title} {bracket_part(e)}"
        old_a[e] = Entry(OLD_A, e, title, role, OLD_CHAPTERS, b, c)

    overflow: dict[int, Entry] = {}
    for e in OVERFLOW:
        title = base_title(1000 + e)
        overflow[e] = Entry(OLD_OVERFLOW, e, title, "b", OLD_CHAPTERS, on(TARGET_B, title, TARGET_CHAPTERS), None)
    partner = old_a[DUPLICATE_OF]
    overflow[DUPLICATE_ENTRY] = Entry(OLD_OVERFLOW, DUPLICATE_ENTRY, partner.title, "duplicate", OLD_CHAPTERS,
                                      partner.on_b, partner.on_c)

    catalogue = {OLD_A: old_a, OLD_OVERFLOW: overflow}
    for source, titles in targets.items():
        catalogue[source] = {n: Entry(source, n, title, "target", chapters)
                             for n, (title, chapters) in enumerate(titles, start=1)}
    return catalogue


def parse_unavailable(spec: str) -> tuple[int, int]:
    """S:N, a fixture source and a count of searches."""
    source, _, count = spec.partition(":")
    if not source.isdigit() or not count.isdigit() or not 1 <= int(source) <= fx.SOURCE_COUNT:
        raise ValueError(f"not S:N with S a fixture source: {spec!r}")
    return int(source), int(count)


def parse_fail_chapters(spec: str) -> set[tuple[int, int]]:
    """S:E or S:E-F, comma separated, as (source, entry) pairs; an empty spec is the empty set."""
    failing: set[tuple[int, int]] = set()
    for part in filter(None, (p.strip() for p in spec.split(","))):
        source, _, entries = part.partition(":")
        first, _, last = entries.partition("-")
        last = last or first
        if not (source.isdigit() and first.isdigit() and last.isdigit()) or int(first) > int(last):
            raise ValueError(f"not S:E or S:E-F: {part!r}")
        if not 1 <= int(source) <= fx.SOURCE_COUNT:
            raise ValueError(f"not a fixture source: {part!r}")
        failing.update((int(source), e) for e in range(int(first), int(last) + 1))
    return failing


class Catalogue:
    """The catalogue and its failures; [respond] stands where the frozen `source_response` was."""

    def __init__(self):
        self.entries = build_catalogue()
        self._lock = threading.Lock()
        self._unavailable: dict[int, int] = {}
        self._failing: set[tuple[int, int]] = set()
        self._unavailable_answered = 0
        self._failed_chapters = 0
        # Requests answered per kind and source, failures included, so a device check can tell a
        # search or a fetch that ran twice from one that ran once.
        self._counts: dict[str, dict[int, int]] = {"search": {}, "details": {}, "chapters": {}}
        self._searches: list[dict] = []

    def set_unavailable(self, source: int, count: int) -> None:
        with self._lock:
            if count > 0:
                self._unavailable[source] = count
            else:
                self._unavailable.pop(source, None)

    def set_failing(self, failing: set[tuple[int, int]]) -> None:
        with self._lock:
            self._failing = set(failing)

    def reset(self) -> None:
        with self._lock:
            self._unavailable.clear()
            self._failing = set()

    def state(self) -> dict:
        with self._lock:
            return {
                "unavailable": {str(s): n for s, n in sorted(self._unavailable.items())},
                "unavailableAnswered": self._unavailable_answered,
                "failChapters": [f"{s}:{e}" for s, e in sorted(self._failing)],
                "failedChapters": self._failed_chapters,
                "answered": {kind: {str(s): n for s, n in sorted(by_source.items())}
                             for kind, by_source in self._counts.items()},
                "searches": list(self._searches),
            }

    def catalogue_json(self) -> dict:
        return {"sources": {str(s): [e.json() for e in entries.values()] for s, entries in self.entries.items()}}

    def respond(self, source: int, path: str, query: dict[str, list[str]]) -> tuple[int, str, str, int | None, bytes]:
        """(status, kind, content type, entry, body), the frozen function's shape."""
        entries = self.entries.get(source, {})
        if path == "/popular":
            needle = query.get("q", [""])[0].lower()
            if "q" in query:
                self._count("search", source)
                with self._lock:
                    self._searches = (self._searches + [{"source": source, "q": query["q"][0]}])[-RECENT_SEARCHES:]
            if "q" in query and self._take_unavailable(source):
                return 503, "popular", "text/plain", None, b"source unavailable\n"
            page = int(query.get("page", ["1"])[0] or 1)
            titles = [(e.entry, e.title) for e in entries.values() if needle in e.title.lower()]
            start = (page - 1) * fx.POPULAR_PAGE_SIZE
            chunk = titles[start:start + fx.POPULAR_PAGE_SIZE]
            body = {"entries": [{"url": fx.entry_url(n), "title": t} for n, t in chunk],
                    "hasNextPage": start + fx.POPULAR_PAGE_SIZE < len(titles)}
            return 200, "popular", "application/json", None, json.dumps(body).encode()

        for pattern, kind in ((frozen.DETAILS, "details"), (frozen.CHAPTERS, "chapters")):
            match = pattern.match(path)
            if not match:
                continue
            number = int(match.group(1))
            self._count(kind, source)
            entry = entries.get(number)
            if entry is None:
                return 404, kind, "text/plain", number, b"no such entry\n"
            if kind == "details":
                body = {"url": fx.entry_url(number), "title": entry.title,
                        "description": f"Synthetic entry {number} of {fx.source_name(source)}."}
                return 200, kind, "application/json", number, json.dumps(body).encode()
            if self._take_failing(source, number):
                return 503, kind, "text/plain", number, b"chapters unavailable\n"
            chapters = [{"url": fx.chapter_url(number, k), "name": fx.chapter_name(k), "number": k,
                         "uploaded": fx.chapter_uploaded_ms(k)}
                        for k in range(entry.chapters, 0, -1)]
            return 200, kind, "application/json", number, json.dumps({"chapters": chapters}).encode()
        return FROZEN_SOURCE_RESPONSE(source, path, query)

    def _count(self, kind: str, source: int) -> None:
        with self._lock:
            self._counts[kind][source] = self._counts[kind].get(source, 0) + 1

    def _take_unavailable(self, source: int) -> bool:
        with self._lock:
            left = self._unavailable.get(source, 0)
            if left <= 0:
                return False
            if left == 1:
                del self._unavailable[source]
            else:
                self._unavailable[source] = left - 1
            self._unavailable_answered += 1
            return True

    def _take_failing(self, source: int, entry: int) -> bool:
        with self._lock:
            if (source, entry) not in self._failing:
                return False
            self._failed_chapters += 1
            return True


def install(catalogue: Catalogue) -> Callable[[], None]:
    """Puts [catalogue] where the frozen handler looks up `source_response`; returns the undo."""
    previous = frozen.source_response
    frozen.source_response = catalogue.respond

    def undo() -> None:
        frozen.source_response = previous

    return undo


class ControlHandler(frozen.Handler):
    server: MigrateFixtureServer

    def do_GET(self) -> None:  # noqa: N802, the name BaseHTTPRequestHandler dispatches on
        split = urlsplit(self.path)
        if split.path in (CONTROL_PATH, CATALOGUE_PATH):
            self.control(split.path, parse_qs(split.query, keep_blank_values=True))
            return
        super().do_GET()

    def control(self, path: str, query: dict[str, list[str]]) -> None:
        t0 = time.monotonic()
        catalogue = self.server.catalogue
        status = 200
        try:
            if path == CATALOGUE_PATH:
                body = json.dumps(catalogue.catalogue_json()).encode()
            else:
                if query.get("reset", ["0"])[0] == "1":
                    catalogue.reset()
                for spec in query.get("unavailable", []):
                    catalogue.set_unavailable(*parse_unavailable(spec))
                if "fail_chapters" in query:
                    catalogue.set_failing(parse_fail_chapters(query["fail_chapters"][-1]))
                body = json.dumps(catalogue.state()).encode()
        except ValueError as e:
            status, body = 400, json.dumps({"error": str(e)}).encode()
        aborted = False
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
        except OSError:
            aborted = True
        self.server.log.write({
            "t0": t0, "t1": time.monotonic(), "source": None, "source_id": None, "kind": "control", "entry": None,
            "status": status, "aborted": aborted, "path": self.path, "latency_ms": 0.0,
        })


class MigrateFixtureServer(frozen.FixtureServer):
    """The frozen FixtureServer with no repository, answering through ControlHandler."""

    def __init__(self, address: tuple[str, int], log: frozen.RequestLog, catalogue: Catalogue,
                 latency: Callable[[str, str], float], sleep: Callable[[float], None] = time.sleep):
        super().__init__(address, log, frozen.Repository(None), latency, sleep)
        self.RequestHandlerClass = ControlHandler
        self.catalogue = catalogue


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--port", type=int, default=fx.SERVER_PORT)
    # Loopback, as the frozen server binds: the emulator reaches it as 10.0.2.2.
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--unavailable", action="append", default=[], metavar="S:N")
    parser.add_argument("--fail-chapters", default="", metavar="S:E[-F],...")
    args = parser.parse_args(argv)

    catalogue = Catalogue()
    try:
        for spec in args.unavailable:
            catalogue.set_unavailable(*parse_unavailable(spec))
        catalogue.set_failing(parse_fail_chapters(args.fail_chapters))
    except ValueError as e:
        print(e, file=sys.stderr)
        return 2

    args.log.parent.mkdir(parents=True, exist_ok=True)
    log = frozen.RequestLog(args.log)
    try:
        server = MigrateFixtureServer((args.bind, args.port), log, catalogue, frozen.Latency().draw)
    except OSError as e:
        print(f"cannot listen on {args.bind}:{args.port}: {e}; is another run holding it?", file=sys.stderr)
        log.close()
        return 3

    def stop(*_):
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    undo = install(catalogue)
    try:
        log.write({**frozen.header(args.port), "catalogue": "migrate", "failures": catalogue.state()})
        print(f"ready {args.port}", flush=True)
        server.serve_forever(poll_interval=0.2)
    finally:
        undo()
        server.server_close()
        log.write({"event": "stop", "monotonic": time.monotonic(), "failures": catalogue.state()})
        log.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
