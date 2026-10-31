#!/usr/bin/env python3
"""Writes an upstream-shaped .tachibk of a large generated library, for phase 5 unit SC0's restore row.

`restore_40k_ms` restores a heavy user's library cold, so the file is the library an upstream user
with 40,000 entries of 50 chapters would bring: every entry in the library, in one of eight
categories, the first half of its chapters read, every seventh chapter bookmarked, a history row on
its last read chapter, a MyAnimeList track on one entry in fifty (a fixture remote id on a reserved
address nothing resolves), and the fixture sources the entries belong to. Nothing in it names a
real source: the sources are "Scale Fixture 1" to "4" under fixture ids, the entry and chapter
urls are update_fixture.py's shapes, and the titles are the same shape with a scale prefix.

The format is the one update_backup.py writes (a gzip of the protobuf `Backup` message), with its
encoder functions imported rather than copied, since that file is frozen (R66). Every field number
here is read from tools/gate/upstream_backup_fields.json, the record of upstream v0.20.4's backup models,
so the file cannot drift from what upstream reads. The fields are written in the order upstream's own
encoder writes them, field number order: the entries (1), the categories (2), the sources (101).

The library is never held whole: each entry is encoded and written into the gzip stream on its
own, so a file of several hundred MB uncompressed takes a few MB of memory to write. Nothing
depends on the clock, and gzip's timestamp is pinned to zero, so the same sizes write the same
bytes on every run.

Usage: big_backup.py <out file> [--entries N] [--chapters C]
Prints the path, the entry and chapter counts and the file's size. Exit codes: 0 written; 2 usage.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import update_backup as ub  # noqa: E402
import update_fixture as fx  # noqa: E402

FIELDS_FILE = HERE.parent / "gate" / "upstream_backup_fields.json"

DEFAULT_ENTRIES = 40_000
DEFAULT_CHAPTERS = 50
CATEGORIES = 8
SOURCES = 4
TRACK_EVERY = 50
BOOKMARK_EVERY = 7
# Phase 4's R131 item 6 left device fixture source ids at 7_310_000_000_000_011 onward; these four
# are SC0's, so the restored entries share a source with nothing else on the device.
FIRST_SOURCE_ID = 7_310_000_000_000_011
TITLE_PREFIX = "Scale"
CATEGORY_PREFIX = "Scale category "
# MyAnimeList's tracker id in both apps, and fixture remote ids far above any real entry's.
MYANIMELIST = 1
TRACK_REMOTE_BASE = 9_000_000_000
TRACK_URL_PREFIX = "https://tracker.invalid/manga/"
READ_DURATION_MS = 60_000


def load_fields(path: Path = FIELDS_FILE) -> dict[str, dict[str, int]]:
    """Every message's field numbers by field name, from the recorded upstream models."""
    data = json.loads(path.read_text())
    return {name: {field["name"]: field["number"] for field in message["fields"]}
            for name, message in data["messages"].items()}


F = load_fields()


def source_id(number: int) -> int:
    return FIRST_SOURCE_ID + number - 1


def source_name(number: int) -> str:
    return f"Scale Fixture {number}"


def entry_source(entry: int) -> int:
    """Entries are dealt to the sources in turn: entry 1 to source 1, entry 2 to source 2."""
    return (entry - 1) % SOURCES + 1


def entry_title(entry: int) -> str:
    return f"{TITLE_PREFIX} {entry_source(entry):02d}-{entry:05d}"


def category_order(entry: int) -> int:
    return (entry - 1) % CATEGORIES


def read_count(chapters: int) -> int:
    """The first half of an entry's chapters are read, as SyncLibrarySeeder reads them."""
    return chapters // 2


def chapter(entry: int, number: int, chapters: int) -> bytes:
    c = F["BackupChapter"]
    parts = [
        ub.str_field(c["url"], fx.chapter_url(entry, number)),
        ub.str_field(c["name"], fx.chapter_name(number)),
    ]
    if number <= read_count(chapters):
        parts.append(ub.int_field(c["read"], 1))
    if number % BOOKMARK_EVERY == 1:
        parts.append(ub.int_field(c["bookmark"], 1))
    parts += [
        ub.int_field(c["dateFetch"], fx.SEEDED_AT_MS),
        ub.int_field(c["dateUpload"], fx.chapter_uploaded_ms(number)),
        ub.float_field(c["chapterNumber"], float(number)),
        # Newest first, as a source lists them: the last chapter at 0.
        ub.int_field(c["sourceOrder"], chapters - number),
    ]
    return b"".join(parts)


def history(entry: int, chapters: int) -> bytes:
    """The last read chapter's history row, read a minute per chapter number after the seed instant."""
    h = F["BackupHistory"]
    last = read_count(chapters)
    return b"".join((
        ub.str_field(h["url"], fx.chapter_url(entry, last)),
        ub.int_field(h["lastRead"], fx.SEEDED_AT_MS + last * 60_000),
        ub.int_field(h["readDuration"], READ_DURATION_MS),
    ))


def tracking(entry: int, chapters: int) -> bytes:
    t = F["BackupTracking"]
    remote = TRACK_REMOTE_BASE + entry
    return b"".join((
        ub.int_field(t["syncId"], MYANIMELIST),
        ub.int_field(t["libraryId"], 0),
        ub.str_field(t["trackingUrl"], f"{TRACK_URL_PREFIX}{remote}"),
        ub.str_field(t["title"], entry_title(entry)),
        ub.float_field(t["lastChapterRead"], float(read_count(chapters))),
        ub.int_field(t["totalChapters"], chapters),
        ub.int_field(t["status"], 1),
        ub.int_field(t["mediaId"], remote),
    ))


def manga(entry: int, chapters: int) -> bytes:
    m = F["BackupManga"]
    parts = [
        ub.int_field(m["source"], source_id(entry_source(entry))),
        ub.str_field(m["url"], fx.entry_url(entry)),
        ub.str_field(m["title"], entry_title(entry)),
        ub.int_field(m["status"], 1),  # SManga.ONGOING
        ub.int_field(m["dateAdded"], fx.SEEDED_AT_MS),
    ]
    parts += [ub.bytes_field(m["chapters"], chapter(entry, k, chapters)) for k in range(1, chapters + 1)]
    # Unpacked, one varint per membership, as upstream's encoder writes a List<Long>.
    parts.append(ub.int_field(m["categories"], category_order(entry)))
    if entry % TRACK_EVERY == 1:
        parts.append(ub.bytes_field(m["tracking"], tracking(entry, chapters)))
    parts.append(ub.int_field(m["favorite"], 1))
    if read_count(chapters) > 0:
        parts.append(ub.bytes_field(m["history"], history(entry, chapters)))
    parts.append(ub.int_field(m["initialized"], 1))
    return b"".join(parts)


def category(order: int) -> bytes:
    c = F["BackupCategory"]
    return b"".join((
        ub.str_field(c["name"], f"{CATEGORY_PREFIX}{order + 1}"),
        ub.int_field(c["order"], order),
        ub.int_field(c["id"], order + 1),
    ))


def source(number: int) -> bytes:
    s = F["BackupSource"]
    return ub.str_field(s["name"], source_name(number)) + ub.int_field(s["sourceId"], source_id(number))


def write(out: Path, entries: int = DEFAULT_ENTRIES, chapters: int = DEFAULT_CHAPTERS) -> int:
    """Streams the backup into `out`, one entry at a time. Returns the file's size in bytes."""
    b = F["Backup"]
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as stream:
        for entry in range(1, entries + 1):
            stream.write(ub.bytes_field(b["backupManga"], manga(entry, chapters)))
        for order in range(CATEGORIES):
            stream.write(ub.bytes_field(b["backupCategories"], category(order)))
        for number in range(1, min(SOURCES, entries) + 1):
            stream.write(ub.bytes_field(b["backupSources"], source(number)))
    return out.stat().st_size


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("out", type=Path)
    parser.add_argument("--entries", type=int, default=DEFAULT_ENTRIES)
    parser.add_argument("--chapters", type=int, default=DEFAULT_CHAPTERS)
    args = parser.parse_args(argv)
    if args.entries < 1 or args.chapters < 1:
        parser.error("--entries and --chapters must be at least 1")
    size = write(args.out, args.entries, args.chapters)
    print(f"{args.out} entries={args.entries} chapters={args.chapters} bytes={size}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
