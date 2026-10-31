#!/usr/bin/env python3
"""Unit tests for tools/avd/devices.env's optional VM heap field and how create-avds.sh writes it (R61 as amended on 2026-09-24).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The low RAM AVD names a VM heap of its own and the other four do not, so they keep the shared one
contract 01 section 7.4 pins for comparability. create-avds.sh runs here for real, against the real
devices.env, with ANDROID_SDK_ROOT pointing at a scratch tree that holds empty image directories and
no cmdline-tools, so it takes the path that writes each config itself. Nothing needs a device or an
SDK.

The cases that matter, and why each one is here:
  - every entry has seven fields or eight, and an eighth is a number of MB;
  - the low RAM AVD's config gets its own vm.heapSize and every other AVD the shared 512;
  - a second run changes no config byte;
  - an AVD whose vm.heapSize differs from devices.env is recreated, and the others are left alone,
    byte for byte.
"""

from __future__ import annotations

import re
import subprocess
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
DEVICES_ENV = TOOLS / "avd" / "devices.env"
CREATE_AVDS = TOOLS / "avd" / "create-avds.sh"

LOWRAM = "saikou_lowram_1_5g"


def entries() -> list[list[str]]:
    """SAIKOU_AVDS as bash sees it, one entry per line, split on the field separator."""
    result = subprocess.run(
        ["bash", "-c", 'source "$1"; printf "%s\\n" "${SAIKOU_AVDS[@]}"', "bash", str(DEVICES_ENV)],
        capture_output=True, text=True, check=True,
    )
    return [line.split("|") for line in result.stdout.splitlines()]


def default_heap() -> str:
    result = subprocess.run(
        ["bash", "-c", 'source "$1"; printf "%s" "$SAIKOU_AVD_HEAP_MB"', "bash", str(DEVICES_ENV)],
        capture_output=True, text=True, check=True,
    )
    return result.stdout


def heap_of(config: Path) -> list[str]:
    return re.findall(r"^vm\.heapSize=(.*)$", config.read_text(), re.MULTILINE)


class DevicesEnvTest(unittest.TestCase):

    def test_every_entry_has_seven_fields_or_a_numeric_eighth(self):
        for fields in entries():
            with self.subTest(name=fields[0]):
                self.assertIn(len(fields), (7, 8))
                if len(fields) == 8:
                    self.assertRegex(fields[7], r"^[0-9]+$")

    def test_only_the_low_ram_avd_names_a_heap_and_the_rest_share_512(self):
        self.assertEqual(default_heap(), "512")
        named = {fields[0]: fields[7] for fields in entries() if len(fields) == 8}
        self.assertEqual(set(named), {LOWRAM})


class CreateAvdsTest(unittest.TestCase):

    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        root = Path(self.scratch.name)
        self.sdk, self.avd_home = root / "sdk", root / "avd"
        for fields in entries():
            (self.sdk / fields[2].replace(";", "/")).mkdir(parents=True, exist_ok=True)
            # write_config ends on `[ -d "$skin" ] && ...`, which returns 1 under `set -e` when the
            # profile has no skin directory and ends the script, so the scratch SDK has one each.
            (self.sdk / "skins" / fields[1]).mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        self.scratch.cleanup()

    def create(self) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["bash", str(CREATE_AVDS)],
            env={"ANDROID_SDK_ROOT": str(self.sdk), "ANDROID_AVD_HOME": str(self.avd_home),
                 "HOME": self.scratch.name, "PATH": "/usr/bin:/bin"},
            capture_output=True, text=True,
        )

    def config(self, name: str) -> Path:
        return self.avd_home / f"{name}.avd" / "config.ini"

    def configs(self) -> dict[str, bytes]:
        return {fields[0]: self.config(fields[0]).read_bytes() for fields in entries()}

    def test_each_avd_gets_the_heap_devices_env_gives_it(self):
        result = self.create()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for fields in entries():
            with self.subTest(name=fields[0]):
                expected = fields[7] if len(fields) == 8 else "512"
                self.assertEqual(heap_of(self.config(fields[0])), [expected])
                self.assertIn(f"created: {fields[0]} ", result.stdout)

    def test_a_second_run_changes_nothing(self):
        self.assertEqual(self.create().returncode, 0)
        before = self.configs()
        result = self.create()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.configs(), before)
        self.assertNotIn("created:", result.stdout)

    def test_an_avd_whose_heap_changed_is_recreated_and_no_other(self):
        self.assertEqual(self.create().returncode, 0)
        lowram = self.config(LOWRAM)
        lowram.write_text(re.sub(r"^vm\.heapSize=.*$", "vm.heapSize=512", lowram.read_text(), flags=re.MULTILINE))
        (lowram.parent / "userdata-qemu.img").write_text("the old profile's data")
        before = self.configs()
        result = self.create()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        heap = next(fields[7] for fields in entries() if fields[0] == LOWRAM)
        self.assertIn(f"heap changed for {LOWRAM}: 512 to {heap}MB", result.stdout)
        self.assertIn(f"created: {LOWRAM} ", result.stdout)
        self.assertEqual(heap_of(lowram), [heap])
        after = self.configs()
        for name in before:
            if name != LOWRAM:
                with self.subTest(name=name):
                    self.assertEqual(after[name], before[name])


if __name__ == "__main__":
    unittest.main()
