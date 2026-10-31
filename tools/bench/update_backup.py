#!/usr/bin/env python3
"""Writes the seeded library of a library update scenario as a .tachibk, ruling R66.

Both apps restore a .tachibk through their own restore screen, which is how the harness seeds a
library of 2,000 or 500 entries with no test-only path in either product (R39). The file is upstream's
backup format: a gzip of the protobuf `Backup` message, written here field by field from the
proto numbers of upstream v0.20.4's backup models (Backup, BackupManga, BackupChapter, BackupSource),
because the standard library has no protobuf encoder.

Every entry is in the library, ongoing, and holds chapters 1 to 49, all read and in the order a
source listed them before chapter 50 existed; the fixture server lists 50. So every run from the
snapshot taken after the restore finds exactly one new chapter per entry, inserts it, and shifts
the other 49 down one place in the source's order, which is what a real new chapter does.

Nothing in it depends on the clock: the dates are fixed instants from update_fixture.py, so the same
scenario writes the same bytes on every run (gzip's own timestamp is pinned to zero).

Usage: update_backup.py <scenario: 2000x20 or 500x2> <out file>
Exit codes: 0 written; 2 usage.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import gzip
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_fixture as fx  # noqa: E402

VARINT, LENGTH, FIXED32 = 0, 2, 5


def varint(value: int) -> bytes:
    if value < 0:
        value += 1 << 64  # int64 in two's complement, as kotlinx.serialization writes a negative Long
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def key(number: int, wire: int) -> bytes:
    return varint(number << 3 | wire)


def int_field(number: int, value: int) -> bytes:
    return key(number, VARINT) + varint(value)


def bytes_field(number: int, value: bytes) -> bytes:
    return key(number, LENGTH) + varint(len(value)) + value


def str_field(number: int, value: str) -> bytes:
    return bytes_field(number, value.encode())


def float_field(number: int, value: float) -> bytes:
    return key(number, FIXED32) + struct.pack("<f", value)


def chapter(entry: int, number: int) -> bytes:
    """BackupChapter: url 1, name 2, read 4, dateFetch 7, dateUpload 8, chapterNumber 9, sourceOrder 10."""
    return b"".join((
        str_field(1, fx.chapter_url(entry, number)),
        str_field(2, fx.chapter_name(number)),
        int_field(4, 1),
        int_field(7, fx.SEEDED_AT_MS),
        int_field(8, fx.chapter_uploaded_ms(number)),
        float_field(9, float(number)),
        # Newest first, as the source listed them the day before chapter 50: chapter 49 at 0.
        int_field(10, fx.SEEDED_CHAPTERS - number),
    ))


def manga(source: int, entry: int) -> bytes:
    """BackupManga: source 1, url 2, title 3, status 8, dateAdded 13, chapters 16, favorite 100,
    initialized 111."""
    parts = [
        int_field(1, fx.source_id(source)),
        str_field(2, fx.entry_url(entry)),
        str_field(3, fx.entry_title(source, entry)),
        int_field(8, 1),  # SManga.ONGOING
        int_field(13, fx.SEEDED_AT_MS),
    ]
    parts += [bytes_field(16, chapter(entry, k)) for k in range(1, fx.SEEDED_CHAPTERS + 1)]
    parts += [int_field(100, 1), int_field(111, 1)]
    return b"".join(parts)


def backup(scenario: fx.Scenario) -> bytes:
    """Backup: backupManga 1, backupSources 101 (name 1, sourceId 2)."""
    parts = [bytes_field(1, manga(source, entry))
             for source in range(1, scenario.sources + 1) for entry in range(1, scenario.per_source + 1)]
    parts += [bytes_field(101, str_field(1, fx.source_name(s)) + int_field(2, fx.source_id(s)))
              for s in range(1, scenario.sources + 1)]
    return gzip.compress(b"".join(parts), mtime=0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("scenario", choices=sorted(fx.SCENARIOS))
    parser.add_argument("out", type=Path)
    args = parser.parse_args(argv)
    data = backup(fx.SCENARIOS[args.scenario])
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(data)
    print(args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
