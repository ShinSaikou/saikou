#!/usr/bin/env python3
"""Unit tests for gate.sh's step 5i, the browse fixture methods on the 4 GB AVD (phase 5 unit TG4).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Nothing here starts a fixture server, binds a port or touches a device. The step is read out of
gate.sh as written, with the functions it calls and the run's EXIT trap, and run by bash in a
temporary tree whose `adb`, `./gradlew` and `ss` are stand-ins that log each call and answer as the
test tells them, and whose tools/bench/browse_fixture_server.py is a stand-in that says ready, waits
for SIGTERM and records it. tools/ci/ran_tests.py is the real one, reading what the stand-in Gradle
wrote, as it reads AGP's results.

The cases that matter, and why each one is here:
  - a passing run: the server starts once, before the first connected run, with the repository; each
    of the five filters runs in its own connected run with saikou.browse.fixture=true and the fixture
    APK's device path; then the server stops and the port is checked free;
  - the server stops on every path: a class that skipped (its logcat holds a Skipped: line, which
    --fail-on-skips fails with no --except), a failed test, and a connected run that ran nothing
    each fail the step, and the EXIT trap has stopped the server by then;
  - a taken port fails the step naming it, and a server that never says ready fails it, both before
    any connected run;
  - without the fixture repository the step is one SKIP line and calls nothing.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

from test_gate_device_steps import function, helpers, line

TOOLS = Path(__file__).resolve().parent
GATE = (TOOLS / "ci" / "gate.sh").read_text()
SERIAL = "emulator-5700"
RESULTS = "app/build/outputs/androidTest-results/connected/debug"
FILTERS = [
    "com.shinsaikou.ui.browse.FixtureSourcesDeviceTest",
    "com.shinsaikou.ui.browse.BrowseSelectionDeviceTest",
    "com.shinsaikou.ui.browse.ExtensionStoresDeviceTest",
    "com.shinsaikou.ui.browse.ExtensionFilterDeviceTest"
    "#overTheFixtureStoreTheFilterListsEnglishAndItsSwitchHidesAndShowsTheExtension",
    "com.shinsaikou.ui.browse.SourcesFilterDeviceTest#aLanguageSwitchedOffAndASourceUncheckedLeaveTheSourcesPage",
]
DEVICE_APK = "/data/local/tmp/saikou-download-fixture.apk"


def step_5i() -> str:
    """Step 5i's block as gate.sh writes it, from its `if phase2; then` to the `fi` that closes it."""
    start = GATE.index('if phase2; then\n    step "5i.')
    return GATE[start:GATE.index("\nfi\n", start) + 4]


# adb, ./gradlew and ss: each logs its name and arguments as one JSON line. ./gradlew writes the
# results directory for the class its filter names, as a passing AGP run does: one test case, a
# failure in it when FAIL_<n> is set, and a per-test logcat holding a Skipped: line when SKIP_<n> is
# set; with EMPTY_<n> it writes nothing. ss prints a listener when PORT_TAKEN is set.
STAND_IN = textwrap.dedent('''
    #!/usr/bin/env python3
    import json, os, re, sys
    from pathlib import Path
    name = os.path.basename(sys.argv[0])
    args = sys.argv[1:]
    with open(os.environ["CALLS"], "a") as log:
        log.write(json.dumps([name, *args]) + "\\n")
    if name == "ss":
        if os.environ.get("PORT_TAKEN"):
            print("LISTEN 0 5 127.0.0.1:18794 0.0.0.0:*")
        sys.exit(0)
    if name == "adb":
        sys.exit(0)
    count = Path(os.environ["CALLS"] + ".gradlew")
    attempt = int(count.read_text()) + 1 if count.exists() else 1
    count.write_text(str(attempt))
    if os.environ.get(f"EMPTY_{attempt}"):
        sys.exit(0)
    wanted = next(a.split("=", 1)[1] for a in args if "testInstrumentationRunnerArguments.class=" in a)
    cls, _, method = wanted.partition("#")
    results = Path(os.environ["RESULTS"])
    results.mkdir(parents=True, exist_ok=True)
    failure = '<failure message="it failed"/>' if os.environ.get(f"FAIL_{attempt}") else ""
    (results / "TEST-emulator-5700.xml").write_text(
        f'<testsuites><testsuite tests="1"><testcase classname="{cls}" name="{method or "m"}">{failure}'
        "</testcase></testsuite></testsuites>")
    logcat = "10-11 07:00:00.000  100  100 I Test: started\\n"
    if os.environ.get(f"SKIP_{attempt}"):
        logcat += f"10-11 07:00:00.100  100  100 I Test: Skipped: {method or 'm'}: no saikou.browse.fixture argument\\n"
    (results / "logcat-x.txt").write_text(logcat)
    sys.exit(1 if os.environ.get(f"FAIL_{attempt}") else 0)
''').lstrip()

# The browse fixture server's stand-in: records its arguments, says ready unless NOT_READY is set (then
# it exits at once), and on SIGTERM records that it stopped and exits. It gives up after 60 s, so a
# gate.sh that never stops it leaves no process behind the failing test.
SERVER = textwrap.dedent('''
    #!/usr/bin/env python3
    import json, os, signal, sys, time
    from pathlib import Path
    state = Path(os.environ["SERVER_STATE"])
    with open(os.environ["CALLS"], "a") as log:
        log.write(json.dumps(["browse_fixture_server.py", *sys.argv[1:]]) + "\\n")
    if os.environ.get("NOT_READY"):
        print("cannot listen on 127.0.0.1:18794: taken", flush=True)
        sys.exit(3)
    def stop(*_):
        state.write_text("stopped")
        sys.exit(0)
    signal.signal(signal.SIGTERM, stop)
    state.write_text("running")
    print("ready 18794", flush=True)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        time.sleep(0.1)
''').lstrip()


class Tree:
    """A checkout-shaped temporary directory with the stand-ins and the real ran_tests.py."""

    def __init__(self, test: unittest.TestCase, repository: bool = True) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="gate-browse-step-"))
        test.addCleanup(shutil.rmtree, self.root, True)
        (self.root / "sdk" / "platform-tools").mkdir(parents=True)
        (self.root / "bin").mkdir()
        (self.root / "tools" / "ci").mkdir(parents=True)
        (self.root / "tools" / "bench").mkdir()
        shutil.copy(TOOLS / "ci" / "ran_tests.py", self.root / "tools" / "ci" / "ran_tests.py")
        self.adb = self.root / "sdk" / "platform-tools" / "adb"
        for path in (self.adb, self.root / "gradlew", self.root / "bin" / "ss"):
            path.write_text(STAND_IN)
            path.chmod(0o755)
        (self.root / "tools" / "bench" / "browse_fixture_server.py").write_text(SERVER)
        if repository:
            fixture = self.root / "tools" / "out" / "update-fixture"
            fixture.mkdir(parents=True)
            (fixture / "update-fixture.apk").write_text("not an apk")
            (fixture / "signer.sha256").write_text("0" * 64)
        self.calls_log = self.root / "calls.log"
        self.server_state = self.root / "server.state"

    def run(self, **env: str) -> subprocess.CompletedProcess:
        script = "\n".join([
            "set -uo pipefail", "PHASE=2", "phase2() { [ \"$PHASE\" -ge 2 ]; }", "passed=0; skipped=0", helpers(),
            f"HERE={self.root / 'tools' / 'ci'}", f"ADB={self.adb}", "GRADLE_TASKS=present",
            f"SERIAL_4G={SERIAL}", "DEVICE_ID_4G=pixel9_4g", "BENCH_OUT=tools/out/bench", "STRICT_ARGS=()",
            line(r"^DOWNLOAD_FIXTURE_APK=.*$"), line(r"^DEVICE_DOWNLOAD_FIXTURE_APK=.*$"),
            line(r"^BROWSE_FIXTURE_REPO=.*$"), "FIXTURE_SERVER_PID=",
            function("start_fixture_server"), function("stop_fixture_server"), function("port_free"),
            function("ran_tests"), function("fresh_results"), line(r"^CONNECTED_LOG=.*$"), function("connected_run"),
            "PLAY_STORE_OFF=()", function("play_store_on"), function("on_exit"), line(r"^trap .*$"),
            step_5i(), 'echo "after the step"'])
        environment = dict(os.environ, CALLS=str(self.calls_log), RESULTS=RESULTS, SERVER_STATE=str(self.server_state),
                           PATH=f"{self.root / 'bin'}:{os.environ['PATH']}", **env)
        return subprocess.run(["bash", "-c", script], cwd=self.root, env=environment, capture_output=True,
                              text=True, timeout=120)

    def calls(self) -> list[list[str]]:
        if not self.calls_log.exists():
            return []
        return [json.loads(entry) for entry in self.calls_log.read_text().splitlines()]

    def names(self) -> list[str]:
        return [call[0] for call in self.calls()]

    def gradle_filters(self) -> list[str]:
        return [next(a.split("=", 1)[1] for a in call if "testInstrumentationRunnerArguments.class=" in a)
                for call in self.calls() if call[0] == "gradlew"]


class BrowseStepTest(unittest.TestCase):

    def test_a_passing_run_serves_every_filter_in_its_own_run_and_stops_the_server_after(self):
        tree = Tree(self)
        answer = tree.run()
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        self.assertIn("after the step", answer.stdout)
        self.assertEqual(tree.gradle_filters(), FILTERS)
        names = tree.names()
        # The push, the port check, the server, one run per filter, then the port checked again.
        self.assertEqual(names, ["adb", "ss", "browse_fixture_server.py", *["gradlew"] * len(FILTERS), "ss"])
        server = tree.calls()[2]
        self.assertEqual(server[1:], ["--log", "tools/out/bench/pixel9_4g/browse-fixture/server.requests.jsonl",
                                      "--repo-dir", "tools/out/update-fixture"])
        self.assertEqual(tree.calls()[0], ["adb", "-s", SERIAL, "push", "tools/out/update-fixture/update-fixture.apk",
                                           DEVICE_APK])
        for call in tree.calls():
            if call[0] == "gradlew":
                self.assertEqual(call[0:2], ["gradlew", ":app:connectedDebugAndroidTest"])
                self.assertIn("-Pandroid.testInstrumentationRunnerArguments.saikou.browse.fixture=true", call)
                self.assertIn(f"-Pandroid.testInstrumentationRunnerArguments.saikou.download.fixture.apk={DEVICE_APK}",
                              call)
        self.assertEqual(tree.server_state.read_text(), "stopped")
        self.assertIn("PASS  port 18794 free again after the browse fixture server", answer.stdout)
        for name in FILTERS:
            self.assertIn(f"PASS  {name} over the browse fixture on pixel9_4g", answer.stdout)

    def test_a_skipped_method_fails_the_step_and_the_server_is_stopped(self):
        tree = Tree(self)
        answer = tree.run(SKIP_2="1")
        self.assertEqual(answer.returncode, 1)
        self.assertNotIn("after the step", answer.stdout)
        self.assertIn(f"phase 2 failed at: {FILTERS[1]} over the browse fixture on pixel9_4g ran no test of its own",
                      answer.stderr)
        self.assertIn("Skipped: line(s) this step does not allow", answer.stderr)
        self.assertEqual(tree.gradle_filters(), FILTERS[:2])
        self.assertEqual(tree.server_state.read_text(), "stopped")

    def test_a_failed_test_fails_the_step_and_the_server_is_stopped(self):
        tree = Tree(self)
        answer = tree.run(FAIL_3="1")
        self.assertEqual(answer.returncode, 1)
        self.assertIn(f"phase 2 failed at: {FILTERS[2]} over the browse fixture on pixel9_4g", answer.stderr)
        self.assertEqual(tree.gradle_filters(), FILTERS[:3])
        self.assertEqual(tree.server_state.read_text(), "stopped")

    def test_a_run_that_ran_nothing_twice_fails_the_step_and_the_server_is_stopped(self):
        tree = Tree(self)
        answer = tree.run(EMPTY_1="1", EMPTY_2="1")
        self.assertEqual(answer.returncode, 1)
        self.assertIn("ran no test; it runs once more", answer.stdout)
        self.assertIn(f"phase 2 failed at: {FILTERS[0]} over the browse fixture on pixel9_4g", answer.stderr)
        self.assertEqual(tree.server_state.read_text(), "stopped")

    def test_a_taken_port_fails_the_step_before_any_server_or_run(self):
        tree = Tree(self)
        answer = tree.run(PORT_TAKEN="1")
        self.assertEqual(answer.returncode, 1)
        self.assertIn("phase 2 failed at: browse fixture methods: port 18794 is in use", answer.stderr)
        self.assertEqual(tree.names(), ["adb", "ss"])
        self.assertFalse(tree.server_state.exists())

    def test_a_server_that_never_says_ready_fails_the_step_before_any_run(self):
        tree = Tree(self)
        answer = tree.run(NOT_READY="1")
        self.assertEqual(answer.returncode, 1)
        self.assertIn("phase 2 failed at: browse fixture server on port 18794", answer.stderr)
        self.assertIn("cannot listen on 127.0.0.1:18794", answer.stderr)
        self.assertNotIn("gradlew", tree.names())

    def test_without_the_fixture_repository_the_step_skips_and_calls_nothing(self):
        tree = Tree(self, repository=False)
        answer = tree.run()
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertRegex(answer.stdout, r"SKIP  browse fixture methods \(no tools/out/update-fixture/update-fixture\.apk")
        self.assertEqual(tree.calls(), [])

    def test_the_step_expects_no_skip_and_runs_each_class_by_name(self):
        step = step_5i()
        self.assertNotIn("--except", step)
        self.assertIn("--fail-on-skips", step)
        self.assertIn('--require-class "${test_filter%%#*}"', step)
        self.assertIn("--fail-on-failures", step)
        self.assertEqual(re.findall(r"^\s*connected_run ", step, re.M), ["            connected_run "])


if __name__ == "__main__":
    unittest.main()
