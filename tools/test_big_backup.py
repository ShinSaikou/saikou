#!/usr/bin/env python3
"""Tests for tools/bench/big_backup.py, phase 5 unit SC0's upstream-shaped backup of a large library.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The file is decoded here by a small protobuf walker of its own rather than through the writer's
helpers, and every field number is looked up in tools/gate/upstream_backup_fields.json by name, so a
field the writer numbered wrongly shows as a field this reader does not expect.
"""

from __future__ import annotations

import contextlib
import gzip
import importlib.util
import io
import json
import struct
import sys
import tempfile
import tracemalloc
import unittest
from collections import Counter
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
FIELDS = json.loads((TOOLS / "gate" / "upstream_backup_fields.json").read_text())["messages"]

_spec = importlib.util.spec_from_file_location("big_backup", TOOLS / "bench" / "big_backup.py")
big_backup = importlib.util.module_from_spec(_spec)
sys.modules.setdefault(_spec.name, big_backup)
_spec.loader.exec_module(big_backup)


def number(message: str, name: str) -> int:
    return next(field["number"] for field in FIELDS[message]["fields"] if field["name"] == name)


def walk(data: bytes) -> list[tuple[int, int | bytes]]:
    """Every (field number, value) of one message: an int for a varint, bytes otherwise."""
    out, i = [], 0
    while i < len(data):
        key, i = varint(data, i)
        field, wire = key >> 3, key & 7
        if wire == 0:
            value, i = varint(data, i)
        elif wire == 2:
            length, i = varint(data, i)
            value, i = data[i:i + length], i + length
        elif wire == 5:
            value, i = data[i:i + 4], i + 4
        elif wire == 1:
            value, i = data[i:i + 8], i + 8
        else:
            raise AssertionError(f"wire type {wire} at offset {i}")
        out.append((field, value))
    return out


def varint(data: bytes, i: int) -> tuple[int, int]:
    shift = result = 0
    while True:
        byte = data[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def by_number(fields: list[tuple[int, int | bytes]]) -> dict[int, list]:
    grouped: dict[int, list] = {}
    for field, value in fields:
        grouped.setdefault(field, []).append(value)
    return grouped


class BigBackupTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name) / "big.tachibk"

    def decoded(self, entries: int = 3, chapters: int = 2) -> dict[int, list]:
        big_backup.write(self.out, entries, chapters)
        return by_number(walk(gzip.decompress(self.out.read_bytes())))

    def test_the_backup_holds_the_counts_it_was_asked_for(self):
        top = self.decoded(entries=3, chapters=2)
        self.assertEqual(set(top), {number("Backup", "backupManga"), number("Backup", "backupCategories"),
                                    number("Backup", "backupSources")})
        mangas = top[number("Backup", "backupManga")]
        self.assertEqual(len(mangas), 3)
        self.assertEqual(len(top[number("Backup", "backupCategories")]), big_backup.CATEGORIES)
        # Three entries reach three of the four sources, and only those are listed.
        self.assertEqual(len(top[number("Backup", "backupSources")]), 3)
        for raw in mangas:
            entry = by_number(walk(raw))
            self.assertEqual(len(entry[number("BackupManga", "chapters")]), 2)
            self.assertEqual(entry[number("BackupManga", "favorite")], [1])
            self.assertEqual(len(entry[number("BackupManga", "history")]), 1)
            self.assertEqual(len(entry[number("BackupManga", "categories")]), 1)

    def test_every_field_number_is_the_recorded_upstream_one(self):
        top = self.decoded(entries=3, chapters=2)
        first = by_number(walk(top[number("Backup", "backupManga")][0]))
        manga = {number("BackupManga", n) for n in ("source", "url", "title", "status", "dateAdded", "chapters",
                                                      "categories", "tracking", "favorite", "history", "initialized")}
        self.assertEqual(set(first), manga)

        chapter = by_number(walk(first[number("BackupManga", "chapters")][0]))
        self.assertEqual(set(chapter), {number("BackupChapter", n) for n in (
            "url", "name", "read", "bookmark", "dateFetch", "dateUpload", "chapterNumber", "sourceOrder")})
        self.assertEqual(chapter[number("BackupChapter", "url")], [b"/manga/1/chapter/1"])
        self.assertEqual(struct.unpack("<f", chapter[number("BackupChapter", "chapterNumber")][0]), (1.0,))

        history = by_number(walk(first[number("BackupManga", "history")][0]))
        self.assertEqual(set(history), {number("BackupHistory", n) for n in ("url", "lastRead", "readDuration")})
        self.assertEqual(history[number("BackupHistory", "url")], [b"/manga/1/chapter/1"])

        track = by_number(walk(first[number("BackupManga", "tracking")][0]))
        self.assertEqual(set(track), {number("BackupTracking", n) for n in (
            "syncId", "libraryId", "trackingUrl", "title", "lastChapterRead", "totalChapters", "status", "mediaId")})
        self.assertEqual(track[number("BackupTracking", "syncId")], [big_backup.MYANIMELIST])

        category = by_number(walk(top[number("Backup", "backupCategories")][0]))
        self.assertEqual(set(category), {number("BackupCategory", n) for n in ("name", "order", "id")})
        source = by_number(walk(top[number("Backup", "backupSources")][0]))
        self.assertEqual(set(source), {number("BackupSource", n) for n in ("name", "sourceId")})
        self.assertEqual(source[number("BackupSource", "sourceId")], [big_backup.FIRST_SOURCE_ID])

    def test_half_the_chapters_are_read_and_one_in_seven_bookmarked(self):
        top = self.decoded(entries=1, chapters=10)
        entry = by_number(walk(top[number("Backup", "backupManga")][0]))
        chapters = [by_number(walk(raw)) for raw in entry[number("BackupManga", "chapters")]]
        read = [c.get(number("BackupChapter", "read"), [0])[0] for c in chapters]
        bookmarked = [c.get(number("BackupChapter", "bookmark"), [0])[0] for c in chapters]
        self.assertEqual(read, [1] * 5 + [0] * 5)
        self.assertEqual(bookmarked, [1, 0, 0, 0, 0, 0, 0, 1, 0, 0])
        history = by_number(walk(entry[number("BackupManga", "history")][0]))
        self.assertEqual(history[number("BackupHistory", "url")], [b"/manga/1/chapter/5"])

    def test_one_entry_in_fifty_is_tracked_and_the_entries_spread_over_categories_and_sources(self):
        top = self.decoded(entries=120, chapters=1)
        entries = [by_number(walk(raw)) for raw in top[number("Backup", "backupManga")]]
        tracked = [i + 1 for i, e in enumerate(entries) if number("BackupManga", "tracking") in e]
        self.assertEqual(tracked, [1, 51, 101])
        orders = Counter(e[number("BackupManga", "categories")][0] for e in entries)
        self.assertEqual(sorted(orders), list(range(big_backup.CATEGORIES)))
        sources = Counter(e[number("BackupManga", "source")][0] for e in entries)
        self.assertEqual(sorted(sources), [big_backup.source_id(n) for n in range(1, big_backup.SOURCES + 1)])
        titles = {e[number("BackupManga", "title")][0] for e in entries}
        self.assertEqual(len(titles), 120)
        self.assertIn(b"Scale 01-00001", titles)

    def test_the_same_sizes_write_the_same_bytes(self):
        big_backup.write(self.out, 5, 3)
        first = self.out.read_bytes()
        big_backup.write(self.out, 5, 3)
        self.assertEqual(self.out.read_bytes(), first)

    def test_the_library_is_streamed_rather_than_held(self):
        tracemalloc.start()
        try:
            big_backup.write(self.out, 3_000, 50)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        uncompressed = len(gzip.decompress(self.out.read_bytes()))
        self.assertGreater(uncompressed, 9_000_000)
        self.assertLess(peak, uncompressed // 4, f"peak {peak} bytes while writing {uncompressed} bytes")

    def test_the_command_line_writes_the_file_and_rejects_a_zero_size(self):
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed):
            self.assertEqual(big_backup.main([str(self.out), "--entries", "2", "--chapters", "2"]), 0)
        self.assertTrue(self.out.is_file())
        self.assertIn("entries=2 chapters=2", printed.getvalue())
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(io.StringIO()):
            big_backup.main([str(self.out), "--entries", "0"])
        self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
