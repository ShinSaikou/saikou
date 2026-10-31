#!/usr/bin/env python3
"""Ends a gate run that lacks a value for a row its phase binds (ruling R36).

collect.py merges every fragment it finds, with no list of the rows it should have found, so a row
whose step was skipped, whose test returned early or whose fragment never landed simply is not in
measured.json. release_gate.py then reports it MISSING among the rows it scored. This check runs
before the release gate in gate.sh's phase 2 and answers one question on its own: does every
(row, device) pair the phase binds have exactly one value its comparator can score? When it does
not, it names each gap with what rows.json says measures the row, and the run ends there.

The binding is release_gate.py's: a row binds when its phase in rows.json is at or below the run's
phase, so phase 2 binds the phase 0 and phase 1 rows as well as its own. The gaps:

  - absent: no row for the pair in measured.json;
  - no value: a row with a null value, the shape for a measurement that could not be taken, shown
    with its reason;
  - not a value of the row's kind: a boolean for a numeric row, a number for a boolean row, text,
    NaN or an infinity (release_gate.value_problem);
  - published more than once: two rows for one pair, of which release_gate.py would score the last
    without saying there was another;
  - an undescribed device: a bound device measured.json's device table does not name, which
    release_gate.py refuses as an input error.

Exit codes: 0 every bound row has one usable value; 2 a file is missing or unreadable, or the phase
is unknown; 3 at least one gap. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import sys
from collections import defaultdict
from pathlib import Path

# release_gate.py sits beside this file; run as a script, its directory is already on the path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from release_gate import load, load_definitions, value_problem  # noqa: E402

COMPLETE, BAD_INPUT, GAPS = 0, 2, 3
PHASES = (0, 1, 2, 3)


def gaps(definitions: list[dict], measured: dict, phase: int) -> tuple[int, list[str]]:
    """The number of bound pairs, and one line per gap among them, in rows.json's order."""
    rows: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for row in measured.get("rows", []):
        rows[(row.get("id"), row.get("device", "any"))].append(row)
    devices = measured.get("devices", {})
    bound = [definition for definition in definitions if definition["phase"] <= phase]
    found: list[str] = []
    for definition in bound:
        row_id, device = definition["id"], definition["device"]
        where = f"{row_id}  {device}"
        source = f"measured by: {definition.get('measuredBy', 'not stated in rows.json')}"
        published = rows.get((row_id, device), [])
        if device != "any" and device not in devices:
            found.append(f"{where}  the device is not in measured.json's device table ({source})")
        elif not published:
            found.append(f"{where}  absent from measured.json ({source})")
        elif len(published) > 1:
            values = ", ".join(repr(row.get("value")) for row in published)
            found.append(f"{where}  published {len(published)} times ({values}); one measurement is one row")
        else:
            row = published[0]
            problem = value_problem(definition["comparator"], row.get("value"))
            if problem is not None:
                reason = row.get("reason")
                found.append(f"{where}  {problem}{f': {reason}' if reason else ''} ({source})")
    return len(bound), found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="End a run that lacks a value for a bound row.")
    parser.add_argument("--measured", type=Path, required=True)
    parser.add_argument("--phase", type=int, required=True, choices=PHASES)
    args = parser.parse_args(argv)

    try:
        definitions = load_definitions()
        measured = load(args.measured, ("measured", "baseline"))
    except SystemExit:
        # release_gate's loaders print their own message and exit 2.
        return BAD_INPUT
    count, found = gaps(definitions, measured, args.phase)
    print(f"    {count} (row, device) pair(s) bound in phase {args.phase}, {count - len(found)} with one usable value")
    for line in found:
        print(f"    GAP  {line}")
    if found:
        # After the gap lines, not before them, when both streams go to one log.
        sys.stdout.flush()
        print(f"    {len(found)} bound row(s) have no usable value in {args.measured}, so the run ends here (R36)",
              file=sys.stderr)
        return GAPS
    return COMPLETE


if __name__ == "__main__":
    raise SystemExit(main())
