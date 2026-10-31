#!/usr/bin/env python3
"""Unit tests for tools/ci/release_signing_row.py, and the wiring of the owner signing flag in gate.sh.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The reports are synthetic, in the shape build-logic's VerifyReleaseSigning writes. Nothing runs
Gradle here; unit R1's report records the task run for real on the release, matching and refused.

The cases that matter, and why each one is here:
  - a fresh report with the flag on and three matches publishes the row true on `any`, in common.sh's
    fragment() shape, with rows.json's unit, and collect.py and release_gate.py --phase 3 score it PASS;
  - nothing is published, and an earlier fragment is removed rather than left to be collected as this
    run's, when: there is no report, it is older than the run, it is not JSON, the flag was off, it
    names another APK, a range is missing, out of order or extra, a range is no match, or Gradle
    exited other than 0; and every reason is printed;
  - the ranges here are the ones build-logic's ReleaseSigners compares, the script runs the task with
    the flag and hands this the path the task writes, and gate.sh's phase 3 step 2b runs the script;
  - gate.sh passes -Psaikou.ownerSigning=true to every Gradle call that builds the release, and only in
    a phase 3 run, so phases 0 to 2 build as before and no later step rebuilds the release on the
    debug key behind step 2b's check;
  - the in-place upgrade check's keep rules reach a release only through -Psaikou.upgradeCheck, which
    the gate never passes.
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
import time
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
_spec = importlib.util.spec_from_file_location("release_signing_row", TOOLS / "ci" / "release_signing_row.py")
release_signing_row = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = release_signing_row
_spec.loader.exec_module(release_signing_row)
sys.path.insert(0, str(TOOLS))
from test_upstream_lossless_row import fragment_keys  # noqa: E402

ROW = "release_build_owner_signed"


def report_json(owner_signing: bool = True, apk: str = "/x/app/build/outputs/apk/release/app-release.apk",
                ranges: list[tuple[str, str]] | None = None) -> str:
    ranges = ranges if ranges is not None else [("26-27", "match"), ("28-32", "match"), ("33-", "match")]
    return json.dumps({
        "apk": apk,
        "ownerSigning": owner_signing,
        "ranges": [{"range": key, "result": result} for key, result in ranges],
    }, indent=2)


class Workspace:
    def __init__(self, test: unittest.TestCase) -> None:
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.report = self.root / "verifyReleaseSigning.json"
        self.out = self.root / "bench" / "any"
        self.since = int(time.time()) - 5

    def write(self, text: str, age_seconds: int = 0) -> None:
        self.report.write_text(text)
        if age_seconds:
            stamp = self.since - age_seconds
            os.utime(self.report, (stamp, stamp))

    def publish(self, gradle_exit: int = 0) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = release_signing_row.main([str(self.report), "--out", str(self.out), "--since", str(self.since),
                                             "--gradle-exit", str(gradle_exit)])
        return code, stdout.getvalue(), stderr.getvalue()

    @property
    def fragment(self) -> Path:
        return self.out / f"{ROW}.any.json"


class PublishTest(unittest.TestCase):

    def test_three_matches_with_the_flag_publish_the_row_true_in_the_fragment_shape(self):
        work = Workspace(self)
        work.write(report_json())
        code, out, err = work.publish()
        self.assertEqual(code, 0, err)
        published = json.loads(work.fragment.read_text())
        self.assertEqual(list(published), fragment_keys())
        row = {r["id"]: r for r in json.loads((TOOLS / "gate" / "rows.json").read_text())["rows"]}[ROW]
        self.assertEqual((published["id"], published["device"], published["value"], published["unit"]),
                         (ROW, "any", True, row["unit"]))
        self.assertEqual((row["devices"], row["phase"], row["comparator"]), (["any"], 3, "bool_true"))
        self.assertIn("API 26 to 27, 28 to 32 and 33 and up", published["method"])
        self.assertIn(f"{ROW} = true on any", out)

    def test_collect_and_the_phase_3_gate_score_the_fragment_pass(self):
        work = Workspace(self)
        work.write(report_json())
        self.assertEqual(work.publish()[0], 0)
        measured = work.root / "measured.json"
        collected = subprocess.run([sys.executable, str(TOOLS / "bench" / "collect.py"), "--bench",
                                    str(work.root / "bench"), "--subject", "com.shinsaikou:0.1.0:0000000",
                                    "--out", str(measured)], capture_output=True, text=True, check=False)
        self.assertEqual(collected.returncode, 0, collected.stderr)
        gate = subprocess.run([sys.executable, str(TOOLS / "gate" / "release_gate.py"), "--baseline",
                               str(TOOLS / "gate" / "baseline.json"), "--measured", str(measured),
                               "--phase", "3", "--rows", ROW], capture_output=True, text=True, check=False)
        self.assertEqual(gate.returncode, 0, gate.stdout + gate.stderr)
        verdicts = [line.split()[0] for line in gate.stdout.splitlines() if ROW in line.split()[1:2]]
        self.assertEqual(verdicts, ["PASS"], gate.stdout)


class RefusedTest(unittest.TestCase):

    def setUp(self) -> None:
        self.work = Workspace(self)
        # An earlier run's fragment, which a refused run must not leave to be collected as this one's.
        self.work.out.mkdir(parents=True)
        self.work.fragment.write_text(json.dumps({"id": ROW, "value": True}))

    def refused(self, *reasons: str, gradle_exit: int = 0) -> str:
        code, _, err = self.work.publish(gradle_exit)
        self.assertEqual(code, 1, err)
        self.assertFalse(self.work.fragment.exists(), "a refused run left a fragment behind")
        for reason in reasons:
            self.assertIn(reason, err)
        self.assertIn(f"{ROW} not published", err)
        return err

    def test_no_report(self):
        self.refused("no verifyReleaseSigning.json at")

    def test_a_report_from_before_the_run(self):
        self.work.write(report_json(), age_seconds=60)
        self.refused("before this run started at", "so it is not this run's")

    def test_a_report_that_is_not_json(self):
        self.work.write("{\"ranges\": [")
        self.refused("verifyReleaseSigning.json is not JSON")

    def test_a_release_built_without_the_flag(self):
        self.work.write(report_json(owner_signing=False, ranges=[]))
        self.refused("the release was not built with -Psaikou.ownerSigning=true")

    def test_another_apk(self):
        self.work.write(report_json(apk="/x/app/build/outputs/apk/benchmark/app-benchmark.apk"))
        self.refused("not app-release.apk")

    def test_a_range_missing_out_of_order_or_extra(self):
        for ranges in ([("26-27", "match"), ("33-", "match")],
                       [("28-32", "match"), ("26-27", "match"), ("33-", "match")],
                       [("26-27", "match"), ("28-32", "match"), ("33-", "match"), ("21-25", "match")]):
            with self.subTest(ranges=ranges):
                self.work.write(report_json(ranges=ranges))
                self.refused("the report lists the ranges")

    def test_one_range_without_a_match(self):
        self.work.write(json.dumps({"apk": "/a/app-release.apk", "ownerSigning": True, "ranges": [
            {"range": "26-27", "result": "match"},
            {"range": "28-32", "result": "no match", "reason": "apksigner could not verify it (exit 1)"},
            {"range": "33-", "result": "no match"}]}))
        self.refused("API 28-32: no match (apksigner could not verify it (exit 1))", "API 33-: no match")

    def test_a_failed_gradle_run_even_when_the_report_reads_as_passed(self):
        self.work.write(report_json())
        self.refused("Gradle exited 1", gradle_exit=1)

    def test_every_reason_is_printed_not_only_the_first(self):
        self.work.write(report_json(owner_signing=False, ranges=[("26-27", "no match")]))
        err = self.refused("Gradle exited 1", "not built with -Psaikou.ownerSigning=true", "the report lists the ranges",
                           "API 26-27: no match", gradle_exit=1)
        self.assertIn("4 reason(s) above", err)


class WiringTest(unittest.TestCase):
    """The row's names held to build-logic, the app build, the shell script and gate.sh."""

    def setUp(self) -> None:
        self.gate = (TOOLS / "ci" / "gate.sh").read_text()
        self.script = (TOOLS / "ci" / "release_signing_row.sh").read_text()

    def test_the_ranges_are_the_ones_build_logic_compares(self):
        kotlin = (ROOT / "build-logic" / "src" / "main" / "kotlin" / "saikou" / "gradle" / "signing" /
                  "ReleaseSigners.kt").read_text()
        declared = re.search(r"val RANGES = listOf\((.*)\)", kotlin)
        self.assertIsNotNone(declared)
        pairs = re.findall(r"ApiRange\((\d+), (\d+|null)\)", declared.group(1))
        self.assertEqual(tuple(f"{low}-{'' if high == 'null' else high}" for low, high in pairs),
                         release_signing_row.RANGES)

    def test_the_script_runs_the_task_with_the_flag_and_reads_the_path_it_writes(self):
        self.assertRegex(self.script, r"\n\./gradlew :app:verifyReleaseSigning -Psaikou\.ownerSigning=true ")
        build = (ROOT / "app" / "build.gradle.kts").read_text()
        written = re.search(r'report\.set\(layout\.buildDirectory\.file\("([^"]+)"\)\)', build)
        self.assertIsNotNone(written)
        self.assertIn(f"REPORT=$ROOT/app/build/{written.group(1)}", self.script)

    def test_gate_sh_runs_the_script_in_phase_3_into_the_bench_output(self):
        step = self.gate[self.gate.index('step "2b.'):self.gate.index('step "3. unit tests"')]
        self.assertIn('bash tools/ci/release_signing_row.sh "$BENCH_OUT/any"', step)
        self.assertIn("./gradlew :build-logic:test --rerun", step)
        for name in ("OwnerSigningTest", "ReleaseSignersTest", "VerifyReleaseSigningTest"):
            self.assertIn(f"--require-class saikou.gradle.signing.{name}", step)

    def test_the_flag_is_set_in_phase_3_only(self):
        self.assertIn("\nSIGNING_ARGS=()\nphase3 && SIGNING_ARGS=(-Psaikou.ownerSigning=true)\n", self.gate)
        self.assertEqual(self.gate.count("SIGNING_ARGS=("), 2)

    def test_every_gradle_call_that_builds_the_release_takes_the_flag(self):
        # A call runs on over backslash-newline continuations; step 2's assembleRelease goes through
        # gradle_step and is checked on its own below. A connected-test run goes through
        # connected_run, whose Gradle arguments follow its label, serial and results directory.
        calls = re.findall(r"\./gradlew ((?:\\\n|[^\n])*)", self.gate)
        calls += re.findall(r'connected_run "[^"]+" "\$\w+" \\\n\s*\S+ ((?:\\\n|[^\n])*)', self.gate)
        release = [call for call in calls if "Release" in call.split(" ", 1)[0]]
        self.assertEqual(len(release), 4, release)
        for call in release:
            with self.subTest(call=call.split(" ", 1)[0]):
                self.assertIn('${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"}', call)
        step2 = self.gate[self.gate.index('step "2. build"'):self.gate.index('step "2b.')]
        self.assertIn('gradle_step "assembleRelease" assembleRelease ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"}', step2)

    def test_the_upgrade_check_keeps_reach_only_a_release_built_for_that_check(self):
        # app/upgrade-check-rules.pro keeps what the in-place upgrade check needs in the minified
        # release. Only -Psaikou.upgradeCheck adds it, and the gate never passes that property, so the
        # release the gate builds and the owner installs carries none of it.
        build = (ROOT / "app" / "build.gradle.kts").read_text()
        self.assertIn('if (providers.gradleProperty("saikou.upgradeCheck").isPresent) '
                      'proguardFiles("upgrade-check-rules.pro")', build)
        self.assertEqual(build.count("upgrade-check-rules.pro"), 1)
        self.assertTrue((ROOT / "app" / "upgrade-check-rules.pro").is_file())
        self.assertNotIn("saikou.upgradeCheck", self.gate)


class ScriptUsageTest(unittest.TestCase):

    def test_no_or_a_blank_output_directory_is_a_usage_error(self):
        for argv in ([], [""], ["a", "b"]):
            result = subprocess.run(["bash", str(TOOLS / "ci" / "release_signing_row.sh"), *argv],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 2, (argv, result.stderr))
            self.assertIn("usage: release_signing_row.sh <out dir>", result.stderr)


if __name__ == "__main__":
    unittest.main()
