#!/usr/bin/env python3
"""The sync_14_day_no_reconsent row on saikou_api26_2g (phase 3 unit H of design/plans/phase3-start.md, Q12).

com.shinsaikou.sync.DriveTokenDeviceTest does the device's half, one step per `am instrument`, against D's
fake Google endpoints; this script does the rest, on sync_3000.py's contract:

  1. checks the serial is saikou_api26_2g (`adb emu avd name`) and refuses any other device;
  2. starts D's fixture on --port (18795) and stops it on exit; the device reaches it as
     http://10.0.2.2:<port>, the `saikou.google.base` argument, which sends every Google call of the
     debug build there with the fixture's public test client (nothing here contacts Google);
  3. installs the debug app and its test APK (`adb install -r -t`) and clears the app's data;
  4. runs `sign-in` (a code from the fixture's authorization endpoint with no browser, Drive's
     sign-in and its first sync), then stops the app's process with `run-as com.shinsaikou kill -9`
     (R89 carry-over 3) if the instrumentation left it running;
  5. sets the fixture's modes: every access token issued so far expired, the next two refreshes
     failing with a dropped connection and then 503, and one Drive call with a valid token answered
     401 (with --revoked, every grant revoked as well);
  6. runs `after-idle` (or `revoked`) in a new process with the app's clock --days ahead
     (`saikou.clock.offsetDays`, which only the debug build reads): one sync, which must succeed, and
     the refresh token in the secret store compared on the device with the one sign-in stored;
  7. reads the fixture's log, waiting LOG_WAIT_S at most for lines of requests already answered:
     no authorization request after sign-in's, at least one refresh_token grant since the modes were
     set, the client secret present on every refresh_token grant, and each injected failure met;
  8. uninstalls both packages, whatever happened.

The row's fragment, `sync_14_day_no_reconsent.any.json` in --out in common.sh's fragment() shape, is
written true only when every check passed; a run that fails for any reason leaves none, and removes
one an earlier run left. With --revoked the step must end signed out (`sync_signed_out`, and the
backend's probe signed out) and the log must show a refresh refused with invalid_grant; the fragment
is then written false into --evidence, never into --out, so it never reaches the gate's bench folder.

Each step's raw output and the fixture's log go to --work.

Usage: sync_14_day.py [--serial-api26 SERIAL] [--out DIR] [--work DIR] [--apk APK] [--test-apk APK]
           [--port N] [--adb ADB] [--days N] [--revoked --evidence DIR]
The serial defaults to SAIKOU_SERIAL_API26. Exit codes: 0 the fragment was written (true, or false
with --revoked); 1 a step or a check failed, and no fragment was written; 2 usage, a missing input
or a device that is not saikou_api26_2g; 3 the fixture could not start, or an install failed.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import time
import urllib.request
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sync_pair as pair  # noqa: E402

ROW = "sync_14_day_no_reconsent"
DEVICE = pair.Device("b", "saikou_api26_2g", "api26_2g")
TEST_CLASS = "com.shinsaikou.sync.DriveTokenDeviceTest#step"
STEP_ARGUMENT = "saikou.drive.token.step"
RESULT_KEY = "saikou.drive.token.result"
BASE_ARGUMENT = "saikou.google.base"
DAYS_ARGUMENT = "saikou.clock.offsetDays"
DAYS = 14
RESULT_LINE = re.compile(rf"^INSTRUMENTATION_STATUS: {re.escape(RESULT_KEY)}=(.*)$", re.M)
# A sync with a refresh that waits 1 s and 2 s between tries; the first sign-in's export of an empty library.
STEP_TIMEOUT_S = 600
LOG_WAIT_S = pair.LOG_WAIT_S
# What step 5's modes ask the fixture for (Q12): the two failed refreshes and the one early 401.
REFRESH_FAILURES = ["drop", "503"]
EARLY_401 = 1


class CheckFailed(Exception):
    pass


def fragment_path(out: Path) -> Path:
    return pair.fragment_path(out, ROW)


def parse_result(output: str) -> dict[str, str] | None:
    """The step's result line as key=value pairs, or None when it printed none. The last wins."""
    lines = RESULT_LINE.findall(output)
    if not lines:
        return None
    return dict(token.partition("=")[::2] for token in lines[-1].strip().split(" ") if "=" in token)


def control(fixture: pair.Fixture, name: str, body: dict | None = None) -> None:
    """One request to the fixture's control path: modes, expire-access-tokens or reset."""
    data = json.dumps(body).encode() if body is not None else b""
    request = urllib.request.Request(f"http://127.0.0.1:{fixture.port}/_fixture/{name}", data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=10) as answer:
        if answer.status != 200:
            raise CheckFailed(f"the fixture's {name} answered {answer.status}")


def read_log(fixture: pair.Fixture) -> list[dict]:
    return [json.loads(line) for line in fixture.log.read_text().splitlines() if line.strip()]


class Measure:
    def __init__(self, run: pair.Run, days: int, revoked: bool):
        self.run = run
        self.adb = run.devices["b"]
        self.days = days
        self.revoked = revoked
        self.base = f"http://{pair.DEVICE_HOST}:{run.fixture.port}"

    def step(self, name: str, *extra: str) -> dict[str, str]:
        """Runs one step through instrument_one's check and returns its result line. Raises CheckFailed."""
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS, "-e", STEP_ARGUMENT, name,
                   "-e", BASE_ARGUMENT, self.base, *extra, self.run.runner(self.adb)]
        try:
            answer = self.adb.run(*command, timeout=STEP_TIMEOUT_S)
        except subprocess.TimeoutExpired:
            raise CheckFailed(f"{name}: no answer from am instrument in {STEP_TIMEOUT_S} s") from None
        output = answer.stdout.replace("\r", "") + answer.stderr.replace("\r", "")
        (self.run.args.work / f"{name}.txt").write_text(output)
        why = pair.verdict(output)
        if why:
            raise CheckFailed(f"{name}: {why}; the runner said:\n" + "\n".join(output.splitlines()[-20:]))
        result = parse_result(output)
        if result is None or result.get("step") != name:
            raise CheckFailed(f"{name}: passed with no {RESULT_KEY} line naming it")
        self.run.say(f"{name}: " + " ".join(f"{k}={v}" for k, v in result.items() if k != "step"))
        return result

    def stop_process(self, pid: str) -> str:
        """Stops the sign-in's process if the instrumentation left it running; says which it was."""
        if pid not in self.adb.shell("pidof", pair.PACKAGE).split():
            return f"process {pid} had ended with its instrumentation"
        stopped = self.adb.run("shell", "run-as", pair.PACKAGE, "kill", "-9", pid, timeout=30)
        if stopped.returncode != 0:
            raise CheckFailed(f"run-as kill -9 {pid} answered {(stopped.stdout + stopped.stderr).strip()}")
        if pid in self.adb.shell("pidof", pair.PACKAGE).split():
            raise CheckFailed(f"process {pid} is still alive after kill -9")
        return f"process {pid} stopped with run-as kill -9"

    def measure(self) -> tuple[bool, str]:
        """Every step and check; the row's value and its method note. Raises CheckFailed."""
        cleared = self.adb.run("shell", "pm", "clear", pair.PACKAGE)
        if "Success" not in cleared.stdout:
            raise CheckFailed(f"pm clear answered {(cleared.stdout + cleared.stderr).strip()}")
        signed_in = self.step("sign-in")
        if signed_in.get("outcome") != "success" or signed_in.get("backend") != "drive-":
            raise CheckFailed(f"sign-in did not sign in to Drive: {signed_in}")
        stopped = self.stop_process(signed_in.get("pid", ""))
        self.run.say(f"sign-in's {stopped}")

        modes = {"refreshFailures": REFRESH_FAILURES, "early401": EARLY_401}
        if self.revoked:
            modes["revoked"] = True
        # The fixture's t0 is the host's CLOCK_MONOTONIC, as time.monotonic() is (sync_pair.py's kill mark).
        marker = time.monotonic()
        control(self.run.fixture, "expire-access-tokens")
        control(self.run.fixture, "modes", modes)
        self.run.say(f"fixture modes set: access tokens expired, {json.dumps(modes)}")

        name = "revoked" if self.revoked else "after-idle"
        idle = self.step(name, "-e", DAYS_ARGUMENT, str(self.days))
        if idle.get("pid") == signed_in.get("pid"):
            raise CheckFailed(f"{name} ran in sign-in's process {idle.get('pid')}, not a cold one")
        if idle.get("offsetDays") != str(self.days):
            raise CheckFailed(f"{name} ran with the clock {idle.get('offsetDays')} days ahead, not {self.days}")
        if self.revoked:
            if idle.get("outcome") != "sync_signed_out" or idle.get("probeAuthenticated") != "false":
                raise CheckFailed(f"revoked did not end signed out: {idle}")
        elif idle.get("outcome") != "success" or idle.get("tokenUnchanged") != "true":
            raise CheckFailed(f"after-idle did not sync with the refresh token unchanged: {idle}")
        found = self.check_log(marker)
        note = (f"tools/bench/sync_14_day.py: DriveTokenDeviceTest sign-in on {DEVICE.avd} (API {self.run.api['b']}, "
                f"{self.adb.serial}) against the sync fixture's Google endpoints on port {self.run.fixture.port}, "
                f"{stopped}, then {name} in process {idle.get('pid')} with the app's clock {self.days} days ahead, "
                f"access tokens expired, the first refresh dropped and the second 503")
        if self.revoked:
            return False, note + (f", every grant revoked: the run ended sync_signed_out and the backend's probe "
                                  f"reported signed out; {found}; debug build SHA-256 {self.run.apk_sha256[:12]}")
        note += " and one Drive call 401: "
        return True, note + (f"the sync succeeded and the refresh token in the secret store is the one sign-in "
                             f"stored; {found}; debug build SHA-256 {self.run.apk_sha256[:12]}")

    def check_log(self, marker: float) -> str:
        """What the fixture's log must show since the modes were set, read again until it shows it or
        LOG_WAIT_S pass, since the fixture writes a request's line after it answers. Raises CheckFailed."""
        deadline = time.monotonic() + LOG_WAIT_S
        while True:
            lines = read_log(self.run.fixture)
            why, found = self.judge(lines, marker)
            if why is None:
                return found
            if time.monotonic() >= deadline:
                raise CheckFailed(f"the fixture's log: {why}")
            time.sleep(0.05)

    def judge(self, lines: list[dict], marker: float) -> tuple[str | None, str]:
        authorize = [line for line in lines if line.get("kind") == "oauth.authorize"]
        refreshes = [line for line in lines if line.get("kind") == "oauth.token" and line.get("grant") == "refresh_token"]
        since = [line for line in refreshes if line.get("t0", 0) > marker]
        drive = [line for line in lines if str(line.get("kind", "")).startswith("drive.") and line.get("t0", 0) > marker]
        injected = Counter(line.get("injected") for line in since + drive)
        # A revoked grant is refused at the refresh, so no Drive call is made to meet the early 401.
        expected = Counter(REFRESH_FAILURES + ([] if self.revoked else ["early401"] * EARLY_401))
        if len(authorize) != 1:
            return f"{len(authorize)} authorization requests where sign-in's is the only one", ""
        if any(line.get("t0", 0) > marker for line in authorize):
            return "an authorization request after sign-in's", ""
        if not since:
            return "no refresh_token grant since the modes were set", ""
        without = [line.get("client_secret") for line in refreshes if line.get("client_secret") != "present"]
        if without:
            return f"a refresh_token grant with the client secret {without[0]}", ""
        for failure, times in expected.items():
            if injected[failure] < times:
                return f"the injected {failure} was met {injected[failure]} of {times} times", ""
        answered = [line for line in since if line.get("injected") is None]
        if self.revoked:
            if not any(line.get("status") == 400 for line in answered):
                return "no refresh refused as revoked", ""
        elif not any(line.get("status") == 200 for line in answered):
            return "no refresh_token grant succeeded", ""
        return None, (f"the fixture's log shows sign-in's authorization request and none after it, "
                      f"{len(since)} refresh_token grants since, {len(refreshes)} in all, each with the client secret")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial-api26", default=os.environ.get("SAIKOU_SERIAL_API26", ""),
                        help="saikou_api26_2g's serial")
    parser.add_argument("--out", type=Path, default=pair.ROOT / "tools" / "out" / "bench" / pair.ROW_DEVICE,
                        help="where the row's fragment is written")
    parser.add_argument("--work", type=Path, default=pair.ROOT / "tools" / "out" / "sync-14-day",
                        help="where each step's output and the fixture's log go")
    parser.add_argument("--apk", type=Path, default=pair.ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path,
                        default=pair.ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--port", type=int, default=pair.PORT)
    parser.add_argument("--adb", default=None, help="adb to use (default: the SDK's)")
    parser.add_argument("--days", type=int, default=DAYS, help="how far after-idle moves the app's clock")
    parser.add_argument("--revoked", action="store_true",
                        help="run with every grant revoked, which must write false, into --evidence")
    parser.add_argument("--evidence", type=Path, default=None, help="where --revoked writes its false fragment")
    args = parser.parse_args(argv)

    adb = args.adb or pair.sdk_adb()
    missing = [what for what, present in (
        ("--serial-api26 or SAIKOU_SERIAL_API26", args.serial_api26),
        (f"the debug APK at {args.apk}", args.apk.is_file()),
        (f"the test APK at {args.test_apk}", args.test_apk.is_file()),
        ("adb (--adb, ANDROID_SDK_ROOT or ANDROID_HOME)", adb),
        ("--evidence with --revoked", args.evidence or not args.revoked),
    ) if not present]
    if missing:
        for what in missing:
            print(f"missing: {what}", file=sys.stderr)
        return pair.BAD_INPUT
    if args.revoked and args.evidence.resolve() == args.out.resolve():
        print("--evidence is --out: a revoked run's false fragment never goes where the gate reads rows", file=sys.stderr)
        return pair.BAD_INPUT

    def stopped(signum, _frame):
        raise KeyboardInterrupt(f"signal {signum}")

    signal.signal(signal.SIGTERM, stopped)
    target_dir = args.evidence if args.revoked else args.out
    target_dir.mkdir(parents=True, exist_ok=True)
    args.work.mkdir(parents=True, exist_ok=True)
    target = fragment_path(target_dir)
    target.unlink(missing_ok=True)
    run = pair.Run(args, adb, {"b": args.serial_api26}, roles={"b": DEVICE})
    run.name = "sync_14_day"
    code = pair.FAILED
    try:
        code = run_steps(run, target)
        return code
    finally:
        if code != pair.PASSED and target.exists():
            target.unlink()
            print(f"sync_14_day: the run did not pass, so no fragment is kept in {target_dir}", file=sys.stderr)


def run_steps(run: pair.Run, target: Path) -> int:
    """Everything after the arguments: the device check, the fixture, the installs and the steps."""
    try:
        run.check_devices()
        run.fixture = pair.Fixture(run.args.port, run.args.work, run.user, run.password)
    except pair.Refused as refused:
        print(f"sync_14_day: {refused}", file=sys.stderr)
        return refused.code
    try:
        run.say(f"sync fixture ready on port {run.fixture.port}, log {run.fixture.log}")
        run.install()
        try:
            value, method = Measure(run, run.args.days, run.args.revoked).measure()
        except CheckFailed as failed:
            print(f"sync_14_day: {failed}", file=sys.stderr)
            return pair.FAILED
        published = dict(pair.fragment(ROW, method), value=value)
        target.write_text(json.dumps(published, indent=2) + "\n")
        run.say(f"{ROW} = {json.dumps(value)}, written to {target}")
        return pair.PASSED
    except pair.Refused as refused:
        print(f"sync_14_day: {refused}", file=sys.stderr)
        return refused.code
    except pair.ScenarioFailed as failed:
        print(f"sync_14_day: {failed}", file=sys.stderr)
        return pair.FAILED
    finally:
        try:
            run.uninstall()
        finally:
            run.fixture.stop()
            run.say("sync fixture stopped")


if __name__ == "__main__":
    raise SystemExit(main())
