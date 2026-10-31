#!/usr/bin/env python3
"""Unit tests for tools/ci/reader_resume_next.py, which reads the resume and Next chapter check's output.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The marker lines are in the shape unit K2's runs on `saikou_api26_2g` logged (`SaikouBench:I` under
`-v threadtime`, `reader.chapter id=<id> pages=<n>`), and the Next chapter node in the shape
uiautomator dumps an icon button: no text, a content description. Nothing needs a device.

The cases that matter, and why each one is here:
  - the timestamp filter keeps a line stamped in the reading's own second or later and drops every
    earlier one, down to a line one second before, since that line is the only thing that tells
    this run's switch from an earlier one on the same seeded ids;
  - it compares time, not text, across minutes, hours, days and months, and reads a January line
    after a December reading as later, not as eleven months earlier;
  - it drops what carries no stamp and strips the carriage returns adb leaves;
  - a reading that is not `MM-DD hh:mm:ss` is an input error (exit 2), never "no lines";
  - the description finder matches exactly, returns the centre, and tells "not on screen" (1) from
    an unreadable dump (2).
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
# reader_resume_next imports reader_process_death from its own directory, as it does when run as a script.
sys.path.insert(0, str(TOOLS / "ci"))
_spec = importlib.util.spec_from_file_location("reader_resume_next", TOOLS / "ci" / "reader_resume_next.py")
reader_resume_next = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = reader_resume_next
_spec.loader.exec_module(reader_resume_next)


def marker(stamp: str, chapter: int = 3) -> str:
    return f"{stamp}  6571  6571 I SaikouBench: reader.chapter id={chapter} pages=4"


DUMP = """UI hierchary dumped to: /dev/tty
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node index="0" text="" content-desc="Previous chapter" bounds="[0,2100][140,2240]" />
<node index="1" text="Chapter 1" content-desc="" bounds="[160,120][900,200]" />
<node index="2" text="" content-desc="Next chapter" bounds="[940,2100][1080,2240]" />
<node index="3" text="" content-desc="Next chapter settings" bounds="[0,0][10,10]" />
</hierarchy>"""


class SinceTest(unittest.TestCase):

    def test_a_line_in_the_readings_own_second_is_kept_and_the_second_before_is_not(self):
        lines = [marker("09-25 01:33:06.999", 1), marker("09-25 01:33:07.000", 2), marker("09-25 01:33:07.412", 3)]
        kept = reader_resume_next.since(lines, "09-25 01:33:07")
        self.assertEqual(kept, lines[1:])

    def test_time_is_compared_as_time_across_every_field(self):
        start = "09-25 23:59:59"
        earlier = [marker("09-25 23:59:58.000"), marker("09-25 22:59:59.500"), marker("09-24 23:59:59.900"),
                   marker("08-30 23:59:59.900")]
        later = [marker("09-26 00:00:00.001"), marker("10-01 00:00:00.000"), marker("09-25 23:59:59.100")]
        self.assertEqual(reader_resume_next.since(earlier + later, start), later)

    def test_a_january_line_after_a_december_reading_is_later(self):
        lines = [marker("12-31 23:59:58.000", 1), marker("01-01 00:00:02.000", 2)]
        self.assertEqual(reader_resume_next.since(lines, "12-31 23:59:59"), [lines[1]])

    def test_a_december_line_before_a_january_reading_is_earlier(self):
        lines = [marker("12-31 23:59:59.000", 1), marker("01-01 00:00:01.000", 2)]
        self.assertEqual(reader_resume_next.since(lines, "01-01 00:00:00"), [lines[1]])

    def test_unstamped_lines_are_dropped_and_carriage_returns_stripped(self):
        lines = ["--------- beginning of main\r", marker("09-25 01:33:08.000") + "\r", "", "garbage"]
        self.assertEqual(reader_resume_next.since(lines, "09-25 01:33:07"), [marker("09-25 01:33:08.000")])

    def test_a_reading_in_another_shape_is_an_input_error(self):
        for reading in ("09-25 1:33:07", "2026-09-25 01:33:07", "09-25\\ 01:33:07", ""):
            with self.subTest(reading=reading), self.assertRaises(reader_resume_next.InputError):
                reader_resume_next.since([marker("09-25 01:33:08.000")], reading)


class MainTest(unittest.TestCase):

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.dir = Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()

    def run_main(self, *argv: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = reader_resume_next.main(list(argv))
        return code, out.getvalue(), err.getvalue()

    def test_since_prints_the_kept_lines(self):
        logcat = self.dir / "next-logcat.txt.all"
        logcat.write_text("\n".join([marker("09-25 01:33:06.000", 9), marker("09-25 01:33:09.000", 3)]) + "\n")
        code, out, _ = self.run_main("since", "--since", "09-25 01:33:07", str(logcat))
        self.assertEqual(code, 0)
        self.assertEqual(out, marker("09-25 01:33:09.000", 3) + "\n")

    def test_since_with_a_bad_reading_is_exit_2(self):
        logcat = self.dir / "next-logcat.txt.all"
        logcat.write_text(marker("09-25 01:33:09.000") + "\n")
        code, out, err = self.run_main("since", "--since", "yesterday", str(logcat))
        self.assertEqual(code, 2)
        self.assertEqual(out, "")
        self.assertIn("not MM-DD hh:mm:ss", err)

    def test_since_on_a_missing_file_is_exit_2(self):
        code, _, _ = self.run_main("since", "--since", "09-25 01:33:07", str(self.dir / "absent"))
        self.assertEqual(code, 2)

    def test_the_next_chapter_button_is_found_by_its_exact_description(self):
        dump = self.dir / "menu.xml"
        dump.write_text(DUMP)
        code, out, _ = self.run_main("find-description", "--description", "Next chapter", str(dump))
        self.assertEqual(code, 0)
        self.assertEqual(out.strip(), "1010 2170")

    def test_a_description_not_on_screen_is_exit_1(self):
        dump = self.dir / "menu.xml"
        dump.write_text(DUMP)
        code, out, _ = self.run_main("find-description", "--description", "Next", str(dump))
        self.assertEqual(code, 1)
        self.assertEqual(out, "")

    def test_text_is_not_a_description(self):
        dump = self.dir / "menu.xml"
        dump.write_text(DUMP)
        code, _, _ = self.run_main("find-description", "--description", "Chapter 1", str(dump))
        self.assertEqual(code, 1)

    def test_a_file_that_is_not_a_dump_is_exit_2(self):
        dump = self.dir / "menu.xml"
        dump.write_text("ERROR: could not get idle state.")
        code, _, err = self.run_main("find-description", "--description", "Next chapter", str(dump))
        self.assertEqual(code, 2)
        self.assertIn("not a uiautomator dump", err)


if __name__ == "__main__":
    unittest.main()
