#!/usr/bin/env python3
"""Unit tests for tools/ci/reader_resume_in_session.sh, unit K3's in-session resume check, which
reads the device's output through tools/ci/reader_resume_next.py and runs in gate.sh's step 5e.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The script's own logic is UI steps on a device; what it reads off the device is the shared parser's,
which test_reader_resume_next.py covers. The cases here are the ones specific to this check, and why
each one is here:
  - its logcat holds the marker it waits for twice: step 1 opens Chapter 2 and logs
    `reader.chapter id=<Chapter 2> pages=4` before the device clock is read, and the Next chapter
    switch logs the same line after. The filter keeps only the second, so a Next chapter button that
    did nothing leaves no marker and the check fails rather than passing on step 1's line;
  - the script reads its timestamps and the Next chapter button through that parser, with no `awk`
    string comparison or inline Python of its own left over from the evidence copy;
  - gate.sh's step 5e runs it after reader_resume_next.sh, on the same install, and phase 2's
    deliverables list it with this file, so a deleted script ends step 0 rather than step 5e.

The marker lines are in the shape K3's runs on `saikou_api26_2g` logged. Nothing needs a device.
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
ROOT = TOOLS.parent
SCRIPT = TOOLS / "ci" / "reader_resume_in_session.sh"
GATE = TOOLS / "ci" / "gate.sh"
# reader_resume_next imports reader_process_death from its own directory, as it does when run as a script.
sys.path.insert(0, str(TOOLS / "ci"))
_spec = importlib.util.spec_from_file_location("reader_resume_next", TOOLS / "ci" / "reader_resume_next.py")
reader_resume_next = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = reader_resume_next
_spec.loader.exec_module(reader_resume_next)

CHAPTER_1, CHAPTER_2 = 2, 1
READING = "09-25 03:15:44"


def marker(stamp: str, chapter: int, pages: int) -> str:
    return f"{stamp}  6571  6571 I SaikouBench: reader.chapter id={chapter} pages={pages}\r"


# Step 1 opens Chapter 2, step 2 opens Chapter 1; the reading is taken just before the Next tap.
BEFORE_THE_TAP = [
    "--------- beginning of main\r",
    marker("09-25 03:15:31.204", CHAPTER_2, 4),
    marker("09-25 03:15:38.771", CHAPTER_1, 5),
]
SWITCH = marker("09-25 03:15:44.630", CHAPTER_2, 4)
WANTED = f"reader.chapter id={CHAPTER_2} pages=4"


class InSessionLogcatTest(unittest.TestCase):

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.dir = Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()

    def filtered(self, lines: list[str]) -> list[str]:
        """What `$RESUME since --since "$since" "$2.all" > "$2"` leaves in the file the script greps."""
        logcat = self.dir / "next-logcat.txt.all"
        logcat.write_text("\n".join(lines) + "\n")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = reader_resume_next.main(["since", "--since", READING, str(logcat)])
        self.assertEqual(code, 0)
        return out.getvalue().splitlines()

    def test_the_switch_leaves_its_marker_and_step_ones_is_dropped(self):
        kept = self.filtered(BEFORE_THE_TAP + [SWITCH])
        self.assertEqual(kept, [SWITCH.rstrip("\r")])
        self.assertEqual(sum(WANTED in line for line in kept), 1)

    def test_a_next_chapter_that_did_nothing_leaves_no_marker(self):
        kept = self.filtered(BEFORE_THE_TAP)
        self.assertEqual(kept, [])
        self.assertFalse(any(WANTED in line for line in kept))


class ScriptTest(unittest.TestCase):

    script = SCRIPT.read_text()

    def test_it_parses_as_bash(self):
        result = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_it_is_executable_like_the_scripts_beside_it(self):
        self.assertTrue(SCRIPT.stat().st_mode & 0o111, "reader_resume_in_session.sh is not executable")

    def test_it_reads_the_device_through_the_shared_parser(self):
        self.assertIn('RESUME="python3 $HERE/reader_resume_next.py"', self.script)
        self.assertRegex(self.script, r'\$RESUME since --since "\$1" "\$2\.all" > "\$2"')
        self.assertRegex(self.script, r'\$RESUME find-description --description "\$1" "\$file"')

    def test_nothing_of_the_evidence_copy_is_left_to_parse_on_its_own(self):
        for leftover in ("awk", "<<'PY'", "$SAIKOU_REPO"):
            self.assertFalse(leftover in self.script, f"the script still has {leftover}")


class GateTest(unittest.TestCase):

    gate = GATE.read_text()

    def step(self, title_start: str) -> str:
        match = re.search(rf'^    step "{re.escape(title_start)}.*?(?=^    step ")', self.gate, re.M | re.S)
        self.assertIsNotNone(match, f"gate.sh has no step {title_start}")
        return match.group(0)

    def test_step_5e_runs_it_after_the_resume_and_next_chapter_check(self):
        step = self.step("5e.")
        runs = re.findall(r"bash tools/ci/(reader_\w+\.sh)", step)
        self.assertEqual(runs, ["reader_process_death.sh", "reader_resume_next.sh", "reader_resume_in_session.sh"])
        call = re.search(r'^        run "[^"]+" env ([^\n]*\\\n)*[^\n]*reader_resume_in_session\.sh$', step, re.M)
        self.assertIsNotNone(call, "step 5e does not run the check through run, which fails the step on exit 1 or 3")
        self.assertIn('SAIKOU_SERIAL="$SERIAL_API26"', call.group(0))

    def test_phase_2_requires_the_script_and_this_test(self):
        block = re.search(r"^if phase2; then\n    REQUIRED_DELIVERABLE_FILES\+=\(\n(.*?)^    \)", self.gate,
                          re.M | re.S)
        self.assertIsNotNone(block, "gate.sh has no phase 2 deliverables block")
        lines = (line.strip() for line in block.group(1).splitlines())
        listed = {line for line in lines if line and not line.startswith("#")}
        for path in ("tools/ci/reader_resume_in_session.sh", "tools/test_reader_resume_in_session.py"):
            self.assertIn(path, listed)
            self.assertTrue((ROOT / path).is_file(), f"{path} is listed but absent")


if __name__ == "__main__":
    unittest.main()
