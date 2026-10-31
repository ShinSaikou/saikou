#!/usr/bin/env python3
"""Starts one library update through an app's own refresh and waits for its end, ruling R66.

The steps file is the app's way to its Library screen's refresh (tools/bench/steps/update-<app>-
refresh.txt, in ui_steps.py's language), and its last step is the tap that starts the update. That
tap is timed apart from the rest: its node is found first, and the clock reads immediately before
`input tap` is sent, so no `uiautomator dump` falls inside the window.

The end is the removal of the app's update progress notification, polled through
`cmd notification list` on the same host clock the fixture server logs on (time.monotonic()). The
device-side window is tap to removal; update_log.py sets it beside the server-side window.

A window this cannot take is not written down as one (R36). The driver records a problem, and
update_log.py ends the run, when the notification never shows within the start timeout, when it is
still up at the run timeout, or when a failed poll (the shared adb server restarting, say) leaves
the moment of its removal uncertain by more than a second: rerun the run then.

Usage: update_drive.py --serial S --package P --notification-id N --steps FILE --out FILE
                       [--start-timeout 60] [--run-timeout 1800] [--settle 5]
Writes {"tap", "seen", "gone", "polls", "failed_polls", "gone_uncertainty_s", "problems"} to --out.
Exit codes: 0 the window was taken; 3 it was not (the file says why); 2 usage, or an upstream setting
the steps file names is not set (ui_steps.py).
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ui_steps  # noqa: E402

POLL_INTERVAL_S = 0.25
# The removal is known to within the gap between the last poll that saw the notification and the
# first that did not. A normal poll gap is a quarter of a second plus one `cmd` round trip.
MAX_GONE_UNCERTAINTY_S = 1.0


def notification_present(listing: str, package: str, notification_id: int) -> bool:
    """Whether `cmd notification list` names the package's notification with that id. Each line is a
    key, `<user>|<package>|<id>|<tag>|<uid>`, and a grouped summary carries `g:` parts after it."""
    for line in listing.splitlines():
        parts = line.strip().split("|")
        if len(parts) >= 3 and parts[1] == package and parts[2] == str(notification_id):
            return True
    return False


class Poller:
    def __init__(self, device: ui_steps.Device, package: str, notification_id: int):
        self.device, self.package, self.notification_id = device, package, notification_id
        self.polls = 0
        self.failed = 0

    def present(self) -> bool | None:
        """True or False, or None when the poll itself failed and says nothing about the device."""
        self.polls += 1
        result = subprocess.run([self.device.adb, "-s", self.device.serial, "shell", "cmd", "notification", "list"],
                                capture_output=True, text=True, stdin=subprocess.DEVNULL)
        if result.returncode != 0:
            self.failed += 1
            return None
        return notification_present(result.stdout, self.package, self.notification_id)


def drive(device: ui_steps.Device, steps: list[str], poller: Poller, start_timeout: float, run_timeout: float,
          settle: float, clock=time.monotonic, sleep=time.sleep) -> dict:
    out: dict = {"tap": None, "seen": None, "gone": None, "problems": []}
    if not steps or not steps[-1].startswith("tap "):
        raise ValueError("the refresh steps file must end with the `tap` that starts the update")
    try:
        for step in steps[:-1]:
            ui_steps.run_step(device, step)
        locator, timeout = ui_steps.parse_locator(steps[-1][len("tap "):])
        node = device.wait_for(locator, timeout)
        if node is None:
            raise ui_steps.StepFailed(f"`{steps[-1]}`: nothing on screen matched {locator} within {timeout:g} s")
        out["tap"] = clock()
        device.tap(*node.centre)
    except ui_steps.StepFailed as e:
        out["problems"].append(f"the refresh could not be reached: {e}")
        return out

    last_present = None
    while True:
        now = clock()
        state = poller.present()
        if state:
            last_present = now
            if out["seen"] is None:
                out["seen"] = now
        elif state is False and out["seen"] is not None:
            out["gone"] = now
            out["gone_uncertainty_s"] = round(now - last_present, 3)
            break
        if out["seen"] is None and now - out["tap"] > start_timeout:
            out["problems"].append(f"the update notification never showed within {start_timeout:g} s of the tap")
            break
        if now - out["tap"] > run_timeout:
            out["problems"].append(f"the update was still running {run_timeout:g} s after the tap")
            break
        sleep(POLL_INTERVAL_S)

    out["polls"], out["failed_polls"] = poller.polls, poller.failed
    if out["gone"] is not None and out["gone_uncertainty_s"] > MAX_GONE_UNCERTAINTY_S:
        out["problems"].append(
            f"the notification's removal is known only to within {out['gone_uncertainty_s']:.1f} s "
            f"({poller.failed} poll(s) failed, the adb server may have restarted); rerun")
        out["gone"] = None
    sleep(settle)
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--notification-id", required=True, type=int)
    parser.add_argument("--steps", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--start-timeout", type=float, default=60.0)
    parser.add_argument("--run-timeout", type=float, default=1800.0)
    parser.add_argument("--settle", type=float, default=5.0)
    args = parser.parse_args(argv)
    device = ui_steps.Device(args.serial, ui_steps.adb_path())
    try:
        steps = ui_steps.steps_of(ui_steps.fill_upstream(args.steps.read_text()))
    except ui_steps.StepFailed as e:
        print(e, file=sys.stderr)
        return 2
    try:
        out = drive(device, steps, Poller(device, args.package, args.notification_id),
                    args.start_timeout, args.run_timeout, args.settle)
    except ValueError as e:
        print(e, file=sys.stderr)
        return 2
    args.out.write_text(json.dumps(out))
    for problem in out["problems"]:
        print(f"update_drive: {problem}", file=sys.stderr)
    return 3 if out["problems"] else 0


if __name__ == "__main__":
    sys.exit(main())
