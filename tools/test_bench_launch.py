#!/usr/bin/env python3
"""Unit tests for the launch guard in tools/bench/common.sh.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

`am start -W` prints `Status: ok` even when it launched nothing. When a runtime
permission dialog owns the foreground, the dialog belongs to the platform permission
controller rather than to the package under test, so `am force-stop` on the package
does not dismiss it, and every later `am start` is delivered to that dialog as the
already-running top-most instance. The platform then prints a
`Warning: Activity not started` line, an `Activity:` line naming the foreign activity,
and `TotalTime: 0`.

This was live. A real run on the 4 GB AVD recorded five samples of `TotalTime: 0` and
wrote a gate fragment with `"value": 0`. `tools/gate/release_gate.py` scores a smaller
cold start as an improvement, so a published zero is the fastest launch ever recorded
and passes the row every time. The warmup launch in the same run reported
`TotalTime: 857` with `LaunchState: COLD` while the `Activity:` line named the
permission dialog, which is the more dangerous shape: 857 is a plausible-looking
number close to the baseline, and nothing downstream would ever have questioned it.

The fix added `assert_launched` to tools/bench/common.sh. It rejects output carrying
the "Activity not started" warning, and rejects output whose `Activity:` line names an
activity outside `$SAIKOU_PACKAGE`.

`common.sh` cannot be sourced without a device: it calls
`require_env SAIKOU_SERIAL SAIKOU_DEVICE_ID` and makes adb calls while being sourced. So
this file extracts just the `assert_launched` shell function out of common.sh by text
and runs that function in a bash subshell. `assert_launched` depends only on
`$SAIKOU_PACKAGE` and `sed`, so it runs correctly in isolation. This mirrors the
text-extraction idiom in app/src/test/kotlin/com/shinsaikou/di/ViewModelBindingTest.kt,
which is a plain text scan standing in for a check the toolchain cannot make.
"""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
COMMON_SH = TOOLS / "bench" / "common.sh"
COLDSTART_SH = TOOLS / "bench" / "coldstart.sh"


def extract_function(source: str, name: str) -> str:
    """Extracts a shell function's body by text, from its `name() {` line through the
    next line that is exactly `}` at column zero.

    A scan that silently stops matching the closing brace must not pass vacuously, so
    this raises instead of returning an empty or partial result when either marker is
    missing.
    """
    lines = source.splitlines()
    start = None
    for index, line in enumerate(lines):
        if line.startswith(f"{name}() {{"):
            start = index
            break
    if start is None:
        raise AssertionError(f"could not find '{name}() {{' in the source")
    end = None
    for index in range(start + 1, len(lines)):
        if lines[index] == "}":
            end = index
            break
    if end is None:
        raise AssertionError(f"could not find the closing '}}' for {name}")
    return "\n".join(lines[start:end + 1])


ASSERT_LAUNCHED = extract_function(COMMON_SH.read_text(), "assert_launched")


def run_assert_launched(am_start_output: str) -> subprocess.CompletedProcess:
    script = f"{ASSERT_LAUNCHED}\nassert_launched \"$1\"\n"
    return subprocess.run(
        ["bash", "-c", script, "bash", am_start_output],
        env={"SAIKOU_PACKAGE": "com.shinsaikou", "PATH": "/usr/bin:/bin"},
        capture_output=True,
        text=True,
    )


class AssertLaunchedExtractionTest(unittest.TestCase):
    """Pins the extracted function to a non-empty body carrying both guards, so a
    future rewrite that guts either check fails here rather than in the field."""

    def test_extraction_is_non_empty(self):
        self.assertTrue(ASSERT_LAUNCHED.strip())

    def test_extraction_still_checks_for_the_activity_not_started_warning(self):
        self.assertIn("Activity not started", ASSERT_LAUNCHED)

    def test_extraction_still_references_saikou_package(self):
        self.assertIn("SAIKOU_PACKAGE", ASSERT_LAUNCHED)


class AssertLaunchedTest(unittest.TestCase):

    def test_rejects_the_real_world_broken_output(self):
        # The exact output captured on the 4 GB AVD run: the permission dialog owned
        # the foreground, am force-stop never touched it, and TotalTime came back 0.
        output = (
            "Starting: Intent { cmp=com.shinsaikou/.ui.main.MainActivity }\n"
            "Warning: Activity not started, intent has been delivered to currently "
            "running top-most instance.\n"
            "Status: ok\n"
            "LaunchState: UNKNOWN (0)\n"
            "Activity: com.google.android.permissioncontroller/"
            "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity\n"
            "TotalTime: 0\n"
        )
        result = run_assert_launched(output)
        self.assertNotEqual(result.returncode, 0)

    def test_rejects_the_plausible_looking_warmup_shape(self):
        # This is the case a human reviewer would never catch: no warning line, a
        # LaunchState of COLD, and a TotalTime of 857 that sits right next to the
        # baseline. The only tell is the Activity line naming the permission
        # controller instead of the package under test.
        output = (
            "Starting: Intent { cmp=com.shinsaikou/.ui.main.MainActivity }\n"
            "Status: ok\n"
            "LaunchState: COLD\n"
            "Activity: com.google.android.permissioncontroller/"
            "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity\n"
            "TotalTime: 857\n"
        )
        result = run_assert_launched(output)
        self.assertNotEqual(result.returncode, 0)

    def test_accepts_a_genuine_cold_launch(self):
        # The real numbers from the verified run.
        output = (
            "Starting: Intent { cmp=com.shinsaikou/.ui.main.MainActivity }\n"
            "Status: ok\n"
            "LaunchState: COLD\n"
            "Activity: com.shinsaikou/.ui.main.MainActivity\n"
            "TotalTime: 428\n"
        )
        result = run_assert_launched(output)
        self.assertEqual(result.returncode, 0, result.stderr)


LAUNCHER_ACTIVITY = extract_function(COMMON_SH.read_text(), "launcher_activity")

# `cmd package query-activities --brief -a android.intent.action.MAIN -c
# android.intent.category.LAUNCHER com.shinsaikou`, captured from a debug build on saikou_api26_2g and
# on saikou_pixel9_4g (API 36) on 2026-09-26: the same text on both.
DEBUG_BUILD_QUERY = (
    "2 activities found:\n"
    "  Activity #0:\n"
    "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n"
    "    com.shinsaikou/.debug.GoogleRoundTripActivity\n"
    "  Activity #1:\n"
    "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n"
    "    com.shinsaikou/.ui.main.MainActivity\n"
)


def run_launcher_activity(query_output: str, package: str = "com.shinsaikou") -> subprocess.CompletedProcess:
    script = f"set -euo pipefail\n{LAUNCHER_ACTIVITY}\nlauncher_activity\n"
    return subprocess.run(
        ["bash", "-c", script],
        input=query_output,
        env={"SAIKOU_PACKAGE": package, "PATH": "/usr/bin:/bin"},
        capture_output=True,
        text=True,
    )


class LauncherActivityTest(unittest.TestCase):
    """The launch activity the bench and ci scripts start. A debug build has two launcher
    entries since phase 3 unit O, and the old `cmd package resolve-activity` lookup got the
    platform's chooser back on both API levels, so step 5e of the phase 3 gate stopped with
    "missing: SAIKOU_LAUNCH_ACTIVITY"."""

    def test_a_debug_build_launches_the_main_activity_not_the_debug_screen(self):
        result = run_launcher_activity(DEBUG_BUILD_QUERY)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, ".ui.main.MainActivity\n")

    def test_carriage_returns_from_an_old_adb_shell_are_dropped(self):
        result = run_launcher_activity(DEBUG_BUILD_QUERY.replace("\n", "\r\n"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, ".ui.main.MainActivity\n")

    def test_a_release_build_with_one_entry_launches_it(self):
        output = (
            "1 activities found:\n"
            "  Activity #0:\n"
            "    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n"
            "    com.shinsaikou/.ui.main.MainActivity\n"
        )
        result = run_launcher_activity(output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, ".ui.main.MainActivity\n")

    def test_a_fully_qualified_class_in_another_package_is_kept(self):
        # The upstream baseline names its activity by a class outside its package name.
        output = (
            "1 activities found:\n"
            "  Activity #0:\n"
            "    org.example.upstream/eu.kanade.tachiyomi.ui.main.MainActivity\n"
        )
        result = run_launcher_activity(output, package="org.example.upstream")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "eu.kanade.tachiyomi.ui.main.MainActivity\n")

    def test_a_fully_qualified_debug_class_is_set_aside(self):
        output = DEBUG_BUILD_QUERY.replace(
            "com.shinsaikou/.debug.GoogleRoundTripActivity", "com.shinsaikou/com.shinsaikou.debug.GoogleRoundTripActivity"
        )
        result = run_launcher_activity(output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, ".ui.main.MainActivity\n")

    def test_two_entries_outside_a_debug_package_fail_and_name_both(self):
        output = DEBUG_BUILD_QUERY.replace(".debug.GoogleRoundTripActivity", ".ui.other.OtherActivity")
        result = run_launcher_activity(output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn(".ui.other.OtherActivity", result.stderr)
        self.assertIn(".ui.main.MainActivity", result.stderr)

    def test_only_a_debug_entry_fails(self):
        output = "1 activities found:\n  Activity #0:\n    com.shinsaikou/.debug.GoogleRoundTripActivity\n"
        result = run_launcher_activity(output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_no_entry_fails(self):
        result = run_launcher_activity("No activities found\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_another_package_s_entry_is_never_taken(self):
        output = "1 activities found:\n  Activity #0:\n    com.shinsaikoux/.ui.main.MainActivity\n"
        result = run_launcher_activity(output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_a_dot_in_the_package_name_matches_only_a_dot(self):
        output = "1 activities found:\n  Activity #0:\n    devXsaikou/.ui.main.MainActivity\n"
        result = run_launcher_activity(output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_common_sh_asks_for_launcher_entries_and_no_longer_resolves(self):
        source = COMMON_SH.read_text()
        self.assertIn("query-activities --brief -a android.intent.action.MAIN", source)
        self.assertIn("-c android.intent.category.LAUNCHER", source)
        self.assertNotIn("resolve-activity --brief", source)


class ColdstartGuardTest(unittest.TestCase):
    """coldstart.sh's `start_once` needs a real device to run, so the honest limit is
    a structural check: pin the presence of the guard, not its behaviour."""

    def test_start_once_still_guards_total_time_greater_than_zero(self):
        source = COLDSTART_SH.read_text()
        self.assertIn("-gt 0", source)

    def test_warmup_launch_is_still_allowed_to_fail(self):
        source = COLDSTART_SH.read_text()
        self.assertIn("start_once warmup >/dev/null || true", source)


if __name__ == "__main__":
    unittest.main()
