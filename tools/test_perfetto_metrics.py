#!/usr/bin/env python3
"""Unit tests for tools/bench/perfetto_metrics.py's `main_thread_pm_okhttp_slices` and
`extension_archive_parses`.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The SQL itself was proved on the 2 GB AVD rather than here. A checked-in trace small enough to keep
would still be a recording of a real device, and a cold start trace with extensions installed
carries their names in its logcat and process tables, which nothing in this repository may hold.
So these tests stub the trace processor and pin what is decided in Python: that the query is built
with the package, the main thread test, the window and the platform method list; that the two
halves are summed; and that each of the three guards refuses a trace which could only have
answered 0 (R29, R36). That last part is the reason this metric needed tests at all: before them,
a trace with no application slices in it read as a clean pass.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
METRICS_PATH = TOOLS / "bench" / "perfetto_metrics.py"

_spec = importlib.util.spec_from_file_location("perfetto_metrics", METRICS_PATH)
perfetto_metrics = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = perfetto_metrics
_spec.loader.exec_module(perfetto_metrics)

PACKAGE = "com.shinsaikou"

# A row shaped like a real cold start with 76 extensions: the client built and 76 archives parsed,
# all off the main thread, and every main thread IPackageManager call on the platform list.
CLEAN = {"sections": 0, "binder_calls": 0, "sections_anywhere": 77, "named_binder_calls": 60,
         "main_threads": 1}


def run_metric(row: dict, window: str = "") -> tuple[int, str]:
    """Runs the metric against one stubbed result row; returns (value or exit code, the SQL)."""
    seen = []

    def fake_query(trace, sql):
        seen.append(sql)
        return [row]

    with mock.patch.object(perfetto_metrics, "query", fake_query), mock.patch("sys.stderr"):
        try:
            value = perfetto_metrics.main_thread_pm_okhttp(Path("trace.pftrace"), PACKAGE, window)
        except SystemExit as e:
            return -int(e.code), seen[0]
    return value, seen[0]


class QueryTest(unittest.TestCase):

    def test_names_the_package_and_the_main_thread(self):
        _, sql = run_metric(CLEAN)
        self.assertIn(f"p.name = '{PACKAGE}'", sql)
        self.assertIn("coalesce(t.is_main_thread, t.tid = p.pid)", sql)

    def test_matches_the_two_section_prefixes_and_the_binder_interface(self):
        _, sql = run_metric(CLEAN)
        self.assertIn("s.name glob 'PackageManager*'", sql)
        self.assertIn("s.name glob 'OkHttpClient*'", sql)
        self.assertIn("b.interface = 'IPackageManager'", sql)

    def test_excludes_exactly_the_platform_methods(self):
        _, sql = run_metric(CLEAN)
        for method in perfetto_metrics.PLATFORM_MAIN_THREAD_PM_METHODS:
            self.assertIn(f"'{method}'", sql)
        # The installed package scan is the call #3703 is about, and it must stay counted.
        self.assertNotIn("getInstalledPackages", perfetto_metrics.PLATFORM_MAIN_THREAD_PM_METHODS)
        self.assertNotIn("getInstalledPackages", sql)

    def test_the_window_bounds_both_halves(self):
        _, sql = run_metric(CLEAN, " and s.ts >= 10 and s.ts <= 20")
        self.assertIn("s.ts >= 10 and s.ts <= 20", sql)
        self.assertIn("b.client_ts >= 10 and b.client_ts <= 20", sql)

    def test_no_window_leaves_no_bound(self):
        _, sql = run_metric(CLEAN)
        self.assertNotIn("s.ts >=", sql)
        self.assertNotIn("client_ts >=", sql)


class ValueTest(unittest.TestCase):

    def test_a_clean_trace_is_zero(self):
        self.assertEqual(run_metric(CLEAN)[0], 0)

    def test_sections_and_binder_calls_are_summed(self):
        self.assertEqual(run_metric(dict(CLEAN, sections=1, binder_calls=1))[0], 2)

    def test_a_null_count_is_zero_rather_than_an_error(self):
        self.assertEqual(run_metric(dict(CLEAN, sections=None, binder_calls=None))[0], 0)


class GuardTest(unittest.TestCase):
    """Each guard stands for a recording that could only ever have produced 0."""

    def test_a_trace_without_the_process_is_refused(self):
        self.assertEqual(run_metric(dict(CLEAN, main_threads=0))[0], -2)

    def test_a_trace_without_application_sections_is_refused(self):
        self.assertEqual(run_metric(dict(CLEAN, sections_anywhere=0))[0], -2)

    def test_a_trace_without_binder_names_is_refused(self):
        self.assertEqual(run_metric(dict(CLEAN, named_binder_calls=0))[0], -2)

    def test_an_empty_result_is_refused(self):
        seen = []
        with mock.patch.object(perfetto_metrics, "query", lambda trace, sql: seen.append(sql) or []), \
                mock.patch("sys.stderr"):
            with self.assertRaises(SystemExit) as raised:
                perfetto_metrics.main_thread_pm_okhttp(Path("trace.pftrace"), PACKAGE, "")
        self.assertEqual(raised.exception.code, 2)


class ArchiveParseTest(unittest.TestCase):
    """The per-run count coldstart.sh compares with the number of extensions the seed loaded."""

    def run_count(self, rows: list[dict], window: str = "") -> tuple[int, str]:
        seen = []
        with mock.patch.object(perfetto_metrics, "query", lambda trace, sql: seen.append(sql) or rows):
            value = perfetto_metrics.extension_archive_parses(Path("trace.pftrace"), PACKAGE, window)
        return value, seen[0]

    def test_counts_the_loader_section_on_any_thread_of_the_package(self):
        value, sql = self.run_count([{"sections": 76}])
        self.assertEqual(value, 76)
        self.assertIn(f"p.name = '{PACKAGE}'", sql)
        self.assertIn("s.name = 'PackageManager.getPackageArchiveInfo'", sql)
        # The loader parses on its IO dispatcher; a main thread filter would count nothing.
        self.assertNotIn("is_main_thread", sql)

    def test_the_window_bounds_the_count(self):
        _, sql = self.run_count([{"sections": 1}], " and s.ts >= 10 and s.ts <= 20")
        self.assertIn("s.ts >= 10 and s.ts <= 20", sql)

    def test_no_sections_is_zero(self):
        self.assertEqual(self.run_count([{"sections": None}])[0], 0)
        self.assertEqual(self.run_count([])[0], 0)


class ArgumentTest(unittest.TestCase):

    def run_main(self, *argv: str) -> int:
        with mock.patch.object(sys, "argv", ["perfetto_metrics.py", *argv]), \
                mock.patch.object(perfetto_metrics, "query", lambda trace, sql: [CLEAN]), \
                mock.patch("sys.stdout"), mock.patch("sys.stderr"):
            try:
                return perfetto_metrics.main()
            except SystemExit as e:
                return e.code

    def test_a_missing_trace_is_missing_input(self):
        self.assertEqual(
            self.run_main("/nonexistent.pftrace", "--package", PACKAGE,
                          "--metric", "main_thread_pm_okhttp_slices"), 2)

    def test_a_malformed_window_is_missing_input(self):
        self.assertEqual(
            self.run_main(__file__, "--package", PACKAGE, "--metric",
                          "main_thread_pm_okhttp_slices", "--window", "soon"), 2)

    def test_the_package_is_required(self):
        self.assertEqual(self.run_main(__file__, "--metric", "main_thread_pm_okhttp_slices"), 2)

    def test_the_archive_parse_count_is_a_metric(self):
        self.assertEqual(
            self.run_main(__file__, "--package", PACKAGE, "--metric", "extension_archive_parses"), 0)

    def test_a_readable_trace_answers(self):
        self.assertEqual(
            self.run_main(__file__, "--package", PACKAGE, "--metric", "main_thread_pm_okhttp_slices",
                          "--window", "10,20"), 0)


if __name__ == "__main__":
    unittest.main()
