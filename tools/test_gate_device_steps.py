#!/usr/bin/env python3
"""Unit tests for two things gate.sh does around its device steps (phase 4 unit GH).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Nothing here runs a gate step or touches a device. The functions under test are read out of gate.sh
as written, with its step, ok, skip, fail and run helpers and its EXIT trap, and run by bash in a
temporary tree whose `adb` and `./gradlew` are stand-ins that log each call and answer as the test
tells them, the way tools/test_gate_phase.py and tools/test_sync_pair.py build theirs.

The cases that matter, and why each one is here:
  - the Play Store (R122 carry-over 6): turned off on a serial whose AVD has it, and on again when
    the run ends, whether it passes or a step fails (fail() exits, and the EXIT trap is what turns it
    back on); nothing but the one listing call on an AVD without it; one serial handed in twice is
    turned off once; and gate.sh calls it for each of the five serials after step 4 and sets its trap
    once, with no step replacing it;
  - one retry of a connected run that ran no test (R98 CO5, R105 CO5, R109 CO6, R123 CO1): an empty
    results directory after a run that exited 0 prints the installer's INSTALL_FAILED lines,
    uninstalls every com.shinsaikou package on that serial and nothing else, and runs the same task once
    more, whose exit decides; a run that wrote a test case is never retried, passed or failed; and
    every connected-test task gate.sh starts goes through that one function.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
GATE = (TOOLS / "ci" / "gate.sh").read_text()
SERIAL = "emulator-5700"
RESULTS = "app/build/outputs/androidTest-results/connected/debug"
TESTCASE_XML = '<testsuites><testsuite tests="1"><testcase classname="com.shinsaikou.X" name="m"/></testsuite></testsuites>'


def function(name: str) -> str:
    """The function's text as gate.sh defines it, from its first line to its closing brace."""
    match = re.search(rf"^{name}\(\) \{{.*?^\}}$", GATE, re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError(f"gate.sh defines no {name} function")
    return match.group(0)


def helpers() -> str:
    """gate.sh's step, ok, skip, fail and run, as test_sync_pair.py's GateStepTest reads them."""
    start = GATE.index("step() {")
    return GATE[start:GATE.index("\n}\n", GATE.index("run() {")) + 3]


def line(pattern: str) -> str:
    matches = re.findall(pattern, GATE, re.MULTILINE)
    if len(matches) != 1:
        raise AssertionError(f"gate.sh has {len(matches)} lines matching {pattern!r}, where the test reads one")
    return matches[0]


# A stand-in that logs its name and arguments as one JSON line and answers from the environment.
STAND_IN = textwrap.dedent('''
    #!/usr/bin/env python3
    import json, os, sys
    from pathlib import Path
    name = os.path.basename(sys.argv[0])
    args = sys.argv[1:]
    with open(os.environ["CALLS"], "a") as log:
        log.write(json.dumps([name, *args]) + "\\n")
    if name == "adb":
        joined = " ".join(args)
        if "pm list packages" in joined:
            wanted = args[-1]
            listed = [p for p in os.environ.get("PACKAGES", "").split() if wanted in p]
            print("\\r\\n".join("package:" + p for p in listed), end="\\r\\n" if listed else "")
        sys.exit(int(os.environ.get("ADB_EXIT", "0")))
    # ./gradlew: attempt n prints OUTPUT_<n>, writes a test case into RESULTS when WRITE_<n> is set,
    # and exits EXIT_<n>.
    count = Path(os.environ["CALLS"] + ".gradlew")
    attempt = int(count.read_text()) + 1 if count.exists() else 1
    count.write_text(str(attempt))
    print(os.environ.get(f"OUTPUT_{attempt}", "BUILD SUCCESSFUL"))
    if os.environ.get(f"WRITE_{attempt}"):
        results = Path(os.environ["RESULTS"])
        results.mkdir(parents=True, exist_ok=True)
        (results / "TEST-emulator-5700 - 16-_app-.xml").write_text(os.environ["TESTCASE_XML"])
    sys.exit(int(os.environ.get(f"EXIT_{attempt}", "0")))
''').lstrip()


class Tree:
    """A checkout-shaped temporary directory with a stand-in adb and ./gradlew."""

    def __init__(self, test: unittest.TestCase) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="gate-device-steps-"))
        test.addCleanup(shutil.rmtree, self.root, True)
        (self.root / "sdk" / "platform-tools").mkdir(parents=True)
        self.adb = self.root / "sdk" / "platform-tools" / "adb"
        self.calls_log = self.root / "calls.log"
        for path in (self.adb, self.root / "gradlew"):
            path.write_text(STAND_IN)
            path.chmod(0o755)

    def run(self, body: str, **env: str) -> subprocess.CompletedProcess:
        script = "\n".join([
            "set -uo pipefail", "PHASE=2", "passed=0; skipped=0", helpers(),
            f"ADB={shlex.quote(str(self.adb))}", "FIXTURE_SERVER_PID=",
            function("stop_fixture_server"), line(r"^CONNECTED_LOG=.*$"), function("connected_run"),
            line(r"^PLAY_STORE=.*$"), line(r"^PLAY_STORE_OFF=.*$"),
            function("play_store_off"), function("play_store_on"), function("on_exit"),
            line(r"^trap .*$"), body])
        environment = dict(os.environ, CALLS=str(self.calls_log), RESULTS=RESULTS, TESTCASE_XML=TESTCASE_XML,
                           **env)
        return subprocess.run(["bash", "-c", script], cwd=self.root, env=environment, capture_output=True,
                              text=True, timeout=60)

    def calls(self) -> list[list[str]]:
        if not self.calls_log.exists():
            return []
        return [json.loads(entry) for entry in self.calls_log.read_text().splitlines()]


def adb(*args: str) -> list[str]:
    return ["adb", "-s", SERIAL, *args]


LIST_STORE = adb("shell", "pm", "list", "packages", "com.android.vending")
DISABLE = adb("shell", "pm", "disable-user", "--user", "0", "com.android.vending")
ENABLE = adb("shell", "pm", "enable", "com.android.vending")


class PlayStoreTest(unittest.TestCase):

    def test_an_avd_with_the_play_store_has_it_off_for_the_run_and_on_again_at_a_passing_end(self):
        tree = Tree(self)
        answer = tree.run(f'play_store_off {SERIAL}\necho "the run goes on"\nexit 0',
                          PACKAGES="com.android.vending com.android.chrome")
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(tree.calls(), [LIST_STORE, DISABLE, ENABLE])
        self.assertIn(f"the Play Store is off on {SERIAL} for this run", answer.stdout)
        self.assertLess(answer.stdout.index("the run goes on"), answer.stdout.index("is on again"))

    def test_a_step_that_fails_still_turns_it_on_again(self):
        tree = Tree(self)
        answer = tree.run(f'play_store_off {SERIAL}\nrun "a device step" false\necho "not reached"',
                          PACKAGES="com.android.vending")
        self.assertEqual(answer.returncode, 1)
        self.assertIn("phase 2 failed at: a device step", answer.stderr)
        self.assertNotIn("not reached", answer.stdout)
        self.assertEqual(tree.calls(), [LIST_STORE, DISABLE, ENABLE])

    def test_an_avd_without_the_play_store_gets_only_the_listing_call(self):
        tree = Tree(self)
        for ending in ("exit 0", 'fail "a step"'):
            with self.subTest(ending=ending):
                tree.calls_log.unlink(missing_ok=True)
                answer = tree.run(f"play_store_off {SERIAL}\n{ending}", PACKAGES="com.android.chrome")
                self.assertEqual(tree.calls(), [LIST_STORE])
                self.assertNotIn("Play Store", answer.stdout)

    def test_a_serial_named_twice_is_turned_off_once_and_on_once(self):
        tree = Tree(self)
        answer = tree.run(f"play_store_off {SERIAL}\nplay_store_off {SERIAL}\nexit 0", PACKAGES="com.android.vending")
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(tree.calls(), [LIST_STORE, DISABLE, ENABLE])

    def test_a_refused_disable_is_not_remembered_and_the_run_goes_on(self):
        tree = Tree(self)
        answer = tree.run(f'play_store_off {SERIAL}\necho "went on"\nexit 0', PACKAGES="com.android.vending",
                          ADB_EXIT="1")
        self.assertEqual(answer.returncode, 0)
        self.assertIn("went on", answer.stdout)
        self.assertIn(f"adb could not turn the Play Store off on {SERIAL}", answer.stderr)
        self.assertEqual(tree.calls(), [LIST_STORE, DISABLE])

    def test_the_gate_turns_it_off_on_each_serial_after_step_4_and_sets_its_trap_once(self):
        self.assertEqual(re.findall(r"^\s*trap .*$", GATE, re.MULTILINE), ["trap on_exit EXIT"])
        self.assertLess(GATE.index("\ntrap on_exit EXIT\n"), GATE.index('step "0. deliverables"'))
        loop = GATE.index('for serial in "$SERIAL_2G" "$SERIAL_4G" "$SERIAL_TAB" "$SERIAL_API26" "$SERIAL_LOWRAM"; do')
        self.assertIn('[ -z "$serial" ] || play_store_off "$serial"', GATE[loop:loop + 200])
        self.assertLess(GATE.index('step "4. AVDs"'), loop)
        self.assertLess(loop, GATE.index('step "5. instrumentation'))
        self.assertIn("stop_fixture_server", function("on_exit"))
        self.assertIn("play_store_on", function("on_exit"))


INSTALL_FAILED = "Failure [INSTALL_FAILED_ALREADY_EXISTS: Attempt to re-install com.shinsaikou.test]"
TASK = [":app:connectedDebugAndroidTest", "-Pandroid.testInstrumentationRunnerArguments.class=com.shinsaikou.X"]
CONNECTED = f'connected_run "the step" {SERIAL} {RESULTS} {shlex.join(TASK)}\necho "after the step"'
LIST_SAIKOU = adb("shell", "pm", "list", "packages", "com.shinsaikou")


class ConnectedRetryTest(unittest.TestCase):

    def test_a_run_that_wrote_no_test_case_is_run_once_more_after_the_uninstall(self):
        tree = Tree(self)
        answer = tree.run(CONNECTED, OUTPUT_1=f"{INSTALL_FAILED}\nINSTALL_FAILED_ALREADY_EXISTS again\nBUILD SUCCESSFUL",
                          WRITE_2="1", PACKAGES="com.shinsaikou com.shinsaikou.test com.example.com.shinsaikou")
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        self.assertEqual(tree.calls(), [
            ["gradlew", *TASK], LIST_SAIKOU, adb("uninstall", "com.shinsaikou"), adb("uninstall", "com.shinsaikou.test"),
            ["gradlew", *TASK]])
        self.assertIn("the step ran no test; it runs once more", answer.stdout)
        failed = [row for row in answer.stdout.splitlines() if "installer refused" in row]
        self.assertEqual(failed, [f"    the installer refused the first run on {SERIAL}: INSTALL_FAILED_ALREADY_EXISTS"])
        self.assertIn("PASS  the step", answer.stdout)
        self.assertIn("after the step", answer.stdout)
        self.assertTrue(list((tree.root / RESULTS).glob("*.xml")))

    def test_the_second_runs_exit_decides(self):
        tree = Tree(self)
        answer = tree.run(CONNECTED, EXIT_2="1", PACKAGES="")
        self.assertEqual(answer.returncode, 1)
        self.assertIn("phase 2 failed at: the step", answer.stderr)
        self.assertEqual(tree.calls(), [["gradlew", *TASK], LIST_SAIKOU, ["gradlew", *TASK]])
        self.assertNotIn("installer refused", answer.stdout)

    def test_a_failed_gradle_run_that_wrote_no_test_case_is_retried_too(self):
        tree = Tree(self)
        answer = tree.run(CONNECTED, EXIT_1="1", OUTPUT_1=INSTALL_FAILED, WRITE_2="1", PACKAGES="com.shinsaikou")
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(tree.calls(), [["gradlew", *TASK], LIST_SAIKOU, adb("uninstall", "com.shinsaikou"),
                                        ["gradlew", *TASK]])

    def test_the_directory_is_emptied_before_the_retry(self):
        tree = Tree(self)
        stale = tree.root / RESULTS / "TEST-empty.xml"
        stale.parent.mkdir(parents=True)
        stale.write_text('<testsuites tests="0"></testsuites>')
        answer = tree.run(CONNECTED, WRITE_2="1", PACKAGES="")
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertFalse(stale.exists())

    def test_a_run_with_test_cases_is_never_retried_whether_it_passed_or_failed(self):
        for code, verdict in (("0", "PASS  the step"), ("1", "phase 2 failed at: the step")):
            with self.subTest(exit=code):
                tree = Tree(self)
                answer = tree.run(CONNECTED, WRITE_1="1", EXIT_1=code, OUTPUT_1=INSTALL_FAILED,
                                  PACKAGES="com.shinsaikou")
                self.assertEqual(answer.returncode, int(code))
                self.assertEqual(tree.calls(), [["gradlew", *TASK]])
                self.assertIn(verdict, answer.stdout + answer.stderr)
                self.assertNotIn("runs once more", answer.stdout)

    def test_every_connected_test_task_in_the_gate_goes_through_connected_run(self):
        started = re.findall(r"^.*:connected(?:Debug|Release)AndroidTest.*$", GATE, re.MULTILINE)
        gradle = [row for row in started if "gradlew" in row]
        self.assertEqual(gradle, [], "a connected-test task starts outside connected_run")
        calls = re.findall(r"^\s*connected_run \"[^\"]+\" \"\$SERIAL_\w+\" \\$", GATE, re.MULTILINE)
        self.assertEqual(len(calls), 8, "steps 5, 5b, 5c, 5d, 5e, 5f, 5i and 7 each start one through connected_run")
        self.assertIn('./gradlew "$@"', function("connected_run"))


if __name__ == "__main__":
    unittest.main()
