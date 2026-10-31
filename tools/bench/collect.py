#!/usr/bin/env python3
"""Merges bench fragments, the compat report and instrumentation fragments into measured.json.

Contract 01, section 7.5. Python 3.11+, standard library only. The output is exactly what
release_gate.py consumes.

Exit codes: 0 ok; 2 missing input.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA_VERSION = 1
TOOLS = Path(__file__).resolve().parents[1]
DEFAULT_DEVICE_FILE = TOOLS / "gate" / "baseline.json"
DEVICE_TMP = "/data/local/tmp/saikou-gate"


def die(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(2)


def fragment_rows(directory: Path) -> list[dict]:
    """Reads every `<id>.<device>.json` fragment under a directory tree."""
    rows = []
    for fragment in sorted(directory.rglob("*.json")):
        try:
            data = json.loads(fragment.read_text())
        except json.JSONDecodeError as exc:
            die(f"invalid json in {fragment}: {exc}")
        if isinstance(data, dict) and "id" in data and "value" in data:
            rows.append(data)
    return rows


def compat_rows(report_path: Path) -> list[dict]:
    report = json.loads(report_path.read_text())
    sample = report.get("sampleSize") or 0
    loaded = report.get("loadedRatio")
    if loaded is None and sample:
        loaded = (sample - len(report.get("unresolvedJars", []))) / sample
    replay = report.get("replay", {})
    calls = replay.get("calls", [])
    return [
        {
            "id": "compat_sample_loaded_ratio",
            "device": "any",
            "value": loaded,
            "unit": "ratio",
            "method": f"compat_job.py {report_path.name}, sample {sample}",
            "recordedAt": report.get("ranAt"),
        },
        {
            "id": "compat_reference_runs_pass",
            "device": "any",
            "value": bool(replay.get("ran")) and bool(calls) and all(c.get("ok") for c in calls),
            "unit": "bool",
            "method": f"compat_job.py --replay {report_path.name}",
            "recordedAt": report.get("ranAt"),
        },
    ]


def instrumentation_rows(serial: str, out: Path) -> list[dict]:
    """Pulls one device's gate fragments into a directory of its own and reads them.

    Each serial gets `<out>/instrumentation/<serial>/`, emptied first. Two devices publish fragments
    under the same file names, so a shared directory would let the second pull overwrite the first
    and read the first device's rows twice; and a directory kept from an earlier collect would
    hand back fragments the device no longer holds.
    """
    pulled = out / "instrumentation" / serial
    shutil.rmtree(pulled, ignore_errors=True)
    pulled.mkdir(parents=True)
    adb = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "")
    adb = str(adb / "platform-tools" / "adb") if adb.name else "adb"
    result = subprocess.run([adb, "-s", serial, "pull", DEVICE_TMP, str(pulled)],
                            capture_output=True, text=True, check=False)
    if result.returncode != 0:
        print(f"warning: no instrumentation fragments on {serial}", file=sys.stderr)
        return []
    return fragment_rows(pulled)


def main() -> int:
    parser = argparse.ArgumentParser(description="Merge measurement fragments.")
    parser.add_argument("--bench", type=Path, required=True, help="bench output directory")
    parser.add_argument("--compat", type=Path, help="compat report.json or a directory holding one")
    parser.add_argument("--instrumentation", action="append", default=[], metavar="SERIAL",
                        help="device serial to pull gate fragments from; repeat once per device")
    parser.add_argument("--devices", type=Path, default=DEFAULT_DEVICE_FILE,
                        help="file holding the device table; default the recorded baseline")
    parser.add_argument("--subject", required=True, help="package:version:commit")
    parser.add_argument("--kind", default="measured", choices=["measured", "baseline"])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    if not args.bench.is_dir():
        die(f"missing: {args.bench}")
    rows = fragment_rows(args.bench)
    if args.compat:
        report = args.compat
        if report.is_dir():
            report = next(iter(sorted(report.rglob("report.json"), reverse=True)), None)
        if report is None or not report.is_file():
            die(f"missing: report.json under {args.compat}")
        rows += compat_rows(report)
    # Blank is absent (R29), and here it would also name the parent of every device's directory. A
    # serial given twice would be pulled twice and its rows counted twice.
    if any(not serial.strip() for serial in args.instrumentation):
        die("missing: --instrumentation needs a device serial")
    if len(set(args.instrumentation)) != len(args.instrumentation):
        die(f"--instrumentation names a serial more than once: {' '.join(args.instrumentation)}")
    for serial in args.instrumentation:
        rows += instrumentation_rows(serial, args.out.parent)

    try:
        package, version, commit = args.subject.split(":")
    except ValueError:
        die("missing: --subject package:version:commit")

    devices: dict[str, dict] = {}
    if args.devices.is_file():
        devices.update(json.loads(args.devices.read_text()).get("devices", {}))
    for row in rows:
        devices.setdefault(row.get("device", "any"), {})
    devices.pop("any", None)

    measured = {
        "schemaVersion": SCHEMA_VERSION,
        "kind": args.kind,
        "recordedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "subject": {"package": package, "versionName": version, "commit": commit},
        "devices": devices,
        "rows": rows,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(measured, indent=2) + "\n")
    print(f"{len(rows)} rows written to {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
