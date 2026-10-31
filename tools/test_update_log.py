#!/usr/bin/env python3
"""Unit tests for tools/bench/update_log.py, the reader, the rates and the guard of ruling R66.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Every log here is written by hand in the shape update_fixture_server.py writes, one JSON object per
line, so nothing needs a device or a server. The cases that matter, and why each one is here:
  - the reader refuses a log it cannot vouch for: a line that is not whole JSON, a request line with
    a field missing, a log with no start header;
  - each source's rate is its request count over its own window, and the throughput ratio is the
    slowest scenario source over the 5 per second limit, and None when a source never answered;
  - the guard ends the run for every way an update can do less or other work than the scenario asks
    for: an entry missing, fetched twice or foreign, a failed or aborted request, a details or page
    request, a request outside the tap-to-removal window, and a server that saw nothing;
  - the 10 percent comparison of the two windows sits exactly on its boundary;
  - a run that ended carries its reasons and no duration, so no reader can publish it.
"""

from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
sys.path.insert(0, str(BENCH))

_spec = importlib.util.spec_from_file_location("update_log", BENCH / "update_log.py")
update_log = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = update_log
_spec.loader.exec_module(update_log)
fx = update_log.fx

HEADER = {"event": "start", "monotonic": 0.0, "port": fx.SERVER_PORT}
SMALL = fx.Scenario("2x3", sources=2, per_source=3)


def record(source, entry, t0, t1=None, kind="chapters", status=200, aborted=False, path=None) -> dict:
    return {"t0": t0, "t1": t0 + 0.5 if t1 is None else t1, "source": source,
            "source_id": str(fx.source_id(source)) if source else None, "kind": kind, "entry": entry,
            "status": status, "aborted": aborted, "path": path or f"/manga/{entry}/chapters", "latency_ms": 500.0}


def log_text(records: list[dict], header: dict | None = HEADER) -> str:
    lines = ([json.dumps(header)] if header else []) + [json.dumps(r) for r in records]
    return "\n".join(lines) + "\n"


def full_run(start: float = 10.0) -> list[dict]:
    """Every entry of SMALL once, a second apart per source, from `start`."""
    return [record(s, e, start + e - 1) for s in (1, 2) for e in (1, 2, 3)]


def requests_of(records: list[dict]) -> list:
    return update_log.read_log(log_text(records))[1]


class ReadLogTest(unittest.TestCase):
    def test_reads_the_header_and_every_request_in_order(self):
        text = log_text(full_run()) + json.dumps({"event": "stop", "monotonic": 99.0}) + "\n"
        header, requests = update_log.read_log(text)
        self.assertEqual(header["port"], fx.SERVER_PORT)
        self.assertEqual([(r.source, r.entry) for r in requests], [(1, 1), (1, 2), (1, 3), (2, 1), (2, 2), (2, 3)])

    def test_a_log_with_no_start_header_is_refused(self):
        with self.assertRaisesRegex(update_log.LogError, "no start header"):
            update_log.read_log(log_text(full_run(), header=None))

    def test_a_line_cut_short_is_refused_by_line_number(self):
        text = log_text(full_run()) + '{"t0": 1.0, "t1'
        with self.assertRaisesRegex(update_log.LogError, "line 8 is not JSON"):
            update_log.read_log(text)

    def test_a_request_line_missing_a_field_is_refused(self):
        broken = record(1, 1, 1.0)
        del broken["status"]
        with self.assertRaisesRegex(update_log.LogError, "line 2 is not a request record"):
            update_log.read_log(log_text([broken]))


class RateTest(unittest.TestCase):
    def test_each_source_rate_is_its_count_over_its_own_window(self):
        # Source 1: three requests from 10.0 to 12.5, 2.5 s. Source 2 starts later and runs longer.
        records = full_run()[:3] + [record(2, e, 20.0 + 2 * (e - 1)) for e in (1, 2, 3)]
        sources = update_log.per_source(update_log.source_requests(requests_of(records)))
        self.assertEqual(sources[1]["rps"], round(3 / 2.5, 4))
        self.assertEqual(sources[2]["rps"], round(3 / 4.5, 4))
        self.assertEqual(sources[1]["id"], str(fx.source_id(1)))

    def test_the_ratio_is_the_slowest_source_over_the_limit(self):
        records = [record(1, e, 10.0 + 0.2 * e, 10.2 + 0.2 * e) for e in (1, 2, 3)]
        records += [record(2, e, 10.0 + e, 10.5 + e) for e in (1, 2, 3)]
        sources = update_log.per_source(update_log.source_requests(requests_of(records)))
        # Source 1 ran 3 in 0.6 s (5 per second), source 2 ran 3 in 2.5 s (1.2 per second).
        self.assertEqual(update_log.min_rate_ratio(sources, SMALL), round(1.2 / 5, 4))

    def test_the_ratio_is_none_when_a_scenario_source_has_no_rate(self):
        sources = update_log.per_source(update_log.source_requests(requests_of(full_run()[:3])))
        self.assertIsNone(update_log.min_rate_ratio(sources, SMALL))

    def test_repository_requests_are_not_source_requests(self):
        records = full_run() + [record(None, None, 1.0, kind="repo", path="/repo/index.min.json")]
        self.assertEqual(len(update_log.source_requests(requests_of(records))), 6)


class GuardTest(unittest.TestCase):
    def guard(self, records: list[dict], tap: float | None = 9.0, gone: float | None = 30.0) -> list[str]:
        return update_log.guard(requests_of(records), SMALL, tap, gone)

    def test_a_whole_run_passes(self):
        self.assertEqual(self.guard(full_run()), [])

    def test_a_missing_entry_ends_the_run(self):
        # The R29 shape: a library one entry short of the scenario.
        problems = self.guard(full_run()[:-1])
        self.assertIn("5 chapter-list request(s) for the 6 seeded entries", problems)
        self.assertTrue(any("1 seeded entry was never fetched, first s02/3" in p for p in problems))

    def test_an_entry_fetched_twice_ends_the_run(self):
        problems = self.guard(full_run() + [record(1, 2, 20.0)])
        self.assertTrue(any("1 entry was fetched more than once, first s01/2" in p for p in problems))

    def test_an_entry_the_scenario_does_not_seed_ends_the_run(self):
        problems = self.guard(full_run()[:-1] + [record(3, 1, 12.0)])
        self.assertTrue(any("for entries the scenario does not seed, first s03/1" in p for p in problems))

    def test_a_failed_or_aborted_request_ends_the_run(self):
        records = full_run()
        records[0]["status"] = 404
        records[1]["aborted"] = True
        problems = self.guard(records)
        self.assertTrue(any(p.startswith("2 fixture request(s) failed") for p in problems))

    def test_a_request_other_than_a_chapter_list_ends_the_run(self):
        problems = self.guard(full_run() + [record(1, 1, 12.0, kind="details", path="/manga/1")])
        self.assertTrue(any("source requests other than chapter lists: 1 details" in p for p in problems))

    def test_requests_outside_the_device_window_end_the_run(self):
        problems = self.guard(full_run(), tap=10.5, gone=11.5)
        self.assertIn("2 source request(s) arrived before the tap", problems)
        self.assertIn("2 source request(s) arrived after the update notification was removed", problems)

    def test_no_request_at_all_ends_the_run(self):
        self.assertEqual(self.guard([]), ["the fixture server received no source request, so no update reached it"])


class WindowTest(unittest.TestCase):
    def test_ten_percent_stands_and_more_ends_the_run(self):
        self.assertIsNone(update_log.compare_windows(100.0, 110.0)[1])
        self.assertIsNone(update_log.compare_windows(100.0, 90.0)[1])
        difference, problem = update_log.compare_windows(100.0, 110.5)
        self.assertAlmostEqual(difference, 10.5)
        self.assertIn("differ by 10.5 percent", problem)

    def test_a_zero_server_window_cannot_pass(self):
        self.assertIsNotNone(update_log.compare_windows(0.0, 1.0)[1])


class ResultTest(unittest.TestCase):
    def test_a_measured_run_publishes_the_server_window(self):
        out = update_log.result(requests_of(full_run()), SMALL, {"tap": 9.9, "gone": 12.6, "problems": []})
        self.assertEqual(out["status"], "ok")
        self.assertEqual(out["duration_ms"], 2500)
        self.assertEqual(out["server_window_ms"], 2500)
        self.assertEqual(out["device_window_ms"], 2700)
        self.assertEqual(out["window_difference_pct"], 8.0)
        self.assertEqual(out["requests"], {"chapters": 6})
        self.assertEqual(out["reasons"], [])

    def test_a_run_whose_windows_differ_ends_with_no_duration(self):
        out = update_log.result(requests_of(full_run()), SMALL, {"tap": 9.9, "gone": 13.0, "problems": []})
        self.assertEqual(out["status"], "ended")
        self.assertIsNone(out["duration_ms"])
        self.assertEqual(out["server_window_ms"], 2500)
        self.assertTrue(any("over the 10 percent R36 allows" in r for r in out["reasons"]))

    def test_a_driver_problem_ends_the_run(self):
        drive = {"tap": 9.9, "gone": None, "problems": ["the notification's removal is known only to within 3.0 s"]}
        out = update_log.result(requests_of(full_run()), SMALL, drive)
        self.assertEqual(out["status"], "ended")
        self.assertIsNone(out["duration_ms"])
        self.assertIn("the driver recorded no device-side window", out["reasons"])
        self.assertEqual(out["reasons"][0], drive["problems"][0])


class CommandLineTest(unittest.TestCase):
    def run_cli(self, log: str, drive: dict) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as tmp:
            log_path, drive_path = Path(tmp, "requests.jsonl"), Path(tmp, "drive.json")
            log_path.write_text(log)
            drive_path.write_text(json.dumps(drive))
            return subprocess.run([sys.executable, str(BENCH / "update_log.py"), str(log_path), "--scenario", "500x2",
                                   "--drive", str(drive_path), "--field", "app=upstream"],
                                  capture_output=True, text=True)

    def test_an_ended_run_still_prints_its_json_and_exits_3(self):
        result = self.run_cli(log_text(full_run()), {"tap": 9.9, "gone": 12.6, "problems": []})
        self.assertEqual(result.returncode, 3)
        out = json.loads(result.stdout)
        self.assertEqual(out["app"], "upstream")
        self.assertEqual(out["status"], "ended")
        self.assertIn("6 chapter-list request(s) for the 500 seeded entries", out["reasons"])

    def test_an_unreadable_log_exits_2(self):
        result = self.run_cli("not json\n", {"tap": 1.0, "gone": 2.0, "problems": []})
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
