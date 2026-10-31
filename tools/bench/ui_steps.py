#!/usr/bin/env python3
"""Drives an app's own screens by what they say, for the library update harness (ruling R66).

Section 4 Q2 starts each app's update the way its owner does, from the Library screen's refresh,
found in a `uiautomator dump` and tapped with `input`. Each app's controls sit in different places,
so a locator is a label rather than a coordinate: a node is found by its text, its content
description or its resource id, and tapped at the centre of its bounds. The same steps drive the
one-time setup of an AVD (the repository, the extension, the restore and the settings), because
every one of those goes through the app's own screens too.

A steps file has one step per line; blank lines and lines starting with # are skipped:

    tap text=<label> [timeout=<s>]      wait for a node whose text is the label, then tap it
    tap desc=<label> [timeout=<s>]      the same, matching the content description
    tap id=<resource id> [timeout=<s>]  the same, matching the resource id
    tap-if text=<label> [timeout=<s>]   tap it when it appears within the timeout, else go on
    check text=<label> [timeout=<s>]    turn on the chip the label is in, or the switch or box on its
                                        row, if it is off
    uncheck text=<label> [timeout=<s>]  turn it off, if it is on; either one reads the state back
    wait text=<label> [timeout=<s>]     wait for it without tapping
    gone text=<label> [timeout=<s>]     wait until no node carries it
    scroll-to text=<label> [timeout=<s>]  swipe up until it is on screen, then stop
    notification <package>|<id> [timeout=<s>]  wait until the package posts that notification
    key <keycode>                       adb shell input keyevent
    type <text>                         adb shell input text (spaces become %s)
    am <arguments>                      adb shell am <arguments>, through the device's shell, so
                                        quote an address with ? or & in it as the shell would
    sleep <seconds>

A {{NAME}} anywhere in a step is replaced by the environment variable NAME before the step runs,
and an unset or empty one fails the step rather than becoming an empty string (R29).

The upstream app's own names are this machine's settings, not the repository's: a steps file writes
its application id as ${UPSTREAM_PACKAGE}, its deep link scheme (without ://) as ${UPSTREAM_SCHEME}
and the folder it makes in shared storage as ${UPSTREAM_STORAGE_DIR}. Those three, and nothing else
spelled ${...}, are filled in on the step lines when the file is read, each from
SAIKOU_UPSTREAM_PACKAGE, SAIKOU_UPSTREAM_SCHEME or SAIKOU_UPSTREAM_STORAGE_DIR, else from
saikou.upstream.package, saikou.upstream.scheme or saikou.upstream.storage.dir in local.properties
(contract 01, section 10). A file that names one with neither set is not run at all (exit 2), and a
file that names none, such as every Saikou one, needs none of them.

A label may also be a prefix with `text^=` or a substring with `text~=`. A label containing spaces
is written as it is, to the end of the line or up to the next ` nth=` or ` timeout=`. `nth=2` picks
the second node that matches, in document order, for a screen that shows one label twice (a
heading and the button under it). The default timeout is
20 s. A step that times out ends the run (exit 3) and names the step and what the screen showed.

Usage: ui_steps.py --serial <serial> run <steps file>
       ui_steps.py --serial <serial> dump        (the labelled nodes on screen, one per line)
       ui_steps.py --serial <serial> tap text=<label>
Exit codes: 0 done; 2 the steps file cannot be read or names an upstream value that is not set;
3 a step failed.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
PLACEHOLDER = re.compile(r"\{\{([A-Z0-9_]+)\}\}")
# A steps file's ${NAME} for each of the upstream app's names: (environment variable, local.properties key).
UPSTREAM_VALUES = {
    "UPSTREAM_PACKAGE": ("SAIKOU_UPSTREAM_PACKAGE", "saikou.upstream.package"),
    "UPSTREAM_SCHEME": ("SAIKOU_UPSTREAM_SCHEME", "saikou.upstream.scheme"),
    "UPSTREAM_STORAGE_DIR": ("SAIKOU_UPSTREAM_STORAGE_DIR", "saikou.upstream.storage.dir"),
}
UPSTREAM_PLACEHOLDER = re.compile(r"\$\{(" + "|".join(UPSTREAM_VALUES) + r")\}")
NOTIFICATION = re.compile(r"^([A-Za-z0-9_.]+)\|(-?\d+)(?:\s+timeout=(\d+(?:\.\d+)?))?\s*$")
LOCATOR = re.compile(r"^(text|desc|id)(\^=|~=|=)(.+?)(?:\s+nth=([1-9]\d*))?(?:\s+timeout=(\d+(?:\.\d+)?))?\s*$")
DEFAULT_TIMEOUT_S = 20.0
DUMP_PATH = "/sdcard/window_dump_update_bench.xml"


class StepFailed(Exception):
    pass


@dataclass(frozen=True)
class Node:
    text: str
    desc: str
    resource_id: str
    bounds: tuple[int, int, int, int]
    checkable: bool = False
    checked: bool = False

    @property
    def centre(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2


@dataclass(frozen=True)
class Locator:
    field: str  # text, desc or id
    mode: str  # =, ^= or ~=
    label: str
    nth: int = 1  # which match, in document order, when a screen shows the label more than once

    def matches(self, node: Node) -> bool:
        value = {"text": node.text, "desc": node.desc, "id": node.resource_id}[self.field]
        if self.mode == "=":
            return value == self.label
        if self.mode == "^=":
            return value.startswith(self.label)
        return self.label in value

    def __str__(self) -> str:
        return f"{self.field}{self.mode}{self.label}" + (f" nth={self.nth}" if self.nth > 1 else "")


def parse_nodes(xml: str) -> list[Node]:
    """Every node of a `uiautomator dump`, in document order, with its bounds."""
    nodes = []
    for element in ET.fromstring(xml).iter("node"):
        match = BOUNDS.fullmatch(element.get("bounds", ""))
        if not match:
            continue
        x1, y1, x2, y2 = (int(v) for v in match.groups())
        if x2 <= x1 or y2 <= y1:
            continue  # off screen or collapsed: nothing to tap
        nodes.append(Node(element.get("text", ""), element.get("content-desc", ""),
                          element.get("resource-id", ""), (x1, y1, x2, y2),
                          element.get("checkable") == "true", element.get("checked") == "true"))
    return nodes


def parse_locator(spec: str) -> tuple[Locator, float]:
    match = LOCATOR.match(spec.strip())
    if not match:
        raise ValueError(f"not a locator: {spec!r} (want text=, desc= or id=, with =, ^= or ~=)")
    field, mode, label, nth, timeout = match.groups()
    return Locator(field, mode, label, int(nth or 1)), float(timeout) if timeout else DEFAULT_TIMEOUT_S


def find(nodes: list[Node], locator: Locator) -> Node | None:
    matches = [n for n in nodes if locator.matches(n)]
    return matches[locator.nth - 1] if len(matches) >= locator.nth else None


def toggle_for(nodes: list[Node], label: Node) -> Node | None:
    """The switch, box or chip a label belongs to. A chip draws its label inside itself, so the
    smallest checkable node around the label's centre wins. Otherwise it is the switch or box on the
    label's row: the checkable node nearest the label's vertical centre, among those whose own
    vertical range comes within 100 px of it. A settings row draws its label and its switch as
    siblings, so nothing in the tree ties one to the other but position."""
    x, y = label.centre
    around = [n for n in nodes if n.checkable and n.bounds[0] <= x <= n.bounds[2] and n.bounds[1] <= y <= n.bounds[3]]
    if around:
        return min(around, key=lambda n: (n.bounds[2] - n.bounds[0]) * (n.bounds[3] - n.bounds[1]))
    near = [n for n in nodes if n.checkable and n.bounds[1] - 100 <= y <= n.bounds[3] + 100]
    return min(near, key=lambda n: abs(n.centre[1] - y), default=None)


class Device:
    def __init__(self, serial: str, adb: str):
        self.serial, self.adb = serial, adb

    def shell(self, *args: str, check: bool = True) -> str:
        result = subprocess.run([self.adb, "-s", self.serial, "shell", *args], capture_output=True, text=True,
                                stdin=subprocess.DEVNULL)
        if check and result.returncode != 0:
            raise StepFailed(f"adb shell {' '.join(args)} failed on {self.serial}: {result.stderr.strip()}")
        return result.stdout

    def nodes(self) -> list[Node]:
        # A dump taken while the window animates can fail once with "could not get idle state".
        for _ in range(3):
            out = self.shell("uiautomator", "dump", DUMP_PATH, check=False)
            if "dumped to" in out:
                xml = self.shell("cat", DUMP_PATH)
                if xml.lstrip().startswith("<?xml"):
                    return parse_nodes(xml)
            time.sleep(0.5)
        raise StepFailed(f"uiautomator could not dump the window on {self.serial}")

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def wait_for(self, locator: Locator, timeout: float) -> Node | None:
        deadline = time.monotonic() + timeout
        while True:
            node = find(self.nodes(), locator)
            if node is not None or time.monotonic() >= deadline:
                return node
            time.sleep(0.5)

    def labels(self) -> list[str]:
        return [f"{n.text!r} desc={n.desc!r} id={n.resource_id!r} {n.bounds}"
                + (f" checked={n.checked}" if n.checkable else "")
                for n in self.nodes() if n.text or n.desc or n.resource_id or n.checkable]


def expand(step: str, environ: dict[str, str] | None = None) -> str:
    environ = os.environ if environ is None else environ

    def value(match: re.Match) -> str:
        name = match.group(1)
        if not environ.get(name):
            raise StepFailed(f"`{step}` needs {name}, which is unset or empty")
        return environ[name]

    return PLACEHOLDER.sub(value, step)


def local_property(key: str, environ: dict[str, str] | None = None) -> str | None:
    """`key` in local.properties (SAIKOU_LOCAL_PROPERTIES names another file), as localprops.sh reads
    it: the last line that sets it wins, and a blank value is absent."""
    environ = os.environ if environ is None else environ
    path = Path(environ.get("SAIKOU_LOCAL_PROPERTIES") or ROOT / "local.properties")
    if not path.is_file():
        return None
    value = None
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(rf"\s*{re.escape(key)}\s*=\s*(.*)$", line)
        if match:
            value = match.group(1).strip()
    return value or None


def fill_upstream(text: str, environ: dict[str, str] | None = None) -> str:
    """A steps file's text with each ${UPSTREAM_*} the module docstring names filled in on its step
    lines, from the environment variable, else the local.properties key. One with neither is a
    StepFailed naming both, before any step runs. Comment lines are left as they are, so a comment
    may name a placeholder, and a text whose steps name none is returned unchanged."""
    environ = os.environ if environ is None else environ
    lines = text.split("\n")
    steps = [i for i, line in enumerate(lines) if not line.lstrip().startswith("#")]
    values, missing = {}, []
    for name in sorted({name for i in steps for name in UPSTREAM_PLACEHOLDER.findall(lines[i])}):
        variable, key = UPSTREAM_VALUES[name]
        value = (environ.get(variable) or "").strip() or local_property(key, environ)
        if value:
            values[name] = value
        else:
            missing.append(f"{variable} (or {key} in local.properties) for ${{{name}}}")
    if missing:
        raise StepFailed("missing: " + "; ".join(missing))
    for i in steps:
        lines[i] = UPSTREAM_PLACEHOLDER.sub(lambda match: values[match.group(1)], lines[i])
    return "\n".join(lines)


def notification_keys(listing: str) -> set[tuple[str, str]]:
    """(package, id) of every notification `cmd notification list` names, one key per line."""
    keys = set()
    for line in listing.splitlines():
        parts = line.strip().split("|")
        if len(parts) >= 3:
            keys.add((parts[1], parts[2]))
    return keys


def run_step(device: Device, step: str) -> None:
    step = expand(step)
    verb, _, rest = step.partition(" ")
    if verb in ("tap", "tap-if", "wait"):
        locator, timeout = parse_locator(rest)
        node = device.wait_for(locator, timeout)
        if node is None:
            if verb == "tap-if":
                return
            raise StepFailed(f"`{step}`: nothing on screen matched {locator} within {timeout:g} s")
        if verb != "wait":
            device.tap(*node.centre)
    elif verb in ("check", "uncheck"):
        locator, timeout = parse_locator(rest)
        want = verb == "check"
        label = device.wait_for(locator, timeout)
        if label is None:
            raise StepFailed(f"`{step}`: nothing on screen matched {locator} within {timeout:g} s")
        toggle = toggle_for(device.nodes(), label)
        if toggle is None:
            raise StepFailed(f"`{step}`: no switch or box sits on the row of {locator}")
        if toggle.checked != want:
            device.tap(*toggle.centre)
            time.sleep(0.5)
            label = find(device.nodes(), locator)
            toggle = toggle_for(device.nodes(), label) if label else None
            if toggle is None or toggle.checked != want:
                raise StepFailed(f"`{step}`: the switch on the row of {locator} did not turn {'on' if want else 'off'}")
    elif verb == "gone":
        locator, timeout = parse_locator(rest)
        deadline = time.monotonic() + timeout
        while find(device.nodes(), locator) is not None:
            if time.monotonic() >= deadline:
                raise StepFailed(f"`{step}`: {locator} was still on screen after {timeout:g} s")
            time.sleep(0.5)
    elif verb == "scroll-to":
        locator, timeout = parse_locator(rest)
        deadline = time.monotonic() + timeout
        while find(device.nodes(), locator) is None:
            if time.monotonic() >= deadline:
                raise StepFailed(f"`{step}`: scrolled for {timeout:g} s and {locator} never came on screen")
            size = device.shell("wm", "size").strip().split()[-1]
            width, height = (int(v) for v in size.split("x"))
            device.shell("input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3),
                         "300")
            time.sleep(0.5)
    elif verb == "notification":
        match = NOTIFICATION.match(rest.strip())
        if not match:
            raise ValueError(f"not a notification: {rest!r} (want <package>|<id>)")
        package, notification_id, timeout = match.group(1), match.group(2), float(match.group(3) or DEFAULT_TIMEOUT_S)
        deadline = time.monotonic() + timeout
        while (package, notification_id) not in notification_keys(device.shell("cmd", "notification", "list", check=False)):
            if time.monotonic() >= deadline:
                raise StepFailed(f"`{step}`: {package} posted no notification {notification_id} within {timeout:g} s")
            time.sleep(1)
    elif verb == "key":
        device.shell("input", "keyevent", rest.strip())
    elif verb == "type":
        device.shell("input", "text", rest.strip().replace(" ", "%s"))
    elif verb == "am":
        # One string, so the device's shell parses it and a quoted address keeps its ? and &.
        device.shell("am " + rest)
    elif verb == "sleep":
        time.sleep(float(rest))
    else:
        raise ValueError(f"unknown step: {step!r}")


def steps_of(text: str) -> list[str]:
    lines = (line.strip() for line in text.splitlines())
    return [line for line in lines if line and not line.startswith("#")]


def adb_path() -> str:
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or str(Path.home() / "Android/Sdk")
    return str(Path(sdk) / "platform-tools" / "adb")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", default=os.environ.get("SAIKOU_SERIAL"), required="SAIKOU_SERIAL" not in os.environ)
    sub = parser.add_subparsers(dest="command", required=True)
    run = sub.add_parser("run")
    run.add_argument("steps", type=Path)
    sub.add_parser("dump")
    tap = sub.add_parser("tap")
    tap.add_argument("locator", nargs="+")
    args = parser.parse_args(argv)
    device = Device(args.serial, adb_path())
    steps = []
    if args.command == "run":
        try:
            steps = steps_of(fill_upstream(args.steps.read_text()))
        except (OSError, StepFailed) as e:
            print(e, file=sys.stderr)
            return 2
    try:
        if args.command == "dump":
            print("\n".join(device.labels()))
        elif args.command == "tap":
            run_step(device, "tap " + " ".join(args.locator))
        else:
            # SAIKOU_UI_TRACE names a file that gets every step and the screen after it, for finding
            # which step left the app somewhere the next one does not expect.
            trace = os.environ.get("SAIKOU_UI_TRACE")
            for step in steps:
                run_step(device, step)
                if trace:
                    with open(trace, "a") as out:
                        out.write(f"{time.strftime('%H:%M:%S')} {step}\n  " + "\n  ".join(
                            label for label in device.labels()[:30] if "nexuslauncher" not in label) + "\n")
    except (StepFailed, ValueError) as e:
        print(e, file=sys.stderr)
        try:
            print("the screen showed:\n  " + "\n  ".join(device.labels()[:40]), file=sys.stderr)
        except StepFailed:
            pass
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
