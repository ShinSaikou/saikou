#!/usr/bin/env python3
"""Runs unit upstream-compare-20k's scenarios S1 to S9 in one app, by one method for every app.

The comparison times Saikou, upstream v0.21.0 and upstream v0.20.4 on the same 20,000-entry library.
Upstream's published APKs are neither debuggable nor profileable, so everything here is read from
outside the app, the same way for all three: what the screen shows (`uiautomator dump`, through
ui_steps.py), the frames the app drew (`dumpsys gfxinfo <package> framestats`, on CLOCK_MONOTONIC),
the app's CPU time (`/proc/<pid>/stat` utime + stime), its memory (`dumpsys meminfo`), `am start -W`,
the app's own notifications, and WorkManager's log line for the restore's worker. An app differs from
another only in its profile, a steps file (steps/compare20k-<app>.txt) that names its package, its
labels and the screens to go through; the timing below never changes per app. An upstream profile
writes the app's package and storage folder as ui_steps.py's ${UPSTREAM_*} placeholders, filled in
from this machine's settings when the profile is read (ui_steps.fill_upstream).

HOW A TIME IS TAKEN (every timed step but S1 and S4):
1. Before: the action's node is found on screen and the content marker is checked absent, so a
   marker that is already showing cannot end the clock at once. The app's frame stats are reset.
2. Start: one `adb shell` writes a log line (`log -t C20K`) and performs the action (a tap, typed
   text, `am start -W`). The line's logcat time (`-v monotonic`) is t0, on the clock framestats
   uses.
3. A fixed settle (SETTLE_S, by scenario) passes with no dump, because a dump makes the app build
   its accessibility tree on its main thread, which would compete with the work being timed. CPU is
   read at the end of the settle, so the CPU figure covers the same fixed window in every app.
4. Check: dumps until the marker shows (each dump waits for the app to be idle), then a second log
   line, t_seen.
5. The content time is the completion of the last frame the app drew between t0 and t_seen: the
   frame that put the checked content on screen, or a later one if the app kept drawing; a screen
   that keeps drawing reads late, never early. `check_ms` (t_seen - t0) and `first_frame_ms` are
   kept beside it, and `polled` says whether the content came after the settle (a dump then ran
   while the app worked).

Commands:
  setup                           the profile's [setup] block, on a fresh install
  restore <file>...               S1: each file through the app's restore, one after another
  scenarios [--only S2,S3,...]    S2 to S9, in the order the module's SCENARIOS lists
Every sample is one JSON line in <out>/samples.jsonl with the host's load average; the raw dumps
are kept under <out>/raw/. The library's words (titles, categories) come from targets.env, written
by real_scale_backup.py outside the repository; nothing here prints them.

Usage: compare20k.py --serial <serial> --profile <steps file> --env <targets.env> --out <dir> <command>
Exit codes: 0 done; 2 usage or a profile error; 3 a step or a check failed (named on stderr).
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import statistics
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import framestats  # noqa: E402
import ui_steps  # noqa: E402

LOG_TAG = "C20K"
# Seconds of no dump after the action, by scenario; see the docstring, step 3.
SETTLE_S = {"S2": 8.0, "S3": 5.0, "S5": 8.0, "S6": 6.0, "S7": 5.0, "S8": 5.0}
CHECK_TIMEOUT_S = 120.0
# S4: the tabs in order from the Library, five times, a tap every TAB_INTERVAL_S.
LOOP_TABS = ("updates", "history", "browse", "more", "library")
LOOP_ROUNDS = 5
TAB_INTERVAL_S = 1.5
LOOP_TAIL_S = 3.0
# S6: flings up the chapter list, each followed by a framestats read (120 frames at most per read).
SCROLL_FLINGS = 10
SCROLL_PAUSE_S = 0.8
# S6 and S8: a letter every TYPE_INTERVAL_S when a search is typed (typing()).
TYPE_INTERVAL_S = 0.15
# A text cursor's blink (blink_tail): a frame every BLINK_NS, alone within BLINK_ALONE_NS.
BLINK_NS = 500_000_000
BLINK_SLACK_NS = 40_000_000
BLINK_ALONE_NS = 100_000_000
BLINK_MIN = 3
# S1: how often the notifications and the memory are read during a restore, and the cap.
RESTORE_POLL_S = 1.0
RESTORE_MEMORY_EVERY_S = 6.0
RESTORE_TIMEOUT_S = 3 * 3600.0
RESTORE_CONFIRM_S = 600.0
# S9: how long the library is left before its memory is read.
MEMORY_SETTLE_S = 10.0
TARGETS = ("1385", "3859")
SCENARIOS = ("S2", "S9", "S3", "S4", "S7", "S5", "S8", "S6")


class Failed(Exception):
    pass


# Profiles ----------------------------------------------------------------------------------------

@dataclass
class Profile:
    """A steps file: `key value` lines first, then `[block]` sections of ui_steps.py steps."""
    values: dict[str, str]
    blocks: dict[str, list[str]]

    def value(self, key: str) -> str:
        if key not in self.values:
            raise Failed(f"the profile has no `{key}`")
        return self.values[key]

    def block(self, name: str) -> list[str]:
        return self.blocks.get(name, [])

    @property
    def package(self) -> str:
        return self.value("package")


REQUIRED_KEYS = ("package", "restore.worker", "restore.done", "tab.library", "tab.updates", "tab.history",
                 "tab.browse", "tab.more", "marker.library", "marker.updates", "marker.history", "marker.stats", "marker.entry",
                 "marker.search", "action.restore", "action.stats", "action.entry", "copy.pattern")
REQUIRED_BLOCKS = ("setup", "restore", "search-open", "search-close", "entry-open", "entry-close")


def parse_profile(text: str) -> Profile:
    values: dict[str, str] = {}
    blocks: dict[str, list[str]] = {}
    current: list[str] | None = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        header = re.fullmatch(r"\[([a-z0-9-]+)\]", line)
        if header:
            current = blocks.setdefault(header.group(1), [])
        elif current is not None:
            current.append(line)
        else:
            key, _, value = line.partition(" ")
            if not value.strip():
                raise Failed(f"profile line without a value: {line!r}")
            values[key] = value.strip()
    missing = [k for k in REQUIRED_KEYS if k not in values] + [f"[{b}]" for b in REQUIRED_BLOCKS if b not in blocks]
    if missing:
        raise Failed(f"the profile lacks {', '.join(missing)}")
    return Profile(values, blocks)


def load_env(path: Path) -> dict[str, str]:
    """A targets.env file as written by real_scale_backup.py: NAME=<shell-quoted value> lines."""
    env = {}
    for line in path.read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            name, _, quoted = line.partition("=")
            words = shlex.split(quoted)
            env[name] = words[0] if words else ""
    return env


# Markers -----------------------------------------------------------------------------------------

@dataclass(frozen=True)
class RegexLocator:
    """`text%=<regex>`: a node whose field the regex finds a match in."""
    field: str
    pattern: str
    nth: int = 1

    def matches(self, node: ui_steps.Node) -> bool:
        value = {"text": node.text, "desc": node.desc, "id": node.resource_id}[self.field]
        return re.search(self.pattern, value) is not None

    def __str__(self) -> str:
        return f"{self.field}%=<pattern>"


def parse_one(spec: str):
    match = re.fullmatch(r"(text|desc|id)%=(.+)", spec.strip())
    if match:
        return RegexLocator(match.group(1), match.group(2))
    return ui_steps.parse_locator(spec)[0]


@dataclass
class Marker:
    """All of `present` on screen and none of `absent`: `<loc> && <loc> && !<loc>`."""
    present: list
    absent: list

    def shown(self, nodes: list[ui_steps.Node]) -> bool:
        return (all(ui_steps.find(nodes, loc) is not None for loc in self.present)
                and not any(ui_steps.find(nodes, loc) is not None for loc in self.absent))

    def absent_now(self, nodes: list[ui_steps.Node]) -> bool:
        """True when the marker cannot already be satisfied: one of its present parts is missing."""
        return any(ui_steps.find(nodes, loc) is None for loc in self.present)


def parse_marker(spec: str) -> Marker:
    present, absent = [], []
    for part in spec.split("&&"):
        part = part.strip()
        if part.startswith("!"):
            absent.append(parse_one(part[1:]))
        else:
            present.append(parse_one(part))
    if not present:
        raise Failed(f"a marker needs something present: {spec!r}")
    return Marker(present, absent)


# The device --------------------------------------------------------------------------------------

class Device(ui_steps.Device):
    def run(self, script: str) -> str:
        """One device shell command line, as the device's shell parses it."""
        return self.shell(script)

    def mark(self, tag: str) -> str:
        return f"log -t {LOG_TAG} {tag} 2>/dev/null"

    def times(self) -> dict[str, int]:
        """Every C20K log line's tag and its CLOCK_MONOTONIC time in ns."""
        out = self.shell("logcat", "-d", "-v", "monotonic", "-s", f"{LOG_TAG}:I", check=False)
        found = {}
        for line in out.splitlines():
            match = re.match(rf"\s*(\d+)\.(\d+)\s+\d+\s+\d+\s+I\s+{LOG_TAG}\s*:\s*(\S+)", line)
            if match:
                found[match.group(3)] = int(match.group(1)) * 1_000_000_000 + int(match.group(2).ljust(9, "0")[:9])
        return found

    def pid(self, package: str) -> str:
        return self.shell("pidof", package, check=False).strip()

    def cpu_ms(self, pid: str) -> int | None:
        """utime + stime of the process, in ms (10 ms ticks)."""
        stat = self.shell("cat", f"/proc/{pid}/stat", check=False)
        if ")" not in stat:
            return None
        fields_ = stat.rsplit(")", 1)[1].split()
        # After the comm: state is field 3, utime 14 and stime 15, so offsets 11 and 12 here.
        return (int(fields_[11]) + int(fields_[12])) * 10

    def memory(self, package: str) -> dict[str, int]:
        text = self.shell("dumpsys", "meminfo", package, check=False)
        found = {}
        for key, pattern in (("pss_kb", r"TOTAL PSS:\s+(\d+)"), ("java_heap_kb", r"Java Heap:\s+(\d+)"),
                             ("native_heap_kb", r"Native Heap:\s+(\d+)"), ("rss_kb", r"TOTAL RSS:\s+(\d+)")):
            match = re.search(pattern, text)
            if match:
                found[key] = int(match.group(1))
        return found

    def framestats(self, package: str) -> str:
        return self.shell("dumpsys", "gfxinfo", package, "framestats", check=False)

    def reset_frames(self, package: str) -> None:
        self.shell("dumpsys", "gfxinfo", package, "reset", check=False)

    def selected(self) -> list[tuple[int, int, int, int]]:
        """The bounds of every node the window marks selected (a navigation bar's current tab),
        which ui_steps.py's nodes do not carry."""
        for _ in range(3):
            if "dumped to" in self.shell("uiautomator", "dump", ui_steps.DUMP_PATH, check=False):
                xml = self.shell("cat", ui_steps.DUMP_PATH)
                if xml.lstrip().startswith("<?xml"):
                    out = []
                    for element in ET.fromstring(xml).iter("node"):
                        match = ui_steps.BOUNDS.fullmatch(element.get("bounds", ""))
                        if element.get("selected") == "true" and match:
                            out.append(tuple(int(v) for v in match.groups()))
                    return out
            time.sleep(0.5)
        raise ui_steps.StepFailed(f"uiautomator could not dump the window on {self.serial}")

    def screen_size(self) -> tuple[int, int]:
        width, height = self.shell("wm", "size").strip().split()[-1].split("x")
        return int(width), int(height)


def load_average() -> str:
    try:
        return " ".join(Path("/proc/loadavg").read_text().split()[:3])
    except OSError:
        return "?"


# The runner --------------------------------------------------------------------------------------

@dataclass
class Runner:
    device: Device
    profile: Profile
    env: dict[str, str]
    out: Path
    scope: dict[str, str] = field(default_factory=dict)
    count: int = 0

    def __post_init__(self) -> None:
        (self.out / "raw").mkdir(parents=True, exist_ok=True)

    @property
    def package(self) -> str:
        return self.profile.package

    def expand(self, text: str) -> str:
        return ui_steps.expand(text, {**os.environ, **self.env, **self.scope})

    def record(self, sample: dict) -> dict:
        sample = {"app": self.package, "load": load_average(), "at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), **sample}
        with (self.out / "samples.jsonl").open("a") as f:
            f.write(json.dumps(sample) + "\n")
        print(json.dumps({k: v for k, v in sample.items() if k not in ("frames",)}), flush=True)
        return sample

    def raw(self, name: str, text: str) -> None:
        (self.out / "raw" / name).write_text(text)

    def tag(self, scenario: str) -> str:
        self.count += 1
        return f"{scenario}-{os.getpid()}-{self.count}"

    # Steps

    def steps(self, block: str) -> None:
        for step in self.profile.block(block):
            self.step(step)

    def step(self, template: str) -> None:
        """One profile step. A failure names the step as the profile writes it, never expanded,
        since an expanded step can carry a title from the owner's library."""
        if os.environ.get("C20K_TRACE"):
            print(f"step: {template}", file=sys.stderr, flush=True)
        step = self.expand(template)
        verb, _, rest = step.partition(" ")
        try:
            if verb == "tab":
                self.tap_tab(rest.strip())
            elif verb == "hscroll-to":
                self.hscroll_to(rest)
            elif verb == "slowtype":
                self.device.run(typing(rest.strip()))
            elif verb == "picker-root":
                self.picker_root(rest)
            else:
                ui_steps.run_step(self.device, step)
        except (ui_steps.StepFailed, Failed) as e:
            raise Failed(f"`{template}` failed" + ("" if "{{" in template else f": {e}")) from e

    def tab_node(self, name: str) -> ui_steps.Node:
        """A tab of the navigation bar: of the nodes its label matches, the lowest on screen, since a
        screen's title can carry the same word above it."""
        locator = parse_one(self.expand(self.profile.value(f"tab.{name}")))
        deadline = time.monotonic() + 20
        while True:
            nodes = [n for n in self.device.nodes() if locator.matches(n)]
            if nodes:
                return max(nodes, key=lambda n: n.bounds[1])
            if time.monotonic() >= deadline:
                raise Failed(f"no {name} tab on screen")
            time.sleep(0.5)

    def tab_selected(self, node: ui_steps.Node) -> bool:
        x, y = node.centre
        return any(x1 <= x <= x2 and y1 <= y <= y2 for x1, y1, x2, y2 in self.device.selected())

    def tap_tab(self, name: str) -> None:
        """Taps a tab unless it is the one shown: upstream answers a tap on the current tab with
        another screen (Library opens its settings sheet, More opens Settings)."""
        node = self.tab_node(name)
        if not self.tab_selected(node):
            self.device.tap(*node.centre)
            time.sleep(1.0)

    def home(self) -> None:
        """Back until the navigation bar shows (its More tab in the bottom fifth of the screen), so
        every scenario starts from a tab whatever the last one left on screen."""
        locator = parse_one(self.expand(self.profile.value("tab.more")))
        _, height = self.device.screen_size()
        for _ in range(6):
            if any(locator.matches(n) and n.bounds[1] > height * 4 // 5 for n in self.device.nodes()):
                return
            self.device.shell("input", "keyevent", "4")
            time.sleep(1.5)
        raise Failed("the navigation bar did not come back after six Back presses")

    def to_library(self) -> None:
        self.tap_tab("library")

    def hscroll_to(self, rest: str) -> None:
        """`hscroll-to <locator> along=<locator>|<locator>...`: swipe the row the first anchor found
        on screen is in sideways until the target is on screen, then tap it. The row is swiped left
        (towards later items) up to eight times, then right up to sixteen. Several anchors, because
        the row may be scrolled so that any one of them is off screen."""
        spec, _, along = rest.partition(" along=")
        target = parse_one(spec)
        anchors = [parse_one(a) for a in along.split("|") if a.strip()]
        row_y = None
        width, _ = self.device.screen_size()
        for attempt in range(25):
            nodes = self.device.nodes()
            node = ui_steps.find(nodes, target)
            if node is not None:
                self.device.tap(*node.centre)
                time.sleep(1.0)
                return
            if row_y is None:
                found = next((n for n in (ui_steps.find(nodes, a) for a in anchors) if n is not None), None)
                if found is None:
                    raise Failed(f"hscroll-to: neither {target} nor any of the row's anchors is on screen")
                row_y = found.centre[1]
            left = attempt < 8
            x1, x2 = (width * 85 // 100, width * 20 // 100) if left else (width * 20 // 100, width * 85 // 100)
            self.device.shell("input", "swipe", str(x1), str(row_y), str(x2), str(row_y), "300")
            time.sleep(0.6)
        raise Failed(f"hscroll-to: {target} never came on screen")

    def picker_root(self, rest: str) -> None:
        """`picker-root <locator> shown=<locator>`: the system file picker's root, opened from its
        drawer until the `shown` node (the root's header) is on screen. The picker can reset to its
        Recent root after a drawer tap has been taken, so the tap is repeated, up to five times. A
        drawer item is a node with the `android:id/title` id; the breadcrumb above the list can show
        the same word."""
        spec, _, shown_spec = rest.partition(" shown=")
        item, shown = parse_one(spec), parse_one(shown_spec)
        roots = ui_steps.parse_locator("desc=Show roots")[0]
        for _ in range(5):
            if self.device.wait_for(shown, 3) is not None:
                return
            nodes = self.device.nodes()
            drawer = [n for n in nodes if item.matches(n) and n.resource_id == "android:id/title"]
            if not drawer:
                button = self.device.wait_for(roots, 30)
                if button is None:
                    raise Failed("picker-root: the picker shows no drawer button")
                self.device.tap(*button.centre)
                time.sleep(1.5)
                drawer = [n for n in self.device.nodes() if item.matches(n) and n.resource_id == "android:id/title"]
            if drawer:
                self.device.tap(*drawer[0].centre)
                time.sleep(2.0)
        if self.device.wait_for(shown, 5) is None:
            raise Failed(f"picker-root: {shown} never came on screen")

    def wait_marker(self, marker: Marker, timeout: float) -> tuple[bool, int]:
        """Dumps until the marker shows. Returns whether it did and how many dumps it took."""
        deadline = time.monotonic() + timeout
        dumps = 0
        while True:
            dumps += 1
            if marker.shown(self.device.nodes()):
                return True, dumps
            if time.monotonic() >= deadline:
                return False, dumps
            time.sleep(0.3)

    def marker(self, name: str) -> Marker:
        return parse_marker(self.expand(self.profile.value(f"marker.{name}")))

    def node_of(self, action: str) -> ui_steps.Node:
        locator = parse_one(self.expand(self.profile.value(f"action.{action}")))
        node = self.device.wait_for(locator, 20)
        if node is None:
            raise Failed(f"action.{action} ({self.profile.value(f'action.{action}')}) is not on screen")
        return node

    # The timed step (docstring, HOW A TIME IS TAKEN)

    def timed(self, scenario: str, command: str, marker: Marker, *, settle: float, reset: bool = True,
              cpu: bool = True, extra: dict | None = None, check_before: bool = True) -> dict:
        package = self.package
        if check_before and not marker.absent_now(self.device.nodes()):
            raise Failed(f"{scenario}: the content marker already shows before the action")
        pid = self.device.pid(package) if cpu else ""
        if reset:
            self.device.reset_frames(package)
        cpu0 = self.device.cpu_ms(pid) if pid else None
        tag = self.tag(scenario)
        output = self.device.run(f"{self.device.mark(tag + '-start')}; {command}")
        time.sleep(settle)
        new_pid = self.device.pid(package)
        cpu1 = self.device.cpu_ms(new_pid) if new_pid else None
        self.device.run(self.device.mark(tag + "-check"))
        seen, dumps = self.wait_marker(marker, CHECK_TIMEOUT_S)
        self.device.run(self.device.mark(tag + "-seen"))
        stats = self.device.framestats(package)
        self.raw(f"{tag}.framestats.txt", stats)
        times = self.device.times()
        t0, t_check, t_seen = (times.get(f"{tag}-{k}") for k in ("start", "check", "seen"))
        sample = {"scenario": scenario, "tag": tag, "content_shown": seen, "settle_s": settle,
                  **(extra or {})}
        if t0 is None or t_check is None or t_seen is None:
            raise Failed(f"{scenario}: the log lines of {tag} are not in logcat")
        sample["check_ms"] = round((t_seen - t0) / 1e6)
        # Shown by the first dump: the content was drawn within the settle, and only frames before
        # the first dump count (a process's first accessibility query can draw a frame of its own).
        # Otherwise every frame up to the dump that found it counts, and the sample says it polled.
        sample["polled"] = dumps > 1
        sample["dumps"] = dumps
        sample.update(frame_window(stats, t0, t_check if dumps == 1 else t_seen, tag))
        if cpu:
            same = bool(pid) and new_pid == pid
            sample["cpu_ms"] = cpu1 - cpu0 if same and cpu0 is not None and cpu1 is not None else None
            sample["cpu_window_s"] = settle
            if pid and not same:
                sample["process_changed"] = True
        sample["output"] = output.strip()[-400:]
        if not seen:
            sample["failed"] = f"the content marker did not show within {CHECK_TIMEOUT_S:g} s of the settle"
        return self.record(sample)

    # S1

    def restore(self, files: list[Path]) -> None:
        worker = self.profile.value("restore.worker")
        done_spec = self.profile.value("restore.done")
        done_package, _, done_id = done_spec.partition("|")
        total_ms = 0
        for k, path in enumerate(files, 1):
            name = path.name
            self.scope = {"BACKUP_NAME": name}
            # The system picker sometimes lands on another root than the one tapped (Recent), so
            # the way to the file is tried three times, from the navigation bar each time.
            for attempt in range(3):
                self.home()
                try:
                    self.steps("restore")
                    break
                except Failed:
                    if attempt == 2:
                        raise
            node = self.node_of("restore")
            tag = self.tag("S1")
            self.device.run(f"{self.device.mark(tag + '-start')}; input tap {node.centre[0]} {node.centre[1]}")
            started = time.monotonic()
            t0 = self.device.times().get(f"{tag}-start")
            if t0 is None:
                raise Failed(f"S1: the log line of {tag} is not in logcat")
            # An app that asks what to restore after the file is picked: its button is tapped as soon
            # as a dump finds it, and the time to it is kept apart (restore_confirm_ms). The memory is
            # read from the pick on, since an app can decode the whole file before it asks.
            confirm = parse_one(self.expand(self.profile.value("restore.confirm"))) \
                if "restore.confirm" in self.profile.values else None
            confirm_at = None
            peak, deaths, pid, series = 0, 0, "", []
            last_memory = 0.0
            done = None
            gone_since = None
            reason = f"no completion notification within {RESTORE_TIMEOUT_S:g} s"
            while time.monotonic() - started < RESTORE_TIMEOUT_S:
                if confirm is not None and confirm_at is None and time.monotonic() - started > RESTORE_CONFIRM_S:
                    reason = f"{self.profile.value('restore.confirm')} did not show within {RESTORE_CONFIRM_S:g} s"
                    break
                # A process gone for a minute with no completion posted: the app died (an
                # OutOfMemoryError ends the process), and no restore is running.
                if self.device.pid(self.package):
                    gone_since = None
                elif gone_since is None:
                    gone_since = time.monotonic()
                elif time.monotonic() - gone_since > 60:
                    deaths += 1
                    reason = "the app's process ended and stayed gone for 60 s with no completion posted"
                    break
                if confirm is not None and confirm_at is None:
                    button = ui_steps.find(self.device.nodes(), confirm)
                    if button is not None:
                        self.device.run(f"{self.device.mark(tag + '-confirm')}; "
                                        f"input tap {button.centre[0]} {button.centre[1]}")
                        confirm_at = self.device.times().get(f"{tag}-confirm")
                results = worker_results(self.device.shell("logcat", "-d", "-v", "monotonic", "-s",
                                                           "WM-WorkerWrapper:I", check=False), worker)
                after = [(t, result) for t, result in results if t > t0]
                if after:
                    done = after[0]
                    detected_ms = round((time.monotonic() - started) * 1000)
                    break
                now = time.monotonic()
                if now - last_memory >= RESTORE_MEMORY_EVERY_S:
                    last_memory = now
                    current = self.device.pid(self.package)
                    # A new process id is a death and a restart; a process gone is counted when its
                    # absence ends the wait above.
                    if pid and current and current != pid:
                        deaths += 1
                    pid = current or pid
                    pss = self.device.memory(self.package).get("pss_kb")
                    if pss:
                        peak = max(peak, pss)
                        series.append([round(now - started), pss])
                time.sleep(RESTORE_POLL_S)
            notification = notification_text(self.device.shell("dumpsys", "notification", "--noredact", check=False),
                                              done_package, done_id)
            sample = {"scenario": "S1", "tag": tag, "file": name, "part": k, "parts": len(files),
                      "bytes": path.stat().st_size, "peak_pss_kb": peak, "pss_series": series,
                      "process_deaths": deaths, "notification": notification}
            if done is not None and done[1] != "SUCCESS":
                reason = f"the restore's worker ended with {done[1]}"
                done = None
            if done is None:
                sample["failed"] = reason
                self.raw(f"{tag}.logcat.txt", self.device.shell("logcat", "-d", "-b", "main,crash", "-t", "3000",
                                                                 check=False))
                self.record(sample)
                raise Failed(f"S1: {name} did not complete")
            sample["restore_ms"] = round((done[0] - t0) / 1e6)
            # The host's view, from the tap's return to the poll that saw the result: a check on
            # restore_ms's clock, up to RESTORE_POLL_S and a logcat read later.
            sample["restore_detected_ms"] = detected_ms
            if confirm_at is not None:
                sample["restore_confirm_ms"] = round((confirm_at - t0) / 1e6)
                sample["restore_after_confirm_ms"] = round((done[0] - confirm_at) / 1e6)
            total_ms += sample["restore_ms"]
            self.record(sample)
            time.sleep(3)
        self.record({"scenario": "S1", "summary": True, "restore_ms_total": total_ms, "parts": len(files)})

    # S2 to S9

    def launch_activity(self) -> str:
        out = self.device.shell("cmd", "package", "resolve-activity", "--brief", "-c",
                                "android.intent.category.LAUNCHER", self.package)
        return out.strip().splitlines()[-1].strip()

    def s2(self) -> None:
        activity = self.launch_activity()
        self.device.shell("am", "force-stop", self.package)
        time.sleep(3)
        sample = self.timed("S2", f"am start -W -n {activity}", self.marker("library"), settle=SETTLE_S["S2"],
                            reset=False, cpu=False, check_before=False)
        out = sample["output"]
        total = re.search(r"TotalTime:\s*(\d+)", out)
        launched = re.search(r"Activity:\s*(\S+)", out)
        problems = []
        if "Activity not started" in out or not launched or not launched.group(1).startswith(self.package + "/"):
            problems.append("am start did not launch the app's own activity")
        if not total or int(total.group(1)) <= 0:
            problems.append("no TotalTime")
        pid = self.device.pid(self.package)
        self.record({"scenario": "S2", "summary": True, "tag": sample["tag"],
                     "am_total_ms": int(total.group(1)) if total else None,
                     "process_cpu_ms_at_check": self.device.cpu_ms(pid) if pid else None,
                     **({"failed": "; ".join(problems)} if problems else {})})

    def s9(self, when: str) -> None:
        time.sleep(MEMORY_SETTLE_S)
        memory = self.device.memory(self.package)
        self.raw(f"S9-{when}.meminfo.txt", self.device.shell("dumpsys", "meminfo", self.package, check=False))
        self.record({"scenario": "S9", "when": when, **memory})

    def s3(self) -> None:
        self.tap_tab("more")
        time.sleep(2)
        node = self.tab_node("library")
        self.timed("S3", f"input tap {node.centre[0]} {node.centre[1]}", self.marker("library"),
                   settle=SETTLE_S["S3"], extra={"from": "more"})

    def s4(self) -> None:
        self.to_library()
        time.sleep(2)
        nodes = {name: self.tab_node(name).centre for name in ("library", *LOOP_TABS)}
        taps = []
        dump = f"/data/local/tmp/c20k-s4-{os.getpid()}"
        for k in range(LOOP_ROUNDS):
            for name in LOOP_TABS:
                x, y = nodes[name]
                taps.append(f"input tap {x} {y}; sleep {TAB_INTERVAL_S}")
            # framestats keeps 120 frames, so each round's are read on the device as it ends.
            taps.append(f"dumpsys gfxinfo {self.package} framestats > {dump}-{k}.txt")
        pid = self.device.pid(self.package)
        self.device.reset_frames(self.package)
        cpu0 = self.device.cpu_ms(pid)
        tag = self.tag("S4")
        self.device.run(f"{self.device.mark(tag + '-start')}; " + "; ".join(taps) + f"; sleep {LOOP_TAIL_S}; "
                        + self.device.mark(tag + "-end"))
        cpu1 = self.device.cpu_ms(pid) if self.device.pid(self.package) == pid else None
        stats = self.device.framestats(self.package)
        dumps = [self.device.shell("cat", f"{dump}-{k}.txt", check=False) for k in range(LOOP_ROUNDS)] + [stats]
        self.device.shell("rm", "-f", *(f"{dump}-{k}.txt" for k in range(LOOP_ROUNDS)), check=False)
        self.raw(f"{tag}.framestats.txt", "\n".join(dumps))
        summary = framestats.parse_summary(stats) or {}
        frames = []
        for k, text in enumerate(dumps):
            frames += framestats.parse_frames(text, f"dump {k}")[0]
        measured = framestats.measure(framestats.union_frames(frames), summary or None)
        on_library = self.marker("library").shown(self.device.nodes())
        self.record({"scenario": "S4", "tag": tag, "taps": LOOP_ROUNDS * len(LOOP_TABS), "interval_s": TAB_INTERVAL_S,
                     "cpu_ms": cpu1 - cpu0 if cpu1 is not None and cpu0 is not None else None,
                     "janky_frames": summary.get("janky"),
                     "janky_pct": framestats.percent(summary["janky"], summary["rendered"]) if summary else None,
                     **measured, "ended_on_library_content": on_library,
                     **({} if cpu1 is not None else {"failed": "the process changed during the loop"})})

    def s7(self) -> None:
        for name in ("updates", "history"):
            self.tap_tab("more")
            time.sleep(2)
            node = self.tab_node(name)
            self.timed("S7", f"input tap {node.centre[0]} {node.centre[1]}", self.marker(name),
                       settle=SETTLE_S["S7"], extra={"tab": name})

    def s5(self) -> None:
        self.tap_tab("more")
        time.sleep(2)
        node = self.node_of("stats")
        self.timed("S5", f"input tap {node.centre[0]} {node.centre[1]}", self.marker("stats"),
                   settle=SETTLE_S["S5"])
        self.s9("after-stats")
        self.home()

    def use_target(self, target: str) -> None:
        self.scope = {key: self.env[f"{key}_{target}"] for key in ("TITLE", "WORD", "CATEGORY")
                      if f"{key}_{target}" in self.env}
        self.scope["CHAPTERS"] = target

    def s8(self) -> None:
        target = TARGETS[0]
        self.use_target(target)
        self.to_library()
        self.steps("search-open")
        marker = self.marker("search")
        nodes = self.device.nodes()
        copy = re.compile(self.profile.value("copy.pattern"))
        word = self.scope["WORD"].casefold()
        # Copy titles on screen now that do not hold the word: the filtered grid shows none of them.
        others = sorted({n.text for n in nodes if copy.search(n.text) and word not in n.text.casefold()})
        filtered = Marker(marker.present, marker.absent + [ui_steps.Locator("text", "=", t) for t in others])
        # All but the last letter typed at TYPE_INTERVAL_S a key, then the clock starts at the last
        # one, which Enter follows: see typing().
        word = self.scope["WORD"]
        self.device.run(typing(word[:-1]) + f"; sleep {TYPE_INTERVAL_S}")
        command = f"input text {shlex.quote(word[-1])}; input keyevent 66"
        self.timed("S8", command, filtered, settle=SETTLE_S["S8"], check_before=False,
                   extra={"target": target, "hidden_titles_checked": len(others),
                          "marker_showed_before": not marker.absent_now(nodes)})
        self.steps("search-close")

    def s6(self) -> None:
        for target in TARGETS:
            if f"TITLE_{target}" not in self.env:
                self.record({"scenario": "S6", "target": target, "failed": "targets.env names no such entry"})
                continue
            self.use_target(target)
            self.to_library()
            self.steps("entry-open")
            node = self.entry_cell()
            self.timed("S6", f"input tap {node.centre[0]} {node.centre[1]}", self.marker("entry"),
                       settle=SETTLE_S["S6"], extra={"target": target})
            self.scroll(target)
            self.steps("entry-close")
            self.steps("search-close")

    def entry_cell(self) -> ui_steps.Node:
        """The target's library cell: of the nodes action.entry matches, the lowest, below the top
        eighth of the screen, where the toolbar is (its search field can hold the same words). An app's
        search can match more than titles (authors, genres), and a tab's grid keeps where it was
        scrolled to, so the results are swiped towards their end until the cell shows or the screen
        stops changing, then back towards their start. Never towards the start first: a swipe down
        on a list already at its start is a pull to refresh, which starts a library update; only a
        cell found in neither direction ends with one."""
        locator = parse_one(self.expand(self.profile.value("action.entry")))
        width, height = self.device.screen_size()
        x, low, high = str(width // 2), str(height * 3 // 4), str(height // 2)
        for start, end in ((low, high), (high, low)):
            seen = None
            for _ in range(40):
                nodes = self.device.nodes()
                cells = [n for n in nodes if locator.matches(n) and n.bounds[1] > height // 8]
                if cells:
                    return max(cells, key=lambda n: n.bounds[1])
                screen = [(n.text, n.desc, n.bounds) for n in nodes]
                if screen == seen:
                    break
                seen = screen
                self.device.shell("input", "swipe", x, start, x, end, "300")
                time.sleep(0.8)
        raise Failed(f"action.entry ({self.profile.value('action.entry')}) never came on screen")

    def scroll(self, target: str) -> None:
        width, height = self.device.screen_size()
        x, y1, y2 = width // 2, height * 3 // 4, height // 4
        self.device.reset_frames(self.package)
        dumps = []
        for k in range(SCROLL_FLINGS):
            self.device.shell("input", "swipe", str(x), str(y1), str(x), str(y2), "150")
            time.sleep(SCROLL_PAUSE_S)
            dumps.append(self.device.framestats(self.package))
        tag = self.tag("S6scroll")
        self.raw(f"{tag}.framestats.txt", "\n".join(dumps))
        frames: list = []
        for k, text in enumerate(dumps):
            frames += framestats.parse_frames(text, f"dump {k}")[0]
        united = framestats.union_frames(frames)
        summary = framestats.parse_summary(dumps[-1])
        measured = framestats.measure(united, summary)
        self.record({"scenario": "S6", "part": "scroll", "target": target, "tag": tag, "flings": SCROLL_FLINGS,
                     "janky_frames": summary["janky"] if summary else None,
                     "janky_pct": measured["total_jank_pct"], **measured})

    def scenarios(self, only: list[str]) -> None:
        for scenario in SCENARIOS:
            if only and scenario not in only:
                continue
            if scenario == "S9":
                self.s9("library")
            else:
                if scenario != "S2":
                    self.home()
                getattr(self, scenario.lower())()


def typing(text: str) -> str:
    """Types text one letter at a time, TYPE_INTERVAL_S apart: a Compose field whose value goes
    through a view model can drop letters typed faster (upstream's library search does, at `input
    text`'s pace), and both apps debounce their library search by 250 ms, so at this pace each app
    filters once, after the last letter."""
    return f"; sleep {TYPE_INTERVAL_S}; ".join(f"input text {shlex.quote(c)}" for c in text) or "true"


def blink_tail(window: list[dict[str, int]]) -> int:
    """How many frames at the end of the window are a text cursor's blink: single frames (none
    other within BLINK_ALONE_NS) BLINK_NS apart, at least BLINK_MIN of them. A focused search field
    draws one every half second for as long as it is focused (upstream's library search stays focused
    after Enter), and those frames are not the content the clock waits for."""
    times = [f["IntendedVsync"] for f in window]

    def alone(i: int) -> bool:
        return all(abs(times[i] - t) > BLINK_ALONE_NS for j, t in enumerate(times) if j != i)

    def apart(a: int, b: int) -> bool:
        return abs(times[b] - times[a] - BLINK_NS) <= BLINK_SLACK_NS

    i = len(times) - 1
    while i >= 1 and alone(i) and apart(i - 1, i):
        i -= 1
    # times[i] starts the series when it is alone and one period before the next.
    tail = len(times) - 1 - i
    if i < len(times) - 1 and alone(i) and apart(i, i + 1):
        tail += 1
    return tail if tail >= BLINK_MIN else 0


def frame_window(stats: str, t0: int, t_end: int, tag: str) -> dict:
    """The frames drawn between the action and t_end: the last one's completion, less a cursor's
    blink (blink_tail), is the content time (docstring, step 5)."""
    try:
        frames, _ = framestats.parse_frames(stats, tag)
    except framestats.FramestatsError as e:
        return {"frames_error": str(e)}
    window = sorted((f for f in frames if f["IntendedVsync"] >= t0 and 0 < f["FrameCompleted"] <= t_end),
                    key=lambda f: f["IntendedVsync"])
    summary = framestats.parse_summary(stats)
    blink = blink_tail(window)
    kept = window[:len(window) - blink]
    result = {"frames_in_window": len(window), "frames_rendered": summary["rendered"] if summary else None,
              "blink_frames_set_aside": blink,
              "frame_starts_ms": [round((f["IntendedVsync"] - t0) / 1e6) for f in window][:60]}
    if kept:
        result["content_ms"] = round((max(f["FrameCompleted"] for f in kept) - t0) / 1e6)
        result["first_frame_ms"] = round((min(f["IntendedVsync"] for f in kept) - t0) / 1e6)
    else:
        result["content_ms"] = None
    return result


def notification_blocks(dump: str, package: str, notification_id: str) -> list[str]:
    head = re.compile(rf"NotificationRecord\(\S+: pkg={re.escape(package)} user=\S+ id={re.escape(notification_id)} ")
    blocks = re.split(r"\n\s*NotificationRecord\(", dump)
    return [b for b in blocks if head.match("NotificationRecord(" + b.split("\n", 1)[0])]


def worker_results(log: str, worker: str) -> list[tuple[int, str]]:
    """Every result WorkManager logged for the worker class (`logcat -v monotonic -s
    WM-WorkerWrapper:I`): its CLOCK_MONOTONIC time in ns and SUCCESS, FAILURE or RETRY, in order.

    S1 ends here rather than at the completion notification: all three apps restore in a WorkManager
    worker that returns once the restore is written and its notification posted, WorkManager logs
    the result at INFO in a release build, and the platform drops a notification posted while the
    package is over its enqueue rate ("Shedding" in logcat), which upstream's last progress updates
    and its completion notice of a short restore are."""
    found = []
    for line in log.splitlines():
        match = re.match(r"\s*(\d+)\.(\d+)\s+\d+\s+\d+\s+I\s+WM-WorkerWrapper\s*:\s*Worker result (\w+) for Work \[(.*)",
                         line)
        if match and re.search(rf"[\s,={{]{re.escape(worker)}[\s,}}]", match.group(4)):
            found.append((int(match.group(1)) * 1_000_000_000 + int(match.group(2).ljust(9, "0")[:9]),
                          match.group(3)))
    return found


def notification_text(dump: str, package: str, notification_id: str) -> str:
    out = []
    for block in notification_blocks(dump, package, notification_id):
        for key in ("android.title", "android.text", "android.bigText"):
            match = re.search(rf"{re.escape(key)}=\S+ \((.*)\)\s*$", block, re.M)
            if match:
                out.append(f"{key.split('.')[1]}={match.group(1)}")
    return "; ".join(out)


def summarise(samples: list[dict]) -> dict:
    """Median, min and max of each scenario's main figures, for the report."""
    keys = ("content_ms", "check_ms", "cpu_ms", "am_total_ms", "restore_ms", "restore_ms_total", "janky_frames",
            "janky_pct", "app_jank_pct", "pss_kb", "peak_pss_kb")
    grouped: dict[str, dict[str, list]] = {}
    for s in samples:
        name = " ".join([s["scenario"], *(str(s[k]) for k in ("tab", "target", "when") if k in s)]
                        + (["scroll"] if s.get("part") == "scroll" else []))
        for key in keys:
            if isinstance(s.get(key), (int, float)) and not isinstance(s.get(key), bool):
                grouped.setdefault(name, {}).setdefault(key, []).append(s[key])
    return {name: {key: {"median": statistics.median(v), "min": min(v), "max": max(v), "n": len(v)}
                   for key, v in figures.items()} for name, figures in grouped.items()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--env", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("setup")
    restore = sub.add_parser("restore")
    restore.add_argument("files", type=Path, nargs="+")
    run = sub.add_parser("scenarios")
    run.add_argument("--only", default="")
    sub.add_parser("summary")
    args = parser.parse_args(argv)
    try:
        profile = parse_profile(ui_steps.fill_upstream(args.profile.read_text()))
        env = load_env(args.env)
    except (OSError, Failed, ui_steps.StepFailed) as e:
        print(f"compare20k: {e}", file=sys.stderr)
        return 2
    args.out.mkdir(parents=True, exist_ok=True)
    if args.command == "summary":
        samples = [json.loads(line) for line in (args.out / "samples.jsonl").read_text().splitlines() if line]
        print(json.dumps(summarise(samples), indent=2))
        return 0
    runner = Runner(Device(args.serial, ui_steps.adb_path()), profile, env, args.out)
    try:
        if args.command == "setup":
            runner.steps("setup")
        elif args.command == "restore":
            runner.restore(args.files)
        else:
            only = [s for s in args.only.split(",") if s]
            unknown = [s for s in only if s not in SCENARIOS]
            if unknown:
                parser.error(f"unknown scenarios: {unknown}")
            runner.scenarios(only)
    except (Failed, ui_steps.StepFailed, ValueError) as e:
        print(f"compare20k: {e}", file=sys.stderr)
        try:
            (args.out / "raw" / f"failed-{int(time.time())}.screen.txt").write_text("\n".join(runner.device.labels()))
        except ui_steps.StepFailed:
            pass
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
