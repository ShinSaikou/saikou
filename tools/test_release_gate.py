#!/usr/bin/env python3
"""Unit tests for the release gate. Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Phase2VerdictTest is unit L's Done line for phase2-start.md: `release_gate.py --phase 2` over a
synthetic measured.json, built from rows.json with one passing value per bound pair, passes on the
complete file, binds the phase 0 and 1 rows as well as phase 2's, and fails on each kind of gap.
Phase3VerdictTest is the same for `--phase 3`, which gate.sh passes since unit L of
phase3-start.md: every row binds, none is deferred, and a missing row of phase 2 or 3 fails.
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
GATE = TOOLS / "gate" / "release_gate.py"
ROWS = json.loads((TOOLS / "gate" / "rows.json").read_text())

DEVICES = {
    "pixel8a_2g": {"avd": "saikou_pixel8a_2g", "api": 36, "ramMb": 2048},
    "pixel9_4g": {"avd": "saikou_pixel9_4g", "api": 36, "ramMb": 4096},
    "tablet_4g": {"avd": "saikou_tablet_4g", "api": 36, "ramMb": 4096},
    "lowram_1_5g": {"avd": "saikou_lowram_1_5g", "api": 28, "ramMb": 1536},
}


def document(kind: str, rows: list[dict]) -> dict:
    return {
        "schemaVersion": 1,
        "kind": kind,
        "recordedAt": "2026-09-11T00:00:00Z",
        "subject": {"package": "upstream", "versionName": "0.20.4", "commit": "df65072"},
        "devices": DEVICES,
        "rows": rows,
    }


class GateRun:
    """One invocation of release_gate.py against throwaway files."""

    def __init__(self, baseline: list[dict], measured: list[dict], phase: int, extra: list[str]):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "baseline.json"
            meas = Path(directory) / "measured.json"
            base.write_text(json.dumps(document("baseline", baseline)))
            meas.write_text(json.dumps(document("measured", measured)))
            result = subprocess.run(
                [sys.executable, str(GATE), "--baseline", str(base), "--measured", str(meas),
                 "--phase", str(phase), *extra],
                capture_output=True, text=True,
            )
        self.code = result.returncode
        self.out = result.stdout
        self.err = result.stderr

    def status(self, row_id: str, device: str) -> str:
        for line in self.out.splitlines():
            fields = line.split()
            if len(fields) >= 3 and fields[1] == row_id and fields[2] == device:
                return fields[0]
        raise AssertionError(f"{row_id} on {device} is not in the output:\n{self.out}")


def run(baseline: list[dict], measured: list[dict], phase: int = 0, *extra: str) -> GateRun:
    return GateRun(baseline, measured, phase, list(extra))


class RowDefinitionsTest(unittest.TestCase):

    def test_every_phase_0_row_of_the_plan_is_defined(self):
        phase0 = {(row["id"], device) for row in ROWS["rows"] if row["phase"] == 0
                  for device in row["devices"]}
        self.assertEqual(phase0, {
            ("cold_start_ms", "pixel8a_2g"),
            ("cold_start_ms", "pixel9_4g"),
            ("webtoon_jank_pct", "pixel8a_2g"),
            ("webtoon_jank_pct", "pixel9_4g"),
            ("tablet_landscape_webtoon_jank_pct", "tablet_4g"),
            ("page_turn_jank_pct", "pixel8a_2g"),
            ("peak_pss_mb", "pixel9_4g"),
            ("reader_crash_count", "pixel8a_2g"),
            ("reader_crash_count", "pixel9_4g"),
            ("reader_crash_count", "tablet_4g"),
            ("compat_sample_loaded_ratio", "any"),
            ("compat_reference_runs_pass", "any"),
        })

    def test_every_row_carries_the_fields_the_gate_reports(self):
        for row in ROWS["rows"]:
            for field in ("id", "label", "unit", "direction", "comparator", "rule", "devices", "phase"):
                self.assertIn(field, row, f"{row.get('id')} is missing {field}")

    def test_a_direction_that_contradicts_its_comparator_is_rejected(self):
        rows_file = TOOLS / "gate" / "rows.json"
        original = rows_file.read_text()
        broken = json.loads(original)
        broken["rows"][0]["direction"] = "higher"
        try:
            rows_file.write_text(json.dumps(broken))
            outcome = run([], [], 0)
            self.assertEqual(outcome.code, 2)
            self.assertIn("direction", outcome.err)
        finally:
            rows_file.write_text(original)


class ReleaseGateTest(unittest.TestCase):

    def test_a_row_under_its_absolute_threshold_passes(self):
        outcome = run([], [{"id": "peak_pss_mb", "device": "pixel9_4g", "value": 312}],
                      0, "--rows", "peak_pss_mb")
        self.assertEqual(outcome.status("peak_pss_mb", "pixel9_4g"), "PASS")
        self.assertEqual(outcome.code, 0)

    def test_a_row_over_its_absolute_threshold_fails(self):
        outcome = run([], [{"id": "peak_pss_mb", "device": "pixel9_4g", "value": 581}],
                      0, "--rows", "peak_pss_mb")
        self.assertEqual(outcome.status("peak_pss_mb", "pixel9_4g"), "FAIL")
        self.assertEqual(outcome.code, 1)

    def test_a_relative_row_is_measured_against_the_baseline(self):
        baseline = [{"id": "cold_start_ms", "device": "pixel8a_2g", "value": 1000},
                    {"id": "cold_start_ms", "device": "pixel9_4g", "value": 1000}]
        within = run(
            baseline,
            [{"id": "cold_start_ms", "device": "pixel8a_2g", "value": 1050},
             {"id": "cold_start_ms", "device": "pixel9_4g", "value": 900}],
            0, "--rows", "cold_start_ms",
        )
        self.assertEqual(within.status("cold_start_ms", "pixel8a_2g"), "PASS")
        self.assertEqual(within.code, 0)

        beyond = run(
            baseline,
            [{"id": "cold_start_ms", "device": "pixel8a_2g", "value": 1051},
             {"id": "cold_start_ms", "device": "pixel9_4g", "value": 900}],
            0, "--rows", "cold_start_ms",
        )
        self.assertEqual(beyond.status("cold_start_ms", "pixel8a_2g"), "FAIL")
        self.assertEqual(beyond.code, 1)

    def test_a_relative_row_without_a_baseline_fails(self):
        outcome = run([], [{"id": "cold_start_ms", "device": "pixel8a_2g", "value": 900},
                           {"id": "cold_start_ms", "device": "pixel9_4g", "value": 900}],
                      0, "--rows", "cold_start_ms")
        self.assertEqual(outcome.status("cold_start_ms", "pixel8a_2g"), "FAIL")
        self.assertIn("no baseline", outcome.out)
        self.assertEqual(outcome.code, 1)

    def test_a_later_phase_row_is_deferred(self):
        outcome = run([], [{"id": "peak_pss_mb", "device": "pixel9_4g", "value": 312}],
                      0, "--rows", "peak_pss_mb,restore_3000_ms")
        self.assertEqual(outcome.status("restore_3000_ms", "pixel9_4g"), "DEFERRED")
        self.assertEqual(outcome.code, 0)

    def test_a_later_phase_row_that_is_measured_and_bad_is_still_only_deferred(self):
        outcome = run([], [{"id": "restore_3000_ms", "device": "pixel9_4g", "value": 999999}],
                      0, "--rows", "restore_3000_ms")
        self.assertEqual(outcome.status("restore_3000_ms", "pixel9_4g"), "DEFERRED")
        self.assertEqual(outcome.code, 0)

    def test_strict_makes_a_later_phase_row_bind(self):
        outcome = run([], [{"id": "restore_3000_ms", "device": "pixel9_4g", "value": 999999}],
                      0, "--rows", "restore_3000_ms", "--strict")
        self.assertEqual(outcome.status("restore_3000_ms", "pixel9_4g"), "FAIL")
        self.assertEqual(outcome.code, 1)

    def test_a_bound_row_absent_from_the_measured_file_is_missing(self):
        outcome = run([], [], 0, "--rows", "peak_pss_mb")
        self.assertEqual(outcome.status("peak_pss_mb", "pixel9_4g"), "MISSING")
        self.assertEqual(outcome.code, 3)

    def test_a_bound_row_recorded_as_null_is_missing_and_shows_its_reason(self):
        outcome = run([], [{"id": "peak_pss_mb", "device": "pixel9_4g", "value": None,
                            "reason": "the AVD does not exist"}],
                      0, "--rows", "peak_pss_mb")
        self.assertEqual(outcome.status("peak_pss_mb", "pixel9_4g"), "MISSING")
        self.assertIn("the AVD does not exist", outcome.out)
        self.assertEqual(outcome.code, 3)

    def test_a_boolean_row_reads_true_and_false(self):
        good = run([], [{"id": "compat_reference_runs_pass", "device": "any", "value": True}],
                   0, "--rows", "compat_reference_runs_pass")
        self.assertEqual(good.status("compat_reference_runs_pass", "any"), "PASS")
        self.assertEqual(good.code, 0)
        bad = run([], [{"id": "compat_reference_runs_pass", "device": "any", "value": False}],
                  0, "--rows", "compat_reference_runs_pass")
        self.assertEqual(bad.status("compat_reference_runs_pass", "any"), "FAIL")
        self.assertEqual(bad.code, 1)

    def test_a_rows_id_that_names_no_row_is_an_input_error_naming_every_unknown_id(self):
        outcome = run([], [{"id": "peak_pss_mb", "device": "pixel9_4g", "value": 312}],
                      0, "--rows", "peak_pss_mb,peak_pss_m,restore_3000")
        self.assertEqual(outcome.code, 2)
        self.assertIn("'peak_pss_m'", outcome.err)
        self.assertIn("'restore_3000'", outcome.err)
        self.assertNotIn("'peak_pss_mb'", outcome.err)
        self.assertEqual(outcome.out, "")

    def test_known_rows_ids_still_score_only_those_rows(self):
        outcome = run([], [{"id": "peak_pss_mb", "device": "pixel9_4g", "value": 312}],
                      0, "--rows", "peak_pss_mb")
        self.assertEqual(outcome.code, 0)
        self.assertEqual(outcome.status("peak_pss_mb", "pixel9_4g"), "PASS")
        self.assertEqual(len(outcome.out.splitlines()), 1)

    def test_a_device_absent_from_the_measured_file_is_an_input_error(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "baseline.json"
            meas = Path(directory) / "measured.json"
            base.write_text(json.dumps(document("baseline", [])))
            without = document("measured", [])
            without["devices"] = {"pixel9_4g": DEVICES["pixel9_4g"]}
            meas.write_text(json.dumps(without))
            result = subprocess.run(
                [sys.executable, str(GATE), "--baseline", str(base), "--measured", str(meas),
                 "--phase", "0", "--rows", "page_turn_jank_pct"],
                capture_output=True, text=True,
            )
        self.assertEqual(result.returncode, 2)
        self.assertIn("pixel8a_2g", result.stderr)

    def test_the_wrong_schema_version_is_an_input_error(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "baseline.json"
            meas = Path(directory) / "measured.json"
            stale = document("baseline", [])
            stale["schemaVersion"] = 99
            base.write_text(json.dumps(stale))
            meas.write_text(json.dumps(document("measured", [])))
            result = subprocess.run(
                [sys.executable, str(GATE), "--baseline", str(base), "--measured", str(meas),
                 "--phase", "0"],
                capture_output=True, text=True,
            )
        self.assertEqual(result.returncode, 2)
        self.assertIn("schemaVersion", result.stderr)


def passing_value(row: dict) -> object:
    match row["comparator"]:
        case "bool_true":
            return True
        case "bool_false":
            return False
        case "abs_min":
            return row["threshold"]
        case _:
            # Half an upper threshold; a ratio row is half its threshold against a baseline of 1000.
            return row["threshold"] / 2 * (1000 if row["comparator"] == "ratio_max" else 1)


def complete(phase: int) -> tuple[list[dict], list[dict]]:
    """A baseline for the ratio rows and a measured file with a passing value for every bound pair."""
    baseline = [{"id": row["id"], "device": device, "value": 1000}
                for row in ROWS["rows"] if row["comparator"] == "ratio_max" for device in row["devices"]]
    measured = [{"id": row["id"], "device": device, "value": passing_value(row)}
                for row in ROWS["rows"] if row["phase"] <= phase for device in row["devices"]]
    return baseline, measured


class Phase2VerdictTest(unittest.TestCase):

    def replaced(self, measured: list[dict], row_id: str, device: str, value: object) -> list[dict]:
        return [{**row, "value": value} if (row["id"], row["device"]) == (row_id, device) else row
                for row in measured]

    def test_a_complete_phase_2_file_passes_and_every_bound_row_is_scored(self):
        baseline, measured = complete(2)
        outcome = run(baseline, measured, 2)
        self.assertEqual(outcome.code, 0, outcome.out + outcome.err)
        for row in ROWS["rows"]:
            for device in row["devices"]:
                expected = "PASS" if row["phase"] <= 2 else "DEFERRED"
                self.assertEqual(outcome.status(row["id"], device), expected, f"{row['id']} on {device}")

    def test_phase_2_binds_the_reader_rows_and_the_import_row(self):
        baseline, measured = complete(2)
        for row_id, device in (("page_turn_jank_pct", "pixel8a_2g"), ("webtoon_jank_pct", "pixel9_4g"),
                               ("restore_3000_ms", "pixel9_4g")):
            with self.subTest(row=row_id):
                gap = [row for row in measured if (row["id"], row["device"]) != (row_id, device)]
                outcome = run(baseline, gap, 2)
                self.assertEqual(outcome.status(row_id, device), "MISSING")
                self.assertEqual(outcome.code, 3)
                over = run(baseline, self.replaced(measured, row_id, device, 10 ** 9), 2)
                self.assertEqual(over.status(row_id, device), "FAIL")
                self.assertEqual(over.code, 1)

    def test_each_kind_of_gap_fails_the_phase_2_verdict(self):
        baseline, measured = complete(2)
        cases = {
            "absent": [row for row in measured if row["id"] != "library_update_2000x20_ms"],
            "null": self.replaced(measured, "download_queue_add_ms_at_1000", "pixel9_4g", None),
            "a boolean for a number": self.replaced(measured, "library_update_cancel_ms", "pixel9_4g", True),
            "a number for a boolean": self.replaced(measured, "download_queue_survives_force_stop", "pixel9_4g", 1),
            "text": self.replaced(measured, "library_update_throughput_ratio", "pixel9_4g", "0.95"),
            "an infinity": self.replaced(measured, "library_update_throughput_ratio", "pixel9_4g", float("inf")),
        }
        for kind, rows in cases.items():
            with self.subTest(gap=kind):
                outcome = run(baseline, rows, 2)
                self.assertEqual(outcome.code, 3, outcome.out + outcome.err)
                self.assertIn("MISSING", outcome.out)

    def test_a_bound_device_absent_from_the_file_fails_as_an_input_error(self):
        baseline, measured = complete(2)
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory) / "baseline.json"
            meas = Path(directory) / "measured.json"
            base.write_text(json.dumps(document("baseline", baseline)))
            without = document("measured", [row for row in measured if row["device"] != "lowram_1_5g"])
            without["devices"] = {k: v for k, v in DEVICES.items() if k != "lowram_1_5g"}
            meas.write_text(json.dumps(without))
            result = subprocess.run(
                [sys.executable, str(GATE), "--baseline", str(base), "--measured", str(meas), "--phase", "2"],
                capture_output=True, text=True,
            )
        self.assertEqual(result.returncode, 2)
        self.assertIn("lowram_1_5g", result.stderr)

    def test_a_boolean_published_for_a_timed_row_no_longer_passes_in_phase_0(self):
        outcome = run([], [{"id": "peak_pss_mb", "device": "pixel9_4g", "value": True}],
                      0, "--rows", "peak_pss_mb")
        self.assertEqual(outcome.status("peak_pss_mb", "pixel9_4g"), "MISSING")
        self.assertIn("not a number: True", outcome.out)
        self.assertEqual(outcome.code, 3)


class Phase3VerdictTest(unittest.TestCase):

    def test_a_complete_phase_3_file_passes_and_no_row_is_deferred(self):
        baseline, measured = complete(3)
        outcome = run(baseline, measured, 3)
        self.assertEqual(outcome.code, 0, outcome.out + outcome.err)
        for row in ROWS["rows"]:
            for device in row["devices"]:
                self.assertEqual(outcome.status(row["id"], device), "PASS", f"{row['id']} on {device}")
        self.assertNotIn("DEFERRED", outcome.out)

    def test_a_missing_phase_3_or_phase_2_row_fails_the_phase_3_verdict(self):
        baseline, measured = complete(3)
        pairs = [(row["id"], device) for row in ROWS["rows"] if row["phase"] == 3 for device in row["devices"]]
        self.assertTrue(pairs, "rows.json has phase 3 rows")
        for row_id, device in pairs + [("library_update_cancel_ms", "pixel9_4g")]:
            with self.subTest(row=row_id):
                gap = [row for row in measured if (row["id"], row["device"]) != (row_id, device)]
                outcome = run(baseline, gap, 3)
                self.assertEqual(outcome.status(row_id, device), "MISSING")
                self.assertEqual(outcome.code, 3)


if __name__ == "__main__":
    unittest.main()
