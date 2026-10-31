#!/usr/bin/env python3
"""Publishes export_reimports_upstream_lossless from this run's JUnit XML, as a host fragment.

Unit R2 of design/plans/phase3-start.md, part 3, and Q18. tools/ci/upstream_lossless_row.sh runs R2's
two :backup JVM tests with --rerun and hands their results directory to this, which writes the row
into the bench output in tools/bench/common.sh's fragment() shape (id, device, value, unit, samples,
method, recordedAt), with the device `any` that rows.json binds it to. gate.sh step 8d's collect.py
--bench reads it from there.

    UpstreamBackupFieldsTest     Saikou's models against tools/gate/upstream_backup_fields.json, both ways
    UpstreamLosslessExportTest   a fixture library exported and decoded as upstream decodes it, every field

The row is true only when both ran in this run and passed. The fragment is removed before anything
is read, so a refused run cannot leave an earlier value behind to be collected as this one's, and a
refused run writes nothing at all: a gate step that fails ends the run, and R36 has an unmeasured
row named by bound_rows.py rather than published as false. Each class is read from its own
`TEST-<class>.xml`, which has to:

  - exist, since the caller deletes both files before Gradle runs;
  - carry a suite timestamp no earlier than --since, the second the caller started Gradle. A task
    served from the build cache restores the XML it cached, timestamp and all, so this is what tells
    a result this run produced from one it was handed (R29: the flag alone is not read back);
  - list at least one test case, with no failure, error or skip among them, and agree with the
    suite's own counts.

Gradle's exit status is passed in as well (--gradle-exit): a failed build publishes nothing even
when both files read as passed.

Exit codes: 0 published; 1 refused, each reason printed; 2 bad arguments. Python 3.11+, standard
library only.
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

PUBLISHED, REFUSED, BAD_INPUT = 0, 1, 2
ROW_ID = "export_reimports_upstream_lossless"
DEVICE = "any"
UNIT = "bool"
# The two tests the row stands on. tools/test_upstream_lossless_row.py holds each name to a source file
# in :backup, so a rename fails the tools suite rather than the gate run.
CLASSES = (
    "com.shinsaikou.backup.UpstreamBackupFieldsTest",
    "com.shinsaikou.backup.UpstreamLosslessExportTest",
)


def fragment_path(out: Path) -> Path:
    """Where fragment() would write the row: `<id>.<device>.json` in the output directory."""
    return out / f"{ROW_ID}.{DEVICE}.json"


def suite_time(value: str | None) -> datetime | None:
    """A JUnit suite timestamp as UTC. Gradle writes UTC, with or without a trailing Z."""
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def read_class(results: Path, name: str, since: datetime) -> tuple[int, list[str]]:
    """The number of test cases `name` ran, and every reason its XML does not show a fresh pass."""
    path = results / f"TEST-{name}.xml"
    if not path.is_file():
        return 0, [f"{name}: no {path.name} in {results}, so it did not run in this run"]
    try:
        suite = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return 0, [f"{name}: {path.name} is not XML: {exc}"]
    if suite.tag != "testsuite":
        return 0, [f"{name}: {path.name} holds <{suite.tag}>, not one <testsuite>"]
    problems: list[str] = []
    ran = suite_time(suite.get("timestamp"))
    if ran is None:
        problems.append(f"{name}: {path.name} has no readable timestamp ({suite.get('timestamp')!r})")
    elif ran < since:
        problems.append(f"{name}: {path.name} is from {ran.isoformat()}, before this run started at "
                        f"{since.isoformat()}, so it was not produced by this run")
    cases = suite.findall("testcase")
    if not cases:
        problems.append(f"{name}: ran no test")
    for case in cases:
        for outcome in ("failure", "error", "skipped"):
            if case.find(outcome) is not None:
                problems.append(f"{name}#{case.get('name', '<no name>')}: {outcome}")
    for count in ("failures", "errors", "skipped"):
        if int(suite.get(count, "0") or 0) != 0:
            problems.append(f"{name}: the suite counts {suite.get(count)} {count}")
    if int(suite.get("tests", "0") or 0) != len(cases):
        problems.append(f"{name}: the suite counts {suite.get('tests')} tests and lists {len(cases)}")
    return len(cases), problems


def publish(results: Path, out: Path, since: datetime, gradle_exit: int) -> tuple[Path | None, list[str], dict[str, int]]:
    """Writes the row when every check holds. Returns the fragment, or None and every reason not."""
    target = fragment_path(out)
    target.unlink(missing_ok=True)
    problems: list[str] = []
    counts: dict[str, int] = {}
    if gradle_exit != 0:
        problems.append(f"Gradle exited {gradle_exit}")
    for name in CLASSES:
        counts[name], found = read_class(results, name, since)
        problems += found
    if problems:
        return None, problems, counts
    ran = ", ".join(f"{name.rsplit('.', 1)[1]} {count}" for name, count in counts.items())
    row = {
        "id": ROW_ID,
        "device": DEVICE,
        "value": True,
        "unit": UNIT,
        "samples": None,
        "method": f"tools/ci/upstream_lossless_row.sh: :backup:testDebugUnitTest --rerun, this run's JUnit XML "
                  f"({ran} test(s), none failed or skipped)",
        "recordedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    out.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(row, indent=2) + "\n")
    return target, [], counts


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=f"Publish {ROW_ID} from this run's JUnit XML.")
    parser.add_argument("results", type=Path, help=":backup's build/test-results/testDebugUnitTest")
    parser.add_argument("--out", required=True, type=Path, help="where the host fragment goes")
    parser.add_argument("--since", required=True, type=int, metavar="EPOCH_SECONDS",
                        help="when the caller started Gradle; an older suite is not this run's")
    parser.add_argument("--gradle-exit", required=True, type=int, help="Gradle's exit status")
    args = parser.parse_args(argv)

    since = datetime.fromtimestamp(args.since, timezone.utc)
    target, problems, counts = publish(args.results, args.out, since, args.gradle_exit)
    for name in CLASSES:
        print(f"    {counts.get(name, 0):>3}  {name}")
    sys.stdout.flush()
    if target is None:
        for problem in problems:
            print(f"    {problem}", file=sys.stderr)
        print(f"{ROW_ID} not published: {len(problems)} reason(s) above", file=sys.stderr)
        return REFUSED
    print(f"    {ROW_ID} = true on {DEVICE}, written to {target}")
    return PUBLISHED


if __name__ == "__main__":
    raise SystemExit(main())
