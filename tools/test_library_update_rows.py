#!/usr/bin/env python3
"""Unit tests for tools/ci/library_update_rows.py and the input check of library_update_rows.sh.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The runs are the JSON tools/bench/library_update.sh prints, in the shape update_log.py builds, with
figures near unit E's runs of the flat queue (41,194 ms for 2000x20, 0.9164 for 500x2). Nothing
needs a device: the shell script's check runs against a stand-in adb.

The cases that matter, and why each one is here:
  - a measured run publishes its scenario's row, duration_ms of 2000x20 and min_rate_ratio of
    500x2, in common.sh's fragment() shape (its keys read out of common.sh), with rows.json's unit;
  - collect.py reads the two fragments and release_gate.py scores them against baseline.json's
    upstream entry: the flat queue's figures pass, a sequential pass's figures fail both rows;
  - a value below the row's pass mark is still published: the gate judges it, this does not;
  - nothing is published from a run that did not measure (R36), and an earlier fragment of the row
    is removed rather than left to be collected as this run's: an ended run, even one that carries
    a duration, and no run JSON at all, exit 3;
  - a JSON that is not the run the step asked for is an input error (exit 2): not JSON, not an
    object, another app, scenario, device or snapshot, a status this does not know, ok with reasons,
    and a measured run whose value is missing, zero, negative, not finite, a boolean or text;
  - library_update_rows.sh --check names the missing input (the APK or a seeded snapshot) with
    exit 2, which is what gate.sh skips the step on, and passes when both snapshots are there.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("library_update_rows", TOOLS / "ci" / "library_update_rows.py")
library_update_rows = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = library_update_rows
_spec.loader.exec_module(library_update_rows)
sys.path.insert(0, str(TOOLS))
from test_bench_launch import extract_function  # noqa: E402

DEVICE = "pixel9_4g"
SHA = "317fe12647a6c10b5cbef56aecf53acbdc70cf285d2d4c9cc60443a4e20107b9"


def run_json(scenario: str, **changes: object) -> dict:
    """A measured run as library_update.sh prints it for the gate's snapshot of the scenario."""
    run = {
        "app": "saikou", "package": "com.shinsaikou", "versionName": "0.1.0", "device": DEVICE,
        "serial": "emulator-5554", "snapshot": f"gate-update-saikou-{scenario}",
        "recordedAt": "2026-09-25T04:20:00Z", "scenario": scenario,
        "rate_limit_rps": 5.0, "status": "ok", "reasons": [],
    }
    if scenario == "2000x20":
        run.update({"entries": 2000, "requests": {"chapters": 2000, "probe": 5},
                    "per_source": {f"{s:02d}": {"id": str(1000 + s), "requests": 100, "rps": 2.45}
                                   for s in range(1, 21)},
                    "min_rate_ratio": 0.4855, "device_window_ms": 42493, "window_difference_pct": 3.15,
                    "server_window_ms": 41194, "duration_ms": 41194})
    else:
        run.update({"entries": 500, "requests": {"chapters": 500, "probe": 5},
                    "per_source": {"01": {"id": "1001", "requests": 250, "rps": 4.582},
                                   "02": {"id": "1002", "requests": 250, "rps": 4.608}},
                    "min_rate_ratio": 0.9164, "device_window_ms": 55382, "window_difference_pct": 1.5,
                    "server_window_ms": 54561, "duration_ms": 54561})
    run.update(changes)
    return run


def ended(scenario: str, **changes: object) -> dict:
    """An ended run as update_log.py writes it: its reasons and no duration."""
    fields = {"status": "ended", "duration_ms": None,
              "reasons": ["3 seeded entries were never fetched, first s04/17, s04/18, s04/19"]}
    return run_json(scenario, **{**fields, **changes})


class Workspace:
    """A run JSON on disk, the bench output it publishes into, and the script's exit and streams."""

    def __init__(self, test: unittest.TestCase) -> None:
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.out = self.root / "bench" / DEVICE
        self.out.mkdir(parents=True)
        self.run_path = self.root / "update-saikou.json"

    def write(self, run: object) -> None:
        self.run_path.write_text(run if isinstance(run, str) else json.dumps(run))

    def publish(self, scenario: str, snapshot: str | None = None) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = library_update_rows.main([
                str(self.run_path), "--scenario", scenario, "--device", DEVICE,
                "--snapshot", snapshot or f"gate-update-saikou-{scenario}", "--out", str(self.out),
                "--apk-sha256", SHA])
        return code, stdout.getvalue(), stderr.getvalue()

    def fragment(self, row_id: str) -> Path:
        return self.out / f"{row_id}.{DEVICE}.json"


def rows_json() -> dict[str, dict]:
    return {row["id"]: row for row in json.loads((TOOLS / "gate" / "rows.json").read_text())["rows"]}


def fragment_keys() -> list[str]:
    """The keys tools/bench/common.sh's fragment() writes, in its order."""
    body = extract_function((TOOLS / "bench" / "common.sh").read_text(), "fragment")
    keys = re.findall(r'^\s*"(\w+)":', body, re.MULTILINE)
    if not keys:
        raise AssertionError("common.sh's fragment() writes no key this test can read")
    return keys


class PublishTest(unittest.TestCase):

    def setUp(self) -> None:
        self.work = Workspace(self)

    def test_2000x20_publishes_the_server_window(self):
        self.work.write(run_json("2000x20"))
        code, out, err = self.work.publish("2000x20")
        self.assertEqual(code, 0, err)
        published = json.loads(self.work.fragment("library_update_2000x20_ms").read_text())
        self.assertEqual(list(published), fragment_keys())
        self.assertEqual(published["id"], "library_update_2000x20_ms")
        self.assertEqual(published["device"], DEVICE)
        self.assertEqual(published["value"], 41194)
        self.assertEqual(published["unit"], "ms")
        self.assertIsNone(published["samples"])
        self.assertEqual(published["recordedAt"], "2026-09-25T04:20:00Z")
        self.assertIn("library_update.sh saikou 2000x20", published["method"])
        self.assertIn("gate-update-saikou-2000x20", published["method"])
        self.assertIn(SHA[:12], published["method"])
        self.assertIn("library_update_2000x20_ms = 41194 ms on pixel9_4g", out)
        self.assertFalse(self.work.fragment("library_update_throughput_ratio").exists())

    def test_500x2_publishes_the_slower_sources_rate_over_the_limit(self):
        self.work.write(run_json("500x2"))
        code, out, err = self.work.publish("500x2")
        self.assertEqual(code, 0, err)
        published = json.loads(self.work.fragment("library_update_throughput_ratio").read_text())
        self.assertEqual(list(published), fragment_keys())
        self.assertEqual(published["value"], 0.9164)
        self.assertEqual(published["unit"], "ratio")
        self.assertIn("s01 4.582, s02 4.608", published["method"])
        self.assertFalse(self.work.fragment("library_update_2000x20_ms").exists())

    def test_units_and_devices_are_the_rows_own(self):
        rows = rows_json()
        for scenario, row in library_update_rows.ROWS.items():
            with self.subTest(scenario=scenario):
                self.assertIn(row.id, rows)
                self.assertEqual(row.unit, rows[row.id]["unit"])
                self.assertIn(DEVICE, rows[row.id]["devices"])
                self.assertEqual(rows[row.id]["phase"], 2)

    def test_a_value_below_the_pass_mark_is_still_published(self):
        self.work.write(run_json("500x2", min_rate_ratio=0.3325))
        code, _, err = self.work.publish("500x2")
        self.assertEqual(code, 0, err)
        self.assertEqual(json.loads(self.work.fragment("library_update_throughput_ratio").read_text())["value"], 0.3325)

    def test_a_run_with_no_recorded_time_is_stamped_now(self):
        run = run_json("2000x20")
        del run["recordedAt"]
        self.work.write(run)
        code, _, err = self.work.publish("2000x20")
        self.assertEqual(code, 0, err)
        stamp = json.loads(self.work.fragment("library_update_2000x20_ms").read_text())["recordedAt"]
        self.assertRegex(stamp, r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")


class ScoredTest(unittest.TestCase):
    """The fragments as step 8d reads them: collect.py --bench, then release_gate.py on baseline.json."""

    ROWS = "library_update_2000x20_ms,library_update_throughput_ratio"

    def verdict(self, duration_ms: int, ratio: float) -> tuple[int, str]:
        work = Workspace(self)
        for scenario, run in (("2000x20", run_json("2000x20", duration_ms=duration_ms, server_window_ms=duration_ms)),
                              ("500x2", run_json("500x2", min_rate_ratio=ratio))):
            work.write(run)
            code, _, err = work.publish(scenario)
            self.assertEqual(code, 0, err)
        measured = work.root / "measured.json"
        collected = subprocess.run([sys.executable, str(TOOLS / "bench" / "collect.py"), "--bench",
                                    str(work.root / "bench"), "--subject", "com.shinsaikou:0.1.0:0000000",
                                    "--out", str(measured)], capture_output=True, text=True, check=False)
        self.assertEqual(collected.returncode, 0, collected.stderr)
        self.assertIn("2 rows written", collected.stdout)
        gate = subprocess.run([sys.executable, str(TOOLS / "gate" / "release_gate.py"), "--baseline",
                               str(TOOLS / "gate" / "baseline.json"), "--measured", str(measured), "--phase", "2",
                               "--rows", self.ROWS], capture_output=True, text=True, check=False)
        return gate.returncode, gate.stdout + gate.stderr

    def lines(self, output: str) -> dict[str, str]:
        return {line.split()[1]: line.split()[0] for line in output.splitlines()
                if len(line.split()) > 1 and line.split()[1] in self.ROWS.split(",")}

    def test_the_flat_queues_figures_pass_both_rows(self):
        code, output = self.verdict(41194, 0.9164)
        self.assertEqual(code, 0, output)
        self.assertEqual(self.lines(output), {"library_update_2000x20_ms": "PASS",
                                              "library_update_throughput_ratio": "PASS"})

    def test_a_sequential_passs_figures_fail_both_rows(self):
        # Saikou's sequential pass on the recording AVD, unit C's day 1: 1,222,414 ms and 0.3325.
        code, output = self.verdict(1222414, 0.3325)
        self.assertEqual(code, 1, output)
        self.assertEqual(self.lines(output), {"library_update_2000x20_ms": "FAIL",
                                              "library_update_throughput_ratio": "FAIL"})


class RefusedTest(unittest.TestCase):

    def setUp(self) -> None:
        self.work = Workspace(self)

    def assert_nothing_published(self) -> None:
        self.assertEqual(sorted(p.name for p in self.work.out.iterdir()), [])

    def stale(self, row_id: str) -> None:
        """An earlier run's fragment of the row, which a refused run must not leave to be collected."""
        self.work.fragment(row_id).write_text(json.dumps({"id": row_id, "device": DEVICE, "value": 1}))

    def test_an_ended_run_publishes_nothing_and_removes_an_earlier_fragment(self):
        self.stale("library_update_2000x20_ms")
        self.work.write(ended("2000x20"))
        code, _, err = self.work.publish("2000x20")
        self.assertEqual(code, 3)
        self.assertIn("did not measure", err)
        self.assertIn("the run ended (R36): 3 seeded entries were never fetched", err)
        self.assert_nothing_published()

    def test_an_ended_run_that_carries_a_duration_is_still_refused(self):
        self.work.write(ended("2000x20", duration_ms=41194))
        code, _, _ = self.work.publish("2000x20")
        self.assertEqual(code, 3)
        self.assert_nothing_published()

    def test_an_ended_500x2_run_publishes_no_ratio(self):
        self.stale("library_update_throughput_ratio")
        self.work.write(ended("500x2"))
        code, _, _ = self.work.publish("500x2")
        self.assertEqual(code, 3)
        self.assert_nothing_published()

    def test_no_run_json_is_unmeasurable(self):
        self.stale("library_update_2000x20_ms")
        code, _, err = self.work.publish("2000x20")
        self.assertEqual(code, 3)
        self.assertIn("no run JSON", err)
        self.assert_nothing_published()

    def test_a_file_that_is_not_a_run_is_an_input_error(self):
        for text in ("{not json", "[1, 2]", "null"):
            with self.subTest(text=text):
                self.stale("library_update_2000x20_ms")
                self.work.write(text)
                code, _, err = self.work.publish("2000x20")
                self.assertEqual(code, 2, err)
                self.assertIn("is not a run this reads", err)
                self.assert_nothing_published()

    def test_another_runs_json_is_an_input_error(self):
        cases = {
            "another app": run_json("2000x20", app="upstream"),
            "another scenario": run_json("500x2"),
            "another device": run_json("2000x20", device="pixel8a_2g"),
            "the seeded snapshot, not the build under test": run_json("2000x20", snapshot="update-bench-saikou-2000x20"),
            "no scenario": {k: v for k, v in run_json("2000x20").items() if k != "scenario"},
        }
        for name, run in cases.items():
            with self.subTest(name):
                self.work.write(run)
                code, _, err = self.work.publish("2000x20")
                self.assertEqual(code, 2, err)
                self.assert_nothing_published()

    def test_a_status_other_than_ok_or_ended_is_an_input_error(self):
        for status in ("OK", None, "measured"):
            with self.subTest(status=status):
                self.work.write(run_json("2000x20", status=status))
                code, _, err = self.work.publish("2000x20")
                self.assertEqual(code, 2, err)
                self.assert_nothing_published()

    def test_ok_with_reasons_is_an_input_error(self):
        for reasons in (["a source request arrived before the tap"], None, "none"):
            with self.subTest(reasons=reasons):
                self.work.write(run_json("2000x20", reasons=reasons))
                code, _, err = self.work.publish("2000x20")
                self.assertEqual(code, 2, err)
                self.assert_nothing_published()

    def test_a_measured_run_with_no_usable_value_is_an_input_error(self):
        for scenario, field in (("2000x20", "duration_ms"), ("500x2", "min_rate_ratio")):
            for value in (None, 0, -1, float("nan"), float("inf"), True, "41194"):
                with self.subTest(scenario=scenario, value=value):
                    self.work.write(run_json(scenario, **{field: value}))
                    code, _, err = self.work.publish(scenario)
                    self.assertEqual(code, 2, err)
                    self.assertIn("not a positive number", err)
                    self.assert_nothing_published()
            with self.subTest(scenario=scenario, value="absent"):
                run = run_json(scenario)
                del run[field]
                self.work.write(run)
                code, _, _ = self.work.publish(scenario)
                self.assertEqual(code, 2)
                self.assert_nothing_published()


# A stand-in adb for the shell script's input check: it answers `-s <serial> emu avd snapshot list`
# with the names in FAKE_SNAPSHOTS, in the emulator console's table, and fails anything else, so a
# check that went further than listing would fail the test.
FAKE_ADB = textwrap.dedent(
    """\
    #!/usr/bin/env python3
    import os, sys
    args = sys.argv[1:]
    if args[2:] != ["emu", "avd", "snapshot", "list"]:
        print(f"unexpected adb call: {args}", file=sys.stderr)
        sys.exit(1)
    print("List of snapshots present on all disks:")
    print("ID        TAG                 VM SIZE                DATE       VM CLOCK")
    for name in os.environ["FAKE_SNAPSHOTS"].split():
        print(f"--        {name}    75M 2026-09-24 19:27:51   00:03:14.598")
    print("OK")
    """
)


class CheckTest(unittest.TestCase):
    """library_update_rows.sh --check, the input test gate.sh skips step 8c on."""

    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        adb = root / "sdk" / "platform-tools" / "adb"
        adb.parent.mkdir(parents=True)
        adb.write_text(FAKE_ADB)
        adb.chmod(0o755)
        self.apk = root / "app-release.apk"
        self.apk.write_bytes(b"not an apk, but not empty")
        self.env = {"PATH": os.environ["PATH"], "HOME": str(root), "ANDROID_SDK_ROOT": str(root / "sdk"),
                    "SAIKOU_SERIAL": "emulator-5554", "SAIKOU_DEVICE_ID": DEVICE, "SAIKOU_APK": str(self.apk),
                    "SAIKOU_BENCH_OUT": str(root / "bench")}

    def check(self, snapshots: str, **env: str) -> subprocess.CompletedProcess:
        return subprocess.run(["bash", str(TOOLS / "ci" / "library_update_rows.sh"), "--check"],
                              env={**self.env, "FAKE_SNAPSHOTS": snapshots, **env},
                              capture_output=True, text=True, check=False)

    def test_both_seeded_snapshots_pass(self):
        result = self.check("update-bench-upstream-2000x20 update-bench-saikou-2000x20 update-bench-saikou-500x2")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")

    def test_a_missing_snapshot_is_named(self):
        for present, missing in (("update-bench-saikou-500x2", "update-bench-saikou-2000x20"),
                                 ("update-bench-saikou-2000x20", "update-bench-saikou-500x2"),
                                 ("update-bench-upstream-2000x20 update-bench-upstream-500x2",
                                  "update-bench-saikou-2000x20")):
            with self.subTest(missing=missing):
                result = self.check(present)
                self.assertEqual(result.returncode, 2)
                self.assertIn(f"missing: AVD snapshot {missing} on emulator-5554", result.stderr)
                self.assertIn("library_update_seed.sh", result.stderr)

    def test_a_gate_snapshot_does_not_stand_in_for_a_seeded_one(self):
        result = self.check("gate-update-saikou-2000x20 gate-update-saikou-500x2")
        self.assertEqual(result.returncode, 2)
        self.assertIn("missing: AVD snapshot update-bench-saikou-2000x20", result.stderr)

    def test_a_missing_apk_is_named(self):
        result = self.check("update-bench-saikou-2000x20 update-bench-saikou-500x2",
                            SAIKOU_APK=str(self.apk.with_name("absent.apk")))
        self.assertEqual(result.returncode, 2)
        self.assertIn("absent.apk, the build under test", result.stderr)

    def test_no_serial_is_a_missing_input(self):
        env = dict(self.env)
        del env["SAIKOU_SERIAL"]
        result = subprocess.run(["bash", str(TOOLS / "ci" / "library_update_rows.sh"), "--check"],
                                env={**env, "FAKE_SNAPSHOTS": ""}, capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 2)
        self.assertIn("missing: SAIKOU_SERIAL", result.stderr)


if __name__ == "__main__":
    unittest.main()
