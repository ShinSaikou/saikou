#!/usr/bin/env python3
"""Tests for tools/bench/real_scale_backup.py, unit upstream-compare-20k's copies of a real library.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The input here is generated, never the owner's backup: a small library written with
update_backup.py's encoder carrying every kind of field the script treats differently (the dropped
manga and chapter fields, history, a track, memos, preferences). The output is decoded by this
file's own protobuf walker, and every field number is looked up by name in
tools/gate/upstream_backup_fields.json.
"""

from __future__ import annotations

import contextlib
import gzip
import importlib.util
import io
import json
import struct
import subprocess
import sys
import tempfile
import tracemalloc
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
FIELDS = json.loads((TOOLS / "gate" / "upstream_backup_fields.json").read_text())["messages"]
sys.path.insert(0, str(TOOLS / "bench"))
import update_backup as ub  # noqa: E402

_spec = importlib.util.spec_from_file_location("real_scale_backup", TOOLS / "bench" / "real_scale_backup.py")
rsb = importlib.util.module_from_spec(_spec)
sys.modules.setdefault(_spec.name, rsb)
_spec.loader.exec_module(rsb)


def number(message: str, name: str) -> int:
    return next(field["number"] for field in FIELDS[message]["fields"] if field["name"] == name)


def walk(data: bytes) -> list[tuple[int, int | bytes]]:
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


M = {name: number("BackupManga", name) for name in ("source", "url", "title", "author", "chapters", "categories",
                                                   "tracking", "favorite", "history", "lastModifiedAt", "version",
                                                   "initialized", "memo")}
C = {name: number("BackupChapter", name) for name in ("url", "name", "scanlator", "read", "lastPageRead",
                                                     "chapterNumber", "lastModifiedAt", "version", "memo")}
H = {name: number("BackupHistory", name) for name in ("url", "lastRead", "readDuration")}
T = {name: number("BackupTracking", name) for name in ("syncId", "title", "mediaId")}
B = {name: number("Backup", name) for name in ("backupManga", "backupCategories", "backupSources",
                                              "backupPreferences")}


def chapter(entry: int, k: int) -> bytes:
    return b"".join((
        ub.str_field(C["url"], f"/e{entry}/c{k}"),
        ub.str_field(C["name"], f"Chapter {k}"),
        ub.str_field(C["scanlator"], "Group"),
        ub.int_field(C["read"], k % 2),
        ub.int_field(C["lastPageRead"], 7),
        ub.float_field(C["chapterNumber"], float(k)),
        ub.int_field(C["lastModifiedAt"], 1_700_000_000),
        ub.int_field(C["version"], 3),
        ub.str_field(C["memo"], "{}"),
    ))


def entry(index: int, chapters: int, title: str) -> bytes:
    parts = [
        ub.int_field(M["source"], 100 + index % 2),
        ub.str_field(M["url"], f"/e{index}"),
        ub.str_field(M["title"], title),
        ub.str_field(M["author"], "Someone"),
    ]
    parts += [ub.bytes_field(M["chapters"], chapter(index, k)) for k in range(1, chapters + 1)]
    parts += [ub.int_field(M["categories"], 0), ub.int_field(M["categories"], 1)]
    if index == 1:
        parts.append(ub.bytes_field(M["tracking"], ub.int_field(T["syncId"], 1) + ub.str_field(T["title"], title)
                                    + ub.int_field(T["mediaId"], 42)))
    parts.append(ub.int_field(M["favorite"], 1))
    parts.append(ub.bytes_field(M["history"], ub.str_field(H["url"], f"/e{index}/c1")
                                + ub.int_field(H["lastRead"], 1_700_000_000_000) + ub.int_field(H["readDuration"], 5)))
    parts += [ub.int_field(M["lastModifiedAt"], 1_700_000_000), ub.int_field(M["version"], 9),
              ub.int_field(M["initialized"], 1), ub.str_field(M["memo"], "{}")]
    return b"".join(parts)


TITLES = ["First Story", "It's a 'Quoted' Tale", "Another Story Here"]
CHAPTERS = [3, 5, 2]


def library_bytes(chapters: list[int] = CHAPTERS, titles: list[str] = TITLES) -> bytes:
    parts = [ub.bytes_field(B["backupManga"], entry(i + 1, n, t)) for i, (n, t) in enumerate(zip(chapters, titles))]
    parts += [ub.bytes_field(B["backupCategories"], ub.str_field(1, f"Cat {k}") + ub.int_field(2, k)) for k in range(2)]
    parts += [ub.bytes_field(B["backupSources"], ub.str_field(1, f"Src {s}") + ub.int_field(2, s)) for s in (100, 101)]
    parts.append(ub.bytes_field(B["backupPreferences"], ub.str_field(1, "pref_key")))
    return gzip.compress(b"".join(parts), mtime=0)


class RealScaleBackupTest(unittest.TestCase):

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.input = self.dir / "in.tachibk"
        self.input.write_bytes(library_bytes())
        self.out = self.dir / "out"

    def written(self, copies: int = 3, parts: int = 1) -> list[Path]:
        return rsb.write(rsb.read_library(self.input), self.out, copies, parts)

    def top(self, path: Path) -> dict[int, list]:
        return by_number(walk(gzip.decompress(path.read_bytes())))

    def test_the_file_holds_every_entry_once_per_copy_with_its_chapters_history_and_track(self):
        [path] = self.written(copies=3)
        top = self.top(path)
        self.assertEqual(set(top), {B["backupManga"], B["backupCategories"], B["backupSources"]})
        entries = [by_number(walk(raw)) for raw in top[B["backupManga"]]]
        self.assertEqual(len(entries), 9)
        self.assertEqual([len(e[M["chapters"]]) for e in entries], CHAPTERS * 3)
        self.assertEqual(sum(len(e.get(M["history"], [])) for e in entries), 9)
        self.assertEqual(sum(len(e.get(M["tracking"], [])) for e in entries), 3)
        self.assertEqual(len(top[B["backupCategories"]]), 2)
        self.assertEqual(len(top[B["backupSources"]]), 2)

    def test_copy_zero_is_the_input_entry_and_a_copy_suffixes_its_urls_and_title(self):
        [path] = self.written(copies=3)
        entries = [by_number(walk(raw)) for raw in self.top(path)[B["backupManga"]]]
        first, copy2 = entries[0], entries[6]
        self.assertEqual(first[M["url"]], [b"/e1"])
        self.assertEqual(first[M["title"]], [b"First Story"])
        self.assertEqual(copy2[M["url"]], [b"/e1#c02"])
        self.assertEqual(copy2[M["title"]], [b"First Story c02"])
        chapters = [by_number(walk(raw)) for raw in copy2[M["chapters"]]]
        self.assertEqual([c[C["url"]][0] for c in chapters], [b"/e1/c1#c02", b"/e1/c2#c02", b"/e1/c3#c02"])
        self.assertEqual([c[C["url"]][0] for c in (by_number(walk(r)) for r in first[M["chapters"]])],
                         [b"/e1/c1", b"/e1/c2", b"/e1/c3"])
        history = by_number(walk(copy2[M["history"]][0]))
        self.assertEqual(history[H["url"]], [b"/e1/c1#c02"])
        self.assertEqual(history[H["lastRead"]], [1_700_000_000_000])
        # The track is copied as it is, its title included.
        self.assertEqual(copy2[M["tracking"]], first[M["tracking"]])
        self.assertEqual(copy2[M["categories"]], [0, 1])

    def test_the_fields_upstream_0_21_no_longer_reads_are_dropped_and_every_other_is_kept(self):
        [path] = self.written(copies=2)
        entries = [by_number(walk(raw)) for raw in self.top(path)[B["backupManga"]]]
        for e in entries:
            self.assertNotIn(M["lastModifiedAt"], e)
            self.assertNotIn(M["version"], e)
            for name in ("source", "author", "favorite", "initialized", "memo"):
                self.assertIn(M[name], e, name)
            for raw in e[M["chapters"]]:
                c = by_number(walk(raw))
                self.assertNotIn(C["lastModifiedAt"], c)
                self.assertNotIn(C["version"], c)
                self.assertEqual(c[C["scanlator"]], [b"Group"])
                self.assertEqual(c[C["lastPageRead"]], [7])
                self.assertEqual(c[C["memo"]], [b"{}"])
        numbers = struct.unpack("<f", by_number(walk(entries[1][M["chapters"]][4]))[C["chapterNumber"]][0])
        self.assertEqual(numbers, (5.0,))

    def test_the_parts_split_the_copies_and_together_hold_the_single_file(self):
        self.assertEqual([len(r) for r in rsb.part_ranges(43, 11)], [4] * 10 + [3])
        self.assertEqual(rsb.part_ranges(5, 2), [range(0, 3), range(3, 5)])
        single = [raw for raw in self.top(self.written(copies=5)[0])[B["backupManga"]]]
        files = self.written(copies=5, parts=2)
        self.assertEqual([p.name for p in files], ["real20k-part01-of-02.tachibk", "real20k-part02-of-02.tachibk"])
        split = []
        for path in files:
            top = self.top(path)
            self.assertEqual(len(top[B["backupCategories"]]), 2)
            self.assertEqual(len(top[B["backupSources"]]), 2)
            split += top[B["backupManga"]]
        self.assertEqual(split, single)

    def test_the_verification_passes_the_written_file_and_names_a_repeated_entry(self):
        library = rsb.read_library(self.input)
        files = rsb.write(library, self.out, 3)
        self.assertEqual(rsb.verify(files, library, 3), [])
        self.assertEqual(rsb.verify(files, library, 4)[0], "entries: 9, want 3 x 4")
        data = gzip.decompress(files[0].read_bytes())
        first = ub.bytes_field(B["backupManga"], self.top(files[0])[B["backupManga"]][0])
        files[0].write_bytes(gzip.compress(first + data, mtime=0))
        problems = rsb.verify(files, library, 3)
        self.assertIn("repeats a source and url", problems[0])

    def test_the_targets_name_the_entry_by_its_chapter_count_and_its_rarest_word(self):
        library = rsb.read_library(self.input)
        found = rsb.targets(library, (5, 2, 99))
        self.assertEqual(set(found), {"5", "2"})
        self.assertEqual(found["5"]["title"], "It's a 'Quoted' Tale")
        self.assertEqual(found["5"]["word"], "Quoted")
        self.assertEqual(found["2"]["word"], "Another")
        self.assertEqual(found["2"]["input_hits"], 1)
        # "Story" is in two titles and "Another" in one; "Here" is in one too, and shorter.
        self.assertEqual(rsb.search_word("Story Here Story", ["Story Here Story", "Story"]), "Here")
        # A tie goes to the longer word; a word under three letters only when the title has no other.
        self.assertEqual(rsb.search_word("A Big Tale", ["A Big Tale", "Big Tale"]), "Tale")
        self.assertEqual(rsb.search_word("Of Big", ["Of Big", "Big"]), "Big")
        self.assertEqual(rsb.search_word("Xy 2", []), "Xy")
        self.assertIsNone(rsb.search_word("123 456", []))
        # Entry 2 is in categories of order 0 and 1; the lower names its tab.
        self.assertEqual(found["5"]["category"], "Cat 0")
        rsb.write_targets(self.dir, found, 43, rsb.Counts(129, 430, 129, 43, 2, 2))
        script = f"set -a; . {self.dir / 'targets.env'}; printf '%s\\n' \"$TITLE_5\" \"$WORD_2\" \"$CATEGORY_5\" \"$CHAPTERS_TOTAL\""
        env = subprocess.run(["bash", "-c", script], capture_output=True, text=True, check=True).stdout
        self.assertEqual(env.splitlines(), ["It's a 'Quoted' Tale", "Another", "Cat 0", "430"])
        written = json.loads((self.dir / "targets.json").read_text())
        self.assertEqual((written["copies"], written["totals"]["entries"]), (43, 129))

    def test_an_entry_of_no_category_is_under_the_default_tab(self):
        self.input.write_bytes(library_bytes())
        library = rsb.read_library(self.input)
        entry = library.entries[0]
        entry.parts = [p for p in entry.parts if not (isinstance(p, bytes) and p[0] >> 3 == M["categories"])]
        self.assertEqual(rsb.category_of(library, entry), "Default")
        self.assertEqual(rsb.category_of(library, library.entries[1]), "Cat 0")

    def test_the_same_input_writes_the_same_bytes(self):
        first = self.written(copies=2)[0].read_bytes()
        self.assertEqual(self.written(copies=2)[0].read_bytes(), first)

    def test_the_copies_are_streamed_rather_than_held(self):
        self.input.write_bytes(library_bytes([400] * 20, [f"Title {k}" for k in range(20)]))
        library = rsb.read_library(self.input)
        tracemalloc.start()
        try:
            [path] = rsb.write(library, self.out, 30)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        uncompressed = len(gzip.decompress(path.read_bytes()))
        self.assertGreater(uncompressed, 10_000_000)
        self.assertLess(peak, uncompressed // 10, f"peak {peak} bytes while writing {uncompressed} bytes")

    def test_the_command_line_prints_counts_never_a_title_or_url(self):
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed):
            self.assertEqual(rsb.main([str(self.input), str(self.out), "--copies", "4", "--parts", "2",
                                       "--target-chapters", "5"]), 0)
        text = printed.getvalue()
        self.assertIn("entries=12 chapters=40 history=12 tracks=4", text)
        self.assertIn("verified", text)
        for secret in TITLES + ["/e1", "Src 100", "Quoted"]:
            self.assertNotIn(secret, text)
        self.assertTrue((self.out / "targets.env").is_file())

    def test_the_command_line_rejects_bad_sizes_and_an_unreadable_input(self):
        for args in (["--copies", "0"], ["--parts", "5", "--copies", "4"], ["--name", "a/b"]):
            with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(io.StringIO()):
                rsb.main([str(self.input), str(self.out), *args])
            self.assertEqual(raised.exception.code, 2)
        (self.dir / "bad.tachibk").write_bytes(b"not gzip")
        with contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertEqual(rsb.main([str(self.dir / "bad.tachibk"), str(self.out)]), 2)
        self.assertIn("cannot read the input", err.getvalue())


if __name__ == "__main__":
    unittest.main()
