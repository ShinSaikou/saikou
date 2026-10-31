#!/usr/bin/env python3
"""Reads what tools/ci/reader_process_death.sh needs off the device's own output.

The shell script drives contract 07 part 2 section 9.2's `ReaderProcessDeathTest` from the host,
because an instrumentation cannot (unit K's report, section 2). It sees the reader only through two
things the device prints, and this reads both:

  - `seed`: the one logcat line `ReaderProcessDeathTest` writes under the tag `SaikouReaderRestore`
    once it has seeded its entry, for the nonce the script passed it. Printed as shell assignments,
    SEED_MANGA, SEED_CHAPTER1, SEED_PAGES1, SEED_CHAPTER2 and SEED_PAGES2. A buffer can hold an
    earlier run's line too, which is what the nonce is for; when this run's line is not there the
    ids would belong to an entry that no longer exists, so that is an error (exit 2).
  - `indicator`: the reader's page indicator, "<page> / <pages>", out of a `uiautomator dump`.
    Printed as "<page> <pages>". The indicator draws its text twice, an outline under a fill, so a
    dump can carry it more than once; two readings that disagree answer for no single page and are
    an error (exit 2). No indicator at all is exit 1, which the script polls on: the reader has not
    drawn it yet, or the menu is open and hides it.
  - `find`: the centre of the first node whose text is exactly the one asked for, as "<x> <y>",
    the point `input tap` needs. Exit 1 when no node carries it.

Exit codes: 0 found; 1 not (yet) on screen; 2 bad input. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

FOUND, ABSENT, BAD_INPUT = 0, 1, 2

# Must match ReaderProcessDeathTest.LOG_TAG and the line it logs.
SEED = re.compile(
    r"\sSaikouReaderRestore\s*: seeded nonce=(?P<nonce>\S+) manga=(?P<manga>\d+)"
    r" chapter1=(?P<chapter1>\d+) pages1=(?P<pages1>\d+)"
    r" chapter2=(?P<chapter2>\d+) pages2=(?P<pages2>\d+)\s*$"
)
INDICATOR = re.compile(r"^(\d+) / (\d+)$")
BOUNDS = re.compile(r"^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$")


class InputError(Exception):
    pass


def seed(logcat: str, nonce: str) -> dict[str, int]:
    """The ids this run's seed logged. The last matching line wins, should the test have run twice."""
    found = None
    for line in logcat.splitlines():
        match = SEED.search(line)
        if match and match.group("nonce") == nonce:
            found = match
    if found is None:
        raise InputError(f"no SaikouReaderRestore seed line for nonce {nonce}")
    return {key: int(found.group(key)) for key in ("manga", "chapter1", "pages1", "chapter2", "pages2")}


def nodes(dump: str) -> list[ET.Element]:
    # uiautomator prints a status line before the XML when its output goes to stdout, so everything
    # before the declaration is dropped rather than failing the parse.
    start = dump.find("<?xml")
    if start < 0:
        start = dump.find("<hierarchy")
    if start < 0:
        raise InputError("not a uiautomator dump")
    try:
        root = ET.fromstring(dump[start:])
    except ET.ParseError as error:
        raise InputError(f"the dump does not parse: {error}") from error
    return list(root.iter("node"))


def indicator(dump: str) -> tuple[int, int] | None:
    readings = {
        (int(match.group(1)), int(match.group(2)))
        for node in nodes(dump)
        if (match := INDICATOR.match((node.get("text") or "").strip()))
    }
    if len(readings) > 1:
        shown = ", ".join(f"{page} / {pages}" for page, pages in sorted(readings))
        raise InputError(f"the dump shows more than one page indicator: {shown}")
    return next(iter(readings), None)


def find(dump: str, text: str) -> tuple[int, int] | None:
    for node in nodes(dump):
        if node.get("text") != text:
            continue
        match = BOUNDS.match(node.get("bounds") or "")
        if not match:
            raise InputError(f"the node '{text}' has no readable bounds")
        left, top, right, bottom = (int(value) for value in match.groups())
        return (left + right) // 2, (top + bottom) // 2
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read the reader restore check's device output.")
    commands = parser.add_subparsers(dest="command", required=True)
    seed_command = commands.add_parser("seed", help="the ids the seed test logged for a nonce")
    seed_command.add_argument("--nonce", required=True)
    seed_command.add_argument("logcat", type=Path)
    indicator_command = commands.add_parser("indicator", help="the page indicator in a dump")
    indicator_command.add_argument("dump", type=Path)
    find_command = commands.add_parser("find", help="the centre of a node with this exact text")
    find_command.add_argument("--text", required=True)
    find_command.add_argument("dump", type=Path)
    args = parser.parse_args(argv)

    try:
        if args.command == "seed":
            ids = seed(args.logcat.read_text(errors="replace"), args.nonce)
            for key, value in ids.items():
                print(f"SEED_{key.upper()}={value}")
            return FOUND
        dump = args.dump.read_text(errors="replace")
        if args.command == "indicator":
            reading = indicator(dump)
        else:
            reading = find(dump, args.text)
    except (InputError, OSError) as error:
        print(f"reader_process_death: {error}", file=sys.stderr)
        return BAD_INPUT
    if reading is None:
        return ABSENT
    print(f"{reading[0]} {reading[1]}")
    return FOUND


if __name__ == "__main__":
    sys.exit(main())
