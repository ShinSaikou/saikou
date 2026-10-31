#!/usr/bin/env python3
"""Unit tests for tools/bench/anr_count.py, the `cold_start_anr_count` row.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Every line here is written in the shape `adb logcat -b events -d` prints on the API 36 AVDs, which
is the only input the parser sees; the two anchor lines were copied from a real dump on
`saikou_pixel8a_2g` and only their package is the one under test. Nothing needs a device.

The cases that matter, and why each one is here:
  - an ANR of the package counts, and one of any other package does not, including a package whose
    name merely starts with this one's, which a prefix match would count;
  - an ANR of one of the package's own processes (`<package>:<name>`) counts;
  - the reason field carries commas and brackets, and must not shift the process field;
  - a dump that never saw the package start is refused rather than read as zero ANRs (R29), which
    is the one way this row could pass while looking at nothing;
  - the sum is over every dump, one per cold start run.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ANR_COUNT_PATH = TOOLS / "bench" / "anr_count.py"

_spec = importlib.util.spec_from_file_location("anr_count", ANR_COUNT_PATH)
anr_count = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = anr_count
_spec.loader.exec_module(anr_count)

PACKAGE = "com.shinsaikou"

PROC_START = (
    "09-23 21:03:22.212   655   684 I am_proc_start: "
    "[0,5438,10228,com.shinsaikou,next-top-activity,{com.shinsaikou/com.shinsaikou.ui.main.MainActivity}]"
)
ANR = (
    "09-23 21:03:30.213   655  5477 I am_anr  : "
    "[0,5438,com.shinsaikou,552091204,Input dispatching timed out (Application does not have a focused window).]"
)
NOISE = (
    "09-23 21:03:22.220   655   732 I am_proc_bound: [0,5438,com.shinsaikou]\n"
    "09-23 21:03:23.100  5438  5438 I wm_on_create_called: "
    "[46011768,com.shinsaikou.ui.main.MainActivity,performCreate,10006]"
)


def other_anr(process: str) -> str:
    return f"09-23 21:03:31.000   655  5478 I am_anr  : [0,6000,{process},0,Broadcast of Intent {{ act=x }}]"


class CountDumpTest(unittest.TestCase):

    def test_a_clean_launch_is_zero(self):
        self.assertEqual(anr_count.count_dump("\n".join([PROC_START, NOISE]), PACKAGE), 0)

    def test_an_anr_of_the_package_counts(self):
        self.assertEqual(anr_count.count_dump("\n".join([PROC_START, NOISE, ANR]), PACKAGE), 1)

    def test_every_anr_line_counts(self):
        self.assertEqual(anr_count.count_dump("\n".join([PROC_START, ANR, ANR]), PACKAGE), 2)

    def test_another_package_does_not_count(self):
        dump = "\n".join([PROC_START, other_anr("com.android.systemui"), other_anr("com.shinsaikou.test")])
        self.assertEqual(anr_count.count_dump(dump, PACKAGE), 0)

    def test_a_name_that_only_starts_with_the_package_does_not_count(self):
        dump = "\n".join([PROC_START, other_anr("com.shinsaikoux")])
        self.assertEqual(anr_count.count_dump(dump, PACKAGE), 0)

    def test_one_of_the_packages_own_processes_counts(self):
        dump = "\n".join([PROC_START, other_anr("com.shinsaikou:worker")])
        self.assertEqual(anr_count.count_dump(dump, PACKAGE), 1)

    def test_commas_and_brackets_in_the_reason_do_not_move_the_process(self):
        line = (
            "09-23 21:03:30.213   655  5477 I am_anr  : "
            "[0,5438,com.shinsaikou,0,executing service com.shinsaikou/.X, [a, b], {c,d}]"
        )
        self.assertEqual(anr_count.count_dump("\n".join([PROC_START, line]), PACKAGE), 1)

    def test_carriage_returns_from_adb_are_tolerated(self):
        dump = "\r\n".join([PROC_START, ANR]) + "\r\n"
        self.assertEqual(anr_count.count_dump(dump, PACKAGE), 1)

    def test_a_dump_that_never_saw_the_package_start_is_refused(self):
        with self.assertRaises(anr_count.NoLaunch):
            anr_count.count_dump(NOISE, PACKAGE)

    def test_an_empty_dump_is_refused(self):
        with self.assertRaises(anr_count.NoLaunch):
            anr_count.count_dump("", PACKAGE)

    def test_another_package_starting_does_not_stand_in_for_this_one(self):
        start = PROC_START.replace(",com.shinsaikou,next-top", ",com.shinsaikou.test,next-top")
        with self.assertRaises(anr_count.NoLaunch):
            anr_count.count_dump("\n".join([start, ANR]), PACKAGE)


class CommandLineTest(unittest.TestCase):

    def run_tool(self, *dumps: str) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, text in enumerate(dumps):
                path = Path(directory) / f"events-{index}.txt"
                path.write_text(text)
                paths.append(str(path))
            return subprocess.run(
                [sys.executable, str(ANR_COUNT_PATH), "--package", PACKAGE, *paths],
                capture_output=True, text=True,
            )

    def test_prints_the_sum_over_every_dump(self):
        result = self.run_tool(
            "\n".join([PROC_START, ANR]),
            "\n".join([PROC_START, NOISE]),
            "\n".join([PROC_START, ANR, ANR]),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "3")

    def test_one_dump_without_a_launch_fails_the_whole_count(self):
        result = self.run_tool("\n".join([PROC_START, ANR]), NOISE)
        self.assertEqual(result.returncode, 3)
        self.assertEqual(result.stdout, "")
        self.assertIn("am_proc_start", result.stderr)

    def test_a_missing_dump_is_missing_input(self):
        result = subprocess.run(
            [sys.executable, str(ANR_COUNT_PATH), "--package", PACKAGE, "/nonexistent/events.txt"],
            capture_output=True, text=True,
        )
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
