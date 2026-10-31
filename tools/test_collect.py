#!/usr/bin/env python3
"""Unit tests for tools/bench/collect.py's `--instrumentation` pulls.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

collect.py runs as it does in the gate, as a process, against a stand-in `adb` placed where it looks
for the real one (`$ANDROID_SDK_ROOT/platform-tools/adb`). The stand-in answers `-s <serial> pull
<remote> <local>` the way adb does for a directory: it copies the device's `saikou-gate` directory
into the local one, or exits 1 when the device holds none. Every call is logged, so a test can see
which serials were pulled. Nothing needs a device.

The cases that matter, and why each one is here:
  - one flag, the shape every existing caller uses, still collects that device's fragments;
  - two flags pull two devices into two directories, and fragments with the same file name on both
    devices are both read, each once; a shared directory would overwrite one with the other and
    read the survivor twice;
  - a fragment left in a device's directory by an earlier collect is not read again;
  - a device with nothing published costs a warning and not the other device's rows;
  - no flag pulls nothing;
  - a blank serial and a repeated serial end the run (exit 2) instead of collecting.

And the compat report as the gate hands it over (PreviousRunTest): gate.sh's own `move_previous_run`
and step 8d's `--compat` line, read out of the script by text, then collect.py for real:
  - an earlier run's report, with no compat job in this run, gives no compat row; collect picks the
    latest report name under the directory, so without the move it published the earlier run's;
  - this run's report is the one read, even when the earlier run's name sorts after it.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
COLLECT = TOOLS / "bench" / "collect.py"
GATE_SH = TOOLS / "ci" / "gate.sh"
sys.path.insert(0, str(TOOLS))
from test_bench_launch import extract_function  # noqa: E402

FAKE_ADB = textwrap.dedent(
    """\
    #!/usr/bin/env python3
    import os, shutil, sys
    from pathlib import Path
    args = sys.argv[1:]
    with open(os.environ["FAKE_ADB_LOG"], "a") as log:
        log.write(" ".join(args) + "\\n")
    assert args[0] == "-s" and args[2] == "pull", args
    serial, remote, local = args[1], args[3], args[4]
    source = Path(os.environ["FAKE_ADB_DEVICES"]) / serial / Path(remote).name
    if not source.is_dir():
        print(f"adb: error: failed to stat remote object '{remote}': No such file or directory", file=sys.stderr)
        sys.exit(1)
    shutil.copytree(source, Path(local) / source.name, dirs_exist_ok=True)
    """
)


def fragment(row_id: str, device: str, value) -> dict:
    return {
        "id": row_id,
        "device": device,
        "value": value,
        "unit": "bool" if isinstance(value, bool) else "ms",
        "method": "test",
        "recordedAt": "2026-09-24T00:00:00Z",
    }


class CollectRun:
    """A throwaway workspace: a stand-in SDK, the devices it serves, and collect.py's output."""

    def __init__(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.root = Path(self._directory.name)
        self.sdk = self.root / "sdk"
        adb = self.sdk / "platform-tools" / "adb"
        adb.parent.mkdir(parents=True)
        adb.write_text(FAKE_ADB)
        adb.chmod(0o755)
        self.devices = self.root / "devices"
        self.devices.mkdir()
        self.bench = self.root / "out" / "bench"
        self.bench.mkdir(parents=True)
        self.out = self.root / "out" / "measured.json"
        self.log = self.root / "adb.log"
        self.log.touch()

    def close(self) -> None:
        self._directory.cleanup()

    def publish(self, serial: str, row: dict, name: str | None = None) -> None:
        directory = self.devices / serial / "saikou-gate"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / (name or f"{row['id']}.json")).write_text(json.dumps(row))

    def run(self, *extra: str) -> subprocess.CompletedProcess:
        env = dict(os.environ)
        env.pop("ANDROID_HOME", None)
        env["ANDROID_SDK_ROOT"] = str(self.sdk)
        env["FAKE_ADB_DEVICES"] = str(self.devices)
        env["FAKE_ADB_LOG"] = str(self.log)
        return subprocess.run(
            [sys.executable, str(COLLECT), "--bench", str(self.bench), "--subject", "com.shinsaikou:0.1.0:abc1234",
             "--devices", str(self.root / "no-baseline.json"), "--out", str(self.out), *extra],
            capture_output=True, text=True, check=False, env=env,
        )

    def rows(self) -> list[dict]:
        return json.loads(self.out.read_text())["rows"]

    def pulled_serials(self) -> list[str]:
        return [line.split()[1] for line in self.log.read_text().splitlines()]


class InstrumentationPullTest(unittest.TestCase):

    def setUp(self) -> None:
        self.work = CollectRun()
        self.addCleanup(self.work.close)

    def test_one_serial_still_collects_that_device(self):
        self.work.publish("emulator-5556", fragment("restore_3000_ms", "pixel9_4g", 4919))
        result = self.work.run("--instrumentation", "emulator-5556")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([(r["id"], r["device"], r["value"]) for r in self.work.rows()],
                         [("restore_3000_ms", "pixel9_4g", 4919)])
        self.assertEqual(self.work.pulled_serials(), ["emulator-5556"])

    def test_two_serials_land_in_two_directories_and_each_row_is_read_once(self):
        # The same file name on both devices, which is what a row published from two AVDs looks like.
        self.work.publish("emulator-5556", fragment("shared_row", "pixel9_4g", 1), name="shared_row.json")
        self.work.publish("emulator-5556", fragment("restore_3000_ms", "pixel9_4g", 4919))
        self.work.publish("emulator-5560", fragment("shared_row", "lowram_1_5g", 2), name="shared_row.json")
        self.work.publish("emulator-5560", fragment("low_ram_repro_pass", "lowram_1_5g", True))
        result = self.work.run("--instrumentation", "emulator-5556", "--instrumentation", "emulator-5560")
        self.assertEqual(result.returncode, 0, result.stderr)

        rows = sorted((r["id"], r["device"], r["value"]) for r in self.work.rows())
        self.assertEqual(rows, [
            ("low_ram_repro_pass", "lowram_1_5g", True),
            ("restore_3000_ms", "pixel9_4g", 4919),
            ("shared_row", "lowram_1_5g", 2),
            ("shared_row", "pixel9_4g", 1),
        ])
        self.assertEqual(self.work.pulled_serials(), ["emulator-5556", "emulator-5560"])
        instrumentation = self.work.root / "out" / "instrumentation"
        self.assertTrue((instrumentation / "emulator-5556" / "saikou-gate" / "shared_row.json").is_file())
        self.assertTrue((instrumentation / "emulator-5560" / "saikou-gate" / "shared_row.json").is_file())

    def test_a_fragment_left_by_an_earlier_collect_is_not_read_again(self):
        stale = self.work.root / "out" / "instrumentation" / "emulator-5560" / "saikou-gate"
        stale.mkdir(parents=True)
        (stale / "low_ram_repro_pass.json").write_text(json.dumps(fragment("low_ram_repro_pass", "lowram_1_5g", True)))
        self.work.publish("emulator-5560", fragment("other_row", "lowram_1_5g", 3))
        result = self.work.run("--instrumentation", "emulator-5560")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([r["id"] for r in self.work.rows()], ["other_row"])

    def test_a_device_with_nothing_published_does_not_cost_the_other_devices_rows(self):
        self.work.publish("emulator-5556", fragment("restore_3000_ms", "pixel9_4g", 4919))
        result = self.work.run("--instrumentation", "emulator-5560", "--instrumentation", "emulator-5556")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("warning: no instrumentation fragments on emulator-5560", result.stderr)
        self.assertEqual([r["id"] for r in self.work.rows()], ["restore_3000_ms"])

    def test_no_flag_pulls_nothing(self):
        self.work.publish("emulator-5556", fragment("restore_3000_ms", "pixel9_4g", 4919))
        result = self.work.run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.work.rows(), [])
        self.assertEqual(self.work.pulled_serials(), [])

    def test_a_blank_serial_ends_the_run(self):
        result = self.work.run("--instrumentation", "emulator-5556", "--instrumentation", "")
        self.assertEqual(result.returncode, 2)
        self.assertIn("needs a device serial", result.stderr)
        self.assertFalse(self.work.out.exists())
        self.assertEqual(self.work.pulled_serials(), [])

    def test_a_repeated_serial_ends_the_run(self):
        result = self.work.run("--instrumentation", "emulator-5556", "--instrumentation", "emulator-5556")
        self.assertEqual(result.returncode, 2)
        self.assertIn("more than once", result.stderr)
        self.assertFalse(self.work.out.exists())


def gate_line(start: str) -> str:
    """The one line of gate.sh that starts with <start> once indentation is dropped."""
    lines = [line.strip() for line in GATE_SH.read_text().splitlines() if line.strip().startswith(start)]
    if len(lines) != 1:
        raise AssertionError(f"gate.sh has {len(lines)} lines starting {start!r}, where the test reads one")
    return lines[0]


def compat_report(loaded: float) -> dict:
    return {"sampleSize": 10, "loadedRatio": loaded, "unresolvedJars": [], "ranAt": "2026-09-01T00:00:00Z",
            "replay": {"ran": True, "calls": [{"ok": True}]}}


class PreviousRunTest(unittest.TestCase):
    """Step 0's move and step 8d's collect, on a scratch tree with an earlier run's compat report."""

    EARLIER = "tools/out/compat/2026-09-30T00-00-00Z/report.json"
    THIS_RUN = "tools/out/compat/2026-09-25T06-00-00Z/report.json"

    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.write(self.EARLIER, compat_report(1.0))

    def write(self, relative: str, data: dict) -> None:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(data))

    def run_gate(self, this_run_report: dict | None = None) -> list[dict]:
        # Step 6 writes this run's report after step 0 has moved the earlier ones aside.
        compat_job = ""
        if this_run_report is not None:
            compat_job = f"mkdir -p {Path(self.THIS_RUN).parent} && echo '{json.dumps(this_run_report)}' > {self.THIS_RUN}"
        script = "\n".join([
            gate_line("BENCH_OUT="), gate_line("COMPAT_OUT="), gate_line("PREVIOUS_OUT="),
            extract_function(GATE_SH.read_text(), "move_previous_run"),
            "move_previous_run || exit 1",
            compat_job,
            'mkdir -p "$BENCH_OUT"',
            'collect_args=(--bench "$BENCH_OUT")',
            gate_line('[ -d "$COMPAT_OUT" ] && collect_args+=(--compat'),
            f'"$1" {COLLECT} "${{collect_args[@]}}" --subject com.shinsaikou:0.1.0:abc1234 '
            f'--devices no-baseline.json --out tools/out/measured.json',
        ])
        result = subprocess.run(["bash", "-c", script, "bash", sys.executable], cwd=self.root,
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads((self.root / "tools/out/measured.json").read_text())["rows"]

    def test_an_earlier_runs_report_is_not_collected_when_no_compat_job_ran(self):
        rows = self.run_gate()
        self.assertEqual([r for r in rows if r["id"].startswith("compat_")], [])
        self.assertFalse((self.root / "tools/out/compat").exists())
        self.assertTrue((self.root / "tools/out/previous" / self.EARLIER.removeprefix("tools/out/")).is_file())

    def test_this_runs_report_is_read_though_the_earlier_name_sorts_after_it(self):
        rows = self.run_gate(compat_report(0.5))
        values = {r["id"]: r["value"] for r in rows}
        self.assertEqual(values, {"compat_sample_loaded_ratio": 0.5, "compat_reference_runs_pass": True})


if __name__ == "__main__":
    unittest.main()
