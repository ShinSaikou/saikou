#!/usr/bin/env python3
"""Unit tests for step 7b's profile check: tools/ci/lowram_preflight.py, and gate.sh's
`avd_memory`, which reads what tools/avd/devices.env asks of the AVD (R36, R61).

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

The dumps are the three profiles this AVD has actually had, from units 8a, 8c and this one: API 36
raised to 2560 MB with a 192 MB growth limit (MemTotal 2531844 kB), API 28 at 1536 MB with the
shared 512 MB heap (MemTotal 1530644 kB), and API 28 at 1536 MB with its own 384 MB heap, which is
the one devices.env now describes. Only the last may pass. Nothing needs a device.

The cases that matter, and why each one is here:
  - the profile devices.env describes passes, and prints what it read;
  - a raised RAM floor fails on MemTotal even when the memory class happens to match, which is the
    API 36 profile against a devices.env heap of 192;
  - an AVD create-avds.sh has not recreated fails on the memory class, which is API 28 at 512;
  - the growth limit wins over the heap size when it is set, as it does in ActivityManager;
  - both failures are named together, with the AVD, the serial and create-avds.sh in the message;
  - a dump with no MemTotal or no heap property cannot be checked and is an input error, not a pass;
  - the tolerance is 10 percent either way of devices.env's RAM;
  - `avd_memory` reads the heap devices.env names, and the default for an entry that names none.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
# gate.sh's function is read out of the script by text, the way test_bench_launch reads common.sh's.
sys.path.insert(0, str(TOOLS))
from test_bench_launch import extract_function  # noqa: E402

_spec = importlib.util.spec_from_file_location("lowram_preflight", TOOLS / "ci" / "lowram_preflight.py")
lowram_preflight = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = lowram_preflight
_spec.loader.exec_module(lowram_preflight)

AVD = "saikou_lowram_1_5g"
SERIAL = "emulator-5554"


def meminfo(total_kb: int) -> str:
    return (f"MemTotal:        {total_kb} kB\nMemFree:          812344 kB\n"
            "MemAvailable:    1040876 kB\nSwapTotal:             0 kB\n")


def getprop(**props: str) -> str:
    lines = ["[dalvik.vm.dex2oat-Xms]: [64m]", "[ro.build.version.sdk]: [28]"]
    lines += [f"[{name.replace('_', '.')}]: [{value}]" for name, value in props.items()]
    return "\n".join(lines) + "\n"


API28_384 = (meminfo(1530644), getprop(dalvik_vm_heapsize="384m"))
API28_512 = (meminfo(1530644), getprop(dalvik_vm_heapsize="512m"))
API36_2560 = (meminfo(2531844), getprop(dalvik_vm_heapgrowthlimit="192m", dalvik_vm_heapsize="576m"))


def run(dumps: tuple[str, str], ram_mb: int = 1536, heap_mb: int = 384) -> tuple[int, str, str]:
    with tempfile.TemporaryDirectory() as directory:
        meminfo_path, getprop_path = Path(directory) / "meminfo.txt", Path(directory) / "getprop.txt"
        meminfo_path.write_text(dumps[0])
        getprop_path.write_text(dumps[1])
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = lowram_preflight.main([
                "--meminfo", str(meminfo_path), "--getprop", str(getprop_path), "--avd", AVD,
                "--serial", SERIAL, "--ram-mb", str(ram_mb), "--heap-mb", str(heap_mb),
            ])
        return code, out.getvalue(), err.getvalue()


class PreflightTest(unittest.TestCase):

    def test_the_profile_devices_env_describes_passes(self):
        code, out, err = run(API28_384)
        self.assertEqual(code, lowram_preflight.MATCHES, err)
        self.assertIn(f"{AVD} on {SERIAL}: MemTotal 1530644 kB, memory class 384 MB (dalvik.vm.heapsize 384m)", out)

    def test_a_raised_ram_floor_fails_on_mem_total_though_the_memory_class_matches(self):
        code, _, err = run(API36_2560, heap_mb=192)
        self.assertEqual(code, lowram_preflight.MISMATCH)
        self.assertIn("MemTotal is 2531844 kB, where devices.env's 1536 MB within 10% is 1415578 to 1730150 kB", err)
        self.assertNotIn("the memory class is", err)

    def test_an_avd_not_recreated_for_its_heap_fails_on_the_memory_class(self):
        code, _, err = run(API28_512)
        self.assertEqual(code, lowram_preflight.MISMATCH)
        self.assertIn("the memory class is 512 MB (dalvik.vm.heapsize 512m), where devices.env asks for 384", err)
        self.assertNotIn("MemTotal is", err)

    def test_both_failures_are_named_with_the_avd_the_serial_and_what_to_run(self):
        code, _, err = run(API36_2560)
        self.assertEqual(code, lowram_preflight.MISMATCH)
        self.assertIn(f"{AVD} on {SERIAL} is not the profile tools/avd/devices.env describes", err)
        self.assertIn("MemTotal is 2531844 kB", err)
        self.assertIn("the memory class is 192 MB (dalvik.vm.heapgrowthlimit 192m), where devices.env asks for 384", err)
        self.assertIn("Run tools/avd/create-avds.sh", err)

    def test_the_growth_limit_wins_over_the_heap_size_as_in_activity_manager(self):
        props = lowram_preflight.properties(API36_2560[1])
        self.assertEqual(lowram_preflight.memory_class_mb(props), (192, "dalvik.vm.heapgrowthlimit 192m"))

    def test_an_empty_growth_limit_falls_back_to_the_heap_size(self):
        props = lowram_preflight.properties(getprop(dalvik_vm_heapgrowthlimit="", dalvik_vm_heapsize="384m"))
        self.assertEqual(lowram_preflight.memory_class_mb(props), (384, "dalvik.vm.heapsize 384m"))

    def test_a_dump_without_mem_total_cannot_be_checked(self):
        code, out, err = run(("MemFree: 812344 kB\n", API28_384[1]))
        self.assertEqual(code, lowram_preflight.BAD_INPUT)
        self.assertEqual(out, "")
        self.assertIn("could not be read", err)
        self.assertIn("MemTotal unread kB", err)

    def test_a_dump_without_a_heap_property_cannot_be_checked(self):
        code, _, err = run((API28_384[0], getprop()))
        self.assertEqual(code, lowram_preflight.BAD_INPUT)
        self.assertIn("neither dalvik.vm.heapgrowthlimit nor dalvik.vm.heapsize is set", err)

    def test_a_heap_property_that_does_not_parse_cannot_be_checked(self):
        code, _, _ = run((API28_384[0], getprop(dalvik_vm_heapsize="lots")))
        self.assertEqual(code, lowram_preflight.BAD_INPUT)

    def test_the_tolerance_is_ten_percent_either_way(self):
        configured = 1536 * 1024
        for total, expected in ((int(configured * 0.9) + 1, lowram_preflight.MATCHES),
                                (int(configured * 1.1), lowram_preflight.MATCHES),
                                (int(configured * 0.9) - 1, lowram_preflight.MISMATCH),
                                (int(configured * 1.1) + 1, lowram_preflight.MISMATCH)):
            with self.subTest(total=total):
                self.assertEqual(run((meminfo(total), API28_384[1]))[0], expected)

    def test_a_missing_dump_is_an_input_error(self):
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            code = lowram_preflight.main([
                "--meminfo", "/nonexistent/meminfo.txt", "--getprop", "/nonexistent/getprop.txt", "--avd", AVD,
                "--serial", SERIAL, "--ram-mb", "1536", "--heap-mb", "384",
            ])
        self.assertEqual(code, lowram_preflight.BAD_INPUT)
        self.assertIn("missing: /nonexistent/meminfo.txt", err.getvalue())


AVD_MEMORY = extract_function((TOOLS / "ci" / "gate.sh").read_text(), "avd_memory")


def avd_memory(name: str, devices_env: Path | None = None) -> subprocess.CompletedProcess:
    """Runs gate.sh's `avd_memory` with HERE pointing at tools/ci, or at a scratch tree whose
    avd/devices.env is <devices_env>."""
    here = TOOLS / "ci"
    with tempfile.TemporaryDirectory() as directory:
        if devices_env is not None:
            (Path(directory) / "avd").mkdir()
            (Path(directory) / "ci").mkdir()
            (Path(directory) / "avd" / "devices.env").write_text(devices_env.read_text())
            here = Path(directory) / "ci"
        return subprocess.run(
            ["bash", "-c", f"{AVD_MEMORY}\navd_memory \"$1\"\n", "bash", name],
            env={"HERE": str(here), "PATH": "/usr/bin:/bin"},
            capture_output=True,
            text=True,
        )


class AvdMemoryTest(unittest.TestCase):

    def test_the_low_ram_avd_gets_its_own_heap(self):
        result = avd_memory(AVD)
        self.assertEqual((result.returncode, result.stdout), (0, "1536 384\n"), result.stderr)

    def test_an_entry_that_names_no_heap_gets_the_shared_one(self):
        for name, ram in (("saikou_pixel8a_2g", 2048), ("saikou_pixel9_4g", 4096), ("saikou_tablet_4g", 4096),
                          ("saikou_api26_2g", 2048)):
            with self.subTest(name=name):
                result = avd_memory(name)
                self.assertEqual((result.returncode, result.stdout), (0, f"{ram} 512\n"), result.stderr)

    def test_an_avd_devices_env_does_not_define_fails_naming_it(self):
        result = avd_memory("saikou_nowhere")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertIn("defines no AVD named saikou_nowhere", result.stderr)

    def test_the_default_heap_is_the_one_devices_env_sets(self):
        with tempfile.TemporaryDirectory() as directory:
            scratch = Path(directory) / "devices.env"
            scratch.write_text((TOOLS / "avd" / "devices.env").read_text()
                               .replace("SAIKOU_AVD_HEAP_MB=512", "SAIKOU_AVD_HEAP_MB=256"))
            result = avd_memory("saikou_pixel9_4g", scratch)
        self.assertEqual(result.stdout, "4096 256\n", result.stderr)


if __name__ == "__main__":
    unittest.main()
