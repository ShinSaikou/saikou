#!/usr/bin/env python3
"""Unit tests for tools/bench/sync_14_day.py, the sync_14_day_no_reconsent row's runner on saikou_api26_2g.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

adb is a stand-in written for each test, as in test_sync_pair.py, that logs every call and answers as
saikou_api26_2g would from a JSON plan. Its DriveTokenDeviceTest steps speak to the real sync fixture
the script started, as the app would: `sign-in` asks the authorization endpoint for a code and
exchanges it with the client secret, `after-idle` refreshes until the fixture answers (so the
dropped connection and the 503 the script asked for are met), reads Drive's about.get and refreshes
once more on the early 401, and `revoked` refreshes until the fixture refuses the grant. So the log
the script judges is the fixture's own. Unit H's report records the device runs.

The cases that matter, and why each one is here:
  - a passing run writes the fragment true on `any` in common.sh's fragment() shape, after the
    install and the clear, with sign-in's process stopped by run-as kill -9 when it outlived its
    instrumentation, the modes set between the two steps, after-idle given the fixture's base and
    the 14 days, and both packages removed after;
  - a --revoked run writes false into --evidence and nothing into --out, and without --evidence, or
    with it naming --out, it is a usage error;
  - no failed run leaves a fragment, and an earlier one is removed: a failing step, a result line
    naming another step or missing, after-idle in sign-in's process, a changed refresh token, an
    authorization request after sign-in's, a refresh without the client secret, no refresh at all,
    an injected failure never met, and a revoked run that synced;
  - a device that is not saikou_api26_2g is refused before anything starts, and a failed install is
    exit 3;
  - the class, argument, result key, steps and skip line are the ones in DriveTokenDeviceTest.kt,
    and gate.sh lists the class and expects its skip line in step 5c.
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
SCRIPT = TOOLS / "bench" / "sync_14_day.py"
TEST_SOURCE = ROOT / "app" / "src" / "androidTest" / "kotlin" / "com" / "shinsaikou" / "sync" / "DriveTokenDeviceTest.kt"
sys.path.insert(0, str(TOOLS))
from test_sync_pair import run_checked  # noqa: E402

_spec = importlib.util.spec_from_file_location("sync_14_day", SCRIPT)
sync_14_day = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sync_14_day)

SERIAL = "emulator-5556"
FRAGMENT_NAME = "sync_14_day_no_reconsent.any.json"

STUB = textwrap.dedent(r'''
    #!/usr/bin/env python3
    import base64, hashlib, http.client, json, os, secrets, sys, urllib.parse
    plan = json.load(open(os.environ["STUB_ADB_PLAN"]))
    state = os.environ["STUB_ADB_STATE"]
    args = sys.argv[1:]
    with open(os.environ["STUB_ADB_LOG"], "a") as log:
        log.write(json.dumps(args) + "\n")
    if len(args) < 3 or args[0] != "-s":
        print("stub adb: every call names its serial", file=sys.stderr)
        sys.exit(1)
    serial, rest = args[1], args[2:]
    device = plan["devices"].get(serial)
    if device is None:
        print(f"adb: device '{serial}' not found", file=sys.stderr)
        sys.exit(1)
    CLIENT_ID = "fixture-desktop-client.apps.googleusercontent.com"
    CLIENT_SECRET = "fixture-desktop-client-secret"
    REDIRECT = "http://127.0.0.1:9/callback"
    SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    def mine(name):
        return os.path.join(state, f"{serial}-{name}")

    def call(port, method, path, body=None, headers=None):
        """One request to the real fixture; status None when it dropped the connection."""
        connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            answer = connection.getresponse()
            return answer.status, answer.getheader("Location"), answer.read()
        except (OSError, http.client.HTTPException):
            return None, None, b""
        finally:
            connection.close()

    def token(port, form, secret=True):
        form = dict(form, client_id=CLIENT_ID, **({"client_secret": CLIENT_SECRET} if secret else {}))
        return call(port, "POST", "/token", urllib.parse.urlencode(form),
                    {"Content-Type": "application/x-www-form-urlencoded"})

    def authorize(port):
        verifier = secrets.token_urlsafe(48)
        challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
        query = urllib.parse.urlencode({"client_id": CLIENT_ID, "redirect_uri": REDIRECT, "response_type": "code",
                                        "scope": SCOPE, "code_challenge": challenge,
                                        "code_challenge_method": "S256", "state": "s"})
        status, location, _ = call(port, "GET", "/o/oauth2/v2/auth?" + query)
        code = urllib.parse.parse_qs(urllib.parse.urlsplit(location).query)["code"][0]
        return code, verifier

    def refresh(port, first_without_secret=False):
        """Refreshes until the fixture answers, as DriveBackoff retries a dropped or 503 refresh."""
        for attempt in range(5):
            status, _, body = token(port, {"grant_type": "refresh_token",
                                           "refresh_token": open(mine("refresh")).read()},
                                    secret=not (first_without_secret and attempt == 0))
            if status not in (None, 503):
                return status, json.loads(body or b"{}")
        return None, {}

    def about(port, access):
        return call(port, "GET", "/drive/v3/about?fields=user(emailAddress)", None,
                    {"Authorization": "Bearer " + access})[0]

    def emit(*lines):
        print("\r\n".join(lines), flush=True)

    if rest == ["emu", "avd", "name"]:
        print(device["avd"] + "\r\nOK\r")
    elif rest == ["shell", "getprop", "ro.build.version.sdk"]:
        print(device["api"] + "\r")
    elif rest[0] == "install":
        if plan.get("installFails"):
            print("Performing Streamed Install\nadb: failed to install: INSTALL_FAILED_INSUFFICIENT_STORAGE")
            sys.exit(1)
        print("Performing Streamed Install\nSuccess")
    elif rest == ["shell", "pm", "list", "instrumentation"]:
        print("instrumentation:com.shinsaikou.test/androidx.test.runner.AndroidJUnitRunner (target=com.shinsaikou)\r")
    elif rest == ["shell", "pm", "clear", "com.shinsaikou"]:
        print("Success\r")
    elif rest[0] == "uninstall":
        if plan.get("uninstallUndecodable"):
            # Bytes that are not UTF-8: the caller's subprocess.run(text=True) raises while reading
            # them, so the run fails after everything before its last clean-up has happened.
            sys.stdout.buffer.write(b"\xff\xfe not text\n")
            sys.exit(0)
        print("Success")
    elif rest == ["shell", "pidof", "com.shinsaikou"]:
        alive = plan.get("aliveAfterSignIn") and not os.path.exists(mine("killed"))
        print(("4001" if alive else "") + "\r")
    elif rest[:5] == ["shell", "run-as", "com.shinsaikou", "kill", "-9"]:
        open(mine("killed"), "w").close()
    elif rest[:3] == ["shell", "am", "instrument"]:
        step = rest[rest.index("saikou.drive.token.step") + 1]
        port = urllib.parse.urlsplit(rest[rest.index("saikou.google.base") + 1]).port
        answer = plan["steps"].get(step, {})
        result = {"step": step}
        if step == "sign-in":
            code, verifier = authorize(port)
            status, _, body = token(port, {"grant_type": "authorization_code", "code": code,
                                           "code_verifier": verifier, "redirect_uri": REDIRECT})
            open(mine("refresh"), "w").write(json.loads(body)["refresh_token"])
            result.update({"outcome": "success", "backend": "drive-", "account": "true", "exported": "true",
                           "pid": "4001"})
        else:
            days = rest[rest.index("saikou.clock.offsetDays") + 1]
            if answer.get("authorizeAgain"):
                authorize(port)
            status, tokens = (None, {}) if answer.get("noRefresh") else refresh(port, answer.get("noSecret", False))
            if step == "after-idle" and status == 200 and not answer.get("noDrive"):
                if about(port, tokens["access_token"]) == 401:
                    status, tokens = refresh(port)
                    about(port, tokens["access_token"])
            signed_out = status == 400
            result.update({"outcome": "sync_signed_out" if signed_out else "success", "offsetDays": days,
                           "pid": "4002"})
            result.update({"probeAuthenticated": "false"} if step == "revoked" else {"tokenUnchanged": "true"})
        result.update(answer.get("result", {}))
        header = ["INSTRUMENTATION_STATUS: class=com.shinsaikou.sync.DriveTokenDeviceTest",
                  "INSTRUMENTATION_STATUS: test=step", "INSTRUMENTATION_STATUS_CODE: 1"]
        lines = list(header)
        if not answer.get("noResult"):
            lines += ["INSTRUMENTATION_STATUS: saikou.drive.token.result="
                      + " ".join(f"{k}={v}" for k, v in result.items()),
                      "INSTRUMENTATION_STATUS_CODE: 2"]
        if answer.get("fail"):
            lines += ["INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: the stand-in says no",
                      "INSTRUMENTATION_STATUS_CODE: -2", "INSTRUMENTATION_RESULT: stream=", "",
                      "FAILURES!!!", "Tests run: 1,  Failures: 1", "", "INSTRUMENTATION_CODE: -1"]
        else:
            lines += ["INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_RESULT: stream=", "", "Time: 1.2", "",
                      "OK (1 test)", "", "", "INSTRUMENTATION_CODE: -1"]
        emit(*lines)
    else:
        print(f"stub adb: unexpected {rest}", file=sys.stderr)
        sys.exit(1)
''').lstrip()


class Single:
    """One sync_14_day.py run against the stand-in adb, in a temporary directory."""

    def __init__(self, cleanup, steps: dict | None = None, avd: str = "saikou_api26_2g", **device: object):
        self.dir = Path(tempfile.mkdtemp(prefix="sync-14-day-test-"))
        cleanup(shutil.rmtree, self.dir, True)
        self.state = self.dir / "state"
        self.state.mkdir()
        self.adb = self.dir / "adb"
        self.adb.write_text(STUB)
        self.adb.chmod(0o755)
        self.log = self.dir / "adb.log"
        self.plan = self.dir / "plan.json"
        self.plan.write_text(json.dumps({"devices": {SERIAL: {"avd": avd, "api": "26"}}, "steps": steps or {}, **device}))
        self.apk = self.dir / "app-debug.apk"
        self.apk.write_bytes(b"debug app")
        self.test_apk = self.dir / "app-debug-androidTest.apk"
        self.test_apk.write_bytes(b"test app")
        self.out = self.dir / "out"
        self.work = self.dir / "work"
        self.evidence = self.dir / "evidence"

    def run(self, *extra: str) -> subprocess.CompletedProcess:
        env = dict(os.environ, STUB_ADB_PLAN=str(self.plan), STUB_ADB_LOG=str(self.log), STUB_ADB_STATE=str(self.state))
        env.pop("SAIKOU_SERIAL_API26", None)
        return run_checked(
            [sys.executable, str(SCRIPT), "--serial-api26", SERIAL, "--adb", str(self.adb), "--apk", str(self.apk),
             "--test-apk", str(self.test_apk), "--out", str(self.out), "--work", str(self.work), "--port", "0", *extra],
            env, self.dir, timeout=120)

    def calls(self) -> list[list[str]]:
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def kinds(self) -> list[str]:
        return [" ".join(call[2:4]) if call[2] == "shell" else call[2] for call in self.calls()]

    def instruments(self) -> list[list[str]]:
        return [call[2:] for call in self.calls() if call[2:5] == ["shell", "am", "instrument"]]

    def fixture_log(self) -> list[dict]:
        path = self.work / "fixture.log"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def files(self, directory: Path) -> list[str]:
        return sorted(p.name for p in directory.iterdir()) if directory.exists() else []


class PassTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.single = Single(cls.addClassCleanup, aliveAfterSignIn=True)
        cls.answer = cls.single.run()

    def test_the_fragment_is_true_on_any_in_the_fragment_shape(self):
        self.assertEqual(self.answer.returncode, 0, self.answer.stdout + self.answer.stderr)
        self.assertEqual(self.single.files(self.single.out), [FRAGMENT_NAME])
        published = json.loads((self.single.out / FRAGMENT_NAME).read_text())
        self.assertEqual((published["id"], published["device"], published["value"], published["unit"]),
                         ("sync_14_day_no_reconsent", "any", True, "bool"))
        self.assertIn("refresh_token grants since", published["method"])
        self.assertIn("run-as kill -9", published["method"])
        self.assertIn("14 days ahead", published["method"])

    def test_sign_in_then_the_kill_then_the_modes_then_after_idle_between_install_and_uninstall(self):
        kinds = self.single.kinds()
        self.assertEqual(kinds[0], "emu")
        order = [kinds.index(kind) for kind in ("install", "shell pm", "shell am", "shell run-as", "uninstall")]
        self.assertEqual(order, sorted(order))
        self.assertEqual(kinds.count("install"), 2)
        self.assertEqual(kinds.count("uninstall"), 2)
        steps = [call[call.index("saikou.drive.token.step") + 1] for call in self.single.instruments()]
        self.assertEqual(steps, ["sign-in", "after-idle"])
        self.assertGreater(min(i for i, kind in enumerate(kinds) if kind == "uninstall"),
                           max(i for i, kind in enumerate(kinds) if kind == "shell am"))
        # The modes were set after sign-in's code and before after-idle's first refresh.
        log = self.single.fixture_log()
        controls = [line for line in log if line.get("kind") == "control" and line["path"].endswith("/modes")]
        self.assertEqual(len(controls), 1)
        refreshes = [line for line in log if line.get("grant") == "refresh_token"]
        self.assertGreater(min(line["t0"] for line in refreshes), controls[0]["t0"])
        self.assertEqual([line.get("injected") for line in refreshes[:2]], ["drop", "503"])
        self.assertEqual(log[-1]["event"], "stop")

    def test_after_idle_is_given_the_fixtures_base_and_the_days(self):
        after = self.single.instruments()[1]
        port = [line for line in self.single.fixture_log() if line.get("event") == "start"][0]["port"]
        self.assertEqual(after[after.index("saikou.google.base") + 1], f"http://10.0.2.2:{port}")
        self.assertEqual(after[after.index("saikou.clock.offsetDays") + 1], "14")
        self.assertNotIn("saikou.clock.offsetDays", self.single.instruments()[0])


class RevokedTest(unittest.TestCase):

    def test_a_revoked_run_writes_false_into_the_evidence_folder_and_nothing_into_out(self):
        single = Single(self.addCleanup)
        single.out.mkdir(parents=True)
        (single.out / FRAGMENT_NAME).write_text('{"id": "sync_14_day_no_reconsent", "value": true}')
        answer = single.run("--revoked", "--evidence", str(single.evidence))
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        published = json.loads((single.evidence / FRAGMENT_NAME).read_text())
        self.assertIs(published["value"], False)
        self.assertIn("every grant revoked", published["method"])
        # --out is never touched by a revoked run, an earlier true fragment there included.
        self.assertEqual(single.files(single.out), [FRAGMENT_NAME])
        steps = [call[call.index("saikou.drive.token.step") + 1] for call in single.instruments()]
        self.assertEqual(steps, ["sign-in", "revoked"])
        modes = [line for line in single.fixture_log() if line.get("kind") == "control" and line["path"].endswith("/modes")]
        self.assertEqual(len(modes), 1)

    def test_revoked_needs_an_evidence_folder_that_is_not_out(self):
        single = Single(self.addCleanup)
        for extra, why in ((("--revoked",), "missing: --evidence with --revoked"),
                           (("--revoked", "--evidence", str(single.out)), "--evidence is --out")):
            with self.subTest(extra=extra):
                answer = single.run(*extra)
                self.assertEqual(answer.returncode, 2, answer.stderr)
                self.assertIn(why, answer.stderr)
        self.assertEqual(single.calls(), [])

    def test_a_revoked_run_that_synced_is_exit_1_with_no_fragment(self):
        single = Single(self.addCleanup, {"revoked": {"result": {"outcome": "success"}}})
        answer = single.run("--revoked", "--evidence", str(single.evidence))
        self.assertEqual(answer.returncode, 1, answer.stderr)
        self.assertIn("revoked did not end signed out", answer.stderr)
        self.assertEqual(single.files(single.evidence), [])


class FailureTest(unittest.TestCase):

    def failed(self, steps: dict, why: str, **device):
        single = Single(self.addCleanup, steps, **device)
        single.out.mkdir(parents=True)
        (single.out / FRAGMENT_NAME).write_text('{"id": "sync_14_day_no_reconsent", "value": true}')
        answer = single.run()
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(why, answer.stderr)
        self.assertEqual(single.files(single.out), [])
        self.assertEqual(single.kinds().count("uninstall"), 2)
        self.assertEqual(single.fixture_log()[-1]["event"], "stop")
        return single

    def test_a_failing_step_is_exit_1(self):
        for step in ("sign-in", "after-idle"):
            with self.subTest(step=step):
                self.failed({step: {"fail": True}}, f"{step}: the runner did not end in OK (1 test)")

    def test_a_result_line_missing_or_naming_another_step_is_exit_1(self):
        self.failed({"after-idle": {"noResult": True}}, "after-idle: passed with no saikou.drive.token.result line")
        self.failed({"after-idle": {"result": {"step": "sign-in"}}}, "after-idle: passed with no saikou.drive.token.result")

    def test_after_idle_in_sign_ins_process_is_exit_1(self):
        self.failed({"after-idle": {"result": {"pid": "4001"}}}, "ran in sign-in's process 4001, not a cold one")

    def test_a_changed_refresh_token_or_another_offset_is_exit_1(self):
        self.failed({"after-idle": {"result": {"tokenUnchanged": "false"}}},
                    "after-idle did not sync with the refresh token unchanged")
        self.failed({"after-idle": {"result": {"offsetDays": "0"}}}, "ran with the clock 0 days ahead, not 14")

    def test_an_authorization_request_after_sign_ins_is_exit_1(self):
        self.failed({"after-idle": {"authorizeAgain": True}}, "2 authorization requests where sign-in's is the only one")

    def test_a_refresh_without_the_client_secret_is_exit_1(self):
        self.failed({"after-idle": {"noSecret": True}}, "a refresh_token grant with the client secret missing")

    def test_no_refresh_since_the_modes_is_exit_1(self):
        self.failed({"after-idle": {"noRefresh": True}}, "no refresh_token grant since the modes were set")

    def test_an_early_401_never_met_is_exit_1(self):
        self.failed({"after-idle": {"noDrive": True}}, "the injected early401 was met 0 of 1 times")


class LastCleanupTest(unittest.TestCase):
    """Stage 2 fix round 1 item 4: a run that fails after its true fragment was written keeps none.
    The uninstall after the steps raises (the stand-in answers bytes that are not text), so the
    fragment is already in --out when the run fails, and only main's last clean-up removes it."""

    def test_a_failure_after_the_true_fragment_was_written_leaves_none_and_exits_1(self):
        single = Single(self.addCleanup, uninstallUndecodable=True)
        answer = single.run()
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"{sync_14_day.ROW} = true, written to", answer.stdout)
        self.assertEqual(single.files(single.out), [])
        self.assertIn("UnicodeDecodeError", answer.stderr)
        self.assertIn("the run did not pass, so no fragment is kept", answer.stderr)
        self.assertEqual(single.fixture_log()[-1]["event"], "stop")


class DeviceTest(unittest.TestCase):

    def test_a_device_that_is_not_the_api26_avd_is_refused_before_anything_starts(self):
        single = Single(self.addCleanup, avd="Pixel_9")
        answer = single.run()
        self.assertEqual(answer.returncode, 2, answer.stderr)
        self.assertIn(f"{SERIAL} is Pixel_9, not saikou_api26_2g", answer.stderr)
        self.assertEqual(set(single.kinds()), {"emu"})
        self.assertEqual(single.fixture_log(), [])

    def test_a_failed_install_is_exit_3_with_what_was_installed_removed(self):
        single = Single(self.addCleanup, installFails=True)
        answer = single.run()
        self.assertEqual(answer.returncode, 3, answer.stderr)
        self.assertIn("installing app-debug.apk on emulator-5556 failed", answer.stderr)
        self.assertNotIn("shell am", single.kinds())
        self.assertEqual(single.kinds().count("uninstall"), 2)


class SourceAgreementTest(unittest.TestCase):
    """What this script names in the device class is what the device class says, and gate.sh knows it."""

    source = TEST_SOURCE.read_text()

    def test_the_class_argument_result_key_steps_and_skip_line_match(self):
        test_class, method = sync_14_day.TEST_CLASS.split("#")
        self.assertEqual(TEST_SOURCE.relative_to(ROOT / "app" / "src" / "androidTest" / "kotlin").with_suffix("")
                         .as_posix().replace("/", "."), test_class)
        self.assertIn(f"    fun {method}() {{", self.source)
        self.assertIn(f'STEP_ARGUMENT = "{sync_14_day.STEP_ARGUMENT}"', self.source)
        self.assertIn(f'RESULT_KEY = "{sync_14_day.RESULT_KEY}"', self.source)
        self.assertIn('"Skipped: no drive token step named"', self.source)
        branches = set(re.findall(r'^\s+"([a-z-]+)" -> result \+= ', self.source, re.M))
        self.assertEqual(branches, {"sign-in", "after-idle", "revoked"})

    def test_the_offset_argument_is_the_one_the_clock_seam_reads(self):
        offset = (ROOT / "app" / "src" / "main" / "kotlin" / "com" / "shinsaikou" / "di" / "ClockOffsetSetting.kt").read_text()
        self.assertIn(f'EXTRA_OFFSET_DAYS = "{sync_14_day.DAYS_ARGUMENT}"', offset)

    def test_gate_sh_lists_the_class_and_expects_its_skip_line_in_step_5c(self):
        gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()
        classes = re.search(r"^DEVICE_CLASSES_APP=\(\n(.*?)^\)", gate, re.M | re.S).group(1).split()
        self.assertIn(sync_14_day.TEST_CLASS.split("#")[0], classes)
        self.assertIn("--except 'Skipped: no drive token step named'", gate)


class GateStepTest(unittest.TestCase):
    """Step 5h as gate.sh runs it, on test_sync_pair.GateStepTest's pattern: its block cut out of
    gate.sh and run by bash with gate.sh's own step, ok, skip, fail and run, where ./gradlew and the
    script are stand-ins that log their arguments and exit as told."""

    gate = (ROOT / "tools" / "ci" / "gate.sh").read_text()
    RUN = ["sync_14_day.py", "--serial-api26", "emulator-5554", "--adb", "/sdk/platform-tools/adb",
           "--out", "tools/out/bench/any", "--work", "tools/out/sync-14-day"]
    GRADLE = ["gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest"]

    def block(self) -> str:
        start = self.gate.index('if phase3; then\n    step "5h.')
        return self.gate[start:self.gate.index("\nfi\n", start) + len("\nfi\n")]

    def helpers(self) -> str:
        start = self.gate.index("step() {")
        return self.gate[start:self.gate.index("\n}\n", self.gate.index("run() {")) + 3]

    def gate_run(self, phase: int = 3, gradle_tasks: str = "assembleDebug", serial: str = "emulator-5554",
                 exit_code: int = 0) -> tuple[subprocess.CompletedProcess, list[list[str]]]:
        directory = Path(tempfile.mkdtemp(prefix="sync-14-day-gate-"))
        self.addCleanup(shutil.rmtree, directory, True)
        (directory / "tools" / "bench").mkdir(parents=True)
        calls = directory / "calls.log"
        stand_in = textwrap.dedent('''
            #!/usr/bin/env python3
            import json, os, sys
            name = os.path.basename(sys.argv[0])
            with open(os.environ["CALLS"], "a") as log:
                log.write(json.dumps([name, *sys.argv[1:]]) + "\\n")
            sys.exit(int(os.environ["EXIT_CODE"]) if name.endswith(".py") else 0)
        ''').lstrip()
        for path in (directory / "gradlew", directory / "tools/bench/sync_14_day.py"):
            path.write_text(stand_in)
            path.chmod(0o755)
        script = "\n".join([
            f"PHASE={phase}", "phase3() { [ \"$PHASE\" -ge 3 ]; }", "passed=0; skipped=0", self.helpers(),
            f"GRADLE_TASKS={shlex.quote(gradle_tasks)}", "ADB=/sdk/platform-tools/adb", "BENCH_OUT=tools/out/bench",
            f"SERIAL_API26={shlex.quote(serial)}", self.block(), 'echo "passed=$passed skipped=$skipped"'])
        env = dict(os.environ, CALLS=str(calls), EXIT_CODE=str(exit_code))
        answer = subprocess.run(["bash", "-c", script], cwd=directory, capture_output=True, text=True, env=env, timeout=60)
        logged = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
        return answer, logged

    def test_phase_3_builds_then_writes_the_row_into_any_from_the_api26_avd(self):
        answer, calls = self.gate_run()
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        self.assertEqual(calls, [self.GRADLE, self.RUN])
        self.assertIn("passed=2 skipped=0", answer.stdout)
        self.assertIn("=== 5h. sync_14_day_no_reconsent on the API 26 AVD", answer.stdout)

    def test_a_failed_run_fails_the_step_whatever_its_exit_code(self):
        for code in (1, 2, 3):
            with self.subTest(code=code):
                answer, calls = self.gate_run(exit_code=code)
                self.assertEqual(answer.returncode, 1)
                self.assertIn("phase 3 failed at: sync_14_day_no_reconsent written true on API 26", answer.stderr)
                self.assertEqual(calls, [self.GRADLE, self.RUN])

    def test_it_is_skipped_without_the_api26_serial_or_gradle_and_absent_before_phase_3(self):
        for kwargs, why in (({"serial": ""}, "set SAIKOU_SERIAL_API26"), ({"gradle_tasks": ""}, "no Gradle wrapper")):
            with self.subTest(why=why):
                answer, calls = self.gate_run(**kwargs)
                self.assertEqual((answer.returncode, calls), (0, []))
                self.assertIn(why, answer.stdout)
                self.assertIn("passed=0 skipped=1", answer.stdout)
        answer, calls = self.gate_run(phase=2)
        self.assertEqual((answer.returncode, calls, answer.stdout.strip()), (0, [], "passed=0 skipped=0"))


if __name__ == "__main__":
    unittest.main()
