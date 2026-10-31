#!/usr/bin/env python3
"""The host half of tools/ci/scale40k.sh: what a 40,000-entry run says when the device could not.

ScaleRowsTest publishes every row it reaches, "failed" with a reason included. The row it cannot
publish is the one its process did not survive, and at this size that is the finding (an
`OutOfMemoryError` on the 384 MB class, R131 item 4), so the runner names it here instead of
leaving a gap the table would read as "not run":

  failed   writes a row's fragment with the value "failed" and the reason the run gave: how
           `am instrument` ended (its `shortMsg`, or that the host stopped waiting) and the first
           `OutOfMemoryError` line of the method's logcat, which the runner cleared before it, or,
           when there is none, the app's process killed by signal 9 with its heap full; or, for a
           method the runner never started, the reason it gives with --reason;
  value    prints a fragment's value, or nothing when it is not a number, so the runner can ask
           whether the 4 GB restore finished under the low-RAM run's limit;
  oom      writes `oom_count_40k` for one device: the out-of-memory counts its methods published,
           plus every method whose process died of memory, each named in the note.

A death of memory is either an `OutOfMemoryError` line, or the app's process killed by signal 9
while its last GC line reported at most HEAP_FULL_PERCENT free: on the 1.5 GB AVD the low memory
killer ends a process whose heap sits at its 384 MB class before the runtime throws (the first run's
low-RAM export: "1% free, 379MB/384MB", then "Process 6194 exited due to signal (9)").

A fragment has the shape GateFragments.publish writes, one `rows[]` entry of tools/gate/rows.json,
so collect.py reads the runner's fragments and the device's alike.

Exit codes: 0 done; 2 usage or an unreadable input. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

FAILED = "failed"
OUT_OF_MEMORY = re.compile(r"java\.lang\.OutOfMemoryError[^\r\n]*")
SHORT_MESSAGE = re.compile(r"INSTRUMENTATION_RESULT: shortMsg=([^\r\n]*)")
PACKAGE = "com.shinsaikou"
DIED = re.compile(rf"Process {re.escape(PACKAGE)} \(pid (\d+)\) has died")
STARTED = re.compile(rf"Start proc (\d+):{re.escape(PACKAGE)}/")
GC_FREE = re.compile(r"(\d+)% free, ([\d.]+[KMG]?B/[\d.]+[KMG]?B)")
HEAP_FULL_PERCENT = 2
# The counts a method publishes itself: an OutOfMemoryError it caught and survived.
OOM_ROWS = ("restore_40k_oom_count", "fingerprint_40k_oom")


def unit_of(row_id: str) -> str:
    """The unit ScaleRowsTest gives the row: a time ends in _ms or carries _ms_, the rest are counts."""
    return "ms" if row_id.endswith("_ms") or "_ms_" in row_id else "count"


def fragment(row_id: str, device: str, value, unit: str, method: str) -> dict:
    recorded = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {"id": row_id, "device": device, "value": value, "unit": unit, "method": method, "recordedAt": recorded}


def pid_of(line: str) -> str | None:
    """The process id of a `logcat -v threadtime` line: date, time, pid, tid, level, tag."""
    fields = line.split()
    return fields[2] if len(fields) > 2 else None


def own_pids(logcat: str) -> set[str] | None:
    """The app processes the method's log saw start, or None when it shows no start at all.

    The log starts at the method's start to the second, so the previous method's last lines can
    lead it (the first full run's low-RAM fingerprint log began with the export's death in the same
    second); only a process started inside the log is the method's own.
    """
    started = set(STARTED.findall(logcat))
    return started or None


def first_out_of_memory(logcat: str) -> str | None:
    own = own_pids(logcat)
    for line in logcat.splitlines():
        found = OUT_OF_MEMORY.search(line)
        if found and (own is None or pid_of(line) in own):
            return found.group(0).strip()
    return None


def killed_full(logcat: str) -> str | None:
    """The app's process killed by signal 9 with its last GC at most HEAP_FULL_PERCENT free, described."""
    own = own_pids(logcat)
    for died in DIED.finditer(logcat):
        pid = died.group(1)
        if own is not None and pid not in own:
            continue
        if not re.search(rf"Process {pid} exited due to signal \(9\)", logcat):
            continue
        last = None
        for line in logcat.splitlines():
            fields = line.split()
            if len(fields) > 2 and fields[2] == pid and (gc := GC_FREE.search(line)):
                last = gc
        if last and int(last.group(1)) <= HEAP_FULL_PERCENT:
            return f"process {pid} killed by signal 9 with its heap full (last GC: {last.group(1)}% free, {last.group(2)})"
    return None


def memory_death(logcat: str) -> str | None:
    return first_out_of_memory(logcat) or killed_full(logcat)


def death_reason(instrument: str, logcat: str, status: int) -> str:
    """How the method's `am instrument` ended, and the out-of-memory line when there was one."""
    if status == 124:
        parts = ["the host stopped waiting for am instrument"]
    else:
        short = SHORT_MESSAGE.search(instrument)
        if short:
            parts = [f"am instrument: {short.group(1).strip()}"]
        elif "INSTRUMENTATION_CODE" not in instrument:
            parts = [f"am instrument ended without its INSTRUMENTATION_CODE line (exit {status})"]
        else:
            parts = ["the method ended without publishing the row"]
    oom = memory_death(logcat)
    if oom:
        parts.append(oom)
    return "; ".join(parts)


def read(path: Path) -> str:
    try:
        return path.read_text(errors="replace")
    except FileNotFoundError:
        return ""


def numeric(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def oom_count(rows_dir: Path, deaths: list[str], device: str, logs: Path) -> dict:
    """`oom_count_40k` over one device's rows and the methods whose process died."""
    total, parts = 0, []
    for row_id in OOM_ROWS:
        path = rows_dir / f"{row_id}.json"
        if not path.is_file():
            parts.append(f"{row_id} absent")
            continue
        value = json.loads(path.read_text()).get("value")
        if numeric(value):
            total += int(value)
        parts.append(f"{row_id} {value}")
    died = []
    for tag in deaths:
        oom = memory_death(read(logs / f"{tag}.logcat.txt"))
        if oom:
            died.append(f"{tag} ({oom})")
    total += len(died)
    note = "scale40k.sh: the out-of-memory counts the methods published (" + ", ".join(parts) + ")"
    note += "; processes that died of memory: " + ("; ".join(died) if died else "none")
    return fragment("oom_count_40k", device, total, "count", note)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)

    failed = commands.add_parser("failed")
    failed.add_argument("out", type=Path)
    failed.add_argument("--id", required=True)
    failed.add_argument("--device", required=True)
    failed.add_argument("--method", required=True, help="the ScaleRowsTest method that should have published it")
    failed.add_argument("--instrument", type=Path, help="the method's am instrument output")
    failed.add_argument("--logcat", type=Path, help="the method's logcat")
    failed.add_argument("--status", type=int, default=0, help="the exit status the host saw")
    failed.add_argument("--reason", help="the reason, when the method was never started")

    value = commands.add_parser("value")
    value.add_argument("fragment", type=Path)

    oom = commands.add_parser("oom")
    oom.add_argument("out", type=Path)
    oom.add_argument("--rows", type=Path, required=True, help="the device's fragments")
    oom.add_argument("--device", required=True)
    oom.add_argument("--logs", type=Path, required=True, help="where the methods' logcat files are")
    oom.add_argument("--deaths", type=Path, required=True, help="one method tag per line whose process died")

    args = parser.parse_args(argv)
    if args.command == "failed":
        if args.reason:
            reason = args.reason
        elif args.instrument and args.logcat:
            reason = death_reason(read(args.instrument), read(args.logcat), args.status)
        else:
            parser.error("failed needs --reason, or --instrument and --logcat")
        row = fragment(args.id, args.device, FAILED, unit_of(args.id), f"scale40k.sh: ScaleRowsTest.{args.method}: {reason}")
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(row) + "\n")
        print(f"{args.id} on {args.device}: failed: {reason}")
    elif args.command == "value":
        try:
            data = json.loads(args.fragment.read_text())
        except (FileNotFoundError, json.JSONDecodeError):
            return 0
        if numeric(data.get("value")):
            print(data["value"])
    else:
        deaths = [line.strip() for line in read(args.deaths).splitlines() if line.strip()]
        row = oom_count(args.rows, deaths, args.device, args.logs)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(row) + "\n")
        print(f"oom_count_40k on {args.device}: {row['value']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
