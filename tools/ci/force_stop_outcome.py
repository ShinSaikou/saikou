#!/usr/bin/env python3
"""Reads the outcome file part one of a force-stop row leaves on the device, before the force-stop.

Phase 2 decision Q8 (a)(i): a row about what survives a force-stop is two instrumentation runs, and
tools/ci/gate.sh's `force_stop_between` runs `am force-stop` between them. The first run writes what
it left behind to an outcome file on the device and returns; the second reads that file and compares
it with what the restarted application has. The gate pulls the file between the two and hands it to
this, so a first run that left nothing to compare, or left a state that would not test the row, ends
the run before the force-stop rather than after it (R36), and the state is printed into the log.

The file is one JSON object. Every outcome carries:

    {"schema": 1, "row": "<the gate row id>", "package": "<the application>", "pid": <part one's pid>,
     "writtenAt": "<ISO instant>", ...}

and the fields its row adds. One row writes one today, `download_queue_survives_force_stop`
(GateRowsTest), which adds:

    "held": <page requests the fixture server was holding open when the file was written>,
    "queue": [{"chapterId": n, "mangaId": n, "state": "QUEUED" | "DOWNLOADING" | ...,
               "queueOrder": n, "downloadedPages": n, "totalPages": n}, ...]   in queue order

and is accepted only when the downloads were mid-flight: at least one page request held open, a
chapter downloading with some of its pages and not all, and no chapter failed.

Exit codes: 0 the outcome is one the second run can test; 2 the file is not an outcome this reads (not
JSON, another package, a field missing or of the wrong type, a row with no rule here); 3 the row
cannot be measured from it (no file at all, or a state that is not mid-flight), which ends the run
(R36). Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# The first run's own failure is quoted the way the low RAM step quotes it. Both run as scripts from
# this directory, which puts it on the import path; the tests put it there themselves.
from lowram_outcome import failure_message

READABLE, BAD_INPUT, UNMEASURABLE = 0, 2, 3
SCHEMA = 1
DOWNLOAD_ROW = "download_queue_survives_force_stop"
# DownloadState's names, as the test writes them.
STATES = {"QUEUED", "DOWNLOADING", "PAUSED", "DONE", "ERROR"}
QUEUE_FIELDS = ("chapterId", "mangaId", "queueOrder", "downloadedPages", "totalPages")


class Refused(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


def is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def common(outcome, package: str) -> str:
    """Checks the fields every outcome carries and returns its row."""
    if not isinstance(outcome, dict):
        raise Refused(BAD_INPUT, "the outcome is not a JSON object")
    if outcome.get("schema") != SCHEMA:
        raise Refused(BAD_INPUT, f"the outcome has schema {outcome.get('schema')!r}, and this reads {SCHEMA}")
    row = outcome.get("row")
    if not isinstance(row, str) or not row:
        raise Refused(BAD_INPUT, "the outcome names no row")
    if outcome.get("package") != package:
        raise Refused(BAD_INPUT, f"the outcome is {outcome.get('package')!r}'s, not {package}'s")
    if not is_int(outcome.get("pid")) or outcome["pid"] <= 0:
        raise Refused(BAD_INPUT, f"the outcome's pid is {outcome.get('pid')!r}, not a process id")
    if not isinstance(outcome.get("writtenAt"), str) or not outcome["writtenAt"]:
        raise Refused(BAD_INPUT, "the outcome has no writtenAt")
    return row


def download_queue(outcome: dict) -> str:
    """The download row's rule: a queue with a chapter part-way through its pages. Returns a summary."""
    queue = outcome.get("queue")
    if not isinstance(queue, list):
        raise Refused(BAD_INPUT, "the outcome has no queue list")
    for index, entry in enumerate(queue):
        if not isinstance(entry, dict):
            raise Refused(BAD_INPUT, f"queue row {index} is not an object")
        for field in QUEUE_FIELDS:
            if not is_int(entry.get(field)) or entry[field] < 0:
                raise Refused(BAD_INPUT, f"queue row {index} has {field} {entry.get(field)!r}")
        if entry.get("state") not in STATES:
            raise Refused(BAD_INPUT, f"queue row {index} has state {entry.get('state')!r}")
        if entry["downloadedPages"] > entry["totalPages"]:
            raise Refused(BAD_INPUT, f"queue row {index} has more pages done than it has")
    chapters = [entry["chapterId"] for entry in queue]
    if len(set(chapters)) != len(chapters):
        raise Refused(BAD_INPUT, "the queue names a chapter twice")
    orders = [entry["queueOrder"] for entry in queue]
    if orders != sorted(set(orders)):
        raise Refused(BAD_INPUT, "the queue is not in queue order")
    held = outcome.get("held")
    if not is_int(held) or held < 0:
        raise Refused(BAD_INPUT, f"the outcome's held count is {held!r}")

    if not queue:
        raise Refused(UNMEASURABLE, "part one left an empty queue, so there is nothing a force-stop could lose")
    failed = [entry["chapterId"] for entry in queue if entry["state"] == "ERROR"]
    if failed:
        raise Refused(UNMEASURABLE, f"chapter {failed[0]} had already failed before the force-stop, so the queue "
                                    f"was not the one the row stops")
    partial = [entry for entry in queue
               if entry["state"] == "DOWNLOADING" and 0 < entry["downloadedPages"] < entry["totalPages"]]
    if not partial:
        raise Refused(UNMEASURABLE, "no chapter was downloading with some of its pages done and some not, so the "
                                    "force-stop would not land mid-flight")
    if held == 0:
        raise Refused(UNMEASURABLE, "the fixture server held no page request open, so nothing was in flight")
    first = partial[0]
    return (f"{len(queue)} queue row(s); chapter {first['chapterId']} downloading, {first['downloadedPages']} of "
            f"{first['totalPages']} pages; {held} page request(s) held open; part one ran as pid {outcome['pid']}")


RULES = {DOWNLOAD_ROW: download_queue}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read a force-stop row's outcome file before the force-stop.")
    parser.add_argument("outcome", type=Path, help="the outcome file as pulled from the device; absent when none")
    parser.add_argument("--package", required=True)
    parser.add_argument("--part-one-log", type=Path, help="part one's am instrument output, quoted when there is no file")
    args = parser.parse_args(argv)

    try:
        if not args.outcome.is_file():
            why = "no part one output"
            if args.part_one_log and args.part_one_log.is_file():
                why = failure_message(args.part_one_log.read_text(errors="replace"))
            raise Refused(UNMEASURABLE, f"part one left no outcome file ({why}), so part two has nothing to compare")
        try:
            outcome = json.loads(args.outcome.read_text())
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise Refused(BAD_INPUT, f"the outcome is not JSON: {exc}") from exc
        row = common(outcome, args.package)
        rule = RULES.get(row)
        if rule is None:
            raise Refused(BAD_INPUT, f"no rule here for the row {row}")
        summary = rule(outcome)
    except Refused as refused:
        what = "cannot be measured (R36)" if refused.code == UNMEASURABLE else "is not readable"
        print(f"the force-stop outcome at {args.outcome} {what}: {refused}", file=sys.stderr)
        return refused.code
    print(f"    {row}: {summary}")
    return READABLE


if __name__ == "__main__":
    raise SystemExit(main())
