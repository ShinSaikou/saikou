#!/usr/bin/env python3
"""Unit tests for the phase gate.sh takes, and the four wrappers that pass it.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Nothing runs a gate step here. gate.sh is copied into an empty directory with the one file it
sources, so a run that gets past the phase check stops at step 0 on the deliverables that directory
lacks (exit 1), and a run that does not stops before step 0 with the usage line (exit 2). Neither
reads a local.properties: SAIKOU_LOCAL_PROPERTIES names a file that does not exist.

The cases that matter, and why each one is here:
  - 0, 1, 2 and 3 pass the phase check, 3 since unit L of phase3-start.md: each run reaches step 0
    and fails there, naming its phase;
  - 4, a word, an empty argument and no argument exit 2 with the usage line and run no step, so a
    typed phase the script does not know is never read as another phase;
  - tools/ci/phase<n>.sh runs gate.sh beside it with <n> and nothing else, for each of the four.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
CI = TOOLS / "ci"
USAGE = "usage: gate.sh <phase>, where <phase> is 0, 1, 2 or 3"


class Tree:
    """An empty checkout holding only tools/ci/gate.sh and what it sources."""

    def __init__(self, test: unittest.TestCase) -> None:
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        (self.root / "tools" / "ci").mkdir(parents=True)
        (self.root / "tools" / "common").mkdir(parents=True)
        shutil.copy(CI / "gate.sh", self.root / "tools" / "ci" / "gate.sh")
        shutil.copy(TOOLS / "common" / "localprops.sh", self.root / "tools" / "common" / "localprops.sh")
        self.env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": str(self.root),
            "ANDROID_SDK_ROOT": str(self.root / "sdk"),
            "SAIKOU_LOCAL_PROPERTIES": str(self.root / "no-local.properties"),
        }

    def gate(self, *argv: str) -> subprocess.CompletedProcess:
        return subprocess.run(["bash", str(self.root / "tools" / "ci" / "gate.sh"), *argv],
                              cwd=self.root, env=self.env, capture_output=True, text=True, timeout=60)


class PhaseCheckTest(unittest.TestCase):

    def test_each_known_phase_passes_the_check_and_reaches_step_0(self):
        tree = Tree(self)
        for phase in ("0", "1", "2", "3"):
            with self.subTest(phase=phase):
                result = tree.gate(phase)
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn("=== 0. deliverables ===", result.stdout)
                self.assertIn(f"phase {phase} failed at: deliverables", result.stderr)
                self.assertNotIn("usage:", result.stderr)

    def test_an_unknown_phase_or_none_exits_2_with_the_usage_line_before_any_step(self):
        tree = Tree(self)
        for argv in (("4",), ("x",), ("",), ()):
            with self.subTest(argv=argv):
                result = tree.gate(*argv)
                self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
                self.assertEqual(result.stderr, USAGE + "\n")
                self.assertEqual(result.stdout, "")


class WrapperTest(unittest.TestCase):

    def test_each_wrapper_runs_the_gate_beside_it_with_its_own_phase(self):
        for phase in ("0", "1", "2", "3"):
            with self.subTest(phase=phase):
                wrapper = CI / f"phase{phase}.sh"
                self.assertTrue(os.access(wrapper, os.X_OK), f"{wrapper} is not executable")
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    shutil.copy(wrapper, root / wrapper.name)
                    # A gate.sh that prints its arguments, one per line, in place of the real one.
                    (root / "gate.sh").write_text('printf "%s\\n" "$@"\n')
                    result = subprocess.run(["bash", str(root / wrapper.name)], cwd=root,
                                            capture_output=True, text=True, timeout=60)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, f"{phase}\n")


if __name__ == "__main__":
    unittest.main()
