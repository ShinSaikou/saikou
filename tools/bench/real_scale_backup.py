#!/usr/bin/env python3
"""Writes a real-shaped .tachibk of N copies of a real library, for unit upstream-compare-20k.

The comparison of Saikou with upstream v0.21.0 and v0.20.4 restores one library of about 20,000
entries into each app. big_backup.py's library is uniform (every entry has the same chapters); this
one keeps a real library's shape instead: its chapters per entry, names, numbers, scanlators, dates,
read and bookmark state, last page read, history, categories, memberships and tracks. It writes
`--copies` copies of every entry of an input backup (43 copies of a 468-entry library is 20,124
entries):

- Copy 0 is the input entry as it is.
- Copy k (1 and up) appends COPY_URL_SUFFIX to the entry's url, every chapter's url and every
  history row's url, so no app takes two copies for the same entry or chapter (upstream v0.21.0 merges
  entries and chapters that share a source and url), and COPY_TITLE_SUFFIX to its title, so a copy
  is told apart on screen while a library search for a word of a title still finds every copy.
- Only the fields all three apps read are written. Upstream v0.21.0 no longer reads BackupManga 106
  (lastModifiedAt) and 109 (version) or BackupChapter 11 and 12, and only it reads BackupManga 113
  (nextUpdate), so those are dropped (DROPPED); every other field of the recorded v0.20.4 models
  (tools/gate/upstream_backup_fields.json) is kept byte for byte. A field the models do not know is
  dropped too, and counted.
- The file holds the entries, the categories and the sources, in upstream's field order. App and
  source preferences and extension stores are left out: each app reads its own, and a comparison
  that restored the input's settings would measure different screens in each app.

The library is never held whole: the input (a few MB) is read once, each entry is reduced to its
fixed parts, and each copy is encoded and written into the gzip stream on its own, so a file of
several hundred MB uncompressed takes a few tens of MB to write. gzip's timestamp is pinned to zero,
so the same input writes the same bytes.

`--parts P` splits the copies over P files, each with the categories and sources, for an app that
cannot decode the whole file at once: restored one after another they hold the same library.

After writing, every file is decoded back as a stream and its counts are checked against the
input's times the copies (entries, chapters, history rows, tracks; every entry's source and url
once). Then `targets.json` and `targets.env` are written beside the files: for each chapter count
named by `--target-chapters`, the first input entry with exactly that many chapters, its title and
the word of its title made of ASCII letters that the fewest input titles hold (what a library
search types), the name of its category tab, and the library's totals. The input and
everything written are the owner's data and stay outside the repository; this script prints counts
only, never a title, url or source.

Usage: real_scale_backup.py <input .tachibk> <out dir> [--copies 43] [--parts 1] [--name real20k]
                            [--target-chapters 1385,3859]
Prints one line per file written, the totals, and the verification. Exit codes: 0 written and
verified; 1 the verification failed; 2 usage or an unreadable input.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import gzip
import json
import re
import shlex
import sys
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import BinaryIO, Iterator

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import big_backup  # noqa: E402
import update_backup as ub  # noqa: E402

F = big_backup.F

DEFAULT_COPIES = 43
DEFAULT_NAME = "real20k"
DEFAULT_TARGETS = (1385, 3859)
# The tab every app shows the entries of no category under.
DEFAULT_CATEGORY = "Default"
COPY_URL_SUFFIX = "#c{:02d}"
COPY_TITLE_SUFFIX = " c{:02d}"
# Fields written by upstream v0.20.4 that v0.21.0 no longer reads, by message and name.
DROPPED = {
    "BackupManga": ("lastModifiedAt", "version"),
    "BackupChapter": ("lastModifiedAt", "version"),
}
WORD = re.compile(r"[A-Za-z]+")


def kept(message: str) -> frozenset[int]:
    """The field numbers of a message this script writes: the recorded models less DROPPED."""
    dropped = {F[message][name] for name in DROPPED.get(message, ())}
    return frozenset(number for number in F[message].values() if number not in dropped)


KEEP_MANGA = kept("BackupManga")
KEEP_CHAPTER = kept("BackupChapter")
M = F["BackupManga"]
C = F["BackupChapter"]
H = F["BackupHistory"]
B = F["Backup"]


class BackupError(Exception):
    pass


def read_varint(data: bytes, i: int) -> tuple[int, int]:
    shift = result = 0
    while True:
        if i >= len(data):
            raise BackupError("a varint runs past the end of its message")
        byte = data[i]
        i += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, i
        shift += 7


def fields(data: bytes) -> Iterator[tuple[int, int, int | bytes, bytes]]:
    """Every field of one message: (number, wire type, value, the field's own bytes, key included)."""
    i = 0
    while i < len(data):
        start = i
        key, i = read_varint(data, i)
        number, wire = key >> 3, key & 7
        if wire == ub.VARINT:
            value, i = read_varint(data, i)
        elif wire == ub.LENGTH:
            length, i = read_varint(data, i)
            if i + length > len(data):
                raise BackupError("a length-delimited field runs past the end of its message")
            value, i = data[i:i + length], i + length
        elif wire == ub.FIXED32:
            value, i = data[i:i + 4], i + 4
        elif wire == 1:
            value, i = data[i:i + 8], i + 8
        else:
            raise BackupError(f"wire type {wire} is not one a backup uses")
        yield number, wire, value, data[start:i]


@dataclass
class Chapter:
    """A chapter less its url: the kept fields before and after it, as bytes."""
    head: bytes
    url: str
    tail: bytes


@dataclass
class Entry:
    """An input entry reduced to what a copy changes (url, title, chapter and history urls) and the
    kept fields around them, in the input's own order."""
    parts: list  # bytes, or one of the markers "url", "title", ("chapter", i), ("history", i)
    url: str
    title: str
    source: int
    chapters: list[Chapter]
    histories: list[tuple[bytes, str, bytes]]
    tracks: int


@dataclass
class Library:
    entries: list[Entry]
    categories: list[bytes]
    sources: list[bytes]
    dropped: Counter = field(default_factory=Counter)


def split_url(message: bytes, url_number: int, keep: frozenset[int] | None, dropped: Counter,
              label: str) -> tuple[bytes, str, bytes]:
    head, tail, url = [], [], None
    for number, _, value, raw in fields(message):
        if number == url_number and url is None:
            url = value.decode()
        elif keep is None or number in keep:
            (tail if url is not None else head).append(raw)
        else:
            dropped[f"{label} {number}"] += 1
    if url is None:
        raise BackupError(f"a {label} has no url")
    return b"".join(head), url, b"".join(tail)


def reduce_entry(message: bytes, dropped: Counter) -> Entry:
    parts: list = []
    url = title = None
    source = 0
    chapters: list[Chapter] = []
    histories: list[tuple[bytes, str, bytes]] = []
    tracks = 0
    for number, _, value, raw in fields(message):
        if number not in KEEP_MANGA:
            dropped[f"BackupManga {number}"] += 1
        elif number == M["url"]:
            url = value.decode()
            parts.append("url")
        elif number == M["title"]:
            title = value.decode()
            parts.append("title")
        elif number == M["chapters"]:
            parts.append(("chapter", len(chapters)))
            chapters.append(Chapter(*split_url(value, C["url"], KEEP_CHAPTER, dropped, "BackupChapter")))
        elif number == M["history"]:
            parts.append(("history", len(histories)))
            histories.append(split_url(value, H["url"], None, dropped, "BackupHistory"))
        else:
            if number == M["source"]:
                source = value
            elif number == M["tracking"]:
                tracks += 1
            parts.append(raw)
    if url is None or title is None:
        raise BackupError("an entry has no url or no title")
    return Entry(parts, url, title, source, chapters, histories, tracks)


def read_library(path: Path) -> Library:
    """The input's entries, categories and sources. Preferences and extension stores are skipped."""
    try:
        data = gzip.decompress(path.read_bytes())
    except (OSError, EOFError) as e:
        raise BackupError(f"the input is not a gzipped backup: {e}") from e
    library = Library([], [], [])
    for number, _, value, _ in fields(data):
        if number == B["backupManga"]:
            library.entries.append(reduce_entry(value, library.dropped))
        elif number == B["backupCategories"]:
            library.categories.append(value)
        elif number == B["backupSources"]:
            library.sources.append(value)
        else:
            library.dropped[f"Backup {number}"] += 1
    if not library.entries:
        raise BackupError("the input holds no entry")
    return library


def suffixed(value: str, copy: int, suffix: str) -> str:
    return value if copy == 0 else value + suffix.format(copy)


def encode(entry: Entry, copy: int) -> bytes:
    out = []
    for part in entry.parts:
        if isinstance(part, bytes):
            out.append(part)
        elif part == "url":
            out.append(ub.str_field(M["url"], suffixed(entry.url, copy, COPY_URL_SUFFIX)))
        elif part == "title":
            out.append(ub.str_field(M["title"], suffixed(entry.title, copy, COPY_TITLE_SUFFIX)))
        elif part[0] == "chapter":
            c = entry.chapters[part[1]]
            body = c.head + ub.str_field(C["url"], suffixed(c.url, copy, COPY_URL_SUFFIX)) + c.tail
            out.append(ub.bytes_field(M["chapters"], body))
        else:
            head, url, tail = entry.histories[part[1]]
            body = head + ub.str_field(H["url"], suffixed(url, copy, COPY_URL_SUFFIX)) + tail
            out.append(ub.bytes_field(M["history"], body))
    return b"".join(out)


def part_ranges(copies: int, parts: int) -> list[range]:
    """The copies each file holds, as even as they divide, the earlier files taking the remainder."""
    size, extra = divmod(copies, parts)
    ranges, start = [], 0
    for k in range(parts):
        end = start + size + (1 if k < extra else 0)
        ranges.append(range(start, end))
        start = end
    return ranges


def file_name(name: str, k: int, parts: int) -> str:
    return f"{name}.tachibk" if parts == 1 else f"{name}-part{k + 1:02d}-of-{parts:02d}.tachibk"


def write(library: Library, out_dir: Path, copies: int = DEFAULT_COPIES, parts: int = 1,
          name: str = DEFAULT_NAME) -> list[Path]:
    """Streams the copies into `parts` files, one entry at a time. Returns the files written."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for k, copy_range in enumerate(part_ranges(copies, parts)):
        path = out_dir / file_name(name, k, parts)
        with path.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as stream:
            for copy in copy_range:
                for entry in library.entries:
                    stream.write(ub.bytes_field(B["backupManga"], encode(entry, copy)))
            for category in library.categories:
                stream.write(ub.bytes_field(B["backupCategories"], category))
            for source in library.sources:
                stream.write(ub.bytes_field(B["backupSources"], source))
        written.append(path)
    return written


def stream_frames(stream: BinaryIO) -> Iterator[tuple[int, bytes]]:
    """The top-level length-delimited fields of a backup read from a stream, one at a time."""
    def byte() -> int | None:
        b = stream.read(1)
        return b[0] if b else None

    def varint(first: int | None) -> int:
        shift = result = 0
        b = first
        while True:
            if b is None:
                raise BackupError("the file ends inside a varint")
            result |= (b & 0x7F) << shift
            if not b & 0x80:
                return result
            shift += 7
            b = byte()

    while (first := byte()) is not None:
        key = varint(first)
        if key & 7 != ub.LENGTH:
            raise BackupError(f"top-level field {key >> 3} is not length-delimited")
        length = varint(byte())
        value = stream.read(length)
        if len(value) != length:
            raise BackupError("the file ends inside a field")
        yield key >> 3, value


@dataclass
class Counts:
    entries: int = 0
    chapters: int = 0
    history: int = 0
    tracks: int = 0
    categories: int = 0
    sources: int = 0

    def as_dict(self) -> dict[str, int]:
        return dict(self.__dict__)


def count_input(library: Library) -> Counts:
    return Counts(len(library.entries), sum(len(e.chapters) for e in library.entries),
                  sum(len(e.histories) for e in library.entries), sum(e.tracks for e in library.entries),
                  len(library.categories), len(library.sources))


def verify(files: list[Path], library: Library, copies: int) -> list[str]:
    """Decodes every file back as a stream. Returns the problems found, empty when it holds the input's
    counts times the copies, and every entry's (source, url) once across all the files."""
    problems = []
    total = Counts()
    seen: set[tuple[int, str]] = set()
    for path in files:
        categories = sources = 0
        with gzip.open(path, "rb") as stream:
            for number, value in stream_frames(stream):
                if number == B["backupManga"]:
                    total.entries += 1
                    source, url = 0, ""
                    for n, _, v, _ in fields(value):
                        if n == M["source"]:
                            source = v
                        elif n == M["url"]:
                            url = v.decode()
                        elif n == M["chapters"]:
                            total.chapters += 1
                            if any(cn not in KEEP_CHAPTER for cn, _, _, _ in fields(v)):
                                problems.append(f"{path.name}: a chapter carries a dropped field")
                        elif n == M["history"]:
                            total.history += 1
                        elif n == M["tracking"]:
                            total.tracks += 1
                        elif n not in KEEP_MANGA:
                            problems.append(f"{path.name}: an entry carries field {n}")
                    if (source, url) in seen:
                        problems.append(f"{path.name}: entry {total.entries} repeats a source and url")
                    seen.add((source, url))
                elif number == B["backupCategories"]:
                    categories += 1
                elif number == B["backupSources"]:
                    sources += 1
                else:
                    problems.append(f"{path.name}: top-level field {number}")
        total.categories = max(total.categories, categories)
        total.sources = max(total.sources, sources)
        if categories != len(library.categories) or sources != len(library.sources):
            problems.append(f"{path.name}: {categories} categories and {sources} sources, "
                            f"want {len(library.categories)} and {len(library.sources)}")
    want = count_input(library)
    for key in ("entries", "chapters", "history", "tracks"):
        if getattr(total, key) != getattr(want, key) * copies:
            problems.append(f"{key}: {getattr(total, key)}, want {getattr(want, key)} x {copies}")
    return problems[:20]


def hits(word: str, titles: list[str]) -> int:
    """How many titles hold the word, case folded, as a library search matches a substring."""
    return sum(1 for title in titles if word.casefold() in title.casefold())


def search_word(title: str, titles: list[str]) -> str | None:
    """The run of ASCII letters in a title (`input text` types it) that the fewest of the titles hold,
    of three letters or more when the title has one: a search for it lands on this entry's copies
    and as few others as the title allows, so the entry is among the results in every app. Ties go
    to the longer word, then the first."""
    words = WORD.findall(title)
    if any(len(w) >= 3 for w in words):
        words = [w for w in words if len(w) >= 3]
    if not words:
        return None
    return min(words, key=lambda w: (hits(w, titles), -len(w)))


def targets(library: Library, chapter_counts: tuple[int, ...]) -> dict[str, dict]:
    """For each chapter count, the first input entry with exactly that many chapters, its title, its
    search word and how many input titles hold that word; each copy of those adds one hit."""
    titles = [e.title for e in library.entries]
    found = {}
    for count in chapter_counts:
        entry = next((e for e in library.entries if len(e.chapters) == count), None)
        if entry is None:
            continue
        word = search_word(entry.title, titles)
        hits_ = hits(word, titles) if word else 0
        found[str(count)] = {"title": entry.title, "word": word, "chapters": count,
                             "input_hits": hits_, "history": len(entry.histories),
                             "category": category_of(library, entry)}
    return found


def category_name(category: bytes) -> tuple[int, str]:
    values = {number: value for number, _, value, _ in fields(category)}
    return values.get(F["BackupCategory"]["order"], 0), values[F["BackupCategory"]["name"]].decode()


def category_of(library: Library, entry: Entry) -> str:
    """The name of the entry's category tab: of its memberships (category orders, as upstream writes
    them) the lowest, or DEFAULT_CATEGORY when it has none."""
    orders = sorted(value for part in entry.parts if isinstance(part, bytes)
                    for number, _, value, _ in fields(part) if number == M["categories"])
    names = dict(category_name(c) for c in library.categories)
    return next((names[o] for o in orders if o in names), DEFAULT_CATEGORY)


def write_targets(out_dir: Path, found: dict[str, dict], copies: int, totals: Counts) -> None:
    """targets.json, and targets.env for the steps files' {{NAME}} placeholders: the library's totals
    and, per target, its title, search word and category tab."""
    (out_dir / "targets.json").write_text(json.dumps({"copies": copies, "totals": totals.as_dict(),
                                                      "targets": found}, indent=2, ensure_ascii=False) + "\n")
    lines = [f"ENTRIES_TOTAL={totals.entries}", f"CHAPTERS_TOTAL={totals.chapters}"]
    for count, target in found.items():
        lines.append(f"TITLE_{count}={shlex.quote(target['title'])}")
        lines.append(f"CATEGORY_{count}={shlex.quote(target['category'])}")
        if target["word"]:
            lines.append(f"WORD_{count}={shlex.quote(target['word'])}")
    (out_dir / "targets.env").write_text("\n".join(lines) + "\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("input", type=Path)
    parser.add_argument("out_dir", type=Path)
    parser.add_argument("--copies", type=int, default=DEFAULT_COPIES)
    parser.add_argument("--parts", type=int, default=1)
    parser.add_argument("--name", default=DEFAULT_NAME)
    parser.add_argument("--target-chapters", default=",".join(map(str, DEFAULT_TARGETS)))
    args = parser.parse_args(argv)
    if args.copies < 1 or args.copies > 99 or args.parts < 1 or args.parts > args.copies:
        parser.error("--copies must be 1 to 99 and --parts 1 to --copies")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.name):
        parser.error("--name is letters, digits, - and _ only")
    try:
        counts = tuple(int(v) for v in args.target_chapters.split(",") if v)
    except ValueError:
        parser.error("--target-chapters is a comma list of chapter counts")
    try:
        library = read_library(args.input)
    except (OSError, BackupError) as e:
        print(f"cannot read the input: {e}", file=sys.stderr)
        return 2
    want = count_input(library)
    print(f"input: {json.dumps(want.as_dict())}; dropped fields: {json.dumps(dict(sorted(library.dropped.items())))}")
    files = write(library, args.out_dir, args.copies, args.parts, args.name)
    for path in files:
        print(f"{path} bytes={path.stat().st_size}")
    print(f"written: entries={want.entries * args.copies} chapters={want.chapters * args.copies} "
          f"history={want.history * args.copies} tracks={want.tracks * args.copies} "
          f"bytes={sum(p.stat().st_size for p in files)}")
    problems = verify(files, library, args.copies)
    if problems:
        print("verification failed:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1
    print(f"verified: every count is the input's x {args.copies}, every entry's source and url once")
    found = targets(library, counts)
    totals = Counts(*(value * args.copies for value in (want.entries, want.chapters, want.history, want.tracks)),
                    want.categories, want.sources)
    write_targets(args.out_dir, found, args.copies, totals)
    print("targets: " + ", ".join(f"{c} chapters (word of {len(t['word'] or '')} letters, "
                                  f"{t['input_hits']} input titles hold it)" for c, t in found.items())
          + f"; missing: {[c for c in counts if str(c) not in found]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
