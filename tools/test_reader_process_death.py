#!/usr/bin/env python3
"""Unit tests for tools/ci/reader_process_death.py, which reads the reader restore check's output,
and for the awk program tools/ci/reader_process_death.sh reads the reader's history record with.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The logcat line and the dump nodes are copied from a real run on `saikou_api26_2g` (API 26): the
seed line ReaderProcessDeathTest logged, the reader with its page indicator showing, and the reader
with its menu open. The dumps keep the nodes that matter and the attributes uiautomator wrote, and
drop the layout nodes around them. Nothing needs a device.

The cases that matter, and why each one is here:
  - the seed line is read for this run's nonce only, since the buffer can hold an earlier run's;
  - the indicator reads "<page> / <pages>" whatever bounds the node has (uiautomator reports it at
    zero size on API 26), is absent while the menu hides it, and two readings that disagree are an
    error rather than one of them;
  - a node is found by its exact text, so "Chapter 1" does not match "Chapter 10";
  - the status line uiautomator prints ahead of the XML does not break the parse, and something
    that is not a dump is an input error, never "not on screen";
  - the script's awk finds the reader's history record whether the record's line reads
    "Hist #1:" (API 26) or "Hist  #1:" (API 36, R105 carry-over 1), and only the reader's.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("reader_process_death", TOOLS / "ci" / "reader_process_death.py")
reader_process_death = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = reader_process_death
_spec.loader.exec_module(reader_process_death)

SEED_LINE = (
    "09-24 17:15:46.395 11408 11425 I SaikouReaderRestore: seeded nonce=probe1 manga=1 chapter1=2 "
    "pages1=5 chapter2=1 pages2=4"
)
EARLIER_SEED_LINE = (
    "09-24 17:02:11.001 10001 10020 I SaikouReaderRestore: seeded nonce=older manga=9 chapter1=8 "
    "pages1=5 chapter2=7 pages2=4"
)

ATTRIBUTES = (
    'resource-id="" class="android.widget.TextView" package="com.shinsaikou" content-desc="" '
    'checkable="false" checked="false" clickable="false" enabled="true" focusable="false" '
    'focused="false" scrollable="false" long-clickable="false" password="false" selected="false"'
)


def text_node(text: str, bounds: str) -> str:
    return f'<node index="0" text="{text}" {ATTRIBUTES} bounds="{bounds}" />'


def dump(*nodes: str) -> str:
    return (
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
        '<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.shinsaikou" '
        'content-desc="" bounds="[0,0][1080,2274]">' + "".join(nodes) + "</node></hierarchy>"
    )


READER = dump(text_node("1 / 5", "[0,0][0,0]"))
MENU = dump(
    text_node("saikou-reader-restore-2439160142340", "[148,83][932,157]"),
    text_node("Chapter 1", "[148,157][313,210]"),
    text_node("5", "[189,2033][210,2096]"),
    text_node("5", "[865,2033][891,2096]"),
)


class SeedTest(unittest.TestCase):

    def test_the_line_for_this_nonce_gives_every_id(self):
        ids = reader_process_death.seed(EARLIER_SEED_LINE + "\n" + SEED_LINE + "\n", "probe1")
        self.assertEqual(ids, {"manga": 1, "chapter1": 2, "pages1": 5, "chapter2": 1, "pages2": 4})

    def test_an_earlier_runs_line_is_not_this_runs(self):
        with self.assertRaises(reader_process_death.InputError):
            reader_process_death.seed(EARLIER_SEED_LINE, "probe1")

    def test_the_last_line_for_a_nonce_wins(self):
        again = SEED_LINE.replace("manga=1 ", "manga=3 ")
        self.assertEqual(reader_process_death.seed(SEED_LINE + "\n" + again, "probe1")["manga"], 3)


class IndicatorTest(unittest.TestCase):

    def test_the_indicator_is_read_whatever_its_bounds(self):
        self.assertEqual(reader_process_death.indicator(READER), (1, 5))

    def test_the_outline_and_the_fill_agreeing_are_one_reading(self):
        both = dump(text_node("3 / 4", "[0,0][0,0]"), text_node("3 / 4", "[0,0][0,0]"))
        self.assertEqual(reader_process_death.indicator(both), (3, 4))

    def test_two_readings_that_disagree_are_an_error(self):
        torn = dump(text_node("3 / 4", "[0,0][0,0]"), text_node("1 / 5", "[0,0][0,0]"))
        with self.assertRaises(reader_process_death.InputError):
            reader_process_death.indicator(torn)

    def test_the_open_menu_hides_the_indicator(self):
        # The slider's two labels are bare numbers, never "<page> / <pages>".
        self.assertIsNone(reader_process_death.indicator(MENU))

    def test_the_status_line_ahead_of_the_xml_is_skipped(self):
        self.assertEqual(reader_process_death.indicator("UI hierchary dumped to: /dev/tty\n" + READER), (1, 5))

    def test_something_that_is_not_a_dump_is_an_input_error(self):
        for broken in ("ERROR: could not get idle state.", "<?xml version='1.0' ?><hierarchy><node"):
            with self.subTest(broken=broken), self.assertRaises(reader_process_death.InputError):
                reader_process_death.indicator(broken)


class FindTest(unittest.TestCase):

    def test_the_centre_of_the_node_with_that_text(self):
        self.assertEqual(reader_process_death.find(MENU, "Chapter 1"), (230, 183))

    def test_the_text_has_to_match_exactly(self):
        tenth = dump(text_node("Chapter 10", "[0,0][100,100]"))
        self.assertIsNone(reader_process_death.find(tenth, "Chapter 1"))
        self.assertIsNone(reader_process_death.find(MENU, "Chapter 2"))


class MainTest(unittest.TestCase):

    def run_main(self, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = reader_process_death.main(list(args))
        return code, out.getvalue()

    def write(self, directory: str, name: str, content: str) -> str:
        path = Path(directory) / name
        path.write_text(content)
        return str(path)

    def test_exit_codes_and_what_the_script_reads(self):
        with tempfile.TemporaryDirectory() as directory:
            logcat = self.write(directory, "logcat.txt", SEED_LINE + "\n")
            reader = self.write(directory, "reader.xml", READER)
            menu = self.write(directory, "menu.xml", MENU)
            broken = self.write(directory, "broken.xml", "ERROR: null root node returned by UiTestAutomationBridge.")

            code, out = self.run_main("seed", "--nonce", "probe1", logcat)
            self.assertEqual(code, reader_process_death.FOUND)
            self.assertEqual(
                out.split(),
                ["SEED_MANGA=1", "SEED_CHAPTER1=2", "SEED_PAGES1=5", "SEED_CHAPTER2=1", "SEED_PAGES2=4"],
            )
            self.assertEqual(self.run_main("seed", "--nonce", "other", logcat)[0], reader_process_death.BAD_INPUT)
            self.assertEqual(self.run_main("indicator", reader), (reader_process_death.FOUND, "1 5\n"))
            self.assertEqual(self.run_main("indicator", menu), (reader_process_death.ABSENT, ""))
            self.assertEqual(self.run_main("indicator", broken)[0], reader_process_death.BAD_INPUT)
            self.assertEqual(self.run_main("find", "--text", "Chapter 1", menu), (reader_process_death.FOUND, "230 183\n"))
            self.assertEqual(self.run_main("find", "--text", "Chapter 2", menu), (reader_process_death.ABSENT, ""))
            missing = str(Path(directory) / "missing.xml")
            self.assertEqual(self.run_main("indicator", missing)[0], reader_process_death.BAD_INPUT)


SCRIPT = TOOLS / "ci" / "reader_process_death.sh"
READER_SHORT = "com.shinsaikou/.reader.ReaderActivity"


def history_record(activities: str) -> str:
    """What the script's own awk program keeps of an `activity activities` dump: the reader's record."""
    program = re.search(r"awk -v reader=\" \$READER_SHORT \" '([^']*)'", SCRIPT.read_text())
    if program is None:
        raise AssertionError("reader_process_death.sh has no awk over the history records")
    return subprocess.run(
        ["awk", "-v", f"reader= {READER_SHORT} ", program.group(1)],
        input=activities, capture_output=True, text=True, check=True, timeout=30,
    ).stdout


def activities(hist: str) -> str:
    """A dump with the reader stopped above the main screen, each record's line written as <hist>."""
    return (
        "ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n"
        "Display #0 (activities from top to bottom):\n"
        "  * Task{5d1c2a0 #12 type=standard A=10187:com.shinsaikou}\n"
        f"    * {hist}1: ActivityRecord{{b0e4f1 u0 {READER_SHORT} t12}}\n"
        "      packageName=com.shinsaikou processName=com.shinsaikou\n"
        "      state=STOPPED stopped=true finishing=false\n"
        f"    * {hist}0: ActivityRecord{{7a61c3e u0 com.shinsaikou/.ui.main.MainActivity t12}}\n"
        "      packageName=com.shinsaikou processName=com.shinsaikou\n"
        "      state=DESTROYED stopped=true finishing=false\n"
    )


class HistoryRecordTest(unittest.TestCase):

    def test_the_reader_record_is_found_on_api_26_and_api_36(self):
        for hist in ("Hist #", "Hist  #"):
            with self.subTest(hist=hist):
                record = history_record(activities(hist))
                self.assertIn(READER_SHORT, record)
                self.assertIn("state=STOPPED", record)
                self.assertNotIn("MainActivity", record)
                self.assertNotIn("state=DESTROYED", record)

    def test_no_reader_record_is_nothing(self):
        dump = activities("Hist  #").replace(READER_SHORT, "com.shinsaikou/.ui.other.OtherActivity")
        self.assertEqual(history_record(dump), "")


if __name__ == "__main__":
    unittest.main()
