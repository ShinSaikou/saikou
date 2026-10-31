#!/usr/bin/env python3
"""The sync_3000_no_oom row on saikou_pixel8a_2g (phase 3 unit I of design/plans/phase3-start.md, Q11).

SyncPairTest's `sync-3000` step does the work on the device: it seeds a 3,000-entry library, exports
it under one device id, reads more chapters, signs out and in again as a new device, and times that
device's first run, which pulls the first id's export onto the same library with its own edits (the
owner's case), counting an OutOfMemoryError the way GateRowsTest counts one for
restore_3000_oom_count and sampling the peak Java heap. It publishes the row's fragment itself,
true or false, with MemTotal and memoryClass in the method note, to /data/local/tmp/saikou-gate.

This script is what gate.sh runs for it, on sync_pair.py's pieces:

  1. checks the serial is saikou_pixel8a_2g (`adb emu avd name`) and refuses any other device;
  2. starts D's fixture on --port (18795) with a WebDAV user and password generated for this run,
     which reach the device only as instrumentation arguments, and stops it on exit;
  3. installs the debug app and its test APK (`adb install -r -t`, never a connected Gradle task);
  4. clears the app's data (`pm clear`), since `install -r` keeps whatever an earlier build left,
     database included, and the step starts from no library and no sign-in;
  5. removes any earlier fragment of the row, on the device and in --out, runs the step through
     instrument_one's check (`OK (1 test)`, and its result line naming the step), copies the
     fragment the step published into --out as <row>.pixel8a_2g.json, and removes it from the
     device, so step 8's pull of the device's gate directory never reads it a second time;
  6. uninstalls both packages, whatever happened;
  7. keeps the copied fragment only when the run passed: a run that fails for any reason, a step
     that published false included, leaves no fragment of the row in --out.

The step's raw output and the fixture's log go to --work.

Usage: sync_3000.py [--serial-2g SERIAL] [--out DIR] [--work DIR] [--apk APK] [--test-apk APK]
           [--port N] [--adb ADB]
The serial defaults to SAIKOU_SERIAL_2G. Exit codes: 0 the row was published true and copied; 1 the
step failed, published false, or published nothing; 2 usage, a missing input or a device that is not
saikou_pixel8a_2g; 3 the fixture could not start, or an install failed. Python 3.11+, standard library
only.
"""

from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sync_pair as pair  # noqa: E402

ROW = "sync_3000_no_oom"
DEVICE = pair.Device("c", "saikou_pixel8a_2g", "pixel8a_2g")
STEP = pair.Step("c", "sync-3000")
DEVICE_FRAGMENT = f"/data/local/tmp/saikou-gate/{ROW}.json"
# Seeding 60,000 chapters and two full syncs of them on the 2 GB AVD.
STEP_TIMEOUT_S = 1800


def fragment_path(out: Path) -> Path:
    return out / f"{ROW}.{DEVICE.device_id}.json"


def measure(run: pair.Run) -> int:
    """Runs the step and copies its fragment. Returns PASSED or FAILED."""
    adb = run.devices["c"]
    cleared = adb.run("shell", "pm", "clear", pair.PACKAGE)
    if "Success" not in cleared.stdout:
        print(f"sync_3000: pm clear answered {(cleared.stdout + cleared.stderr).strip()}", file=sys.stderr)
        return pair.FAILED
    adb.shell("rm", "-f", DEVICE_FRAGMENT)
    if adb.shell("ls", DEVICE_FRAGMENT).strip() == DEVICE_FRAGMENT:
        print(f"sync_3000: an earlier fragment is still at {DEVICE_FRAGMENT}", file=sys.stderr)
        return pair.FAILED
    try:
        answer = adb.run(*run.instrument_args(adb, STEP, "-e", "saikou.device.id", DEVICE.device_id),
                         timeout=STEP_TIMEOUT_S)
    except subprocess.TimeoutExpired:
        print(f"sync_3000: no answer from am instrument in {STEP_TIMEOUT_S} s", file=sys.stderr)
        return pair.FAILED
    output = answer.stdout.replace("\r", "") + answer.stderr.replace("\r", "")
    (run.args.work / "sync-3000.txt").write_text(output)

    target = fragment_path(run.args.out)
    adb.run("pull", DEVICE_FRAGMENT, str(target), timeout=60)
    adb.shell("rm", "-f", DEVICE_FRAGMENT)
    if adb.shell("ls", DEVICE_FRAGMENT).strip() == DEVICE_FRAGMENT:
        target.unlink(missing_ok=True)
        print(f"sync_3000: the fragment is still at {DEVICE_FRAGMENT} after its copy, where step 8 would pull it "
              "again", file=sys.stderr)
        return pair.FAILED
    published = None
    if target.exists():
        try:
            published = json.loads(target.read_text())
        except json.JSONDecodeError:
            published = None
        if not isinstance(published, dict) or published.get("id") != ROW or published.get("device") != DEVICE.device_id:
            target.unlink()
            print(f"sync_3000: the fragment at {DEVICE_FRAGMENT} is not {ROW} on {DEVICE.device_id}", file=sys.stderr)
            return pair.FAILED
        run.say(f"{ROW} = {json.dumps(published['value'])}, copied to {target}: {published.get('method')}")

    why = pair.verdict(output)
    result = pair.parse_result(output)
    if why:
        print(f"sync_3000: the step did not pass: {why}; the runner said:\n" + "\n".join(output.splitlines()[-20:]),
              file=sys.stderr)
        return pair.FAILED
    if result is None or result.get("step") != STEP.name:
        print(f"sync_3000: the step passed with no {pair.RESULT_KEY} line naming {STEP.name}", file=sys.stderr)
        return pair.FAILED
    if published is None or published.get("value") is not True:
        print(f"sync_3000: the step passed but published {published and published.get('value')!r}", file=sys.stderr)
        return pair.FAILED
    return pair.PASSED


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial-2g", default=os.environ.get("SAIKOU_SERIAL_2G", ""),
                        help="saikou_pixel8a_2g's serial")
    parser.add_argument("--out", type=Path, default=pair.ROOT / "tools" / "out" / "bench" / DEVICE.device_id,
                        help="where the row's fragment is copied")
    parser.add_argument("--work", type=Path, default=pair.ROOT / "tools" / "out" / "sync-3000",
                        help="where the step's output and the fixture's log go")
    parser.add_argument("--apk", type=Path, default=pair.ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path,
                        default=pair.ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--port", type=int, default=pair.PORT)
    parser.add_argument("--adb", default=None, help="adb to use (default: the SDK's)")
    args = parser.parse_args(argv)

    adb = args.adb or pair.sdk_adb()
    missing = [what for what, present in (
        ("--serial-2g or SAIKOU_SERIAL_2G", args.serial_2g),
        (f"the debug APK at {args.apk}", args.apk.is_file()),
        (f"the test APK at {args.test_apk}", args.test_apk.is_file()),
        ("adb (--adb, ANDROID_SDK_ROOT or ANDROID_HOME)", adb),
    ) if not present]
    if missing:
        for what in missing:
            print(f"missing: {what}", file=sys.stderr)
        return pair.BAD_INPUT

    def stopped(signum, _frame):
        raise KeyboardInterrupt(f"signal {signum}")

    signal.signal(signal.SIGTERM, stopped)
    args.out.mkdir(parents=True, exist_ok=True)
    args.work.mkdir(parents=True, exist_ok=True)
    fragment_path(args.out).unlink(missing_ok=True)
    run = pair.Run(args, adb, {"c": args.serial_2g}, roles={"c": DEVICE})
    run.name = "sync_3000"
    code = pair.FAILED
    try:
        code = run_step(run)
        return code
    finally:
        target = fragment_path(args.out)
        if code != pair.PASSED and target.exists():
            target.unlink()
            print(f"sync_3000: the run did not pass, so no fragment is kept in {args.out}", file=sys.stderr)


def run_step(run: pair.Run) -> int:
    """Everything after the arguments: the device check, the fixture, the installs and [measure]."""
    try:
        run.check_devices()
        run.fixture = pair.Fixture(run.args.port, run.args.work, run.user, run.password)
    except pair.Refused as refused:
        print(f"sync_3000: {refused}", file=sys.stderr)
        return refused.code
    try:
        run.say(f"sync fixture ready on port {run.fixture.port}, log {run.fixture.log}")
        run.install()
        return measure(run)
    except pair.Refused as refused:
        print(f"sync_3000: {refused}", file=sys.stderr)
        return refused.code
    finally:
        try:
            run.uninstall()
        finally:
            run.fixture.stop()
            run.say("sync fixture stopped")


if __name__ == "__main__":
    raise SystemExit(main())
