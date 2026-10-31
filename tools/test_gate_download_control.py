#!/usr/bin/env python3
"""Unit tests for gate.sh's step 5f run of the chapter row's downloading state (phase 5 unit ID3).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Nothing here starts a fixture server, binds a port or touches a device. Step 5f is read out of gate.sh
as written, with the functions it calls and the run's EXIT trap, and run by bash in a temporary tree
whose `adb`, `./gradlew` and `ss` are stand-ins that log each call, and whose
tools/bench/download_hold_server.py is a stand-in that says ready, waits for SIGTERM and records it.
The force-stop row's two helpers, force_stop_between and published_true, are stand-ins too: their
own runs are not what this file tests. tools/ci/ran_tests.py is the real one, reading what the
stand-in Gradle wrote, as it reads AGP's results.

The stand-in Gradle logs ChapterRowDeviceTest's Skipped: line when its run carries no
saikou.download.fixture.apk argument, as the method does, so a step that dropped the argument fails.

The cases that matter, and why each one is here:
  - a passing run: after the force-stop row, whose server has stopped, a second server starts with a
    log of its own, the one method runs in one connected run with the fixture APK's device path, the
    server stops and the port is checked free;
  - the run fails, and the server is stopped by then, when the method skipped, when it failed, and
    when the port was taken before the second server;
  - the step reads the run back by name with --fail-on-skips and no expected skip.
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
CLASS = "com.shinsaikou.ui.manga.ChapterRowDeviceTest"
METHOD = "aDownloadingChapterDrawsItsProgressAndItsTapsAreTheQueuedOnes"
DEVICE_APK = "/data/local/tmp/saikou-download-fixture.apk"
LABEL = f"{CLASS}#{METHOD} over the download fixture on pixel9_4g"


def step_5f() -> str:
    """Step 5f as gate.sh writes it, from its step line to the `fi` that closes phase 2's block."""
    start = GATE.index('    step "5f.')
    return "if phase2; then\n" + GATE[start:GATE.index("\nfi\n", start) + 4]


# adb, ./gradlew and ss: each logs its name and arguments as one JSON line. A connected ./gradlew run
# writes the results directory for the filter it names, as a passing AGP run does: one test case, a
# failure in it when FAIL is set, and a per-test logcat holding the method's Skipped: line when its
# arguments carry no saikou.download.fixture.apk. Any other ./gradlew call (the install) only passes.
# ss prints a listener on its <PORT_TAKEN_AT>th call.
STAND_IN = textwrap.dedent('''
    #!/usr/bin/env python3
    import json, os, sys
    from pathlib import Path
    name = os.path.basename(sys.argv[0])
    args = sys.argv[1:]
    with open(os.environ["CALLS"], "a") as log:
        log.write(json.dumps([name, *args]) + "\\n")
    if name == "ss":
        count = Path(os.environ["CALLS"] + ".ss")
        seen = int(count.read_text()) + 1 if count.exists() else 1
        count.write_text(str(seen))
        if os.environ.get("PORT_TAKEN_AT") == str(seen):
            print("LISTEN 0 5 127.0.0.1:18794 0.0.0.0:*")
        sys.exit(0)
    if name == "adb" or not any(a.endswith("connectedDebugAndroidTest") for a in args):
        sys.exit(0)
    wanted = next(a.split("=", 1)[1] for a in args if "testInstrumentationRunnerArguments.class=" in a)
    cls, _, method = wanted.partition("#")
    results = Path(os.environ["RESULTS"])
    results.mkdir(parents=True, exist_ok=True)
    failure = '<failure message="it failed"/>' if os.environ.get("FAIL") else ""
    (results / "TEST-emulator-5700.xml").write_text(
        f'<testsuites><testsuite tests="1"><testcase classname="{cls}" name="{method}">{failure}'
        "</testcase></testsuite></testsuites>")
    logcat = "10-11 07:00:00.000  100  100 I Test: started\\n"
    if not any("saikou.download.fixture.apk=" in a for a in args):
        logcat += f"10-11 07:00:00.100  100  100 I ChapterRowDeviceTest: Skipped: {method}: no saikou.download.fixture.apk argument\\n"
    (results / "logcat-x.txt").write_text(logcat)
    sys.exit(1 if os.environ.get("FAIL") else 0)
''').lstrip()

# The hold server's stand-in: records its arguments, appends "started <log>" to the state file, says
# ready, and on SIGTERM appends "stopped <log>" and exits. It gives up after 60 s, so a gate.sh that
# never stops it leaves no process behind the failing test.
SERVER = textwrap.dedent('''
    #!/usr/bin/env python3
    import json, os, signal, sys, time
    with open(os.environ["CALLS"], "a") as log:
        log.write(json.dumps(["download_hold_server.py", *sys.argv[1:]]) + "\\n")
    where = sys.argv[sys.argv.index("--log") + 1]
    def note(what):
        with open(os.environ["SERVER_STATE"], "a") as state:
            state.write(f"{what} {where}\\n")
    def stop(*_):
        note("stopped")
        sys.exit(0)
    signal.signal(signal.SIGTERM, stop)
    note("started")
    print("ready 18794", flush=True)
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        time.sleep(0.1)
''').lstrip()

# The force-stop row's own helpers, which this file does not test: each logs its name and passes.
ROW_HELPERS = textwrap.dedent('''
    force_stop_between() { printf '["force_stop_between"]\\n' >> "$CALLS"; }
    published_true() { printf '["published_true"]\\n' >> "$CALLS"; }
''')

FORCE_STOP_LOG = "tools/out/bench/pixel9_4g/download-force-stop/server.requests.jsonl"
CONTROL_LOG = "tools/out/bench/pixel9_4g/download-control/server.requests.jsonl"


class Tree:
    """A checkout-shaped temporary directory with the stand-ins and the real ran_tests.py."""

    def __init__(self, test: unittest.TestCase) -> None:
        self.root = Path(tempfile.mkdtemp(prefix="gate-download-control-"))
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
        (self.root / "tools" / "bench" / "download_hold_server.py").write_text(SERVER)
        fixture = self.root / "tools" / "out" / "update-fixture"
        fixture.mkdir(parents=True)
        (fixture / "update-fixture.apk").write_text("not an apk")
        self.calls_log = self.root / "calls.log"
        self.server_state = self.root / "server.state"

    def run(self, step: str | None = None, **env: str) -> subprocess.CompletedProcess:
        script = "\n".join([
            "set -uo pipefail", "PHASE=2", "phase2() { [ \"$PHASE\" -ge 2 ]; }", "passed=0; skipped=0", helpers(),
            f"HERE={self.root / 'tools' / 'ci'}", f"ADB={self.adb}", "GRADLE_TASKS=present",
            f"SERIAL_4G={SERIAL}", "DEVICE_ID_4G=pixel9_4g", "BENCH_OUT=tools/out/bench", "STRICT_ARGS=()",
            "SAIKOU_STRICT=true", line(r"^DOWNLOAD_FIXTURE_APK=.*$"), line(r"^DEVICE_DOWNLOAD_FIXTURE_APK=.*$"),
            line(r"^DOWNLOAD_FORCE_STOP_OUTCOME=.*$"), "FIXTURE_SERVER_PID=",
            function("start_fixture_server"), function("stop_fixture_server"), function("port_free"),
            function("ran_tests"), function("fresh_results"), line(r"^CONNECTED_LOG=.*$"), function("connected_run"),
            "PLAY_STORE_OFF=()", function("play_store_on"), function("on_exit"), line(r"^trap .*$"), ROW_HELPERS,
            step if step is not None else step_5f(), 'echo "after the step"'])
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

    def connected(self) -> list[list[str]]:
        return [call for call in self.calls() if call[0] == "gradlew" and ":app:connectedDebugAndroidTest" in call]

    def servers(self) -> list[str]:
        return self.server_state.read_text().splitlines() if self.server_state.exists() else []


class DownloadControlRunTest(unittest.TestCase):

    def test_a_passing_run_serves_the_method_on_a_fresh_server_after_the_force_stop_row(self):
        tree = Tree(self)
        answer = tree.run()
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        self.assertIn("after the step", answer.stdout)
        # The force-stop row as before, then the port checked, a second server, the one run, and the
        # port checked again.
        self.assertEqual(tree.names(), [
            "gradlew", "adb", "ss", "download_hold_server.py", "force_stop_between", "adb", "adb", "published_true",
            "ss", "download_hold_server.py", "gradlew", "ss"])
        self.assertEqual(tree.servers(), [f"started {FORCE_STOP_LOG}", f"stopped {FORCE_STOP_LOG}",
                                          f"started {CONTROL_LOG}", f"stopped {CONTROL_LOG}"])
        [run] = tree.connected()
        self.assertIn(f"-Pandroid.testInstrumentationRunnerArguments.class={CLASS}#{METHOD}", run)
        self.assertIn(f"-Pandroid.testInstrumentationRunnerArguments.saikou.download.fixture.apk={DEVICE_APK}", run)
        self.assertIn(f"PASS  {LABEL}", answer.stdout)
        self.assertIn("PASS  port 18794 free again after the download fixture servers", answer.stdout)

    def test_a_run_without_the_fixture_argument_fails_the_step_and_the_server_is_stopped(self):
        # As the R29 of this run: the step with its fixture argument taken out.
        argument = f'            "-Pandroid.testInstrumentationRunnerArguments.saikou.download.fixture.apk=$DEVICE_DOWNLOAD_FIXTURE_APK" \\\n'
        step = step_5f()
        self.assertEqual(step.count(argument), 1)
        tree = Tree(self)
        answer = tree.run(step=step.replace(argument, ""))
        self.assertEqual(answer.returncode, 1)
        self.assertNotIn("after the step", answer.stdout)
        self.assertIn(f"phase 2 failed at: {CLASS}#{METHOD} over the download fixture on pixel9_4g ran no test of its own",
                      answer.stderr)
        self.assertIn("Skipped: line(s) this step does not allow", answer.stderr)
        self.assertEqual(tree.servers()[-1], f"stopped {CONTROL_LOG}")

    def test_a_failed_method_fails_the_step_and_the_server_is_stopped(self):
        tree = Tree(self)
        answer = tree.run(FAIL="1")
        self.assertEqual(answer.returncode, 1)
        self.assertIn(f"phase 2 failed at: {LABEL}", answer.stderr)
        self.assertEqual(tree.servers()[-1], f"stopped {CONTROL_LOG}")

    def test_a_port_taken_before_the_second_server_fails_the_step_before_its_run(self):
        tree = Tree(self)
        answer = tree.run(PORT_TAKEN_AT="2")
        self.assertEqual(answer.returncode, 1)
        self.assertIn("phase 2 failed at: chapter row downloading state: port 18794 is in use", answer.stderr)
        self.assertEqual(tree.connected(), [])
        self.assertEqual(tree.servers(), [f"started {FORCE_STOP_LOG}", f"stopped {FORCE_STOP_LOG}"])

    def test_the_run_expects_no_skip_and_reads_its_class_back_by_name(self):
        step = step_5f()
        block = step[step.index("download_control_out="):]
        self.assertNotIn("--except", block)
        self.assertIn("--fail-on-skips", block)
        self.assertIn('--require-class "${download_control_filter%%#*}"', block)
        self.assertIn("--fail-on-failures", block)
        self.assertEqual(len(re.findall(r"^\s*connected_run ", block, re.M)), 1)


if __name__ == "__main__":
    unittest.main()
