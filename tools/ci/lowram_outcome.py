#!/usr/bin/env python3
"""Names what happened to the low RAM repro when it left no fragment.

Ruling R61 binds `low_ram_repro_pass` to `LowRamReproTest` on `lowram_1_5g`, and the test publishes
its fragment whether its checks pass or fail, so a FAIL carries its numbers. The one run that cannot
publish is one the process did not survive, and without this that death would reach the release
gate as the same MISSING a test that never ran produces. tools/ci/gate.sh clears the device's main,
crash and events buffers before the run, saves them after it beside the `am instrument` output, and
hands all of them to this, which prints one of five outcomes:

  - the fragment was published: its value and method note, whatever the value (exit 0);
  - the process died: the package's crash buffer entry; or else the events buffer's `am_kill` for one
    of its processes whose reason is not the instrumentation's own stop, or `am_proc_died` for one
    that no such stop explains; or else `am instrument`'s own "Process crashed." (exit 1);
  - the measurement could not be taken (R36): the test failed and published nothing, and its failure
    message says why (exit 3);
  - the test never ran, which is the only MISSING: no `am instrument` output, a run of no tests, a
    runner that never started the test, no process of the package started, or a test that passed
    without publishing, which is an early return (exit 4);
  - the session was lost: the test started and `am instrument`'s output ends without the
    `INSTRUMENTATION_CODE` line it always closes with, so the host stopped hearing from the run and
    nothing says how the repro went (exit 5). Checked before a death, because it causes one: when
    the shared adb server restarted during a run on the clone, the shell's `am` process went with
    it, the test's UiAutomation connection died under it, and the process then crashed in
    `Instrumentation.finish` with a DeadObjectException. That entry is quoted as a consequence,
    not named as the application's death. Rerun the step.

The death rules come from unit 8a's runs on this AVD (phase1-units/phase1-gate-lowram.report.md).
A crash was seen there: `am crash` wrote the crash buffer entry, `am_crash` and then `am_proc_died`,
and no finished-instrumentation `am_kill`. A normal end, pass or fail, writes that `am_kill`. A
low-memory kill was never seen, because the process never died on its own. That it shows up as
`am_proc_died` (or `am_kill` with lmkd's reason) without the finished-instrumentation stop, and
writes nothing to the crash buffer, is inferred from the platform's contract, not observed.

Exit codes: 0 published; 1 died; 2 a buffer dump is missing or the fragment is not the row's;
3 unmeasurable; 4 never ran; 5 the session was lost. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# The crash buffer is read the way the R58 check reads it. Both run as scripts from this directory,
# which puts it on the import path; the tests put it there themselves.
from crash_buffer import QUOTED_LINES, belongs_to, crashes

PUBLISHED, DIED, BAD_INPUT, UNMEASURABLE, NEVER_RAN, LOST = 0, 1, 2, 3, 4, 5

# An events buffer line in threadtime format ends in `<tag>: [<fields>]`; the three tags that say
# when a process of the package started and how it went.
EVENT = re.compile(r"\s(am_proc_start|am_proc_died|am_kill)\s*: \[(.*)\]\s*$")
# `am instrument -r` reports each test's start as status code 1, and the runner's summary at the end.
TEST_STARTED = re.compile(r"^INSTRUMENTATION_STATUS_CODE: 1\s*$", re.MULTILINE)
RESULT_CODE = re.compile(r"^INSTRUMENTATION_CODE: -?\d+\s*$", re.MULTILINE)
NO_TESTS = re.compile(r"^OK \(0 tests\)\s*$", re.MULTILINE)
PROCESS_CRASHED = re.compile(r"^INSTRUMENTATION_RESULT: shortMsg=Process crashed\.?\s*$", re.MULTILINE)
FAILURES = re.compile(r"^FAILURES!!!\s*$", re.MULTILINE)
STACK = re.compile(r"^INSTRUMENTATION_STATUS: stack=(.*)$", re.MULTILINE)


def finished_inst(package: str) -> str:
    """The `am_kill` reason ActivityManager writes when an instrumentation run ends normally."""
    return f"stop {package} due to finished inst"


def events(dump: str, package: str) -> list[tuple[str, str, list[str], str]]:
    """The package's process events: (tag, pid, fields, line), in buffer order."""
    found = []
    for line in dump.splitlines():
        match = EVENT.search(line)
        if not match:
            continue
        tag, fields = match.group(1), match.group(2).split(",")
        # am_proc_start: user, pid, uid, process, type, component. The other two: user, pid, process, ...
        process = fields[3] if tag == "am_proc_start" and len(fields) > 3 else fields[2] if len(fields) > 2 else ""
        if belongs_to(process, package):
            found.append((tag, fields[1], fields, line.strip()))
    return found


def kill_reason(fields: list[str]) -> str:
    # user, pid, process, oom adj, reason, rss. The reason is free text, so the fields between the
    # adj and the last one are joined back; a row without the rss field ends at the reason.
    return ",".join(fields[4:-1] if len(fields) > 5 else fields[4:])


def death_lines(dump: str, package: str) -> list[str]:
    """The events buffer lines that say a process of the package died other than by the runner's stop."""
    process_events = events(dump, package)
    stopped = {pid for tag, pid, fields, _ in process_events
               if tag == "am_kill" and kill_reason(fields) == finished_inst(package)}
    lines = []
    for tag, pid, fields, line in process_events:
        if tag == "am_kill" and kill_reason(fields) != finished_inst(package):
            lines.append(line)
        elif tag == "am_proc_died" and pid not in stopped:
            lines.append(line)
    return lines


def failure_message(log: str) -> str:
    """The test's own failure message: the first line of the stack `am instrument -r` reports."""
    match = STACK.search(log)
    if match and match.group(1).strip():
        return match.group(1).strip()
    lines = log.splitlines()
    for index, line in enumerate(lines):
        # The runner's summary: `1) <test>(<class>)` and the exception on the next line.
        if re.match(r"^1\) ", line) and index + 1 < len(lines):
            return lines[index + 1].strip()
    return "no failure message in the am instrument output"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Name the low RAM repro's outcome.")
    parser.add_argument("--fragment", type=Path, required=True,
                        help="the row's fragment as pulled from the device; absent when none was published")
    parser.add_argument("--instrument-log", type=Path, required=True,
                        help="am instrument's output; absent when no runner was installed")
    parser.add_argument("--crash-buffer", type=Path, required=True, help="`logcat -b crash -d -v threadtime`")
    parser.add_argument("--events-buffer", type=Path, required=True, help="`logcat -b events -d -v threadtime`")
    parser.add_argument("--main-buffer", type=Path, help="`logcat -d -v threadtime`, for a Skipped: line")
    parser.add_argument("--package", required=True)
    parser.add_argument("--device", required=True, help="the gate's name for the device")
    parser.add_argument("--row", default="low_ram_repro_pass")
    args = parser.parse_args(argv)

    for dump in (args.crash_buffer, args.events_buffer):
        if not dump.is_file():
            print(f"missing: {dump}", file=sys.stderr)
            return BAD_INPUT

    what = f"{args.row} on {args.device}"
    if args.fragment.is_file():
        try:
            fragment = json.loads(args.fragment.read_text())
        except json.JSONDecodeError as exc:
            print(f"the {what} fragment is not JSON ({args.fragment}): {exc}", file=sys.stderr)
            return BAD_INPUT
        if not isinstance(fragment, dict) or fragment.get("id") != args.row or fragment.get("device") != args.device:
            print(f"{args.fragment} is not the {what} fragment", file=sys.stderr)
            return BAD_INPUT
        print(f"    {what} published {json.dumps(fragment.get('value'))}: {fragment.get('method')}")
        return PUBLISHED

    log = args.instrument_log.read_text(errors="replace") if args.instrument_log.is_file() else None

    events_dump = args.events_buffer.read_text(errors="replace")
    found = crashes(args.crash_buffer.read_text(errors="replace"), args.package)
    if log is not None and TEST_STARTED.search(log) and not RESULT_CODE.search(log):
        print(f"the low RAM repro on {args.device} lost its am instrument session: the output ends without "
              f"INSTRUMENTATION_CODE, so the host stopped hearing from the run (the shared adb server "
              f"restarting does this) and nothing says how the repro went. Rerun the step.", file=sys.stderr)
        if found:
            print("    the package's crash buffer entry, which the lost session can cause:", file=sys.stderr)
            for line in found[0][:QUOTED_LINES]:
                print(f"    {line}", file=sys.stderr)
        return LOST
    died = death_lines(events_dump, args.package)
    if found or died or (log is not None and PROCESS_CRASHED.search(log)):
        if found:
            how, quoted = f"{len(found)} crash buffer entr{'y' if len(found) == 1 else 'ies'}", found[0]
        elif died:
            how, quoted = "the events buffer", died
        else:
            how, quoted = "am instrument", [PROCESS_CRASHED.search(log).group(0).strip()]
        print(f"process died during the low RAM repro on {args.device}, so {args.row} has no fragment "
              f"({how}):", file=sys.stderr)
        for line in quoted[:QUOTED_LINES]:
            print(f"    {line}", file=sys.stderr)
        return DIED

    started = any(tag == "am_proc_start" for tag, *_ in events(events_dump, args.package))
    never = None
    if log is None:
        never = f"no am instrument output at {args.instrument_log}; the runner was not installed"
    elif NO_TESTS.search(log) or not TEST_STARTED.search(log):
        never = "am instrument started no test: " + (log.strip().splitlines() or ["(empty output)"])[-1].strip()
    elif not started:
        never = f"no process of {args.package} started in the events buffer"
    elif not FAILURES.search(log):
        skips = []
        if args.main_buffer and args.main_buffer.is_file():
            skips = [line.strip() for line in args.main_buffer.read_text(errors="replace").splitlines()
                     if ": Skipped: " in line]
        never = "the test passed without publishing, so it returned before measuring: " + (
            skips[0] if skips else "no Skipped: line in the main buffer")
    if never:
        print(f"MISSING {what}: the repro never ran ({never})", file=sys.stderr)
        return NEVER_RAN

    print(f"the low RAM repro on {args.device} could not take its measurement and published nothing (R36): "
          f"{failure_message(log)}", file=sys.stderr)
    return UNMEASURABLE


if __name__ == "__main__":
    raise SystemExit(main())
