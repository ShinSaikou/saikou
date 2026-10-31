#!/usr/bin/env python3
"""Unit tests for the upstream app's names as this machine's settings, not the repository's.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The library update and compare-20k benches install and drive the upstream app. Its application id,
its deep link scheme and the folder it makes in shared storage come from SAIKOU_UPSTREAM_PACKAGE,
SAIKOU_UPSTREAM_SCHEME and SAIKOU_UPSTREAM_STORAGE_DIR, else saikou.upstream.package,
saikou.upstream.scheme and saikou.upstream.storage.dir in local.properties; a steps file writes them as
${UPSTREAM_PACKAGE}, ${UPSTREAM_SCHEME} and ${UPSTREAM_STORAGE_DIR}. Nothing here needs a device:
every script stops on its missing input before it reaches adb.

The cases that matter, and why each one is here:
  - ui_steps.fill_upstream takes the environment first and local.properties second, treats a blank
    value as absent, and names every missing one with both its names, so the owner knows what to set;
  - it fills step lines only: a comment may name a placeholder, and a ${...} that is not one of the
    three is left for whatever reads it, so no other text of a steps file changes meaning;
  - every shipped steps file either needs none of the settings (Saikou's) or is filled completely,
    writes no ${...} but the three, and the upstream setup's deep link comes out as the device's
    shell expects it;
  - ui_steps.py, update_drive.py and compare20k.py stop with exit 2 before any device call when an
    upstream file names a setting that is not set;
  - library_update.sh, library_update_seed.sh and compare20k.sh ask for the settings only when
    they run the upstream app, with the `missing:` line every bench script prints, so a Saikou run
    never needs them; compare20k.sh takes the APKs from their settings, else the cache under
    ~/.cache/saikou-baseline/apk, and refuses a storage folder it could not remove safely.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
BENCH = TOOLS / "bench"
STEPS = BENCH / "steps"
sys.path.insert(0, str(BENCH))

import ui_steps  # noqa: E402

SETTINGS = {"SAIKOU_UPSTREAM_PACKAGE": "org.example.upstream", "SAIKOU_UPSTREAM_SCHEME": "exampleupstream",
            "SAIKOU_UPSTREAM_STORAGE_DIR": "ExampleUpstream"}
MISSING_PACKAGE = "missing: SAIKOU_UPSTREAM_PACKAGE (or saikou.upstream.package in local.properties)"


class Machine:
    """A throwaway HOME and local.properties, with no upstream setting anywhere unless one is given."""

    def __init__(self, test: unittest.TestCase, properties: str = "") -> None:
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.properties = self.root / "local.properties"
        self.properties.write_text(properties)
        self.env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(self.root),
                    "ANDROID_SDK_ROOT": str(self.root / "sdk"), "SAIKOU_LOCAL_PROPERTIES": str(self.properties)}

    def run(self, *argv: str, **env: str) -> subprocess.CompletedProcess:
        return subprocess.run(list(argv), env={**self.env, **env}, cwd=self.root, capture_output=True,
                              text=True, timeout=60, stdin=subprocess.DEVNULL)


class FillTest(unittest.TestCase):

    def test_the_environment_wins_and_local_properties_is_the_fallback(self):
        machine = Machine(self, "saikou.upstream.package=from.the.file\nsaikou.upstream.scheme=filescheme\n")
        text = "am start -d '${UPSTREAM_SCHEME}://x' ${UPSTREAM_PACKAGE}\n"
        filled = ui_steps.fill_upstream(text, {**machine.env, "SAIKOU_UPSTREAM_PACKAGE": "from.the.environment"})
        self.assertEqual(filled, "am start -d 'filescheme://x' from.the.environment\n")

    def test_every_placeholder_with_neither_set_is_named_with_both_names(self):
        machine = Machine(self, "saikou.upstream.package=\n")
        text = "tap text=${UPSTREAM_STORAGE_DIR}\nnotification ${UPSTREAM_PACKAGE}|-504\nam ${UPSTREAM_SCHEME}://\n"
        with self.assertRaises(ui_steps.StepFailed) as failed:
            ui_steps.fill_upstream(text, {**machine.env, "SAIKOU_UPSTREAM_PACKAGE": "  "})
        message = str(failed.exception)
        self.assertTrue(message.startswith(MISSING_PACKAGE), message)
        self.assertIn("SAIKOU_UPSTREAM_SCHEME (or saikou.upstream.scheme in local.properties)", message)
        self.assertIn("SAIKOU_UPSTREAM_STORAGE_DIR (or saikou.upstream.storage.dir in local.properties)", message)

    def test_comments_and_other_placeholders_are_left_as_they_are(self):
        machine = Machine(self)
        text = "# The folder, ${UPSTREAM_STORAGE_DIR}, is made first.\ntap text={{BACKUP_NAME}}\ntype ${HOME}\n"
        self.assertEqual(ui_steps.fill_upstream(text, machine.env), text)
        filled = ui_steps.fill_upstream(text + "tap text=${UPSTREAM_STORAGE_DIR}", {**machine.env, **SETTINGS})
        self.assertEqual(filled, text + "tap text=ExampleUpstream")


class ShippedStepsTest(unittest.TestCase):

    def test_each_steps_file_needs_no_setting_or_is_filled_completely(self):
        machine = Machine(self)
        upstream = []
        for path in sorted(STEPS.glob("*.txt")):
            text = path.read_text()
            with self.subTest(path.name):
                if "upstream" in path.name:
                    upstream.append(path.name)
                    filled = ui_steps.fill_upstream(text, {**machine.env, **SETTINGS})
                    self.assertNotIn("${", "\n".join(ui_steps.steps_of(filled)))
                else:
                    self.assertEqual(ui_steps.fill_upstream(text, machine.env), text)
        self.assertIn("compare20k-upstream-0.20.4.txt", upstream)
        self.assertIn("update-upstream-setup.txt", upstream)

    def test_no_steps_file_writes_a_placeholder_outside_the_three(self):
        names = set()
        for path in STEPS.glob("*.txt"):
            for step in ui_steps.steps_of(path.read_text()):
                names.update(re.findall(r"\$\{([A-Z0-9_]+)\}", step))
        self.assertLessEqual(names, set(ui_steps.UPSTREAM_VALUES))

    def test_the_upstream_setup_adds_the_fixture_repository_through_its_own_link(self):
        machine = Machine(self)
        for name in ("update-upstream-setup.txt", "update-upstream021-setup.txt"):
            with self.subTest(name):
                text = ui_steps.fill_upstream((STEPS / name).read_text(), {**machine.env, **SETTINGS})
                steps = ui_steps.steps_of(text)
                self.assertIn("am start -a android.intent.action.VIEW -d "
                              "'exampleupstream://extension-store?url=http://10.0.2.2:18794/repo/index.min.json' "
                              "org.example.upstream", steps)
                self.assertIn("tap text=ExampleUpstream", steps)


class DriverTest(unittest.TestCase):
    """ui_steps.py, update_drive.py and compare20k.py stop on a missing setting before any device call
    (exit 2)."""

    def test_ui_steps_runs_no_step_of_an_upstream_file_without_its_settings(self):
        machine = Machine(self)
        result = machine.run(sys.executable, str(BENCH / "ui_steps.py"), "--serial", "emulator-5554", "run",
                             str(STEPS / "update-upstream-setup.txt"))
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn(MISSING_PACKAGE, result.stderr)
        self.assertNotIn("the screen showed", result.stderr)

    def test_update_drive_runs_no_refresh_that_names_an_unset_setting(self):
        machine = Machine(self)
        steps = machine.root / "refresh.txt"
        steps.write_text("wait text=Library\ntap text=${UPSTREAM_STORAGE_DIR}\n")
        result = machine.run(sys.executable, str(BENCH / "update_drive.py"), "--serial", "emulator-5554",
                             "--package", "org.example.upstream", "--notification-id", "-101",
                             "--steps", str(steps), "--out", str(machine.root / "drive.json"))
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn("missing: SAIKOU_UPSTREAM_STORAGE_DIR", result.stderr)
        self.assertFalse((machine.root / "drive.json").exists())

    def test_compare20k_reads_no_upstream_profile_without_its_settings(self):
        machine = Machine(self)
        targets = machine.root / "targets.env"
        targets.write_text("")
        result = machine.run(sys.executable, str(BENCH / "compare20k.py"), "--serial", "emulator-5554",
                             "--profile", str(STEPS / "compare20k-upstream-0.21.0.txt"), "--env", str(targets),
                             "--out", str(machine.root / "out"), "setup")
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn(MISSING_PACKAGE, result.stderr)
        self.assertIn("SAIKOU_UPSTREAM_STORAGE_DIR", result.stderr)


class ScriptTest(unittest.TestCase):
    """The bench scripts ask for the settings when they run the upstream app, and only then."""

    def test_library_update_needs_the_package_for_either_upstream_app_only(self):
        machine = Machine(self)
        for app in ("upstream", "upstream021"):
            with self.subTest(app):
                result = machine.run("bash", str(BENCH / "library_update.sh"), app, "2000x20")
                self.assertEqual(result.returncode, 2)
                self.assertEqual(result.stderr, MISSING_PACKAGE + "\n")
        result = machine.run("bash", str(BENCH / "library_update.sh"), "saikou", "2000x20")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr, "missing: SAIKOU_SERIAL\nmissing: SAIKOU_DEVICE_ID\n")

    def test_library_update_seed_needs_the_scheme_and_folder_for_the_base_snapshot_only(self):
        machine = Machine(self, "saikou.upstream.package=org.example.upstream\n")
        seed = str(BENCH / "library_update_seed.sh")
        result = machine.run("bash", seed, "upstream", "base")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr,
                         "missing: SAIKOU_UPSTREAM_SCHEME (or saikou.upstream.scheme in local.properties)\n")
        result = machine.run("bash", seed, "upstream021", "500x2")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("SAIKOU_UPSTREAM", result.stderr)
        self.assertIn("missing: SAIKOU_SERIAL", result.stderr)
        result = Machine(self).run("bash", seed, "saikou", "base")
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("SAIKOU_UPSTREAM", result.stderr)
        self.assertIn("missing: SAIKOU_SERIAL", result.stderr)
        result = Machine(self).run("bash", seed, "upstream", "base")
        self.assertEqual(result.stderr, MISSING_PACKAGE + "\n")

    def compare20k(self, machine: Machine, apps: str, **env: str) -> subprocess.CompletedProcess:
        targets = machine.root / "targets.env"
        targets.write_text("")
        apk = machine.root / "app-release.apk"
        apk.write_bytes(b"not an apk, but readable")
        return machine.run("bash", str(BENCH / "compare20k.sh"), "--out", str(machine.root / "out"),
                           "--env", str(targets), "--apps", apps, "--skip-restore", SAIKOU_APK=str(apk), **env)

    def test_compare20k_needs_the_settings_only_when_an_upstream_app_is_in_the_session(self):
        result = self.compare20k(Machine(self), "saikou,upstream-0.20.4")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr, MISSING_PACKAGE + "\n")
        result = self.compare20k(Machine(self), "upstream-0.21.0", SAIKOU_UPSTREAM_PACKAGE="org.example.upstream")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr, "missing: SAIKOU_UPSTREAM_STORAGE_DIR (or saikou.upstream.storage.dir "
                                        "in local.properties)\n")
        # Saikou alone gets past them to the next input, the emulator lock.
        result = self.compare20k(Machine(self), "saikou")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(result.stderr, "missing: SAIKOU_AVD_LOCKS and SAIKOU_AVD_BOOT\n")

    def test_compare20k_takes_the_upstream_apks_from_local_properties_else_the_cache(self):
        machine = Machine(self)
        apk = machine.root / "upstream.apk"
        apk.write_bytes(b"not the published release")
        machine.properties.write_text(f"saikou.upstream.package=org.example.upstream\n"
                                      f"saikou.upstream.storage.dir=ExampleUpstream\nsaikou.upstream.apk={apk}\n")
        result = self.compare20k(machine, "upstream-0.20.4")
        self.assertEqual((result.returncode, result.stderr), (2, f"{apk} is not the published upstream-0.20.4 APK\n"))
        result = self.compare20k(machine, "upstream-0.21.0")
        cached = machine.root / ".cache/saikou-baseline/apk/upstream-v0.21.0/upstream-x86_64-v0.21.0.apk"
        self.assertEqual((result.returncode, result.stderr), (2, f"missing APK for upstream-0.21.0: {cached}\n"))

    def test_compare20k_refuses_a_storage_folder_that_is_not_one_name(self):
        for folder in ("a/b", "..", "two words"):
            with self.subTest(folder=folder):
                result = self.compare20k(Machine(self), "saikou", SAIKOU_UPSTREAM_STORAGE_DIR=folder)
                self.assertEqual(result.returncode, 2)
                self.assertIn("SAIKOU_UPSTREAM_STORAGE_DIR is not one folder name", result.stderr)

    def test_require_localprop_exports_the_value_under_its_variable(self):
        machine = Machine(self, "saikou.upstream.package = org.example.upstream\n")
        script = (f'source "{TOOLS / "common" / "localprops.sh"}"\n'
                  "require_localprop saikou.upstream.package SAIKOU_UPSTREAM_PACKAGE\n"
                  'bash -c \'printf "%s\\n" "$SAIKOU_UPSTREAM_PACKAGE"\'\n')
        result = machine.run("bash", "-c", script)
        self.assertEqual((result.returncode, result.stdout), (0, "org.example.upstream\n"), result.stderr)


if __name__ == "__main__":
    unittest.main()
