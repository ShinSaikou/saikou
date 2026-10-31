#!/usr/bin/env python3
"""Counts one package's ANRs in `adb logcat -b events -d` dumps (the `cold_start_anr_count` row).

usage: anr_count.py --package <package> <events dump>...

Prints the number of `am_anr` entries whose process is the package, or one of its own processes
(`<package>:<name>`), summed over every dump given. `coldstart.sh` clears the events buffer before
each run of its extension pass and dumps it after, so one dump is one cold start.

The system server writes one `am_anr` line per ANR it raises, whatever the cause (input dispatch,
a broadcast, a service), in the shape

    09-23 21:03:30.213   655  5477 I am_anr  : [0,5438,com.shinsaikou,552091204,Input dispatching ...]

where the fields are user, pid, process, flags and reason. The reason is free text and may carry
commas and brackets of its own, so only the first three fields are read.

A dump that holds no `am_proc_start` for the package did not bracket a launch of it, and a count of
zero from it would say that no launch ANRed when no launch was seen at all (R29). Such a dump ends
the run instead of adding zero.

Exit codes: 0 ok; 2 missing input; 3 a dump in which the package never started.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# The tag and the bracketed payload of an events buffer line in logcat's default threadtime format,
# which is what `coldstart.sh` dumps: `<level> <tag> : [...]`, the tag padded to a width.
EVENT = re.compile(r"(?:^|\s)(am_anr|am_proc_start)\s*:\s*\[(.*)\]\s*$")

# am_anr: [user, pid, process, flags, reason]; am_proc_start: [user, pid, uid, process, type, component]
PROCESS_FIELD = {"am_anr": 2, "am_proc_start": 3}


class NoLaunch(Exception):
    """A dump in which the package never started."""


def is_package_process(process: str, package: str) -> bool:
    return process == package or process.startswith(package + ":")


def count_dump(text: str, package: str) -> int:
    """The package's ANRs in one dump. Raises [NoLaunch] when the dump never saw it start."""
    anrs = 0
    started = False
    for line in text.splitlines():
        match = EVENT.search(line.rstrip("\r"))
        if match is None:
            continue
        tag, payload = match.group(1), match.group(2)
        fields = payload.split(",", PROCESS_FIELD[tag] + 1)
        if len(fields) <= PROCESS_FIELD[tag]:
            continue
        process = fields[PROCESS_FIELD[tag]].strip()
        if tag == "am_proc_start":
            started = started or process == package
        elif is_package_process(process, package):
            anrs += 1
    if not started:
        raise NoLaunch(f"the dump holds no am_proc_start for {package}")
    return anrs


def main() -> int:
    parser = argparse.ArgumentParser(description="Counts a package's ANRs in events buffer dumps.")
    parser.add_argument("--package", required=True)
    parser.add_argument("dumps", nargs="+", type=Path)
    args = parser.parse_args()

    total = 0
    for dump in args.dumps:
        if not dump.is_file():
            print(f"missing: {dump}", file=sys.stderr)
            return 2
        try:
            total += count_dump(dump.read_text(errors="replace"), args.package)
        except NoLaunch as e:
            print(f"{dump}: {e}, so it cannot say whether that launch ANRed", file=sys.stderr)
            return 3
    print(total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
