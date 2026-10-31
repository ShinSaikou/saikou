#!/usr/bin/env python3
"""A text scan of the tools' shell scripts for one pipefail hazard.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

`grep -q` stops reading at its first match. Whatever writes to it next dies of SIGPIPE, and under
`set -o pipefail` the pipeline's status is that death (141), so a search that matched reports that
it did not. It shows only when the input outgrows what the writer hands over in one write, which is
why it hid: reader_process_death.sh piped `dumpsys activity recents` into `grep -q`, and the check
passed in Phase 2 and then failed 20 times in 20 on saikou_api26_2g once its recents list carried the
browser's task too (the coordinator's phase 3 dry run on 8e610f5, step 5e, 2026-09-26).

A `dumpsys` is the large input on these devices, so the scan fails any pipeline in a pipefail script
that runs a dumpsys and ends in `grep -q`. The dump is read into a variable first instead. Smaller
listings (`adb devices`, a snapshot list) are left alone; they are one write.
"""

from __future__ import annotations

import re
import subprocess
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
QUIET_GREP = re.compile(r"\|\s*grep\s+-[A-Za-z]*q[A-Za-z]*\b[^|]*$")


def logical_lines(text: str) -> list[tuple[int, str]]:
    """Joins lines continued by a trailing backslash or a trailing pipe, keeping the first line's number."""
    joined: list[tuple[int, str]] = []
    start, parts = None, []
    for number, line in enumerate(text.splitlines(), 1):
        if start is None:
            start = number
        stripped = line.rstrip()
        continued = stripped.endswith("\\") or stripped.endswith("|")
        parts.append(stripped[:-1] if stripped.endswith("\\") else stripped)
        if not continued:
            joined.append((start, " ".join(part.strip() for part in parts)))
            start, parts = None, []
    if parts:
        joined.append((start, " ".join(part.strip() for part in parts)))
    return joined


def hazards(text: str) -> list[tuple[int, str]]:
    found = []
    for number, line in logical_lines(text):
        # Whole comment lines only: a `#` inside a pipeline is often quoted (awk's "Hist #").
        if line.lstrip().startswith("#"):
            continue
        pipeline = line.split(";")[0] if line.startswith("if ") else line
        for segment in re.split(r"\|\||&&|\bthen\b", pipeline):
            if "dumpsys" in segment and QUIET_GREP.search(segment.rstrip()):
                found.append((number, line))
    return found


def pipefail_scripts() -> list[Path]:
    return [p for p in sorted(TOOLS.rglob("*.sh")) if "pipefail" in p.read_text() and "/out/" not in str(p)]


class PipefailQuietGrepTest(unittest.TestCase):

    def test_no_pipefail_script_pipes_a_dumpsys_into_grep_q(self):
        scripts = pipefail_scripts()
        self.assertGreater(len(scripts), 10, "the scan found almost no scripts, so it would pass vacuously")
        offenders = [f"{p.relative_to(TOOLS)}:{n}: {line}" for p in scripts for n, line in hazards(p.read_text())]
        self.assertEqual(offenders, [], "read the dump into a variable, then grep the variable")

    def test_the_scan_flags_the_shape_that_failed(self):
        # reader_process_death.sh before the fix, both sites as they were.
        before = (
            "set -euo pipefail\n"
            "$ADB shell dumpsys activity recents | tr -d '\\r' | grep -q \"Activities=.*$READER_SHORT\" ||\n"
            "    unmeasurable \"the task no longer holds the reader after the kill\"\n"
            "    if $ADB shell dumpsys activity activities | tr -d '\\r' |\n"
            "            awk -v reader=\" $READER_SHORT \" 'index($0, \"Hist #\") { mine = 1 } mine' |\n"
            "            grep -q 'state=STOPPED'; then\n"
        )
        self.assertEqual([n for n, _ in hazards(before)], [2, 4])

    def test_the_scan_leaves_a_variable_search_alone(self):
        after = (
            "set -euo pipefail\n"
            "recents=$($ADB shell dumpsys activity recents | tr -d '\\r') ||\n"
            "    unmeasurable \"adb could not read the recent tasks after the kill\"\n"
            "grep -q \"Activities=.*$READER_SHORT\" <<< \"$recents\" ||\n"
            "    unmeasurable \"the task no longer holds the reader after the kill\"\n"
        )
        self.assertEqual(hazards(after), [])

    def test_the_hazard_is_real_under_pipefail(self):
        # 200 KB through tr into grep -q, which matches on the first line: the match is found and
        # the pipeline still fails, with tr's status 141.
        script = "set -o pipefail; head -c 200000 /dev/zero | tr '\\0' 'y' | fold -w 80 | grep -q y"
        result = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        captured = "set -o pipefail; out=$(head -c 200000 /dev/zero | tr '\\0' 'y' | fold -w 80); grep -q y <<< \"$out\""
        self.assertEqual(subprocess.run(["bash", "-c", captured]).returncode, 0)


if __name__ == "__main__":
    unittest.main()
