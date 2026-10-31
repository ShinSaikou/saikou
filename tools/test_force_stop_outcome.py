#!/usr/bin/env python3
"""Unit tests for tools/ci/force_stop_outcome.py, which reads a force-stop row's outcome file (Q8).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The accepted outcome is the shape GateRowsTest's part one writes, with the values of a real run on
the 4 GB phone profile (unit I's report quotes it): six chapters of three sources, the first
downloading with one of its three pages on disk and two page requests held. Nothing needs a device.

The cases that matter, and why each one is here:
  - a mid-flight queue is readable, and what it prints names the chapter, its pages and the pid;
  - no file is unmeasurable (R36), and quotes part one's own failure when its output is given;
  - a state that would not test the row is unmeasurable, not a pass: an empty queue, nothing part
    way through its pages (none started, or all done), a failed chapter, no request held;
  - a file that is not this row's outcome is an input error: not JSON, not an object, another
    schema, another package, no pid, a field of the wrong type, a row nothing here reads;
  - the queue itself is checked for what part two relies on: each chapter once, in queue order.
"""

from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
# force_stop_outcome imports lowram_outcome from its own directory, as it does when run as a script.
sys.path.insert(0, str(TOOLS / "ci"))
_spec = importlib.util.spec_from_file_location("force_stop_outcome", TOOLS / "ci" / "force_stop_outcome.py")
force_stop_outcome = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = force_stop_outcome
_spec.loader.exec_module(force_stop_outcome)

PACKAGE = "com.shinsaikou"


def queue_row(chapter: int, order: int, state: str = "QUEUED", done: int = 0, total: int = 0) -> dict:
    return {"chapterId": chapter, "mangaId": 100 + (chapter - 1) // 2, "state": state, "queueOrder": order,
            "downloadedPages": done, "totalPages": total}


MID_FLIGHT = {
    "schema": 1,
    "row": "download_queue_survives_force_stop",
    "package": PACKAGE,
    "pid": 8123,
    "writtenAt": "2026-09-24T17:02:11Z",
    "held": 2,
    "queue": [queue_row(1, 1, "DOWNLOADING", 1, 3)] + [queue_row(c, c) for c in range(2, 7)],
    "sourceIds": [1, 2, 3],
    "extension": "app.fixture.libraryupdate",
}

# The last lines of `am instrument -r` for a part one that failed its wait.
PART_ONE_FAILED = """INSTRUMENTATION_STATUS: class=com.shinsaikou.gate.GateRowsTest
INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: the downloads never stood mid-flight within 60000 ms: held 0, queue
\tat com.shinsaikou.gate.GateRowsTest.awaitMidFlight(GateRowsTest.kt:1083)
INSTRUMENTATION_STATUS: test=theDownloadQueueForceStopPartOneLeavesDownloadsMidFlight
INSTRUMENTATION_STATUS_CODE: -2
INSTRUMENTATION_RESULT: stream=

Time: 61.2

FAILURES!!!
Tests run: 1,  Failures: 1


INSTRUMENTATION_CODE: -1
"""


class OutcomeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name, "outcome.pulled")

    def run_with(self, outcome=None, text: str | None = None, log: str | None = None) -> tuple[int, str, str]:
        if outcome is not None:
            self.path.write_text(json.dumps(outcome))
        elif text is not None:
            self.path.write_text(text)
        argv = [str(self.path), "--package", PACKAGE]
        if log is not None:
            log_path = Path(self.tmp.name, "part-one.txt")
            log_path.write_text(log)
            argv += ["--part-one-log", str(log_path)]
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = force_stop_outcome.main(argv)
        return code, out.getvalue(), err.getvalue()

    def changed(self, **fields) -> dict:
        outcome = copy.deepcopy(MID_FLIGHT)
        outcome.update(fields)
        return outcome

    def test_a_mid_flight_queue_is_readable_and_summarised(self):
        code, out, err = self.run_with(MID_FLIGHT)
        self.assertEqual(code, force_stop_outcome.READABLE, err)
        self.assertIn("6 queue row(s)", out)
        self.assertIn("chapter 1 downloading, 1 of 3 pages", out)
        self.assertIn("2 page request(s) held open", out)
        self.assertIn("pid 8123", out)

    def test_no_file_is_unmeasurable(self):
        code, _, err = self.run_with()
        self.assertEqual(code, force_stop_outcome.UNMEASURABLE)
        self.assertIn("left no outcome file", err)
        self.assertIn("R36", err)

    def test_no_file_quotes_part_ones_failure(self):
        code, _, err = self.run_with(log=PART_ONE_FAILED)
        self.assertEqual(code, force_stop_outcome.UNMEASURABLE)
        self.assertIn("the downloads never stood mid-flight", err)

    def test_states_that_would_not_test_the_row_are_unmeasurable(self):
        all_done = [queue_row(1, 1, "DOWNLOADING", 3, 3)] + [queue_row(c, c) for c in range(2, 7)]
        none_started = [queue_row(c, c) for c in range(1, 7)]
        failed = [queue_row(1, 1, "DOWNLOADING", 1, 3), queue_row(2, 2, "ERROR")]
        cases = {
            "empty queue": (self.changed(queue=[]), "empty queue"),
            "every page done": (self.changed(queue=all_done), "would not land mid-flight"),
            "nothing started": (self.changed(queue=none_started), "would not land mid-flight"),
            "a failed chapter": (self.changed(queue=failed), "had already failed"),
            "nothing held": (self.changed(held=0), "held no page request"),
        }
        for name, (outcome, message) in cases.items():
            with self.subTest(name):
                code, _, err = self.run_with(outcome)
                self.assertEqual(code, force_stop_outcome.UNMEASURABLE, err)
                self.assertIn(message, err)

    def test_a_file_that_is_not_this_outcome_is_an_input_error(self):
        missing_pid = copy.deepcopy(MID_FLIGHT)
        del missing_pid["pid"]
        bad_row = copy.deepcopy(MID_FLIGHT)
        bad_row["queue"][2]["downloadedPages"] = "1"
        bad_state = copy.deepcopy(MID_FLIGHT)
        bad_state["queue"][0]["state"] = "RUNNING"
        too_many = copy.deepcopy(MID_FLIGHT)
        too_many["queue"][0]["downloadedPages"] = 4
        cases = {
            "not JSON": dict(text="{\"schema\": 1,"),
            "not an object": dict(outcome=[MID_FLIGHT]),
            "another schema": dict(outcome=self.changed(schema=2)),
            "another package": dict(outcome=self.changed(package="dev.other")),
            "no pid": dict(outcome=missing_pid),
            "a boolean pid": dict(outcome=self.changed(pid=True)),
            "no writtenAt": dict(outcome=self.changed(writtenAt="")),
            "a row nothing reads": dict(outcome=self.changed(row="some_other_row")),
            "no queue": dict(outcome=self.changed(queue=None)),
            "a count as text": dict(outcome=bad_row),
            "an unknown state": dict(outcome=bad_state),
            "more pages done than exist": dict(outcome=too_many),
            "a held count as text": dict(outcome=self.changed(held="2")),
        }
        for name, arguments in cases.items():
            with self.subTest(name):
                code, _, err = self.run_with(**arguments)
                self.assertEqual(code, force_stop_outcome.BAD_INPUT, err)
                self.assertIn("is not readable", err)

    def test_the_queue_names_each_chapter_once_in_queue_order(self):
        twice = copy.deepcopy(MID_FLIGHT)
        twice["queue"][3]["chapterId"] = 1
        out_of_order = copy.deepcopy(MID_FLIGHT)
        out_of_order["queue"][1]["queueOrder"], out_of_order["queue"][2]["queueOrder"] = 3, 2
        same_order = copy.deepcopy(MID_FLIGHT)
        same_order["queue"][2]["queueOrder"] = 2
        for name, outcome, message in (("a chapter twice", twice, "names a chapter twice"),
                                       ("out of order", out_of_order, "not in queue order"),
                                       ("a shared position", same_order, "not in queue order")):
            with self.subTest(name):
                code, _, err = self.run_with(outcome)
                self.assertEqual(code, force_stop_outcome.BAD_INPUT, err)
                self.assertIn(message, err)


if __name__ == "__main__":
    unittest.main()
