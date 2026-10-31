#!/usr/bin/env python3
"""Finds the package's crashes in a dump of the platform crash buffer.

Ruling R58. `reader_crash_count` counts process id changes inside the 120 second meminfo hold, so a
crash outside that window reached no row at all. tools/ci/gate.sh therefore empties the crash
buffer (`adb logcat -b crash -c`) before each bench device's benches and dumps it
(`adb logcat -b crash -d -v threadtime`) after them, and this reads the dump. Any entry from the
package fails the run, naming the device and quoting the entry's first lines.

An entry is every line one process id wrote. It belongs to the package when one of its lines names
the package as its process: the runtime's `Process: <name>, PID: <pid>` line for a Java crash, or
the `>>> <name> <<<` line of a native crash dump. A process of the package's own (`<package>:<name>`)
counts; a different package whose name only starts with this one's does not. A platform low-memory
kill writes nothing to this buffer, so what it finds is the application's own defects.

Exit codes: 0 no entry from the package; 1 at least one; 2 the dump is missing.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# logcat's threadtime format: date, time, pid, tid, level, then `<tag>: <message>`.
THREADTIME = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+ [VDIWEFA] ")
JAVA_PROCESS = re.compile(r"\bProcess: ([^\s,]+), PID: \d+")
NATIVE_PROCESS = re.compile(r">>> (\S+) <<<")
QUOTED_LINES = 12


def belongs_to(process: str, package: str) -> bool:
    return process == package or process.startswith(package + ":")


def crashes(dump: str, package: str) -> list[list[str]]:
    """The package's entries in `dump`, each as its lines in buffer order."""
    by_pid: dict[str, list[str]] = {}
    for line in dump.splitlines():
        match = THREADTIME.match(line)
        if match:
            by_pid.setdefault(match.group(1), []).append(line)
    found = []
    for lines in by_pid.values():
        names = [m.group(1) for line in lines for m in (JAVA_PROCESS.search(line), NATIVE_PROCESS.search(line)) if m]
        if any(belongs_to(name, package) for name in names):
            found.append(lines)
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Find the package's entries in a crash buffer dump.")
    parser.add_argument("dump", type=Path, help="output of `adb logcat -b crash -d -v threadtime`")
    parser.add_argument("--package", required=True)
    parser.add_argument("--device", required=True, help="the gate's name for the device, for the message")
    args = parser.parse_args(argv)

    if not args.dump.is_file():
        print(f"missing: {args.dump}", file=sys.stderr)
        return 2
    found = crashes(args.dump.read_text(errors="replace"), args.package)
    if not found:
        print(f"    crash buffer on {args.device}: no entry from {args.package}")
        return 0
    print(f"{args.package} crashed on {args.device}: {len(found)} entr{'y' if len(found) == 1 else 'ies'} "
          f"in the crash buffer ({args.dump}). The first:", file=sys.stderr)
    for line in found[0][:QUOTED_LINES]:
        print(f"    {line}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
