#!/usr/bin/env python3
"""Publishes one library update row from one run of unit A's frozen harness, as a bench fragment.

Unit F of phase2-start.md, ruling R66. tools/ci/library_update_rows.sh runs
tools/bench/library_update.sh against the build under test on the 4 GB AVD, once per scenario, and
hands each run's JSON to this, which writes the row that scenario measures into the bench output in
tools/bench/common.sh's fragment() shape (id, device, value, unit, samples, method, recordedAt).
gate.sh step 8d's collect.py --bench reads it from there.

    scenario  row                              the run's field
    2000x20   library_update_2000x20_ms        duration_ms, the fixture server's window
    500x2     library_update_throughput_ratio  min_rate_ratio, the slower source's rate over its limit

Nothing is published from a run that did not measure (R36). The row's fragment is removed before the
run is read, so a refused run cannot leave an earlier run's value behind to be collected as this
one's. A run is published only when its JSON is the run this step asked for, on the app, scenario,
device and snapshot it names, says `status: ok` with no reason, and carries a positive finite number
in the row's field.

Exit codes: 0 published; 2 the JSON is not a run this reads (not JSON, not an object, another app,
scenario, device or snapshot, a status this does not know, or a measured run with no usable value);
3 the run did not measure: no JSON at all, or `status: ended` with its reasons, which ends the gate
run (R36). Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

PUBLISHED, BAD_INPUT, UNMEASURABLE = 0, 2, 3
APP = "saikou"


@dataclass(frozen=True)
class Row:
    id: str
    field: str
    unit: str


# The units are rows.json's; the tests hold them to it.
ROWS = {
    "2000x20": Row("library_update_2000x20_ms", "duration_ms", "ms"),
    "500x2": Row("library_update_throughput_ratio", "min_rate_ratio", "ratio"),
}


class Refused(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


def fragment_path(out: Path, row: Row, device: str) -> Path:
    """Where fragment() would write the row: `<id>.<device>.json` in the device's bench output."""
    return out / f"{row.id}.{device}.json"


def usable(value: object) -> bool:
    return (isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
            and value > 0)


def measured_value(run: object, scenario: str, device: str, snapshot: str) -> float | int:
    """The row's value from a run's JSON, or Refused with the reason it cannot be published."""
    if not isinstance(run, dict):
        raise Refused(BAD_INPUT, "the run's JSON is not an object")
    expected = {"app": APP, "scenario": scenario, "device": device, "snapshot": snapshot}
    for key, want in expected.items():
        if run.get(key) != want:
            raise Refused(BAD_INPUT, f"the run's {key} is {run.get(key)!r}, where this step ran {want!r}")
    status, reasons = run.get("status"), run.get("reasons")
    if status == "ended":
        why = "; ".join(str(reason) for reason in reasons) if isinstance(reasons, list) and reasons else "no reason given"
        raise Refused(UNMEASURABLE, f"the run ended (R36): {why}")
    if status != "ok":
        raise Refused(BAD_INPUT, f"the run's status is {status!r}, which is neither ok nor ended")
    if reasons != []:
        raise Refused(BAD_INPUT, f"the run says ok and lists reasons it ended: {reasons!r}")
    row = ROWS[scenario]
    value = run.get(row.field)
    if not usable(value):
        raise Refused(BAD_INPUT, f"the run says ok and its {row.field} is {value!r}, not a positive number")
    return value


def method(run: dict, scenario: str, snapshot: str, apk_sha256: str | None) -> str:
    """What the fragment says measured it: the harness, the snapshot, the build and both windows."""
    row = ROWS[scenario]
    build = f", build SHA-256 {apk_sha256[:12]}" if apk_sha256 else ""
    text = (f"tools/bench/library_update.sh {APP} {scenario} (unit A's harness, frozen by R66) from the {snapshot} "
            f"snapshot{build}, {row.field} of the run; server window {run.get('server_window_ms')} ms, device window "
            f"{run.get('device_window_ms')} ms, {run.get('window_difference_pct')} percent apart")
    # The throughput row is the slower of two sources, so the other one's rate is worth keeping beside it.
    sources = run.get("per_source")
    if scenario == "500x2" and isinstance(sources, dict) and sources:
        rates = ", ".join(f"s{name} {entry.get('rps')}" for name, entry in sorted(sources.items())
                          if isinstance(entry, dict))
        text += f"; requests per second by source: {rates}"
    return text


def fragment(run: dict, scenario: str, device: str, snapshot: str, value: float | int,
             apk_sha256: str | None) -> dict:
    """The row in fragment()'s shape. recordedAt is the run's own, when it gave one."""
    recorded = run.get("recordedAt")
    if not isinstance(recorded, str) or not recorded:
        recorded = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    row = ROWS[scenario]
    return {
        "id": row.id,
        "device": device,
        "value": value,
        "unit": row.unit,
        "samples": None,
        "method": method(run, scenario, snapshot, apk_sha256),
        "recordedAt": recorded,
    }


def publish(run_path: Path, scenario: str, device: str, snapshot: str, out: Path,
            apk_sha256: str | None = None) -> Path:
    """Writes the scenario's row from the run at run_path, or raises Refused having written nothing."""
    target = fragment_path(out, ROWS[scenario], device)
    target.unlink(missing_ok=True)
    if not run_path.is_file():
        raise Refused(UNMEASURABLE, f"no run JSON at {run_path}, so the run left nothing to publish")
    try:
        run = json.loads(run_path.read_text())
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise Refused(BAD_INPUT, f"the run's JSON at {run_path} is not JSON: {exc}") from exc
    value = measured_value(run, scenario, device, snapshot)
    out.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(fragment(run, scenario, device, snapshot, value, apk_sha256), indent=2) + "\n")
    return target


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Publish one library update row from one harness run.")
    parser.add_argument("run", type=Path, help="the JSON library_update.sh printed and kept for the run")
    parser.add_argument("--scenario", required=True, choices=sorted(ROWS))
    parser.add_argument("--device", required=True, help="the gate's device id, as SAIKOU_DEVICE_ID")
    parser.add_argument("--snapshot", required=True, help="the AVD snapshot the run was asked to load")
    parser.add_argument("--out", required=True, type=Path, help="the device's bench output directory")
    parser.add_argument("--apk-sha256", help="the build under test, named in the fragment's method")
    args = parser.parse_args(argv)

    row = ROWS[args.scenario]
    try:
        target = publish(args.run, args.scenario, args.device, args.snapshot, args.out, args.apk_sha256)
    except Refused as refused:
        what = "did not measure" if refused.code == UNMEASURABLE else "is not a run this reads"
        print(f"{row.id} not published: the {args.scenario} run at {args.run} {what}: {refused}", file=sys.stderr)
        return refused.code
    value = json.loads(target.read_text())["value"]
    print(f"    {row.id} = {value} {row.unit} on {args.device}, written to {target}")
    return PUBLISHED


if __name__ == "__main__":
    raise SystemExit(main())
