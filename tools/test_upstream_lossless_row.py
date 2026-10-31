#!/usr/bin/env python3
"""Unit tests for tools/ci/upstream_lossless_row.py and the wiring of upstream_lossless_row.sh.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The results directories are synthetic JUnit XML in the shape Gradle writes for a JVM test task, one
`TEST-<class>.xml` per class. Nothing runs Gradle here; the gate step does (unit R2's report records
the step run for real, passing and refused).

The cases that matter, and why each one is here:
  - two fresh passing classes publish the row true on `any`, in common.sh's fragment() shape (its
    keys read out of common.sh), with rows.json's unit, and collect.py and release_gate.py --phase 3
    score it PASS;
  - nothing is published, and an earlier fragment of the row is removed rather than left to be
    collected as this run's, when: a class wrote no XML, a suite is older than the run (what a task
    served from the build cache leaves), a suite has no or an unreadable timestamp, a class ran no
    test, a test failed, errored or was skipped, the suite's counts disagree with its cases, the XML
    is not XML, or Gradle exited other than 0;
  - every refusal reason is printed, not only the first;
  - the two classes are the ones in :backup's test tree, the shell script names the same two and
    runs them with --rerun, and gate.sh's phase 3 step runs the script, so a rename cannot leave the
    row standing on a filter that matches nothing.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
_spec = importlib.util.spec_from_file_location("upstream_lossless_row", TOOLS / "ci" / "upstream_lossless_row.py")
upstream_lossless_row = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = upstream_lossless_row
_spec.loader.exec_module(upstream_lossless_row)
sys.path.insert(0, str(TOOLS))
from test_bench_launch import extract_function  # noqa: E402

FIELDS, EXPORT = upstream_lossless_row.CLASSES
SINCE = datetime(2026, 9, 25, 13, 0, 0, tzinfo=timezone.utc)
AFTER = "2026-09-25T13:00:04.512Z"


def suite_xml(name: str, cases: list[str | tuple[str, str]], timestamp: str | None = AFTER,
              counts: dict[str, int] | None = None) -> str:
    """A suite as Gradle writes it. A case is its name, or (name, outcome) for failure, error or skipped."""
    body = []
    tally = {"failures": 0, "errors": 0, "skipped": 0}
    for case in cases:
        test, outcome = (case, None) if isinstance(case, str) else case
        if outcome is None:
            body.append(f'  <testcase name="{test}" classname="{name}" time="0.01"/>')
            continue
        tally[{"failure": "failures", "error": "errors", "skipped": "skipped"}[outcome]] += 1
        body.append(f'  <testcase name="{test}" classname="{name}" time="0.01">\n'
                    f'    <{outcome} message="m" type="t">trace</{outcome}>\n  </testcase>')
    tally["tests"] = len(cases)
    tally.update(counts or {})
    stamp = f' timestamp="{timestamp}"' if timestamp is not None else ""
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n'
            f'<testsuite name="{name}" tests="{tally["tests"]}" skipped="{tally["skipped"]}" '
            f'failures="{tally["failures"]}" errors="{tally["errors"]}"{stamp} hostname="h" time="1.0">\n'
            + "\n".join(body) + "\n</testsuite>\n")


class Workspace:
    def __init__(self, test: unittest.TestCase) -> None:
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.results = self.root / "test-results"
        self.results.mkdir()
        self.out = self.root / "bench" / "any"

    def write(self, name: str, text: str) -> None:
        (self.results / f"TEST-{name}.xml").write_text(text)

    def passing(self, *names: str) -> None:
        for name in names:
            self.write(name, suite_xml(name, ["a()", "b()"]))

    def publish(self, gradle_exit: int = 0) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = upstream_lossless_row.main([
                str(self.results), "--out", str(self.out), "--since", str(int(SINCE.timestamp())),
                "--gradle-exit", str(gradle_exit)])
        return code, stdout.getvalue(), stderr.getvalue()

    @property
    def fragment(self) -> Path:
        return self.out / "export_reimports_upstream_lossless.any.json"


def fragment_keys() -> list[str]:
    """The keys tools/bench/common.sh's fragment() writes, in its order."""
    body = extract_function((TOOLS / "bench" / "common.sh").read_text(), "fragment")
    keys = re.findall(r'^\s*"(\w+)":', body, re.MULTILINE)
    if not keys:
        raise AssertionError("common.sh's fragment() writes no key this test can read")
    return keys


class PublishTest(unittest.TestCase):

    def test_two_fresh_passing_classes_publish_the_row_true_in_the_fragment_shape(self):
        work = Workspace(self)
        work.passing(FIELDS, EXPORT)
        code, out, err = work.publish()
        self.assertEqual(code, 0, err)
        published = json.loads(work.fragment.read_text())
        self.assertEqual(list(published), fragment_keys())
        row = {r["id"]: r for r in json.loads((TOOLS / "gate" / "rows.json").read_text())["rows"]}[
            "export_reimports_upstream_lossless"]
        self.assertEqual((published["id"], published["device"], published["value"], published["unit"]),
                         (row["id"], "any", True, row["unit"]))
        self.assertEqual(row["devices"], ["any"])
        self.assertEqual((row["phase"], row["comparator"]), (3, "bool_true"))
        self.assertIn("UpstreamBackupFieldsTest 2", published["method"])
        self.assertIn("UpstreamLosslessExportTest 2", published["method"])
        self.assertIn(f"  2  {FIELDS}", out)
        self.assertIn(f"  2  {EXPORT}", out)

    def test_a_suite_timestamp_without_a_zone_is_read_as_utc(self):
        work = Workspace(self)
        work.passing(FIELDS)
        work.write(EXPORT, suite_xml(EXPORT, ["a()"], timestamp="2026-09-25T13:00:00"))
        code, _, err = work.publish()
        self.assertEqual(code, 0, err)

    def test_collect_and_the_phase_3_gate_score_the_fragment_pass(self):
        work = Workspace(self)
        work.passing(FIELDS, EXPORT)
        self.assertEqual(work.publish()[0], 0)
        measured = work.root / "measured.json"
        collected = subprocess.run([sys.executable, str(TOOLS / "bench" / "collect.py"), "--bench",
                                    str(work.root / "bench"), "--subject", "com.shinsaikou:0.1.0:0000000",
                                    "--out", str(measured)], capture_output=True, text=True, check=False)
        self.assertEqual(collected.returncode, 0, collected.stderr)
        gate = subprocess.run([sys.executable, str(TOOLS / "gate" / "release_gate.py"), "--baseline",
                               str(TOOLS / "gate" / "baseline.json"), "--measured", str(measured),
                               "--phase", "3", "--rows", "export_reimports_upstream_lossless"],
                              capture_output=True, text=True, check=False)
        self.assertEqual(gate.returncode, 0, gate.stdout + gate.stderr)
        verdicts = [line.split()[0] for line in gate.stdout.splitlines()
                    if "export_reimports_upstream_lossless" in line.split()[1:2]]
        self.assertEqual(verdicts, ["PASS"], gate.stdout)


class RefusedTest(unittest.TestCase):

    def setUp(self) -> None:
        self.work = Workspace(self)
        # An earlier run's fragment, which a refused run must not leave to be collected as this one's.
        self.work.out.mkdir(parents=True)
        self.work.fragment.write_text(json.dumps({"id": "export_reimports_upstream_lossless", "value": True}))

    def refused(self, *reasons: str, gradle_exit: int = 0) -> str:
        code, _, err = self.work.publish(gradle_exit)
        self.assertEqual(code, 1, err)
        self.assertFalse(self.work.fragment.exists(), "a refused run left a fragment behind")
        for reason in reasons:
            self.assertIn(reason, err)
        self.assertIn("export_reimports_upstream_lossless not published", err)
        return err

    def test_a_class_that_wrote_no_xml(self):
        self.work.passing(FIELDS)
        self.refused(f"{EXPORT}: no TEST-{EXPORT}.xml")

    def test_a_suite_from_before_the_run_as_a_cache_hit_restores_it(self):
        self.work.passing(FIELDS)
        self.work.write(EXPORT, suite_xml(EXPORT, ["a()"], timestamp="2026-09-25T12:59:59Z"))
        self.refused(f"{EXPORT}: TEST-{EXPORT}.xml is from 2026-09-25T12:59:59+00:00, before this run")

    def test_a_suite_with_no_or_an_unreadable_timestamp(self):
        self.work.write(FIELDS, suite_xml(FIELDS, ["a()"], timestamp=None))
        self.work.write(EXPORT, suite_xml(EXPORT, ["a()"], timestamp="yesterday"))
        self.refused(f"{FIELDS}: TEST-{FIELDS}.xml has no readable timestamp (None)",
                     f"{EXPORT}: TEST-{EXPORT}.xml has no readable timestamp ('yesterday')")

    def test_a_class_that_ran_no_test(self):
        self.work.passing(FIELDS)
        self.work.write(EXPORT, suite_xml(EXPORT, []))
        self.refused(f"{EXPORT}: ran no test")

    def test_a_failed_an_errored_and_a_skipped_test(self):
        self.work.write(FIELDS, suite_xml(FIELDS, ["a()", ("b()", "failure")]))
        self.work.write(EXPORT, suite_xml(EXPORT, [("c()", "error"), ("d()", "skipped")]))
        self.refused(f"{FIELDS}#b(): failure", f"{EXPORT}#c(): error", f"{EXPORT}#d(): skipped",
                     f"{FIELDS}: the suite counts 1 failures")

    def test_suite_counts_that_disagree_with_the_cases_listed(self):
        self.work.passing(FIELDS)
        self.work.write(EXPORT, suite_xml(EXPORT, ["a()"], counts={"tests": 3, "skipped": 1}))
        self.refused(f"{EXPORT}: the suite counts 3 tests and lists 1", f"{EXPORT}: the suite counts 1 skipped")

    def test_xml_that_is_not_xml(self):
        self.work.passing(FIELDS)
        self.work.write(EXPORT, "<testsuite")
        self.refused(f"{EXPORT}: TEST-{EXPORT}.xml is not XML")

    def test_a_failed_gradle_run_even_when_both_files_read_as_passed(self):
        self.work.passing(FIELDS, EXPORT)
        self.refused("Gradle exited 1", gradle_exit=1)

    def test_every_reason_is_printed_not_only_the_first(self):
        err = self.refused("Gradle exited 1", f"{FIELDS}: no TEST-{FIELDS}.xml",
                           f"{EXPORT}: no TEST-{EXPORT}.xml", gradle_exit=1)
        self.assertIn("3 reason(s) above", err)


class WiringTest(unittest.TestCase):
    """The names the row stands on, held to the tree, the shell script and gate.sh."""

    def test_each_class_is_a_test_file_in_backup(self):
        for name in upstream_lossless_row.CLASSES:
            path = ROOT / "backup" / "src" / "test" / "kotlin" / (name.replace(".", "/") + ".kt")
            self.assertTrue(path.is_file(), f"{name} has no source at {path}")

    def test_the_script_runs_the_same_two_classes_with_rerun(self):
        script = (TOOLS / "ci" / "upstream_lossless_row.sh").read_text()
        classes = re.search(r"^CLASSES=\((.*)\)$", script, re.MULTILINE)
        self.assertIsNotNone(classes)
        self.assertEqual(tuple(classes.group(1).split()), upstream_lossless_row.CLASSES)
        self.assertRegex(script, r"\./gradlew :backup:testDebugUnitTest --rerun ")
        self.assertIn("backup/build/test-results/testDebugUnitTest", script)

    def test_gate_sh_runs_the_script_in_phase_3_into_the_bench_output(self):
        gate = (TOOLS / "ci" / "gate.sh").read_text()
        step = gate[gate.index('step "3b.'):gate.index('step "4. AVDs"')]
        self.assertIn('bash tools/ci/upstream_lossless_row.sh "$BENCH_OUT/any"', step)
        guard = gate[:gate.index('step "3b.')].rsplit("\n", 2)[-2]
        self.assertEqual(guard.strip(), "if phase3; then")


class ScriptUsageTest(unittest.TestCase):

    def test_no_or_a_blank_output_directory_is_a_usage_error(self):
        for argv in ([], [""], ["a", "b"]):
            result = subprocess.run(["bash", str(TOOLS / "ci" / "upstream_lossless_row.sh"), *argv],
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 2, (argv, result.stderr))
            self.assertIn("usage: upstream_lossless_row.sh <out dir>", result.stderr)


if __name__ == "__main__":
    unittest.main()
