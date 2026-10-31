#!/usr/bin/env python3
"""Tests for tools/bench/compare20k.py, unit upstream-compare-20k's black-box driver.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Only what runs on the host is tested here: the profile and targets.env readers, the markers, the
typed-search command line, the frame window a content time is read from (with a cursor's blink set
aside), the notification readers S1 times a restore with, the summary, the three profiles in
tools/bench/steps, and the picker-root step against a scripted screen. No device is used.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS / "bench"))

import compare20k  # noqa: E402
import ui_steps  # noqa: E402

MS = 1_000_000
T0 = 50_000_000_000_000
COLUMNS = ["Flags", "FrameTimelineVsyncId", "IntendedVsync", "HandleInputStart", "PerformTraversalsStart",
           "DrawStart", "FrameDeadline", "SwapBuffers", "GpuCompleted", "FrameCompleted"]

MINIMAL_PROFILE = """\
# A comment.
package dev.example
restore.worker dev.example.RestoreWorker
restore.done dev.example|504
tab.library text=Library
tab.updates text=Updates
tab.history text=History
tab.browse text=Browse
tab.more text=More
copy.pattern  c\\d\\d$
marker.library text%= c\\d\\d$
marker.updates text%= c\\d\\d$
marker.history text%= c\\d\\d$
marker.stats text={{CHAPTERS_TOTAL}}
marker.entry text={{CHAPTERS}} chapters
marker.search text%=(?i){{WORD}}.* c\\d\\d$
action.restore text={{BACKUP_NAME}}
action.stats text=Statistics
action.entry desc={{TITLE}}

[setup]
tap text=Allow

[restore]
tab more
tap text=Backup

[search-open]
tap desc=Search
[search-close]
[entry-open]
slowtype {{WORD}}
[entry-close]
tap desc=Back
"""


def node(text: str = "", desc: str = "", resource_id: str = "", bounds=(0, 0, 10, 10)) -> ui_steps.Node:
    return ui_steps.Node(text, desc, resource_id, bounds)


def frame(start_ms: float, done_ms: float, vsync_id: int = 1) -> dict[str, int]:
    intended = T0 + round(start_ms * MS)
    done = T0 + round(done_ms * MS)
    return {"Flags": 0, "FrameTimelineVsyncId": vsync_id, "IntendedVsync": intended, "HandleInputStart": intended,
            "PerformTraversalsStart": intended, "DrawStart": intended, "FrameDeadline": intended + 16 * MS,
            "SwapBuffers": done, "GpuCompleted": done, "FrameCompleted": done}


def stats(frames: list[dict[str, int]], rendered: int = 9) -> str:
    lines = ["Stats since: 1ns", f"Total frames rendered: {rendered}", "Janky frames: 0 (0.00%)",
             "---PROFILEDATA---", ",".join(COLUMNS) + ","]
    lines += [",".join(str(f[c]) for c in COLUMNS) + "," for f in frames]
    lines += ["---PROFILEDATA---", ""]
    return "\n".join(lines) + "\n"


def notification(package: str, notification_id: str, title: str, text: str = "") -> str:
    record = [
        f"    NotificationRecord(0x0a1b2c3d: pkg={package} user=UserHandle{{0}} id={notification_id} tag=null "
        f"importance=3 key=0|{package}|{notification_id}|null|10190: Notification(channel=backup))",
        "      uid=10190 userId=0",
        "      extras={",
        f"        android.title=String ({title})",
    ]
    if text:
        record.append(f"        android.text=String ({text})")
    record += ["      }", "      stats=SingleNotificationStats{posttimeElapsedMs=1, posttimeToFirstClickMs=-1}"]
    return "\n".join(record)


class ProfileTest(unittest.TestCase):
    def test_values_and_blocks_are_read_and_comments_skipped(self):
        profile = compare20k.parse_profile(MINIMAL_PROFILE)
        self.assertEqual(profile.package, "dev.example")
        # The value keeps its inner spaces but not the one separating it from the key.
        self.assertEqual(profile.value("copy.pattern"), "c\\d\\d$")
        self.assertEqual(profile.block("restore"), ["tab more", "tap text=Backup"])
        self.assertEqual(profile.block("search-close"), [])
        self.assertEqual(profile.block("no-such-block"), [])

    def test_a_missing_key_or_block_is_named(self):
        text = MINIMAL_PROFILE.replace("action.stats text=Statistics\n", "").replace("[entry-close]\n", "")
        with self.assertRaises(compare20k.Failed) as caught:
            compare20k.parse_profile(text)
        self.assertIn("action.stats", str(caught.exception))
        self.assertIn("[entry-close]", str(caught.exception))

    def test_a_key_without_a_value_is_refused(self):
        with self.assertRaises(compare20k.Failed):
            compare20k.parse_profile("package\n" + MINIMAL_PROFILE)

    def test_an_unknown_key_is_refused_on_use(self):
        profile = compare20k.parse_profile(MINIMAL_PROFILE)
        with self.assertRaises(compare20k.Failed):
            profile.value("restore.confirm")

    def test_the_three_shipped_profiles_parse_and_name_their_packages(self):
        # The upstream profiles take their package from this machine's settings, here a stand-in.
        steps = TOOLS / "bench" / "steps"
        settings = {"SAIKOU_UPSTREAM_PACKAGE": "org.example.upstream", "SAIKOU_UPSTREAM_STORAGE_DIR": "Upstream",
                    "SAIKOU_LOCAL_PROPERTIES": str(steps / "no-local.properties")}
        profiles = {name: compare20k.parse_profile(ui_steps.fill_upstream(
                        (steps / f"compare20k-{name}.txt").read_text(), settings))
                    for name in ("saikou", "upstream-0.21.0", "upstream-0.20.4")}
        self.assertEqual({name: profile.package for name, profile in profiles.items()},
                         {"saikou": "com.shinsaikou", "upstream-0.21.0": "org.example.upstream",
                          "upstream-0.20.4": "org.example.upstream"})
        for name in ("upstream-0.21.0", "upstream-0.20.4"):
            self.assertEqual(profiles[name].value("restore.done"), "org.example.upstream|-504")
            self.assertIn("tap text=Upstream", profiles[name].block("setup"))

    def test_every_step_of_the_shipped_profiles_is_a_known_verb(self):
        known = {"tab", "hscroll-to", "slowtype", "picker-root", "tap", "tap-if", "wait", "check", "uncheck",
                 "sleep", "key", "scroll-to"}
        steps = TOOLS / "bench" / "steps"
        for path in sorted(steps.glob("compare20k-*.txt")):
            profile = compare20k.parse_profile(path.read_text())
            for name, block in profile.blocks.items():
                for step in block:
                    self.assertIn(step.split(" ", 1)[0], known, f"{path.name} [{name}]: {step}")


class EnvTest(unittest.TestCase):
    def test_shell_quoted_values_come_back_whole(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "targets.env"
            path.write_text("# written by real_scale_backup.py\nTITLE_1385='Two words'\nWORD_1385=word\n"
                            "CATEGORY_3859='It'\"'\"'s'\nEMPTY=''\nnot a line\n")
            env = compare20k.load_env(path)
        self.assertEqual(env, {"TITLE_1385": "Two words", "WORD_1385": "word", "CATEGORY_3859": "It's", "EMPTY": ""})


class MarkerTest(unittest.TestCase):
    def test_a_regex_locator_searches_its_field(self):
        locator = compare20k.parse_one("text%= c\\d\\d$")
        self.assertIsInstance(locator, compare20k.RegexLocator)
        self.assertTrue(locator.matches(node("Some title c07")))
        self.assertFalse(locator.matches(node("Some title")))
        self.assertFalse(locator.matches(node(desc="Some title c07")))
        # Printed without its pattern, which can carry a word from the owner's library.
        self.assertEqual(str(locator), "text%=<pattern>")

    def test_a_plain_locator_is_ui_steps_one(self):
        locator = compare20k.parse_one("desc=Search")
        self.assertIsInstance(locator, ui_steps.Locator)
        self.assertTrue(locator.matches(node(desc="Search")))

    def test_a_marker_needs_every_present_part_and_no_absent_one(self):
        marker = compare20k.parse_marker("text=A && text%=^B && !text=C")
        self.assertTrue(marker.shown([node("A"), node("Bee")]))
        self.assertFalse(marker.shown([node("A")]))
        self.assertFalse(marker.shown([node("A"), node("Bee"), node("C")]))
        self.assertTrue(marker.absent_now([node("Bee")]))
        self.assertFalse(marker.absent_now([node("A"), node("Bee")]))

    def test_a_marker_of_only_absent_parts_is_refused(self):
        with self.assertRaises(compare20k.Failed):
            compare20k.parse_marker("!text=C")


class TypingTest(unittest.TestCase):
    def test_one_letter_at_a_time_with_a_pause_between(self):
        self.assertEqual(compare20k.typing("ab"),
                         f"input text a; sleep {compare20k.TYPE_INTERVAL_S}; input text b")

    def test_a_shell_character_is_quoted(self):
        self.assertIn("input text ''\"'\"''", compare20k.typing("'"))

    def test_nothing_to_type_is_a_no_op(self):
        self.assertEqual(compare20k.typing(""), "true")


class BlinkTailTest(unittest.TestCase):
    @staticmethod
    def window(*starts_ms: float) -> list[dict[str, int]]:
        return [frame(s, s + 5) for s in starts_ms]

    def test_three_lone_frames_half_a_second_apart_are_a_blink(self):
        self.assertEqual(compare20k.blink_tail(self.window(0, 8, 16, 300, 800, 1300)), 3)

    def test_two_lone_frames_are_not_enough(self):
        self.assertEqual(compare20k.blink_tail(self.window(0, 8, 16, 800, 1300)), 0)

    def test_a_frame_with_a_neighbour_is_content_and_ends_the_blink(self):
        # 1300 and 1310 are close together, so the tail ends in content rather than a blink.
        self.assertEqual(compare20k.blink_tail(self.window(0, 300, 800, 1300, 1310)), 0)

    def test_frames_at_another_period_are_not_a_blink(self):
        self.assertEqual(compare20k.blink_tail(self.window(0, 300, 700, 1100, 1500)), 0)

    def test_the_slack_is_allowed_either_way(self):
        self.assertEqual(compare20k.blink_tail(self.window(0, 8, 300, 830, 1300, 1835)), 4)

    def test_an_empty_window_has_no_blink(self):
        self.assertEqual(compare20k.blink_tail([]), 0)


class FrameWindowTest(unittest.TestCase):
    def test_content_time_is_the_last_frame_done_inside_the_window(self):
        frames = [frame(-50, -40, 1), frame(10, 30, 2), frame(200, 260, 3), frame(5000, 5010, 4)]
        result = compare20k.frame_window(stats(frames), T0, T0 + 1000 * MS, "t")
        self.assertEqual(result["frames_in_window"], 2)
        self.assertEqual(result["content_ms"], 260)
        self.assertEqual(result["first_frame_ms"], 10)
        self.assertEqual(result["frames_rendered"], 9)
        self.assertEqual(result["blink_frames_set_aside"], 0)
        self.assertEqual(result["frame_starts_ms"], [10, 200])

    def test_a_frame_still_drawing_at_the_end_is_left_out(self):
        frames = [frame(10, 30, 1), frame(900, 1100, 2)]
        result = compare20k.frame_window(stats(frames), T0, T0 + 1000 * MS, "t")
        self.assertEqual(result["content_ms"], 30)

    def test_a_cursor_blink_after_the_content_is_set_aside(self):
        frames = [frame(10, 30, 1), frame(40, 120, 2), frame(620, 625, 3), frame(1120, 1125, 4),
                  frame(1620, 1625, 5)]
        result = compare20k.frame_window(stats(frames), T0, T0 + 2000 * MS, "t")
        self.assertEqual(result["blink_frames_set_aside"], 3)
        self.assertEqual(result["content_ms"], 120)

    def test_no_frame_in_the_window_is_no_content_time(self):
        result = compare20k.frame_window(stats([frame(-50, -40)]), T0, T0 + 1000 * MS, "t")
        self.assertIsNone(result["content_ms"])

    def test_a_dump_without_the_needed_columns_is_reported_not_raised(self):
        text = "---PROFILEDATA---\nFlags,IntendedVsync,\n0,1,\n---PROFILEDATA---\n"
        self.assertIn("frames_error", compare20k.frame_window(text, T0, T0 + MS, "t"))


class NotificationTest(unittest.TestCase):
    DUMP = "\n".join([
        "Current Notification Manager state:",
        "  Notification List:",
        notification("dev.example", "503", "Restoring"),
        notification("dev.example", "504", "Restore complete", "Done in 5 s"),
        notification("dev.other", "504", "Elsewhere"),
        notification("dev.example", "-504", "Negative id"),
    ])

    def test_the_text_is_the_named_notifications_title_and_text(self):
        self.assertEqual(compare20k.notification_text(self.DUMP, "dev.example", "504"),
                         "title=Restore complete; text=Done in 5 s")
        self.assertEqual(compare20k.notification_text(self.DUMP, "dev.example", "-504"), "title=Negative id")
        self.assertEqual(compare20k.notification_text(self.DUMP, "dev.example", "505"), "")


class WorkerResultsTest(unittest.TestCase):
    # As `logcat -d -v monotonic -s WM-WorkerWrapper:I` prints them: a newer WorkManager names the
    # class, an older one only tags the work with it.
    LOG = "\n".join([
        "--------- beginning of main",
        "  6996.282 19867 19915 I WM-WorkerWrapper: Worker result SUCCESS for Work [ id=d9f6, "
        "tags={ dev.example.RestoreWorker,backup-import } ]",
        "  7010.5 19867 19915 I WM-WorkerWrapper: Worker result SUCCESS for Work [ id=aa, "
        "tags={ dev.example.RestoreWorkerHelper } ]",
        "  7020.000001 2366 20584 I WM-WorkerWrapper: Worker result SUCCESS for Work [ id=b6, "
        "class=dev.other.SyncWorker, tags={ dev.other.SyncWorker } ]",
        "  8993.316 1936 2040 I WM-WorkerWrapper: Worker result FAILURE for Work [ id=6848, "
        "class=dev.example.RestoreWorker, tags={ dev.example.RestoreWorker } ]",
        "  8999.000 1936 1936 D WM-WorkerWrapper: Starting work for dev.example.RestoreWorker",
    ])

    def test_each_result_of_the_named_worker_with_its_monotonic_time(self):
        self.assertEqual(compare20k.worker_results(self.LOG, "dev.example.RestoreWorker"),
                         [(6_996_282_000_000, "SUCCESS"), (8_993_316_000_000, "FAILURE")])

    def test_a_class_whose_name_extends_the_worker_is_not_it(self):
        self.assertEqual(compare20k.worker_results(self.LOG, "dev.example.RestoreWorkerHelper"),
                         [(7_010_500_000_000, "SUCCESS")])

    def test_another_worker_or_no_log_has_no_result(self):
        self.assertEqual(compare20k.worker_results(self.LOG, "dev.example.Missing"), [])
        self.assertEqual(compare20k.worker_results("", "dev.example.RestoreWorker"), [])


class SummariseTest(unittest.TestCase):
    def test_figures_are_grouped_by_scenario_and_its_qualifiers(self):
        samples = [
            {"scenario": "S3", "content_ms": 100, "cpu_ms": 50},
            {"scenario": "S3", "content_ms": 300, "cpu_ms": 70},
            {"scenario": "S3", "content_ms": 200, "cpu_ms": None},
            {"scenario": "S6", "target": "1385", "content_ms": 40},
            {"scenario": "S6", "target": "1385", "part": "scroll", "janky_pct": 1.5},
            {"scenario": "S7", "tab": "updates", "content_ms": 10, "content_shown": True},
        ]
        summary = compare20k.summarise(samples)
        self.assertEqual(summary["S3"]["content_ms"], {"median": 200, "min": 100, "max": 300, "n": 3})
        self.assertEqual(summary["S3"]["cpu_ms"]["n"], 2)
        self.assertEqual(summary["S6 1385"]["content_ms"]["median"], 40)
        self.assertEqual(summary["S6 1385 scroll"]["janky_pct"]["max"], 1.5)
        self.assertEqual(set(summary["S7 updates"]), {"content_ms"})


class ScriptedDevice(compare20k.Device):
    """A screen that changes as it is tapped: `screens` maps a name to its nodes, `on_tap` a
    (screen, x, y) to the next screen's name."""

    def __init__(self, screens: dict[str, list[ui_steps.Node]], start: str, on_tap):
        self.serial = "test"
        self.screens = screens
        self.screen = start
        self.on_tap = on_tap
        self.taps: list[tuple[str, int, int]] = []

    def nodes(self) -> list[ui_steps.Node]:
        return self.screens[self.screen]

    def tap(self, x: int, y: int) -> None:
        self.taps.append((self.screen, x, y))
        self.screen = self.on_tap(self.screen, x, y)


class ScrolledGrid(compare20k.Device):
    """A results list of `items`, three on screen from `offset`; a swipe up moves one on, a swipe
    down one back, and a swipe down at the start is counted as a pull to refresh."""

    def __init__(self, items: list[str], offset: int):
        self.serial = "test"
        self.items = items
        self.offset = offset
        self.swipes: list[str] = []
        self.refreshes = 0

    def nodes(self) -> list[ui_steps.Node]:
        shown = self.items[self.offset:self.offset + 3]
        return [node(desc="Search library", bounds=(0, 100, 500, 200))] + [
            node(desc=item, bounds=(0, 600 + 500 * k, 300, 1000 + 500 * k)) for k, item in enumerate(shown)]

    def screen_size(self) -> tuple[int, int]:
        return 1080, 2424

    def shell(self, *args: str, check: bool = True) -> str:
        assert args[:2] == ("input", "swipe"), args
        y1, y2 = int(args[3]), int(args[5])
        if y1 > y2:
            self.swipes.append("up")
            self.offset = min(self.offset + 1, max(len(self.items) - 3, 0))
        else:
            self.swipes.append("down")
            if self.offset == 0:
                self.refreshes += 1
            self.offset = max(self.offset - 1, 0)
        return ""


class EntryCellTest(unittest.TestCase):
    ITEMS = [f"Other c{k:02d}" for k in range(10)] + ["Target c01", "Target c02"] + [f"Last c{k:02d}" for k in range(10)]

    def find(self, device: ScrolledGrid) -> ui_steps.Node:
        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(compare20k.time, "sleep"):
            runner = compare20k.Runner(device, compare20k.parse_profile(MINIMAL_PROFILE), {}, Path(tmp))
            runner.scope = {"TITLE": "Target c02"}
            return runner.entry_cell()

    def test_a_cell_below_is_found_swiping_towards_the_end(self):
        device = ScrolledGrid(self.ITEMS, 0)
        self.assertEqual(self.find(device).desc, "Target c02")
        self.assertEqual(set(device.swipes), {"up"})

    def test_a_cell_above_is_found_after_the_end_without_a_pull_to_refresh(self):
        device = ScrolledGrid(self.ITEMS, 15)
        self.assertEqual(self.find(device).desc, "Target c02")
        self.assertIn("down", device.swipes)
        self.assertEqual(device.refreshes, 0)

    def test_a_cell_in_neither_direction_fails(self):
        device = ScrolledGrid([f"Other c{k:02d}" for k in range(8)], 2)
        with self.assertRaises(compare20k.Failed):
            self.find(device)


class PickerRootTest(unittest.TestCase):
    SCREENS = {
        "recent": [node(desc="Show roots", bounds=(0, 100, 100, 200)), node("Recent files")],
        "drawer": [node("Recent", resource_id="android:id/title", bounds=(0, 300, 500, 350)),
                   node("Downloads", resource_id="android:id/title", bounds=(0, 700, 500, 750))],
        "downloads": [node("Downloads", resource_id="com.example:id/breadcrumb_text"),
                      node("Files in Downloads"), node("real20k.tachibk")],
    }

    def run_picker(self, device: ScriptedDevice) -> None:
        # A clock that moves a second a reading, and sleeps that return at once (compare20k and
        # ui_steps share the time module), so the waits run without waiting.
        clock = iter(range(10**6))
        with (tempfile.TemporaryDirectory() as tmp, mock.patch.object(compare20k.time, "sleep"),
              mock.patch.object(compare20k.time, "monotonic", side_effect=lambda: next(clock))):
            runner = compare20k.Runner(device, compare20k.parse_profile(MINIMAL_PROFILE), {}, Path(tmp))
            runner.picker_root("text=Downloads shown=text=Files in Downloads")

    def test_the_root_is_opened_from_the_drawer(self):
        def on_tap(screen, x, y):
            return {"recent": "drawer", "drawer": "downloads"}[screen]
        device = ScriptedDevice(self.SCREENS, "recent", on_tap)
        self.run_picker(device)
        self.assertEqual(device.screen, "downloads")
        # The drawer's own item, never the breadcrumb of the same word.
        self.assertEqual(device.taps, [("recent", 50, 150), ("drawer", 250, 725)])

    def test_a_tap_the_picker_resets_after_is_repeated(self):
        resets = iter([True, False])

        def on_tap(screen, x, y):
            if screen == "recent":
                return "drawer"
            return "recent" if next(resets) else "downloads"
        device = ScriptedDevice(self.SCREENS, "recent", on_tap)
        self.run_picker(device)
        self.assertEqual(device.screen, "downloads")
        self.assertEqual(len(device.taps), 4)

    def test_a_root_already_shown_is_left_alone(self):
        device = ScriptedDevice(self.SCREENS, "downloads", lambda *a: self.fail("tapped"))
        self.run_picker(device)
        self.assertEqual(device.taps, [])

    def test_a_root_that_never_shows_fails(self):
        device = ScriptedDevice(self.SCREENS, "recent", lambda screen, x, y: "drawer" if screen == "recent" else "recent")
        with self.assertRaises(compare20k.Failed):
            self.run_picker(device)


if __name__ == "__main__":
    unittest.main()
