#!/usr/bin/env python3
"""Reads what tools/ci/reader_resume_next.sh needs off the device's output, beyond what
reader_process_death.py already reads for it (the seed line, the page indicator, a node by text).

  - `since`: the logcat lines stamped at or after a reading of the device's own clock, as
    `date +'%m-%d %H:%M:%S'` prints it there. The script takes that reading just before it taps
    Next chapter and keeps only the reader's markers from then on, without clearing logcat (a clear
    failed on the API 26 image, unit K2's report 7.2). A line is compared by its threadtime stamp,
    `MM-DD hh:mm:ss.mmm`, cut to the second, so a line from the same second as the reading is kept:
    the reading is taken before the tap, and nothing the tap causes can be stamped earlier. Lines
    with no stamp (logcat's `--------- beginning of main`) are dropped. logcat prints no year, so a
    month more than six before the reading's is read as January after a December reading, and one
    more than six after it as last year's.
  - `find-description`: the centre of the first node whose content description is exactly the one
    asked for, as "<x> <y>", the point `input tap` needs (the reader menu's Next chapter button is
    an icon with no text). Exit 1 when no node carries it.

Exit codes: 0 found (for `since`, always, with whatever lines qualify); 1 not (yet) on screen;
2 bad input. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# The dump reader and the bounds shape are reader_process_death.py's. Both scripts run from this
# directory, which puts it on the import path; the tests put it there themselves.
from reader_process_death import BOUNDS, InputError, nodes

FOUND, ABSENT, BAD_INPUT = 0, 1, 2

# `date +'%m-%d %H:%M:%S'` on the device.
READING = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)$")
# logcat -v threadtime: `MM-DD hh:mm:ss.mmm  pid  tid level tag: message`.
STAMP = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.\d+\s")


def reading(text: str) -> tuple[int, ...]:
    match = READING.match(text.strip())
    if not match:
        raise InputError(f"the device clock reading '{text}' is not MM-DD hh:mm:ss")
    return tuple(int(part) for part in match.groups())


def since(lines: list[str], clock: str) -> list[str]:
    """The lines stamped at or after `clock`, in their order, with carriage returns removed."""
    start = reading(clock)
    kept = []
    for line in lines:
        line = line.rstrip("\r\n").replace("\r", "")
        match = STAMP.match(line)
        if not match:
            continue
        stamp = tuple(int(part) for part in match.groups())
        # The year logcat leaves out: 0 for the reading's own, 1 for the next, -1 for the last.
        year = 1 if start[0] - stamp[0] > 6 else -1 if stamp[0] - start[0] > 6 else 0
        if (year, *stamp) >= (0, *start):
            kept.append(line)
    return kept


def find_description(dump: str, description: str) -> tuple[int, int] | None:
    for node in nodes(dump):
        if node.get("content-desc") != description:
            continue
        match = BOUNDS.match(node.get("bounds") or "")
        if not match:
            raise InputError(f"the node described as '{description}' has no readable bounds")
        left, top, right, bottom = (int(value) for value in match.groups())
        return (left + right) // 2, (top + bottom) // 2
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read the reader resume and Next chapter check's device output.")
    commands = parser.add_subparsers(dest="command", required=True)
    since_command = commands.add_parser("since", help="the logcat lines at or after a device clock reading")
    since_command.add_argument("--since", required=True, metavar="'MM-DD hh:mm:ss'")
    since_command.add_argument("logcat", type=Path)
    find_command = commands.add_parser("find-description", help="the centre of a node with this content description")
    find_command.add_argument("--description", required=True)
    find_command.add_argument("dump", type=Path)
    args = parser.parse_args(argv)

    try:
        if args.command == "since":
            for line in since(args.logcat.read_text(errors="replace").splitlines(), args.since):
                print(line)
            return FOUND
        point = find_description(args.dump.read_text(errors="replace"), args.description)
    except (InputError, OSError) as error:
        print(f"reader_resume_next: {error}", file=sys.stderr)
        return BAD_INPUT
    if point is None:
        return ABSENT
    print(f"{point[0]} {point[1]}")
    return FOUND


if __name__ == "__main__":
    sys.exit(main())
