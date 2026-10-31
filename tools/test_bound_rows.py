#!/usr/bin/env python3
"""Unit tests for tools/gate/bound_rows.py, the check that ends a gate run lacking a bound row.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The measured files are synthetic and built from rows.json itself, one passing value per
(row, device) pair, so a row added to rows.json is covered here without an edit. Nothing needs a
device.

The cases that matter, and why each one is here:
  - a complete phase 2 file passes, and so does one that lacks every phase 3 row;
  - phase 3 binds every row rows.json has, so a complete phase 3 file passes and a missing phase 3
    row, or a missing phase 2 row, is a gap there (gate.sh's phase 3 passes --phase 3 since unit L);
  - phase 2 binds the phase 0 and phase 1 rows (PLAN's reader rows and the 3,000-entry import), so a
    missing one of those is a gap in phase 2, and a missing phase 2 row is none in phase 1;
  - each kind of gap ends the run with exit 3 and names the row and device: absent, null (with its
    reason), a value of the wrong kind, two rows for one pair, and a device the file does not
    describe; one line per gap, so a run missing several names all of them;
  - a missing or unreadable measured file is an input error (exit 2), never "complete";
  - unit F's two rows, which step 8c of gate.sh skips when the 4 GB device, the APK or a seeded
    snapshot is missing, are gaps in phase 2: this is the check that ends the run on their absence.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
# bound_rows imports release_gate from its own directory, as it does when run as a script.
sys.path.insert(0, str(TOOLS / "gate"))
_spec = importlib.util.spec_from_file_location("bound_rows", TOOLS / "gate" / "bound_rows.py")
bound_rows = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = bound_rows
_spec.loader.exec_module(bound_rows)

ROWS = json.loads((TOOLS / "gate" / "rows.json").read_text())["rows"]
DEVICES = {
    "pixel8a_2g": {"avd": "saikou_pixel8a_2g"},
    "pixel9_4g": {"avd": "saikou_pixel9_4g"},
    "tablet_4g": {"avd": "saikou_tablet_4g"},
    "lowram_1_5g": {"avd": "saikou_lowram_1_5g"},
}
F_ROWS = {"library_update_2000x20_ms", "library_update_throughput_ratio"}


def passing_value(row: dict) -> object:
    """A value that passes the row: half an upper threshold, a lower one exactly, or the boolean."""
    match row["comparator"]:
        case "bool_true":
            return True
        case "bool_false":
            return False
        case "abs_min":
            return row["threshold"]
        case _:
            return row["threshold"] / 2


def complete_rows(phase: int) -> list[dict]:
    return [{"id": row["id"], "device": device, "value": passing_value(row)}
            for row in ROWS if row["phase"] <= phase for device in row["devices"]]


def document(rows: list[dict], devices: dict | None = None) -> dict:
    return {"schemaVersion": 1, "kind": "measured", "recordedAt": "2026-09-25T00:00:00Z",
            "subject": {"package": "com.shinsaikou", "versionName": "0.1.0", "commit": "0000000"},
            "devices": DEVICES if devices is None else devices, "rows": rows}


def without(rows: list[dict], row_id: str, device: str | None = None) -> list[dict]:
    return [row for row in rows if not (row["id"] == row_id and (device is None or row["device"] == device))]


class BoundRowsCase(unittest.TestCase):

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.measured = Path(self.scratch.name) / "measured.json"

    def tearDown(self):
        self.scratch.cleanup()

    def check(self, rows: list[dict], phase: int = 2, devices: dict | None = None) -> tuple[int, str, str]:
        self.measured.write_text(json.dumps(document(rows, devices)))
        return self.run_main("--measured", str(self.measured), "--phase", str(phase))

    def run_main(self, *argv: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = bound_rows.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def gap_lines(self, out: str) -> list[str]:
        return [line.strip() for line in out.splitlines() if line.strip().startswith("GAP ")]


class CompleteTest(BoundRowsCase):

    def test_a_complete_phase_2_file_passes(self):
        code, out, err = self.check(complete_rows(2))
        self.assertEqual(code, 0, out + err)
        bound = sum(len(row["devices"]) for row in ROWS if row["phase"] <= 2)
        self.assertIn(f"{bound} (row, device) pair(s) bound in phase 2, {bound} with one usable value", out)
        self.assertEqual(self.gap_lines(out), [])

    def test_phase_3_rows_do_not_bind_in_phase_2(self):
        self.assertTrue(any(row["phase"] == 3 for row in ROWS), "rows.json has phase 3 rows to leave out")
        code, _, _ = self.check(complete_rows(2))
        self.assertEqual(code, 0)
        code, out, _ = self.check(complete_rows(2), phase=3)
        self.assertEqual(code, 3)
        self.assertIn("GAP  sync_3000_no_oom  pixel8a_2g  absent", out)

    def test_phase_3_binds_every_row_and_a_complete_phase_3_file_passes(self):
        bound = sum(len(row["devices"]) for row in ROWS)
        self.assertEqual(bound, sum(len(row["devices"]) for row in ROWS if row["phase"] <= 3))
        code, out, err = self.check(complete_rows(3), phase=3)
        self.assertEqual(code, 0, out + err)
        self.assertIn(f"{bound} (row, device) pair(s) bound in phase 3, {bound} with one usable value", out)
        self.assertEqual(self.gap_lines(out), [])
        pairs = [(row["id"], device) for row in ROWS if row["phase"] == 3 for device in row["devices"]]
        for row_id, device in pairs + [("download_queue_add_ms_at_1000", "pixel9_4g")]:
            with self.subTest(row=row_id):
                code, out, _ = self.check(without(complete_rows(3), row_id, device), phase=3)
                self.assertEqual(code, 3)
                self.assertEqual(len(self.gap_lines(out)), 1)
                self.assertTrue(self.gap_lines(out)[0].startswith(f"GAP  {row_id}  {device}  absent"), out)

    def test_rows_beyond_the_phase_may_be_present(self):
        code, _, _ = self.check(complete_rows(3), phase=2)
        self.assertEqual(code, 0)


class BindingTest(BoundRowsCase):

    def test_phase_2_binds_the_phase_0_and_phase_1_rows(self):
        for row_id, device in (("cold_start_ms", "pixel9_4g"), ("webtoon_jank_pct", "pixel8a_2g"),
                               ("restore_3000_ms", "pixel9_4g"), ("low_ram_repro_pass", "lowram_1_5g")):
            with self.subTest(row=row_id):
                code, out, _ = self.check(without(complete_rows(2), row_id, device))
                self.assertEqual(code, 3)
                lines = self.gap_lines(out)
                self.assertEqual(len(lines), 1, out)
                self.assertTrue(lines[0].startswith(f"GAP  {row_id}  {device}  absent"), lines[0])

    def test_phase_1_does_not_bind_a_phase_2_row(self):
        code, _, _ = self.check(without(complete_rows(2), "download_queue_add_ms_at_1000"), phase=1)
        self.assertEqual(code, 0)

    def test_unit_fs_two_rows_are_gaps_until_their_slot_publishes_them(self):
        rows = [row for row in complete_rows(2) if row["id"] not in F_ROWS]
        code, out, err = self.check(rows)
        self.assertEqual(code, 3)
        self.assertEqual(sorted(line.split()[1] for line in self.gap_lines(out)), sorted(F_ROWS))
        self.assertIn("2 bound row(s) have no usable value", err)
        self.assertIn("the run ends here (R36)", err)


class GapKindsTest(BoundRowsCase):

    def replaced(self, row_id: str, device: str, **fields: object) -> list[dict]:
        rows = complete_rows(2)
        for row in rows:
            if row["id"] == row_id and row["device"] == device:
                row.update(fields)
        return rows

    def test_an_absent_row_names_what_measures_it(self):
        code, out, _ = self.check(without(complete_rows(2), "download_queue_survives_force_stop"))
        self.assertEqual(code, 3)
        self.assertIn("GAP  download_queue_survives_force_stop  pixel9_4g  absent from measured.json "
                      "(measured by: instrumentation fragment; phase 2 pulls it from the 4 GB device in gate.sh "
                      "step 8b and reads it with collect.py --bench)", out)

    def test_a_null_value_is_a_gap_with_its_reason(self):
        code, out, _ = self.check(self.replaced("peak_pss_mb", "pixel9_4g", value=None, reason="the poll fell behind"))
        self.assertEqual(code, 3)
        self.assertIn("GAP  peak_pss_mb  pixel9_4g  no value recorded: the poll fell behind", out)

    def test_a_boolean_for_a_numeric_row_is_a_gap(self):
        code, out, _ = self.check(self.replaced("library_update_cancel_ms", "pixel9_4g", value=True))
        self.assertEqual(code, 3)
        self.assertIn("GAP  library_update_cancel_ms  pixel9_4g  not a number: True", out)

    def test_a_number_for_a_boolean_row_is_a_gap(self):
        code, out, _ = self.check(self.replaced("low_ram_repro_pass", "lowram_1_5g", value=1))
        self.assertEqual(code, 3)
        self.assertIn("GAP  low_ram_repro_pass  lowram_1_5g  not a boolean: 1", out)

    def test_text_nan_and_infinity_are_gaps(self):
        for value in ("412", float("nan"), float("inf")):
            with self.subTest(value=value):
                code, out, _ = self.check(self.replaced("cold_start_ms", "pixel8a_2g", value=value))
                self.assertEqual(code, 3)
                self.assertIn("GAP  cold_start_ms  pixel8a_2g  not a number", out)

    def test_a_row_published_twice_is_a_gap(self):
        rows = complete_rows(2) + [{"id": "download_queue_add_ms_at_1000", "device": "pixel9_4g", "value": 999}]
        code, out, _ = self.check(rows)
        self.assertEqual(code, 3)
        self.assertIn("GAP  download_queue_add_ms_at_1000  pixel9_4g  published 2 times", out)

    def test_a_bound_device_the_file_does_not_describe_is_a_gap(self):
        devices = {name: spec for name, spec in DEVICES.items() if name != "lowram_1_5g"}
        code, out, _ = self.check(complete_rows(2), devices=devices)
        self.assertEqual(code, 3)
        self.assertIn("GAP  low_ram_repro_pass  lowram_1_5g  the device is not in measured.json's device table", out)

    def test_several_gaps_are_all_named(self):
        rows = without(without(complete_rows(2), "cold_start_ms"), "library_update_cancel_ms")
        code, out, _ = self.check(rows)
        self.assertEqual(code, 3)
        self.assertEqual(len(self.gap_lines(out)), 3)

    def test_a_device_bound_row_published_as_any_is_absent(self):
        rows = without(complete_rows(2), "peak_pss_mb") + [{"id": "peak_pss_mb", "device": "any", "value": 100}]
        code, out, _ = self.check(rows)
        self.assertEqual(code, 3)
        self.assertIn("GAP  peak_pss_mb  pixel9_4g  absent", out)


class InputTest(BoundRowsCase):

    def test_a_missing_file_is_exit_2(self):
        code, _, err = self.run_main("--measured", str(self.measured), "--phase", "2")
        self.assertEqual(code, 2)
        self.assertIn("missing:", err)

    def test_invalid_json_is_exit_2(self):
        self.measured.write_text("{")
        code, _, err = self.run_main("--measured", str(self.measured), "--phase", "2")
        self.assertEqual(code, 2)
        self.assertIn("invalid json", err)

    def test_the_wrong_schema_version_is_exit_2(self):
        stale = document(complete_rows(2))
        stale["schemaVersion"] = 99
        self.measured.write_text(json.dumps(stale))
        code, _, err = self.run_main("--measured", str(self.measured), "--phase", "2")
        self.assertEqual(code, 2)
        self.assertIn("schemaVersion", err)


if __name__ == "__main__":
    unittest.main()
