#!/usr/bin/env python3
"""Unit tests for tools/ci/crash_buffer.py, the crash buffer check on every bench device (R58).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The Java entry is copied from a real `adb logcat -b crash -d -v threadtime` on `saikou_pixel9_4g`
(API 36) after `adb shell am crash com.shinsaikou`, trimmed to its first frames; the native entry is in
the shape the platform's crash dumper writes. Nothing needs a device.

The cases that matter, and why each one is here:
  - an empty buffer, and one holding only the `beginning of crash` banner, pass;
  - the package's Java crash fails, and so does one in a process of its own (`<package>:<name>`);
  - another package's crash does not, including one whose name only starts with the package's;
  - a native crash of the package fails, though its lines come from the dumper's process id;
  - the failure names the device and quotes the entry, starting at its first line.
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
_spec = importlib.util.spec_from_file_location("crash_buffer", TOOLS / "ci" / "crash_buffer.py")
crash_buffer = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = crash_buffer
_spec.loader.exec_module(crash_buffer)

PACKAGE = "com.shinsaikou"
BANNER = "--------- beginning of crash"
AM_CRASH = """\
09-24 00:09:15.889  6152  6152 E AndroidRuntime: FATAL EXCEPTION: main
09-24 00:09:15.889  6152  6152 E AndroidRuntime: Process: com.shinsaikou, PID: 6152
09-24 00:09:15.889  6152  6152 E AndroidRuntime: android.app.RemoteServiceException$CrashedByAdbException: shell-induced crash
09-24 00:09:15.889  6152  6152 E AndroidRuntime: \tat android.app.ActivityThread.throwRemoteServiceException(ActivityThread.java:2377)
09-24 00:09:15.889  6152  6152 E AndroidRuntime: \tat android.os.Looper.loop(Looper.java:338)"""


def java_crash(process: str, pid: int) -> str:
    return (f"09-24 00:10:01.000  {pid}  {pid} E AndroidRuntime: FATAL EXCEPTION: main\n"
            f"09-24 00:10:01.000  {pid}  {pid} E AndroidRuntime: Process: {process}, PID: {pid}\n"
            f"09-24 00:10:01.000  {pid}  {pid} E AndroidRuntime: java.lang.IllegalStateException: boom")


def native_crash(process: str) -> str:
    # The dumper logs under its own pid; the crashed process is named only inside the text.
    return ("09-24 00:11:00.100  7001  7001 F DEBUG   : *** *** *** *** *** *** *** *** *** ***\n"
            f"09-24 00:11:00.100  7001  7001 F DEBUG   : pid: 6999, tid: 6999, name: main  >>> {process} <<<\n"
            "09-24 00:11:00.100  7001  7001 F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)")


class CrashBufferTest(unittest.TestCase):

    def check(self, dump: str) -> tuple[int, str, str]:
        with tempfile.TemporaryDirectory() as scratch:
            path = Path(scratch) / "crash-buffer.txt"
            path.write_text(dump)
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = crash_buffer.main([str(path), "--package", PACKAGE, "--device", "pixel9_4g"])
        return code, out.getvalue(), err.getvalue()

    def test_an_empty_buffer_passes(self):
        self.assertEqual(self.check("")[0], 0)
        code, out, _ = self.check(BANNER + "\n")
        self.assertEqual(code, 0)
        self.assertIn("no entry from com.shinsaikou", out)

    def test_the_package_crashing_fails_naming_the_device_and_quoting_the_entry(self):
        code, _, err = self.check(f"{BANNER}\n{AM_CRASH}\n")
        self.assertEqual(code, 1)
        self.assertIn("com.shinsaikou crashed on pixel9_4g: 1 entry", err)
        quoted = [line.strip() for line in err.splitlines()[1:]]
        self.assertTrue(quoted[0].endswith("FATAL EXCEPTION: main"))
        self.assertIn("CrashedByAdbException: shell-induced crash", err)

    def test_a_process_of_the_package_counts(self):
        self.assertEqual(self.check(java_crash("com.shinsaikou:sync", 5001))[0], 1)

    def test_other_packages_do_not_count(self):
        dump = "\n".join([BANNER, java_crash("com.android.systemui", 5002),
                          java_crash("com.shinsaikou.test", 5003), java_crash("com.shinsaikouother", 5004),
                          native_crash("com.shinsaikou.test")])
        code, _, _ = self.check(dump)
        self.assertEqual(code, 0)

    def test_a_native_crash_of_the_package_fails(self):
        code, _, err = self.check(native_crash(PACKAGE))
        self.assertEqual(code, 1)
        self.assertIn(">>> com.shinsaikou <<<", err)

    def test_two_entries_are_counted_and_the_first_is_quoted(self):
        code, _, err = self.check("\n".join([AM_CRASH, java_crash("com.shinsaikou", 6200)]))
        self.assertEqual(code, 1)
        self.assertIn("2 entries", err)
        self.assertIn("PID: 6152", err)
        self.assertNotIn("PID: 6200", err)

    def test_a_missing_dump_is_exit_2(self):
        with contextlib.redirect_stderr(io.StringIO()):
            code = crash_buffer.main(["/nonexistent/crash-buffer.txt", "--package", PACKAGE, "--device", "x"])
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
