#!/usr/bin/env python3
"""Publishes release_build_owner_signed from verifyReleaseSigning's report, as a host fragment.

Unit R1 of design/plans/phase3-start.md and Q18. tools/ci/release_signing_row.sh runs
`:app:verifyReleaseSigning -Psaikou.ownerSigning=true` and hands its report to this, which writes the
row into the bench output in tools/bench/common.sh's fragment() shape (id, device, value, unit,
samples, method, recordedAt), with the device `any` that rows.json binds it to. gate.sh step 8d's
collect.py --bench reads it from there.

The row is true when the release was built with the flag and the certificate of the alias
keystore.properties names matches every signer apksigner reports in each of API 26 to 27, 28 to 32 and
33 and up. Under Q15 that key is the debug key the owner's devices trust, so the row says the release
came from the owner's keystore with no fallback. The fragment is removed before anything is read, and
a refused run writes nothing at all (R36: an unmeasured row is named by bound_rows.py rather than
published as false). The report has to:

  - exist, since the caller deletes it before Gradle runs;
  - have been written no earlier than --since, the second the caller started Gradle (R29: a report an
    earlier run left is not this run's);
  - say `ownerSigning: true`, name an APK called app-release.apk, and list exactly the three ranges, in
    order, each `match`.

Gradle's exit status is passed in as well (--gradle-exit): a failed build publishes nothing even when
the report reads as passed.

Exit codes: 0 published; 1 refused, each reason printed; 2 bad arguments. Python 3.11+, standard
library only.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

PUBLISHED, REFUSED, BAD_INPUT = 0, 1, 2
ROW_ID = "release_build_owner_signed"
DEVICE = "any"
UNIT = "bool"
# verifyReleaseSigning's ranges (build-logic's ReleaseSigners.RANGES), as the report keys them.
# tools/test_release_signing_row.py holds this to the Kotlin source.
RANGES = ("26-27", "28-32", "33-")
APK_NAME = "app-release.apk"


def fragment_path(out: Path) -> Path:
    """Where fragment() would write the row: `<id>.<device>.json` in the output directory."""
    return out / f"{ROW_ID}.{DEVICE}.json"


def read_report(report: Path, since: datetime) -> tuple[dict | None, list[str]]:
    if not report.is_file():
        return None, [f"no {report.name} at {report.parent}, so verifyReleaseSigning did not write one in this run"]
    written = datetime.fromtimestamp(report.stat().st_mtime, timezone.utc)
    problems: list[str] = []
    # The file system keeps sub-second times and --since is whole seconds, so a report written in the
    # second Gradle started compares equal or later, never earlier.
    if written < since:
        problems.append(f"{report.name} was written at {written.isoformat()}, before this run started at "
                        f"{since.isoformat()}, so it is not this run's")
    try:
        data = json.loads(report.read_text())
    except json.JSONDecodeError as exc:
        return None, problems + [f"{report.name} is not JSON: {exc}"]
    if not isinstance(data, dict):
        return None, problems + [f"{report.name} holds no JSON object"]
    return data, problems


def check(data: dict) -> list[str]:
    problems: list[str] = []
    if data.get("ownerSigning") is not True:
        problems.append("the release was not built with -Psaikou.ownerSigning=true")
    apk = data.get("apk")
    if not isinstance(apk, str) or Path(apk).name != APK_NAME:
        problems.append(f"the report names {apk!r}, not {APK_NAME}")
    ranges = data.get("ranges")
    if not isinstance(ranges, list):
        return problems + ["the report lists no ranges"]
    keys = tuple(entry.get("range") for entry in ranges if isinstance(entry, dict))
    if keys != RANGES:
        problems.append(f"the report lists the ranges {list(keys)}, not {list(RANGES)}")
    for entry in ranges:
        if isinstance(entry, dict) and entry.get("result") != "match":
            reason = f" ({entry['reason']})" if entry.get("reason") else ""
            problems.append(f"API {entry.get('range')}: {entry.get('result')}{reason}")
    return problems


def publish(report: Path, out: Path, since: datetime, gradle_exit: int) -> tuple[Path | None, list[str]]:
    """Writes the row when every check holds. Returns the fragment, or None and every reason not."""
    target = fragment_path(out)
    target.unlink(missing_ok=True)
    problems: list[str] = []
    if gradle_exit != 0:
        problems.append(f"Gradle exited {gradle_exit}")
    data, found = read_report(report, since)
    problems += found
    if data is not None:
        problems += check(data)
    if problems:
        return None, problems
    row = {
        "id": ROW_ID,
        "device": DEVICE,
        "value": True,
        "unit": UNIT,
        "samples": None,
        "method": "tools/ci/release_signing_row.sh: :app:verifyReleaseSigning -Psaikou.ownerSigning=true, the alias "
                  "keystore.properties names against every apksigner signer of app-release.apk in API 26 to 27, "
                  "28 to 32 and 33 and up: match in each",
        "recordedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    out.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(row, indent=2) + "\n")
    return target, []


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=f"Publish {ROW_ID} from verifyReleaseSigning's report.")
    parser.add_argument("report", type=Path, help="app/build/outputs/release-signing/verifyReleaseSigning.json")
    parser.add_argument("--out", required=True, type=Path, help="where the host fragment goes")
    parser.add_argument("--since", required=True, type=int, metavar="EPOCH_SECONDS",
                        help="when the caller started Gradle; an older report is not this run's")
    parser.add_argument("--gradle-exit", required=True, type=int, help="Gradle's exit status")
    args = parser.parse_args(argv)

    since = datetime.fromtimestamp(args.since, timezone.utc)
    target, problems = publish(args.report, args.out, since, args.gradle_exit)
    if target is None:
        for problem in problems:
            print(f"    {problem}", file=sys.stderr)
        print(f"{ROW_ID} not published: {len(problems)} reason(s) above", file=sys.stderr)
        return REFUSED
    print(f"    {ROW_ID} = true on {DEVICE}, written to {target}")
    return PUBLISHED


if __name__ == "__main__":
    raise SystemExit(main())
