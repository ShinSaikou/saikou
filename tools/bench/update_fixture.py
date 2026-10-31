#!/usr/bin/env python3
"""The frozen parameters of the library update harness, ruling R66, and the names derived from them.

Everything the fixture server, the backup generator and the log reader agree on lives here, and the
fixture extension (tools/fixtures/update-extension) carries the values it needs as constants of its
own. tools/test_update_fixture.py reads those constants back out of the Kotlin source and compares
them with these, so the two sides cannot drift apart without a failing test.

The numbers are section 4 Q1's, decided on 2026-09-24: latency uniform between 400 and 800 ms from a
seeded generator (mean 600 ms), 5 requests per second per source (latency times limit is 3), 50
chapters per list with one of them new on every run, the same parameters in both scenarios. They
never change after the first recording morning, since a change costs three new days.

Python 3.11+, standard library only.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

SOURCE_COUNT = 20
HOST_SUFFIX = "update-fixture.test"
SERVER_PORT = 18794
RATE_LIMIT_PERMITS = 5
RATE_LIMIT_PERIOD_SECONDS = 1
LATENCY_MIN_S = 0.4
LATENCY_MAX_S = 0.8
# An opaque frozen label that seeds every latency draw. It was the ruling's working number when the
# draws were first taken and is not a reference to any ruling; changing it changes every latency.
LATENCY_SEED = "R64"
CHAPTERS_PER_LIST = 50
# The backup seeds one chapter fewer than the list serves, and every run starts from the snapshot
# taken after the restore, so every run finds exactly one new chapter per entry and every run
# exercises the insert path.
SEEDED_CHAPTERS = CHAPTERS_PER_LIST - 1
PAGES_PER_CHAPTER = 3
POPULAR_PAGE_SIZE = 50
# The most entries any scenario seeds in one source; the server answers for these and no others.
MAX_ENTRIES_PER_SOURCE = 250

SOURCE_LANG = "en"
SOURCE_VERSION_ID = 1
EXTENSION_PACKAGE = "app.fixture.libraryupdate"
EXTENSION_NAME = "Library Update Fixture"
REPO_NAME = "Library update fixture"
REPO_SHORT_NAME = "Fixture"

# Chapter k was uploaded (k - 1) days after this instant, 2026-01-01T00:00:00Z.
UPLOAD_EPOCH_MS = 1_767_225_600_000
DAY_MS = 86_400_000
# dateAdded and dateFetch in the backup, 2026-06-01T00:00:00Z, so a restore stores no host clock.
SEEDED_AT_MS = 1_780_272_000_000


@dataclass(frozen=True)
class Scenario:
    """One library shape. The first `sources` sources hold `per_source` entries each."""

    name: str
    sources: int
    per_source: int

    @property
    def entries(self) -> int:
        return self.sources * self.per_source

    def expected(self) -> set[tuple[int, int]]:
        """Every (source number, entry number) the scenario seeds, each fetched once per run."""
        return {(s, e) for s in range(1, self.sources + 1) for e in range(1, self.per_source + 1)}


SCENARIOS = {
    "2000x20": Scenario("2000x20", sources=20, per_source=100),
    "500x2": Scenario("500x2", sources=2, per_source=250),
}


def source_name(number: int) -> str:
    return f"Update Fixture {number:02d}"


def source_host(number: int) -> str:
    return f"s{number:02d}.{HOST_SUFFIX}"


def source_id(number: int) -> int:
    """HttpSource.generateId: the first 8 bytes of MD5("<name lowercased>/<lang>/<versionId>")."""
    key = f"{source_name(number).lower()}/{SOURCE_LANG}/{SOURCE_VERSION_ID}"
    digest = hashlib.md5(key.encode()).digest()
    return int.from_bytes(digest[:8], "big") & 0x7FFF_FFFF_FFFF_FFFF


def source_number_for_host(host: str) -> int | None:
    """The source a Host header names, without its port, or None when it names none of them."""
    name = host.split(":", 1)[0].lower()
    suffix = "." + HOST_SUFFIX
    if not name.endswith(suffix):
        return None
    label = name[: -len(suffix)]
    if len(label) != 3 or label[0] != "s" or not label[1:].isdigit():
        return None
    number = int(label[1:])
    return number if 1 <= number <= SOURCE_COUNT else None


def entry_url(entry: int) -> str:
    return f"/manga/{entry}"


def entry_title(source: int, entry: int) -> str:
    return f"Fixture {source:02d}-{entry:03d}"


def chapter_url(entry: int, chapter: int) -> str:
    return f"/manga/{entry}/chapter/{chapter}"


def chapter_name(chapter: int) -> str:
    return f"Chapter {chapter}"


def chapter_uploaded_ms(chapter: int) -> int:
    return UPLOAD_EPOCH_MS + (chapter - 1) * DAY_MS
