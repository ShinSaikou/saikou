#!/usr/bin/env python3
"""Reads back what a connected-test run actually ran, from AGP's results directory.

Ruling R29: a connected-test task whose class filter matched nothing writes tests="0" and exits 0,
so every instrumentation step in tools/ci/gate.sh reads the count back out of the JUnit XML and
fails on zero. Ruling R49 adds what that total cannot show. The count per class goes into the log,
so a filter that drops or adds a class is visible at a glance. And the `Skipped:` lines the device
tests write before an early return are counted from the per-test logcat AGP captures beside the
XML, because the XML itself reads skipped="0" for a test that returned before its assertions.

With --fail-on-skips (the tablet step, ruling R51) every `Skipped:` line fails the run unless it
matches one of the --except patterns, which name the skips that step expects.

With --require-class (repeatable) a named class that ran no test fails the run. A step that runs
one class on its own passes the total check on any class the filter happened to match, so the class
it exists for is named and read back out of the XML by name.

No expected count is written anywhere: R49 rejected one, because the suites grow every week. A
required class is asked for at least one test, never for a number of them. Phase 2's device steps
pass their whole expected class list this way (gate.sh's DEVICE_CLASSES_* arrays), so a class that
ran nothing there fails the step by name.

A test case that failed or errored is listed whenever there is one, but it fails the run only with
--fail-on-failures: without the flag the verdict on failures is the caller's, which in gate.sh is
Gradle's exit status. A caller that reads only this script's exit takes the flag, or it reads a
failed run as green.

Exit codes: 0 at least one test ran and no skip or failure failed the run; 1 no test ran, a
required class ran none, an unexpected skip, or a failed test under --fail-on-failures; 2 the
results directory is missing. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

# The message the device tests log, after logcat's own `<level> <tag>: ` prefix.
SKIP_MARKER = ": Skipped: "
# logcat's default threadtime prefix: date, time, pid, tid and level, before `<tag>: <message>`.
THREADTIME_PREFIX = re.compile(r"^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+ [VDIWEFA] ")


def class_counts(directory: Path) -> Counter[str]:
    """Test cases per class, over the XML files directly in `directory`."""
    counts: Counter[str] = Counter()
    for path in sorted(directory.glob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else [s for s in root if s.tag == "testsuite"]
        for suite in suites:
            cases = suite.findall("testcase")
            if cases:
                for case in cases:
                    counts[case.attrib.get("classname", "<no class>")] += 1
            else:
                # A suite that states a count and lists no cases still ran that many.
                total = int(suite.attrib.get("tests", 0) or 0)
                if total:
                    counts[suite.attrib.get("name", "<no class>")] += total
    return counts


def failed_cases(directory: Path) -> list[str]:
    """`<class>#<method>` of every test case with a failure or an error, over the same XML files."""
    failed: list[str] = []
    for path in sorted(directory.glob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{case.attrib.get('classname', '<no class>')}#{case.attrib.get('name', '<no name>')}")
    return failed


def skip_lines(directory: Path) -> list[str] | None:
    """The distinct `Skipped:` lines in the per-test logcat files anywhere under `directory`.

    None when AGP captured no logcat at all, which is not the same answer as no skips: a capture
    that stopped with an AGP upgrade would otherwise read as a suite that skipped nothing.
    A logcat window can overlap the next test's, so one line may sit in two files; it is counted
    once.
    """
    files = sorted(directory.rglob("logcat-*.txt"))
    if not files:
        return None
    seen: dict[str, None] = {}
    for path in files:
        for line in path.read_text(errors="replace").splitlines():
            if SKIP_MARKER in line:
                seen.setdefault(line.strip(), None)
    return list(seen)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Read back what a connected-test run ran.")
    parser.add_argument("directory", type=Path, help="androidTest-results/connected/<variant>")
    parser.add_argument("--fail-on-skips", action="store_true",
                        help="fail on any Skipped: line no --except pattern matches")
    parser.add_argument("--except", dest="expected", action="append", default=[], metavar="REGEX",
                        help="a Skipped: line this step expects; repeat for several")
    parser.add_argument("--require-class", dest="required", action="append", default=[], metavar="CLASS",
                        help="a fully qualified test class that must have run a test; repeat for several")
    parser.add_argument("--fail-on-failures", action="store_true",
                        help="fail when any test case failed or errored, rather than leaving that to the caller")
    args = parser.parse_args(argv)

    if not args.directory.is_dir():
        print(f"missing: {args.directory}", file=sys.stderr)
        return 2
    counts = class_counts(args.directory)
    total = sum(counts.values())
    print(f"    {total} test(s) in {args.directory}")
    for name in sorted(counts):
        print(f"      {counts[name]:>3}  {name}")
    failed = failed_cases(args.directory)
    if failed:
        print(f"    {len(failed)} test(s) failed")
        for name in failed:
            print(f"      {name}")

    skips = skip_lines(args.directory)
    unexpected: list[str] = []
    if skips is None:
        print("    no logcat captured, so skipped assertions cannot be counted")
    else:
        print(f"    {len(skips)} Skipped: line(s) in the per-test logcat")
        for line in skips:
            message = THREADTIME_PREFIX.sub("", line)
            expected = any(re.search(pattern, line) for pattern in args.expected)
            if args.fail_on_skips and not expected:
                unexpected.append(message)
            print(f"      {message}{'  (expected here)' if args.fail_on_skips and expected else ''}")

    # What failed is said on stderr below, after the counts it refers to when both share one log.
    sys.stdout.flush()
    if total == 0:
        return 1
    if args.fail_on_failures and failed:
        print(f"    {len(failed)} test(s) failed, and this step fails on a failed test", file=sys.stderr)
        return 1
    missing = [name for name in args.required if counts[name] == 0]
    if missing:
        for name in missing:
            print(f"    {name} ran no test here, though this step exists to run it", file=sys.stderr)
        return 1
    if args.fail_on_skips:
        if skips is None:
            # The step exists to prove its assertions ran, and without the capture it cannot.
            print("    this step fails on a skip, and with no logcat it cannot see one", file=sys.stderr)
            return 1
        if unexpected:
            print(f"    {len(unexpected)} Skipped: line(s) this step does not allow", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
