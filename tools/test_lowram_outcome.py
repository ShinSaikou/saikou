#!/usr/bin/env python3
"""Unit tests for tools/ci/lowram_outcome.py, which names the low RAM repro's outcome (R61).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The events buffer lines and the `am instrument` output of a normal end are copied from a real run
of LowRamReproTest on a clone of `saikou_lowram_1_5g` (API 36), and the crash buffer and events lines
of a death are the ones `adb shell am crash com.shinsaikou` left there after library pass 1. Written by
hand: the unmeasurable run's output (from unit 8a's message for a short library pass), an
`am_proc_died` after the runner's stop (the dump taken a second after a normal end had none), and
the low-memory kill, which no device here has shown, so its case pins the inferred rule. Nothing
needs a device.

The cases that matter, and why each one is here:
  - a fragment, true or false, is the outcome whatever am instrument said, since a FAIL verdict
    also fails the test;
  - a crash buffer entry is a death, quoted from its first line;
  - `am_proc_died` without the runner's finished-instrumentation stop is a death, and so is an
    `am_kill` with any other reason, while the runner's own stop and the test APK's own process are
    not;
  - a failed test with no fragment and no death is unmeasurable, quoting the test's message;
  - no runner, no test started, no process started, or a test that returned early is MISSING, and
    never a death;
  - a session the host lost (no INSTRUMENTATION_CODE after the test started) is its own outcome,
    even with the crash it causes in the buffer, and a real death's output does close with one;
  - a missing buffer dump or a fragment for another row is an input error.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
# lowram_outcome imports crash_buffer from its own directory, as it does when run as a script.
sys.path.insert(0, str(TOOLS / "ci"))
_spec = importlib.util.spec_from_file_location("lowram_outcome", TOOLS / "ci" / "lowram_outcome.py")
lowram_outcome = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = lowram_outcome
_spec.loader.exec_module(lowram_outcome)

PACKAGE = "com.shinsaikou"
DEVICE = "lowram_1_5g"
TEST = "theLibraryAndAWebtoonChapterSurviveOnTheLowRamProfile"

START = "09-24 04:16:31.205   646   708 I am_proc_start: [0,6544,10217,com.shinsaikou,added application,com.shinsaikou]"
TEST_APK_START = ("09-24 04:19:54.836   646   708 I am_proc_start: [0,7006,10218,com.shinsaikou.test,"
                  "next-top-activity,{com.shinsaikou.test/androidx.test.core.app.InstrumentationActivityInvoker$EmptyActivity}]")
FINISHED = "09-24 04:19:55.526   646  1909 I am_kill : [0,6544,com.shinsaikou,0,stop com.shinsaikou due to finished inst,256784]"
DIED_AFTER_FINISHED = "09-24 04:19:55.600   646   708 I am_proc_died: [0,6544,com.shinsaikou,0,20]"
# The crash run: its own start, then what `am crash` left after library pass 1.
CRASH_START = "09-24 04:25:53.824   646   708 I am_proc_start: [0,7583,10217,com.shinsaikou,added application,com.shinsaikou]"
AM_CRASH = ("09-24 04:26:34.197   646  1631 I am_crash: [7583,0,com.shinsaikou,549994052,"
            "android.app.RemoteServiceException$CrashedByAdbException,shell-induced crash,ActivityThread.java,2377,0]")
DIED = "09-24 04:26:34.216   646  1631 I am_proc_died: [0,7583,com.shinsaikou,0,2]"
LMK_KILL = "09-24 04:26:34.100   646   700 I am_kill : [0,7583,com.shinsaikou,900,lmk (low memory killer),180220]"
OTHER_DIED = "09-24 04:17:00.000   646   708 I am_proc_died: [0,5100,com.android.systemui,0,2]"

CRASH = """\
--------- beginning of crash
09-24 04:26:34.183  7583  7583 E AndroidRuntime: FATAL EXCEPTION: main
09-24 04:26:34.183  7583  7583 E AndroidRuntime: Process: com.shinsaikou, PID: 7583
09-24 04:26:34.183  7583  7583 E AndroidRuntime: android.app.RemoteServiceException$CrashedByAdbException: shell-induced crash
09-24 04:26:34.183  7583  7583 E AndroidRuntime: \tat android.app.ActivityThread.throwRemoteServiceException(ActivityThread.java:2377)"""
CRASH_EVENTS = "\n".join([CRASH_START, AM_CRASH, DIED])

# A run the host lost when the shared adb server restarted under it: the crash it caused, and what
# the events buffer kept. The am instrument output stops at HEADER.
LOST_CRASH = """\
--------- beginning of crash
09-24 04:38:07.879  8862  8875 E AndroidRuntime: FATAL EXCEPTION: Instr: androidx.test.runner.AndroidJUnitRunner
09-24 04:38:07.879  8862  8875 E AndroidRuntime: Process: com.shinsaikou, PID: 8862
09-24 04:38:07.879  8862  8875 E AndroidRuntime: java.lang.RuntimeException: Error while disconnecting UiAutomation@787d85c[id=-1, displayId=0, flags=0]
09-24 04:38:07.879  8862  8875 E AndroidRuntime: Caused by: android.os.DeadObjectException"""
LOST_EVENTS = "\n".join([
    "09-24 04:36:46.245   646   708 I am_proc_start: [0,8862,10217,com.shinsaikou,added application,com.shinsaikou]",
    "09-24 04:38:07.879   646   760 I am_crash: [8862,0,com.shinsaikou,549994052,android.os.DeadObjectException,"
    "Error while disconnecting UiAutomation@787d85c[id=-1, displayId=0, flags=0],BinderProxy.java,-2,0]",
    "09-24 04:38:07.909   646  2913 I am_proc_died: [0,8862,com.shinsaikou,0,4]",
])

HEADER = f"""\
INSTRUMENTATION_STATUS: class=com.shinsaikou.bench.LowRamReproTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
com.shinsaikou.bench.LowRamReproTest:
INSTRUMENTATION_STATUS: test={TEST}
INSTRUMENTATION_STATUS_CODE: 1
"""
PASSED = HEADER + f"""\
INSTRUMENTATION_STATUS: class=com.shinsaikou.bench.LowRamReproTest
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test={TEST}
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 204.243

OK (1 test)


INSTRUMENTATION_CODE: -1
"""
UNMEASURED_MESSAGE = ("java.lang.AssertionError: library pass 1 showed 94 of the 1000 seeded entries, so the pass "
                      "measured another library expected:<1000> but was:<94>")
FAILED = HEADER + f"""\
INSTRUMENTATION_STATUS: class=com.shinsaikou.bench.LowRamReproTest
INSTRUMENTATION_STATUS: stack={UNMEASURED_MESSAGE}
\tat org.junit.Assert.fail(Assert.java:89)
INSTRUMENTATION_STATUS: test={TEST}
INSTRUMENTATION_STATUS_CODE: -2
INSTRUMENTATION_RESULT: stream=

Time: 61.2
There was 1 failure:
1) {TEST}(com.shinsaikou.bench.LowRamReproTest)
{UNMEASURED_MESSAGE}

FAILURES!!!
Tests run: 1,  Failures: 1


INSTRUMENTATION_CODE: -1
"""
CRASHED = HEADER + """\
INSTRUMENTATION_RESULT: shortMsg=Process crashed.
INSTRUMENTATION_CODE: 0
"""
NO_TESTS = """\
INSTRUMENTATION_RESULT: stream=

Time: 0.01

OK (0 tests)


INSTRUMENTATION_CODE: -1
"""


def fragment(value: bool, row: str = "low_ram_repro_pass") -> str:
    return json.dumps({"id": row, "device": DEVICE, "value": value, "unit": "bool",
                       "method": "LowRamReproTest: growth pass1 to pass3 +13.3% (limit +10.0%)",
                       "recordedAt": "2026-09-24T01:19:55Z"})


class LowRamOutcomeTest(unittest.TestCase):

    def outcome(self, *, frag: str | None = None, log: str | None = PASSED, crash: str = "",
                events: str = START + "\n" + FINISHED, main: str = "") -> tuple[int, str, str]:
        with tempfile.TemporaryDirectory() as scratch:
            out = Path(scratch)
            if frag is not None:
                (out / "fragment.pulled").write_text(frag)
            if log is not None:
                (out / "lowram-repro.txt").write_text(log)
            (out / "crash-buffer.txt").write_text(crash)
            (out / "events-buffer.txt").write_text(events)
            (out / "main-buffer.txt").write_text(main)
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = lowram_outcome.main([
                    "--fragment", str(out / "fragment.pulled"), "--instrument-log", str(out / "lowram-repro.txt"),
                    "--crash-buffer", str(out / "crash-buffer.txt"), "--events-buffer", str(out / "events-buffer.txt"),
                    "--main-buffer", str(out / "main-buffer.txt"), "--package", PACKAGE, "--device", DEVICE])
        return code, stdout.getvalue(), stderr.getvalue()

    def test_a_passing_fragment_is_published(self):
        code, out, _ = self.outcome(frag=fragment(True))
        self.assertEqual(code, lowram_outcome.PUBLISHED)
        self.assertIn("low_ram_repro_pass on lowram_1_5g published true", out)

    def test_a_failing_fragment_is_published_with_its_numbers_though_the_test_failed(self):
        code, out, _ = self.outcome(frag=fragment(False), log=FAILED)
        self.assertEqual(code, lowram_outcome.PUBLISHED)
        self.assertIn("published false", out)
        self.assertIn("+13.3%", out)

    def test_a_crash_buffer_entry_is_a_death_quoted_from_its_first_line(self):
        code, _, err = self.outcome(log=CRASHED, crash=CRASH, events=CRASH_EVENTS)
        self.assertEqual(code, lowram_outcome.DIED)
        self.assertIn("process died during the low RAM repro on lowram_1_5g", err)
        self.assertIn("1 crash buffer entry", err)
        quoted = [line.strip() for line in err.splitlines()[1:]]
        self.assertTrue(quoted[0].endswith("FATAL EXCEPTION: main"))
        self.assertIn("CrashedByAdbException", err)

    def test_am_proc_died_without_the_runners_stop_is_a_death(self):
        code, _, err = self.outcome(log=CRASHED, events="\n".join([OTHER_DIED, CRASH_EVENTS]))
        self.assertEqual(code, lowram_outcome.DIED)
        self.assertIn("(the events buffer)", err)
        self.assertIn("am_proc_died: [0,7583,com.shinsaikou,0,2]", err)
        self.assertNotIn("com.android.systemui", err)

    def test_a_low_memory_kill_is_a_death_quoting_its_reason(self):
        # Inferred, not observed: lmkd's kill as ActivityManager logs it, with no crash buffer entry,
        # and am instrument closing the way it did for the crash, since the runner outlives the process.
        code, _, err = self.outcome(log=CRASHED, events="\n".join([CRASH_START, LMK_KILL, DIED]))
        self.assertEqual(code, lowram_outcome.DIED)
        self.assertIn("lmk (low memory killer)", err)

    def test_the_runners_own_stop_and_the_death_after_it_are_not_a_death(self):
        events = "\n".join([START, TEST_APK_START, FINISHED, DIED_AFTER_FINISHED])
        self.assertEqual(lowram_outcome.death_lines(events, PACKAGE), [])

    def test_the_test_apks_own_process_is_not_the_package(self):
        events = "\n".join([START, TEST_APK_START.replace("am_proc_start", "am_proc_died"), FINISHED])
        self.assertEqual(lowram_outcome.death_lines(events, PACKAGE), [])

    def test_am_instrument_saying_the_process_crashed_is_a_death_when_the_buffers_say_nothing(self):
        code, _, err = self.outcome(log=CRASHED, events=CRASH_START)
        self.assertEqual(code, lowram_outcome.DIED)
        self.assertIn("shortMsg=Process crashed.", err)

    def test_a_failure_without_a_fragment_or_a_death_is_unmeasurable_quoting_the_test(self):
        code, _, err = self.outcome(log=FAILED)
        self.assertEqual(code, lowram_outcome.UNMEASURABLE)
        self.assertIn("could not take its measurement", err)
        self.assertIn("R36", err)
        self.assertIn("showed 94 of the 1000 seeded entries", err)

    def test_no_runner_installed_is_missing_and_not_a_death(self):
        code, _, err = self.outcome(log=None, events="")
        self.assertEqual(code, lowram_outcome.NEVER_RAN)
        self.assertIn("MISSING low_ram_repro_pass on lowram_1_5g", err)
        self.assertIn("the runner was not installed", err)
        self.assertNotIn("died", err)

    def test_a_run_of_no_tests_is_missing(self):
        code, _, err = self.outcome(log=NO_TESTS, events="")
        self.assertEqual(code, lowram_outcome.NEVER_RAN)
        self.assertIn("started no test", err)

    def test_a_test_that_started_no_process_is_missing(self):
        code, _, err = self.outcome(log=FAILED, events=TEST_APK_START)
        self.assertEqual(code, lowram_outcome.NEVER_RAN)
        self.assertIn("no process of com.shinsaikou started", err)

    def test_a_pass_that_published_nothing_is_an_early_return_and_missing(self):
        skipped = ("09-24 04:16:32.000  6544  6570 I LowRamReproTest: Skipped: " + TEST +
                   ": the row binds lowram_1_5g and this run is on pixel9_4g")
        code, _, err = self.outcome(main=skipped)
        self.assertEqual(code, lowram_outcome.NEVER_RAN)
        self.assertIn("this run is on pixel9_4g", err)

    def test_a_session_the_host_lost_is_not_the_applications_death(self):
        code, _, err = self.outcome(log=HEADER, crash=LOST_CRASH, events=LOST_EVENTS)
        self.assertEqual(code, lowram_outcome.LOST)
        self.assertIn("lost its am instrument session", err)
        self.assertIn("Rerun the step", err)
        self.assertIn("DeadObjectException", err)
        self.assertNotIn("process died", err)

    def test_a_real_deaths_output_closes_with_a_result_code(self):
        self.assertIsNotNone(lowram_outcome.RESULT_CODE.search(CRASHED))
        self.assertEqual(self.outcome(log=CRASHED, crash=CRASH, events=CRASH_EVENTS)[0], lowram_outcome.DIED)

    def test_a_missing_buffer_dump_or_another_rows_fragment_is_an_input_error(self):
        with contextlib.redirect_stderr(io.StringIO()):
            code = lowram_outcome.main(["--fragment", "/nonexistent/f", "--instrument-log", "/nonexistent/l",
                                        "--crash-buffer", "/nonexistent/c", "--events-buffer", "/nonexistent/e",
                                        "--package", PACKAGE, "--device", DEVICE])
        self.assertEqual(code, lowram_outcome.BAD_INPUT)
        self.assertEqual(self.outcome(frag=fragment(True, row="webtoon_jank_pct"))[0], lowram_outcome.BAD_INPUT)
        self.assertEqual(self.outcome(frag="{not json")[0], lowram_outcome.BAD_INPUT)


if __name__ == "__main__":
    unittest.main()
