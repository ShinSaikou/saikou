#!/usr/bin/env python3
"""Release gate: compares a measured JSON against the baseline row by row.

Contract 01, section 7.3. Python 3.11+, standard library only.

Exit codes: 0 all required rows pass; 1 at least one required row fails; 2 a file is missing, has
the wrong schemaVersion, a row references a device absent from the measured file, or --rows names an
id no row has; 3 a required row is missing from the measured file, or has no value its comparator
can score.

A row binds when its phase is at or below --phase, so a phase 2 verdict binds every phase 0 and
phase 1 row as well as phase 2's own: PLAN's definition of done for phase 2 names the reader rows
and the 3,000-entry import, which are phase 0 and 1 rows. tools/gate/bound_rows.py reads the same
binding to end a gate run that lacks a value before this runs.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

SCHEMA_VERSION = 1
ROWS_FILE = Path(__file__).with_name("rows.json")

# A row's direction has to agree with its comparator, so a threshold can never be read backwards.
DIRECTION_BY_COMPARATOR = {
    "ratio_max": "lower",
    "abs_max": "lower",
    "abs_min": "higher",
    "bool_true": "boolean",
    "bool_false": "boolean",
}


def value_problem(comparator: str, value: object) -> str | None:
    """Why `value` cannot be scored by `comparator`, or None when it can.

    A boolean published for a timed row would read as 0 or 1 milliseconds and pass, a number
    published for a boolean row would fail as if it were false, and text, NaN or an infinity would
    compare as nonsense or crash the comparison. None of them is a measurement of the row (R36).
    """
    if value is None:
        return "no value recorded"
    if DIRECTION_BY_COMPARATOR.get(comparator) == "boolean":
        return None if isinstance(value, bool) else f"not a boolean: {value!r}"
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        return f"not a number: {value!r}"
    return None


def die(code: int, message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def load(path: Path, kinds: tuple[str, ...] | None = None) -> dict:
    if not path.is_file():
        die(2, f"missing: {path}")
    try:
        data = json.loads(path.read_text())
    except json.JSONDecodeError as exc:
        die(2, f"invalid json in {path}: {exc}")
    if data.get("schemaVersion") != SCHEMA_VERSION:
        die(2, f"{path}: schemaVersion {data.get('schemaVersion')} is not {SCHEMA_VERSION}")
    if kinds is not None and data.get("kind") not in kinds:
        die(2, f"{path}: kind {data.get('kind')} is not one of {', '.join(kinds)}")
    return data


def load_definitions() -> list[dict]:
    """Expands rows.json into one definition per (id, device) pair, checking each row."""
    definitions = []
    for row in load(ROWS_FILE)["rows"]:
        comparator = row["comparator"]
        expected = DIRECTION_BY_COMPARATOR.get(comparator)
        if expected is None:
            die(2, f"unknown comparator {comparator} for row {row['id']}")
        if row.get("direction") != expected:
            die(2, f"{row['id']}: direction {row.get('direction')} does not fit {comparator}")
        if expected != "boolean" and row.get("threshold") is None:
            die(2, f"{row['id']}: {comparator} needs a threshold")
        for device in row["devices"]:
            definitions.append({**row, "device": device})
    return definitions


def index_rows(data: dict) -> dict[tuple[str, str], dict]:
    return {(row["id"], row.get("device", "any")): row for row in data.get("rows", [])}


def evaluate(definition: dict, measured: dict | None, baseline: dict | None) -> tuple[str, str]:
    """Returns (status, detail). Status is PASS, FAIL or MISSING.

    A row that is absent, or present with a null value (the shape the baseline uses to record a
    measurement that could not be taken, with a reason beside it), is MISSING: never a silent pass.
    So is a value of the wrong kind for its comparator (value_problem).
    """
    if measured is None:
        return "MISSING", "measured=<absent>"
    value = measured.get("value")
    comparator = definition["comparator"]
    threshold = definition.get("threshold")
    base = baseline.get("value") if baseline else None
    detail = f"measured={value} baseline={base} threshold={threshold}"
    if value is None:
        reason = measured.get("reason")
        return "MISSING", detail + (f" ({reason})" if reason else " (no value recorded)")
    problem = value_problem(comparator, value)
    if problem is not None:
        return "MISSING", detail + f" ({problem})"
    match comparator:
        case "ratio_max":
            if base in (None, 0):
                return "FAIL", detail + " (no baseline)"
            return ("PASS" if value / base <= threshold else "FAIL"), detail
        case "abs_max":
            return ("PASS" if value <= threshold else "FAIL"), detail
        case "abs_min":
            return ("PASS" if value >= threshold else "FAIL"), detail
        case "bool_true":
            return ("PASS" if value is True else "FAIL"), detail
        case "bool_false":
            return ("PASS" if value is False else "FAIL"), detail
    die(2, f"unknown comparator {comparator} for row {definition['id']}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare a measured JSON against the baseline.")
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--measured", type=Path, required=True)
    parser.add_argument("--phase", type=int, required=True, choices=[0, 1, 2, 3])
    parser.add_argument("--rows", help="comma separated row ids to restrict the run to")
    parser.add_argument("--strict", action="store_true", help="later-phase rows bind too")
    parser.add_argument("--json", dest="json_out", type=Path)
    args = parser.parse_args()

    definitions = load_definitions()
    only = set(args.rows.split(",")) if args.rows else None
    # An id that names no row would filter every row out and score nothing, which reads as a pass.
    unknown = sorted(only - {definition["id"] for definition in definitions}) if only else []
    if unknown:
        die(2, "--rows names no row: " + ", ".join(repr(row_id) for row_id in unknown))
    baseline = load(args.baseline, ("baseline",))
    # A baseline may stand in as the measured file, so a freshly recorded baseline can be checked
    # against itself without being renamed first.
    measured = load(args.measured, ("measured", "baseline"))
    baseline_rows = index_rows(baseline)
    measured_rows = index_rows(measured)
    devices = measured.get("devices", {})

    results: list[dict] = []
    failed = False
    missing_required = False

    for definition in definitions:
        row_id = definition["id"]
        device = definition["device"]
        if only is not None and row_id not in only:
            continue
        key = (row_id, device)
        binding = definition["phase"] <= args.phase or args.strict
        row = measured_rows.get(key)
        if device != "any" and device not in devices and (binding or row is not None):
            die(2, f"{args.measured}: device {device} referenced by {row_id} is absent")
        if not binding and row is None:
            status, detail = "DEFERRED", "measured=<absent>"
        else:
            status, detail = evaluate(definition, row, baseline_rows.get(key))
            if not binding and status != "PASS":
                status = "DEFERRED"
        if binding and status == "FAIL":
            failed = True
        if binding and status == "MISSING":
            missing_required = True
        print(f"{status:<9}{row_id}  {device}  {detail}")
        results.append({
            "id": row_id,
            "device": device,
            "label": definition["label"],
            "phase": definition["phase"],
            "rule": definition["rule"],
            "status": status,
            "detail": detail,
        })

    if args.json_out:
        args.json_out.write_text(json.dumps(results, indent=2) + "\n")

    if missing_required:
        return 3
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
