#!/usr/bin/env python3
"""Unit tests for the device side of ruling R66: tools/bench/ui_steps.py and update_drive.py.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The device is replaced by a fake that answers `uiautomator dump`, `cat` and `cmd notification list`
from scripted screens, and the driver's clock and sleep are replaced by counters, so the timing
logic runs without an emulator and without waiting. The cases that matter, and why each one is here:
  - a locator matches by text, description or id, exactly, by prefix or by substring, a chip is the
    checkable around its label, and a switch is found by its row, because Compose draws a label and
    its switch as unrelated siblings;
  - a {{NAME}} placeholder with no value fails the step rather than typing an empty label;
  - `am` hands its arguments to the device's shell as one string, so a quoted address keeps its ?;
  - the driver's window ends at the first poll that no longer sees the notification, and a failed
    poll is unknown rather than absent: the adb server restarting under a run once ended a probe
    early by reading as "gone", and a removal known only to within more than a second is a rerun.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
sys.path.insert(0, str(BENCH))


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, BENCH / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


ui_steps = load("ui_steps")
update_drive = load("update_drive")

DUMP = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node text="" content-desc="" resource-id="" checkable="false" checked="false" bounds="[0,0][1080,2424]">
    <node text="Library" content-desc="" resource-id="" checkable="false" checked="false" bounds="[42,190][300,260]" />
    <node text="" content-desc="More options" resource-id="" checkable="false" checked="false" bounds="[975,195][1038,258]" />
    <node text="Send crash logs" content-desc="" resource-id="" checkable="false" checked="false" bounds="[84,1412][401,1475]" />
    <node text="" content-desc="" resource-id="" checkable="true" checked="true" bounds="[900,1420][1000,1500]" />
    <node text="Allow analytics" content-desc="" resource-id="" checkable="false" checked="false" bounds="[84,1643][384,1706]" />
    <node text="" content-desc="" resource-id="" checkable="true" checked="false" bounds="[900,1640][1000,1720]" />
    <node text="Hidden" content-desc="" resource-id="" checkable="false" checked="false" bounds="[0,0][0,0]" />
    <node text="Fixture 01-001" content-desc="" resource-id="app:id/title" checkable="false" checked="false" bounds="[40,600][500,660]" />
  </node>
</hierarchy>"""


# Saikou's "Automatic backup interval" row: each chip is checkable and holds its label.
CHIPS = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node text="Automatic backup interval" content-desc="" resource-id="" checkable="false" checked="false" bounds="[63,336][1017,389]" />
  <node text="" content-desc="" resource-id="" checkable="true" checked="false" bounds="[63,415][200,541]">
    <node text="Off" content-desc="" resource-id="" checkable="false" checked="false" bounds="[105,452][158,505]" />
  </node>
  <node text="" content-desc="" resource-id="" checkable="true" checked="true" bounds="[221,415][376,541]">
    <node text="12 h" content-desc="" resource-id="" checkable="false" checked="false" bounds="[263,452][334,505]" />
  </node>
  <node text="" content-desc="" resource-id="" checkable="true" checked="false" bounds="[397,415][552,541]">
    <node text="24 h" content-desc="" resource-id="" checkable="false" checked="false" bounds="[439,452][510,505]" />
  </node>
</hierarchy>"""


class FakeDevice(ui_steps.Device):
    """Answers the shell calls ui_steps and update_drive make, from one screen."""

    def __init__(self, xml: str = DUMP):
        super().__init__("emulator-0000", "adb")
        self.xml, self.calls, self.taps = xml, [], []

    def shell(self, *args: str, check: bool = True) -> str:
        self.calls.append(args)
        if args[:2] == ("uiautomator", "dump"):
            return f"UI hierchary dumped to: {ui_steps.DUMP_PATH}"
        if args[0] == "cat":
            return self.xml
        if args[:2] == ("input", "tap"):
            self.taps.append((int(args[2]), int(args[3])))
        return ""


class LocatorTest(unittest.TestCase):
    def setUp(self):
        self.nodes = ui_steps.parse_nodes(DUMP)

    def find(self, spec: str):
        return ui_steps.find(self.nodes, ui_steps.parse_locator(spec)[0])

    def test_matches_by_text_description_and_id(self):
        self.assertEqual(self.find("text=Library").bounds, (42, 190, 300, 260))
        self.assertEqual(self.find("desc=More options").centre, (1006, 226))
        self.assertEqual(self.find("id=app:id/title").text, "Fixture 01-001")

    def test_prefix_and_substring_modes(self):
        self.assertEqual(self.find("text^=Fixture").text, "Fixture 01-001")
        self.assertEqual(self.find("text~=crash").text, "Send crash logs")
        self.assertIsNone(self.find("text=Fixture"))

    def test_nth_picks_a_later_match_and_finds_nothing_past_the_last(self):
        nodes = ui_steps.parse_nodes(DUMP.replace('text="Allow analytics"', 'text="Send crash logs"'))
        second = ui_steps.find(nodes, ui_steps.parse_locator("text=Send crash logs nth=2 timeout=5")[0])
        self.assertEqual(second.bounds, (84, 1643, 384, 1706))
        self.assertIsNone(ui_steps.find(nodes, ui_steps.parse_locator("text=Send crash logs nth=3")[0]))

    def test_a_node_with_no_area_is_never_found(self):
        self.assertIsNone(self.find("text=Hidden"))

    def test_a_label_keeps_its_spaces_and_the_timeout_is_read_off_the_end(self):
        locator, timeout = ui_steps.parse_locator("text=Skip entries with \"Completed\" status timeout=45")
        self.assertEqual((locator.label, timeout), ('Skip entries with "Completed" status', 45.0))
        self.assertEqual(ui_steps.parse_locator("desc=Install")[1], ui_steps.DEFAULT_TIMEOUT_S)

    def test_a_malformed_locator_is_refused(self):
        with self.assertRaises(ValueError):
            ui_steps.parse_locator("label=Library")

    def test_a_chip_is_the_checkable_around_its_label(self):
        # Three interval chips on one row share a vertical centre; the label picks its own.
        chips = ui_steps.parse_nodes(CHIPS)
        label = ui_steps.find(chips, ui_steps.parse_locator("text=12 h")[0])
        chip = ui_steps.toggle_for(chips, label)
        self.assertEqual((chip.bounds, chip.checked), ((221, 415, 376, 541), True))
        off = ui_steps.toggle_for(chips, ui_steps.find(chips, ui_steps.parse_locator("text=Off")[0]))
        self.assertEqual((off.bounds, off.checked), ((63, 415, 200, 541), False))

    def test_the_switch_on_a_row_is_the_nearest_checkable(self):
        crash = self.find("text=Send crash logs")
        analytics = self.find("text=Allow analytics")
        self.assertTrue(ui_steps.toggle_for(self.nodes, crash).checked)
        self.assertFalse(ui_steps.toggle_for(self.nodes, analytics).checked)
        self.assertIsNone(ui_steps.toggle_for(self.nodes, self.find("text=Library")))


class StepTest(unittest.TestCase):
    def test_placeholders_come_from_the_environment_and_an_empty_one_fails(self):
        self.assertEqual(ui_steps.expand("tap text={{BACKUP_NAME}}", {"BACKUP_NAME": "update-500x2.tachibk"}),
                         "tap text=update-500x2.tachibk")
        with self.assertRaisesRegex(ui_steps.StepFailed, "needs BACKUP_NAME"):
            ui_steps.expand("tap text={{BACKUP_NAME}}", {"BACKUP_NAME": ""})

    def test_am_reaches_the_device_shell_as_one_string(self):
        device = FakeDevice()
        ui_steps.run_step(device, "am start -a android.intent.action.VIEW -d 'x://y?url=http://h:1/a' pkg")
        self.assertEqual(device.calls[-1], ("am start -a android.intent.action.VIEW -d 'x://y?url=http://h:1/a' pkg",))

    def test_tap_taps_the_centre_of_the_match(self):
        device = FakeDevice()
        ui_steps.run_step(device, "tap desc=More options")
        self.assertEqual(device.taps, [(1006, 226)])

    def test_tap_if_goes_on_when_nothing_matches(self):
        device = FakeDevice()
        ui_steps.run_step(device, "tap-if text=Not there timeout=0")
        self.assertEqual(device.taps, [])

    def test_uncheck_taps_the_switch_only_when_it_is_on(self):
        device = FakeDevice()
        with self.assertRaisesRegex(ui_steps.StepFailed, "did not turn off"):
            # The fake screen never changes, so the switch reads on after the tap too.
            ui_steps.run_step(device, "uncheck text=Send crash logs")
        self.assertEqual(device.taps, [(950, 1460)])
        device = FakeDevice()
        ui_steps.run_step(device, "uncheck text=Allow analytics")
        self.assertEqual(device.taps, [])

    def test_notification_keys_read_package_and_id(self):
        listing = "0|org.example.upstream|-101|null|10279\n0|android|17040000|null|1000\n"
        self.assertEqual(ui_steps.notification_keys(listing),
                         {("org.example.upstream", "-101"), ("android", "17040000")})

    def test_steps_skip_comments_and_blank_lines(self):
        self.assertEqual(ui_steps.steps_of("# a comment\n\n  tap text=A\nwait text=B  \n"), ["tap text=A", "wait text=B"])


class FakePoller:
    """Answers present() from a script: True, False or None (a failed poll)."""

    def __init__(self, answers: list[bool | None]):
        self.answers = list(answers)
        self.polls = self.failed = 0

    def present(self) -> bool | None:
        self.polls += 1
        answer = self.answers.pop(0) if self.answers else False
        if answer is None:
            self.failed += 1
        return answer


class Clock:
    """A clock that moves only when the driver sleeps."""

    def __init__(self):
        self.now = 100.0

    def __call__(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


REFRESH = ["wait text=Library", "tap desc=More options"]


class DriveTest(unittest.TestCase):
    def drive(self, answers: list[bool | None], start_timeout: float = 60.0, run_timeout: float = 1800.0) -> dict:
        clock = Clock()
        poller = FakePoller(answers)
        out = update_drive.drive(FakeDevice(), REFRESH, poller, start_timeout, run_timeout, settle=5.0,
                                 clock=clock, sleep=clock.sleep)
        out["_polls"] = poller.polls
        return out

    def test_the_window_runs_from_the_tap_to_the_first_poll_without_the_notification(self):
        out = self.drive([False, True, True, True, False])
        self.assertEqual(out["tap"], 100.0)
        self.assertEqual(out["seen"], 100.25)
        self.assertEqual(out["gone"], 101.0)
        self.assertEqual(out["gone_uncertainty_s"], 0.25)
        self.assertEqual(out["problems"], [])

    def test_a_failed_poll_is_not_a_removal(self):
        out = self.drive([True, None, True, False])
        self.assertEqual(out["gone"], 100.75)
        self.assertEqual(out["failed_polls"], 1)
        self.assertEqual(out["problems"], [])

    def test_a_removal_known_only_to_more_than_a_second_is_a_rerun(self):
        out = self.drive([True] + [None] * 5 + [False])
        self.assertIsNone(out["gone"])
        self.assertEqual(out["gone_uncertainty_s"], 1.5)
        self.assertIn("rerun", out["problems"][0])

    def test_a_notification_that_never_shows_ends_the_run(self):
        out = self.drive([False] * 10, start_timeout=1.0)
        self.assertIsNone(out["seen"])
        self.assertIn("never showed within 1 s", out["problems"][0])

    def test_an_update_that_outlasts_the_run_timeout_ends_the_run(self):
        out = self.drive([True] * 100, run_timeout=2.0)
        self.assertIsNone(out["gone"])
        self.assertIn("still running 2 s after the tap", out["problems"][0])

    def test_an_unreachable_refresh_is_a_problem_and_nothing_is_tapped(self):
        device = FakeDevice()
        steps = ["wait text=Library", "tap text=Update library timeout=0"]
        out = update_drive.drive(device, steps, FakePoller([]), 60, 1800, 0, sleep=lambda _: None)
        self.assertIsNone(out["tap"])
        self.assertIn("could not be reached", out["problems"][0])
        self.assertEqual(device.taps, [])

    def test_the_steps_must_end_with_the_tap_that_starts_the_update(self):
        with self.assertRaises(ValueError):
            update_drive.drive(FakeDevice(), ["tap desc=More options", "wait text=Library"], FakePoller([]), 1, 1, 0)

    def test_notification_present_matches_package_and_id_exactly(self):
        listing = "0|org.example.upstream|-101|null|10279\n0|com.shinsaikou|1010|null|10300\n"
        self.assertTrue(update_drive.notification_present(listing, "org.example.upstream", -101))
        self.assertFalse(update_drive.notification_present(listing, "org.example.upstream", 101))
        self.assertFalse(update_drive.notification_present(listing, "com.shinsaikou", 101))


if __name__ == "__main__":
    unittest.main()
