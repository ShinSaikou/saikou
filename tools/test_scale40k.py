#!/usr/bin/env python3
"""Tests for tools/ci/scale40k.sh, phase 5 unit SC0's 40,000-entry runner, and its scale40k.py.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Nothing here needs a device. The runner is read through its dry run, which prints the plan a real
run follows (both walk the same steps_of), and the plan is checked against two sources it could
drift from: section 4.1 of design/plans/phase5-start.md, whose row ids are copied below because the
design folder is outside this repository, and ScaleRowsTest.kt, whose `row(...)` calls name the rows
each method publishes. The dry run is also checked to touch nothing: no output folder, no work
folder, and no adb call (a fake adb on the SDK path records any call).
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
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
SCRIPT = TOOLS / "ci" / "scale40k.sh"
SCALE_ROWS_TEST = ROOT / "app" / "src" / "androidTest" / "kotlin" / "com" / "shinsaikou" / "gate" / "ScaleRowsTest.kt"

_spec = importlib.util.spec_from_file_location("scale40k", TOOLS / "ci" / "scale40k.py")
scale40k = importlib.util.module_from_spec(_spec)
sys.modules.setdefault(_spec.name, scale40k)
_spec.loader.exec_module(scale40k)

# design/plans/phase5-start.md section 4.1, the target table's rows and the devices it binds them to.
SECTION_4_1 = {
    "oom_count_40k": "lowram_1_5g",
    "library_open_ms_40k": "pixel9_4g",
    "library_change_ms_40k": "pixel9_4g",
    "library_search_ms_40k": "pixel9_4g",
    "restore_40k_ms": "pixel9_4g",
    "export_40k_ms": "pixel9_4g",
    "fingerprint_40k_ms": "pixel9_4g",
    "sync_first_push_40k_ms": "pixel9_4g",
    "sync_join_40k_ms": "api26_2g",
    "update_queue_build_ms_40k": "pixel9_4g",
    "download_next_ms_at_5000": "pixel9_4g",
    "stats_open_ms_40k": "pixel9_4g",
}
STEP = re.compile(r"^(?P<device>\w+): (?P<method>the\w+)(?: sample (?P<sample>\d+/\d+))? -> (?P<rows>[\w ]+?)(?: \(.*\))?$")


class DryRun:
    """One dry run in a scratch folder, with a fake adb that records any call."""

    def __init__(self, test: unittest.TestCase, *args: str, **env: str):
        tmp = tempfile.TemporaryDirectory()
        test.addCleanup(tmp.cleanup)
        base = Path(tmp.name)
        sdk = base / "sdk"
        (sdk / "platform-tools").mkdir(parents=True)
        self.adb_calls = base / "adb-calls"
        adb = sdk / "platform-tools" / "adb"
        adb.write_text(f"#!/bin/sh\necho \"$@\" >> {self.adb_calls}\n")
        adb.chmod(0o755)
        self.out = base / "out"
        self.work = base / "work"
        environment = {key: value for key, value in os.environ.items() if not key.startswith("SAIKOU_")}
        environment.update(ANDROID_SDK_ROOT=str(sdk), ANDROID_HOME=str(sdk), SAIKOU_SCALE_OUT=str(self.out),
                           SAIKOU_SCALE_WORK=str(self.work), **env)
        self.result = subprocess.run(["bash", str(SCRIPT), *args], capture_output=True, text=True,
                                     env=environment, timeout=60, check=False)
        self.lines = self.result.stdout.splitlines()

    def steps(self) -> list[dict]:
        return [match.groupdict() for line in self.lines if (match := STEP.match(line))]


class PlanTest(unittest.TestCase):

    def test_the_dry_run_names_every_row_of_section_4_1_on_its_device(self):
        run = DryRun(self, "--dry-run", "all")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        planned = {(step["device"], row) for step in run.steps() for row in step["rows"].split()}
        oom = [line for line in run.lines if line.endswith("-> oom_count_40k")]
        self.assertEqual(oom, ["lowram_1_5g: the methods' out-of-memory counts and deaths -> oom_count_40k"])
        planned.add(("lowram_1_5g", "oom_count_40k"))
        for row, device in SECTION_4_1.items():
            self.assertIn((device, row), planned, f"{row} on {device}")
        self.assertIn(("pixel9_4g", "seed_40k_ms"), planned)

    def test_the_four_gb_phone_restores_first_clears_seeds_and_ends_with_the_push_and_the_fingerprint(self):
        run = DryRun(self, "--dry-run", "36")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        device_lines = [line for line in run.lines if line.startswith("pixel9_4g: ")]
        self.assertTrue(device_lines[0].startswith("pixel9_4g: MemAvailable at least 8 GB, lock saikou_pixel9_4g, boot"))
        self.assertEqual(device_lines[1], "pixel9_4g: theRestoreIsTimed -> restore_40k_ms restore_40k_oom_count")
        self.assertEqual(device_lines[2], "pixel9_4g: pm clear com.shinsaikou")
        self.assertEqual(device_lines[3], "pixel9_4g: theSeedIsTimed -> seed_40k_ms")
        samples = [step["sample"] for step in run.steps() if step["method"] == "theLibraryOpenIsTimed"]
        self.assertEqual(samples, ["1/3", "2/3", "3/3"])
        methods = [step["method"] for step in run.steps()]
        # Once a sign-in is stored and no sync has succeeded, every process start begins a sync, so
        # the two sign-in rows come last and the push first of them.
        self.assertEqual(methods[-2:], ["theFirstPushIsTimed", "theFingerprintIsTimed"])
        self.assertTrue(device_lines[-1].endswith("release saikou_pixel9_4g"))
        self.assertNotIn("theJoinIsTimed", methods)

    def test_the_devices_run_in_the_set_order_whatever_order_they_are_named_in(self):
        run = DryRun(self, "--dry-run", "28,api26_2g,36")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        self.assertEqual(run.lines[0], "plan: devices pixel9_4g api26_2g lowram_1_5g, one at a time")
        order = []
        for step in run.steps():
            if not order or order[-1] != step["device"]:
                order.append(step["device"])
        self.assertEqual(order, ["pixel9_4g", "api26_2g", "lowram_1_5g"])

    def test_the_low_ram_phone_runs_the_rows_that_fit_and_the_fingerprint_last(self):
        run = DryRun(self, "--dry-run", "lowram_1_5g", SAIKOU_SCALE_LOWRAM_ENTRIES="10000")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        self.assertIn("plan: library 40000 entries of 50 chapters; lowram_1_5g 10000 of 50", run.lines)
        methods = [step["method"] for step in run.steps()]
        self.assertEqual(methods, ["theRestoreIsTimed", "theSeedIsTimed", "theLibraryOpenIsTimed", "theLibraryOpenIsTimed",
                                   "theLibraryOpenIsTimed", "theLibraryChangeIsTimed", "theLibrarySearchIsTimed",
                                   "theFingerprintWriteStallIsTimed", "theExportIsTimed", "theFingerprintIsTimed"])
        restore = next(line for line in run.lines if "theRestoreIsTimed" in line)
        self.assertIn("only when pixel9_4g's restore_40k_ms is under 1200000 ms", restore)

    def test_every_planned_method_is_a_scale_rows_method_publishing_the_rows_the_plan_names(self):
        source = SCALE_ROWS_TEST.read_text()
        declared = {name: set(re.findall(r'"(\w+)"', ids))
                    for name, ids in re.findall(r'fun (the\w+)\(\) = row\("\1", ([^)]*)\)', source)}
        self.assertEqual(len(declared), 13, sorted(declared))
        run = DryRun(self, "--dry-run", "all")
        for step in run.steps():
            self.assertIn(step["method"], declared)
            self.assertEqual(set(step["rows"].split()), declared[step["method"]], step["method"])
        # Every method runs somewhere.
        self.assertEqual({step["method"] for step in run.steps()}, set(declared))

    def test_the_sync_plan_seeds_and_pushes_on_the_four_gb_phone_and_joins_on_api_26(self):
        run = DryRun(self, "--dry-run", "all", SAIKOU_SCALE_PLAN="sync")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        self.assertEqual(run.lines[0], "plan: devices pixel9_4g api26_2g, one at a time")
        self.assertIn("plan: rows sync", run.lines)
        self.assertEqual([(step["device"], step["method"]) for step in run.steps()],
                         [("pixel9_4g", "theSeedIsTimed"), ("pixel9_4g", "theFirstPushIsTimed"),
                          ("api26_2g", "theJoinIsTimed")])
        self.assertFalse(any(line.startswith("lowram_1_5g") for line in run.lines))

    def test_the_fixture_holds_each_request_the_latency_given_and_none_by_default(self):
        for env, latency in (({}, "0"), ({"SAIKOU_SCALE_LATENCY_MS": "60"}, "60")):
            with self.subTest(env=env):
                run = DryRun(self, "--dry-run", "36,26", **env)
                self.assertEqual(run.result.returncode, 0, run.result.stderr)
                fixture = next(line for line in run.lines if line.startswith("fixture: "))
                self.assertIn(f"sync_fixture_server.py --port 0 --latency-ms {latency},", fixture)

    def test_a_method_may_run_30_minutes_unless_the_environment_says_otherwise(self):
        run = DryRun(self, "--dry-run", "36")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        self.assertIn("plan: each method may run 1800 s before the host stops waiting", run.lines)
        run = DryRun(self, "--dry-run", "36", SAIKOU_SCALE_METHOD_TIMEOUT_S="600")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        self.assertIn("plan: each method may run 600 s before the host stops waiting", run.lines)

    def test_the_dry_run_touches_nothing(self):
        run = DryRun(self, "--dry-run", "all")
        self.assertEqual(run.result.returncode, 0, run.result.stderr)
        self.assertFalse(run.out.exists())
        self.assertFalse(run.work.exists())
        self.assertFalse(run.adb_calls.exists())

    def test_a_real_run_without_the_lock_folder_stops_before_anything(self):
        run = DryRun(self, "all", SAIKOU_AVD_BOOT="/bin/false")
        self.assertEqual(run.result.returncode, 2)
        self.assertIn("missing: SAIKOU_AVD_LOCKS", run.result.stderr)
        self.assertFalse(run.out.exists())
        self.assertFalse(run.adb_calls.exists())

    def test_usage_errors_exit_2(self):
        for args, env in ((("--dry-run",), {}), (("--dry-run", "pixel8a_2g"), {}), (("--dry-run", "36", "26"), {}),
                          (("--dry-run", "--verbose", "36"), {}), (("--dry-run", "36"), {"SAIKOU_SCALE_ENTRIES": "0"}),
                          (("--dry-run", "36"), {"SAIKOU_SCALE_LATENCY_MS": "-1"}),
                          (("--dry-run", "36"), {"SAIKOU_SCALE_PLAN": "push"}),
                          (("--dry-run", "28"), {"SAIKOU_SCALE_PLAN": "sync"})):
            with self.subTest(args=args, env=env):
                run = DryRun(self, *args, **env)
                self.assertEqual(run.result.returncode, 2, run.result.stdout)
                self.assertEqual(run.result.stdout, "")


class HelperTest(unittest.TestCase):

    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = Path(tmp.name)

    def write(self, name: str, text: str) -> Path:
        path = self.dir / name
        path.write_text(text)
        return path

    def run_main(self, *args: str) -> str:
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed):
            self.assertEqual(scale40k.main([*args]), 0)
        return printed.getvalue()

    def test_a_process_that_died_of_memory_is_published_as_failed_with_its_line(self):
        instrument = self.write("i.txt", "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\r\n"
                                         "INSTRUMENTATION_CODE: 0\n")
        logcat = self.write("l.txt", "10-08 E AndroidRuntime: java.lang.OutOfMemoryError: Failed to allocate a 16 byte "
                                     "allocation with 0 free bytes\n10-08 E later: java.lang.OutOfMemoryError: second\n")
        out = self.dir / "rows" / "fingerprint_40k_ms.json"
        self.run_main("failed", str(out), "--id", "fingerprint_40k_ms", "--device", "lowram_1_5g",
                      "--method", "theFingerprintIsTimed", "--instrument", str(instrument), "--logcat", str(logcat),
                      "--status", "0")
        row = json.loads(out.read_text())
        self.assertEqual({k: row[k] for k in ("id", "device", "value", "unit")},
                         {"id": "fingerprint_40k_ms", "device": "lowram_1_5g", "value": "failed", "unit": "ms"})
        self.assertEqual(row["method"], "scale40k.sh: ScaleRowsTest.theFingerprintIsTimed: am instrument: Process crashed.; "
                                        "java.lang.OutOfMemoryError: Failed to allocate a 16 byte allocation with 0 free bytes")
        self.assertRegex(row["recordedAt"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")

    def test_the_reason_names_a_host_timeout_a_lost_session_and_a_silent_end(self):
        self.assertEqual(scale40k.death_reason("", "", 124), "the host stopped waiting for am instrument")
        self.assertEqual(scale40k.death_reason("INSTRUMENTATION_STATUS: test=x\n", "", 255),
                         "am instrument ended without its INSTRUMENTATION_CODE line (exit 255)")
        self.assertEqual(scale40k.death_reason("OK (1 test)\nINSTRUMENTATION_CODE: -1\n", "", 0),
                         "the method ended without publishing the row")

    def test_a_count_row_gets_the_count_unit_and_a_given_reason_is_used_as_it_is(self):
        out = self.dir / "j.json"
        self.run_main("failed", str(out), "--id", "restore_40k_oom_count", "--device", "lowram_1_5g",
                      "--method", "theRestoreIsTimed", "--reason", "not run: too slow")
        row = json.loads(out.read_text())
        self.assertEqual((row["unit"], row["method"]), ("count", "scale40k.sh: ScaleRowsTest.theRestoreIsTimed: not run: too slow"))
        self.assertEqual(scale40k.unit_of("download_next_ms_at_5000"), "ms")
        self.assertEqual(scale40k.unit_of("fingerprint_40k_oom"), "count")

    def test_failed_needs_a_reason_or_the_method_output(self):
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(io.StringIO()):
            scale40k.main(["failed", str(self.dir / "x.json"), "--id", "a_ms", "--device", "d", "--method", "m"])
        self.assertEqual(raised.exception.code, 2)

    def test_value_prints_a_number_and_nothing_for_failed_or_absent(self):
        number = self.write("n.json", json.dumps({"id": "restore_40k_ms", "value": 812345}))
        failed = self.write("f.json", json.dumps({"id": "restore_40k_ms", "value": "failed"}))
        self.assertEqual(self.run_main("value", str(number)), "812345\n")
        self.assertEqual(self.run_main("value", str(failed)), "")
        self.assertEqual(self.run_main("value", str(self.dir / "absent.json")), "")

    # The first run's low-RAM export, its last lines (lowram_1_5g, API 28): the heap at its class and
    # the low memory killer's signal, with no OutOfMemoryError line.
    KILLED_FULL = (
        "10-08 09:09:16.709  6194  6213 I com.shinsaikou: Alloc concurrent copying GC freed 131901(3MB) AllocSpace objects, "
        "0(0B) LOS objects, 1% free, 379MB/384MB, paused 231us total 179.243ms\n"
        "10-08 09:09:16.748  6194  6213 I com.shinsaikou: Waiting for a blocking GC Alloc\n"
        "10-08 09:09:17.119  1917  1917 I Zygote  : Process 6194 exited due to signal (9)\n"
        "10-08 09:09:17.129  1969  4855 I ActivityManager: Process com.shinsaikou (pid 6194) has died: fore FGS \n"
    )

    def test_a_kill_with_the_heap_full_is_a_death_of_memory_and_one_with_room_is_not(self):
        self.assertEqual(scale40k.memory_death(self.KILLED_FULL),
                         "process 6194 killed by signal 9 with its heap full (last GC: 1% free, 379MB/384MB)")
        roomy = self.KILLED_FULL.replace("1% free", "35% free")
        self.assertIsNone(scale40k.memory_death(roomy))
        # Another process's GC line says nothing about the app's heap.
        other = self.KILLED_FULL.replace("  6194  6213 I com.shinsaikou: Alloc", "  7000  7001 I other: Alloc")
        self.assertIsNone(scale40k.memory_death(other))
        # A death with no signal 9 is not a kill.
        self.assertIsNone(scale40k.memory_death(self.KILLED_FULL.replace("exited due to signal (9)", "exited cleanly")))
        self.assertEqual(scale40k.death_reason("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n",
                                               self.KILLED_FULL, 0),
                         "am instrument: Process crashed.; process 6194 killed by signal 9 with its heap full "
                         "(last GC: 1% free, 379MB/384MB)")

    def test_only_a_process_started_inside_the_log_is_the_methods_own(self):
        # The first full run's low-RAM fingerprint log: the export's death in its first second, then
        # the fingerprint's own process, which lived.
        started = "10-08 09:26:24.157  1881  1898 I ActivityManager: Start proc 6025:com.shinsaikou/u0a86 for added application com.shinsaikou\n"
        lived = self.KILLED_FULL + started
        self.assertIsNone(scale40k.memory_death(lived))
        oom_before = "10-08 09:26:23.000  6194  6194 E AndroidRuntime: java.lang.OutOfMemoryError: earlier\n" + started
        self.assertIsNone(scale40k.memory_death(oom_before))
        own_oom = started + "10-08 09:26:30.000  6025  6025 E AndroidRuntime: java.lang.OutOfMemoryError: own\n"
        self.assertEqual(scale40k.memory_death(own_oom), "java.lang.OutOfMemoryError: own")
        # The test APK's process is not the app's.
        self.assertIsNone(scale40k.own_pids("I ActivityManager: Start proc 7000:com.shinsaikou.test/u0a87 for added application\n"))

    def test_the_oom_count_adds_the_published_counts_and_the_deaths_of_memory(self):
        rows = self.dir / "rows"
        rows.mkdir()
        (rows / "restore_40k_oom_count.json").write_text(json.dumps({"id": "restore_40k_oom_count", "value": 2}))
        (rows / "fingerprint_40k_oom.json").write_text(json.dumps({"id": "fingerprint_40k_oom", "value": "failed"}))
        self.write("theFingerprintIsTimed.logcat.txt", "E art: java.lang.OutOfMemoryError: OOM\n")
        self.write("theLibraryOpenIsTimed.1of3.logcat.txt", "I nothing about memory\n")
        self.write("theExportIsTimed.logcat.txt", self.KILLED_FULL)
        deaths = self.write("deaths.txt", "theFingerprintIsTimed\ntheLibraryOpenIsTimed.1of3\ntheExportIsTimed\n")
        out = self.dir / "oom.json"
        self.assertEqual(self.run_main("oom", str(out), "--rows", str(rows), "--device", "lowram_1_5g",
                                       "--logs", str(self.dir), "--deaths", str(deaths)),
                         "oom_count_40k on lowram_1_5g: 4\n")
        row = json.loads(out.read_text())
        self.assertEqual((row["id"], row["device"], row["value"], row["unit"]), ("oom_count_40k", "lowram_1_5g", 4, "count"))
        self.assertIn("restore_40k_oom_count 2, fingerprint_40k_oom failed", row["method"])
        self.assertIn("theFingerprintIsTimed (java.lang.OutOfMemoryError: OOM)", row["method"])
        self.assertIn("theExportIsTimed (process 6194 killed by signal 9 with its heap full", row["method"])
        self.assertNotIn("theLibraryOpenIsTimed", row["method"])


if __name__ == "__main__":
    unittest.main()
