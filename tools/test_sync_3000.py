#!/usr/bin/env python3
"""Unit tests for tools/bench/sync_3000.py, the sync_3000_no_oom row's runner on saikou_pixel8a_2g.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

adb is test_sync_pair.py's stand-in, with one device in role c; its `sync-3000` step publishes the
row's fragment on the stand-in device the way GateFragments.publish does, with the device id the
step was given. The sync fixture is the real one on a free port. Unit I's report records the device
run.

The cases that matter, and why each one is here:
  - a passing step's fragment is copied into --out as sync_3000_no_oom.pixel8a_2g.json in the step
    itself, after the app's data was cleared (an install -r keeps an earlier build's database, which
    is how the first device run failed) and the device's earlier copy removed, and before both
    packages are uninstalled; the step receives the device id and the fixture's sign-in, and no
    file the run wrote holds the password; the device's copy is gone afterwards, so step 8's pull of
    the device's gate directory never reads the row twice; a clear that fails is exit 1 before the
    step;
  - no run that fails leaves a fragment in --out (fix round 1 item 3: a failing runner once left the
    step's true fragment behind): a step that fails, whatever it published, true or false, one whose
    result line names another step or is missing, a fragment of another row or device, a step that
    passed with a false fragment, a device copy that stays after the pull and an earlier device
    fragment that cannot be removed are all exit 1 with --out empty, and a host fragment from an
    earlier run is removed first, so it never survives a failed run; a run that fails after the true
    fragment was copied (the uninstall raising) keeps none either, which only main's last clean-up
    does (unit H stage 2 fix round 1 item 4);
  - every run fails when a fixture it started is still running after it returned (test_sync_pair's
    run_checked);
  - a device that is not saikou_pixel8a_2g is refused before anything starts (exit 2), a missing
    serial is a usage error (exit 2), and a failed install is exit 3 with what was installed removed.
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
SCRIPT = TOOLS / "bench" / "sync_3000.py"
sys.path.insert(0, str(TOOLS))
from test_sync_pair import DIGEST, STUB, run_checked  # noqa: E402

_spec = importlib.util.spec_from_file_location("sync_3000", SCRIPT)
sync_3000 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sync_3000)

SERIAL = "emulator-5560"
FRAGMENT_NAME = "sync_3000_no_oom.pixel8a_2g.json"


class Single:
    """One sync_3000.py run against the stand-in adb, in a temporary directory."""

    def __init__(self, cleanup, step: dict | None = None, avd: str = "saikou_pixel8a_2g", **device: object):
        self.dir = Path(tempfile.mkdtemp(prefix="sync-3000-test-"))
        cleanup(shutil.rmtree, self.dir, True)
        self.state = self.dir / "state"
        self.state.mkdir()
        self.adb = self.dir / "adb"
        self.adb.write_text(STUB)
        self.adb.chmod(0o755)
        self.log = self.dir / "adb.log"
        self.plan = self.dir / "plan.json"
        self.plan.write_text(json.dumps({
            "devices": {SERIAL: {"role": "c", "avd": avd, "api": "36"}},
            "digest": DIGEST,
            "steps": {"c:sync-3000": step or {}},
            **device,
        }))
        self.apk = self.dir / "app-debug.apk"
        self.apk.write_bytes(b"debug app")
        self.test_apk = self.dir / "app-debug-androidTest.apk"
        self.test_apk.write_bytes(b"test app")
        self.out = self.dir / "out"
        self.work = self.dir / "work"

    def device_fragment(self) -> Path:
        return self.state / (SERIAL + sync_3000.DEVICE_FRAGMENT.replace("/", "_"))

    def run(self, *extra: str, serial: str = SERIAL) -> subprocess.CompletedProcess:
        env = dict(os.environ, STUB_ADB_PLAN=str(self.plan), STUB_ADB_LOG=str(self.log),
                   STUB_ADB_COUNTS=str(self.dir / "counts.json"), STUB_ADB_STATE=str(self.state))
        env.pop("SAIKOU_SERIAL_2G", None)
        return run_checked(
            [sys.executable, str(SCRIPT), "--serial-2g", serial, "--adb", str(self.adb), "--apk", str(self.apk),
             "--test-apk", str(self.test_apk), "--out", str(self.out), "--work", str(self.work), "--port", "0", *extra],
            env, self.dir, timeout=120)

    def calls(self) -> list[list[str]]:
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def kinds(self) -> list[str]:
        return [" ".join(call[2:4]) if call[2] == "shell" else call[2] for call in self.calls()]

    def fixture_log(self) -> list[dict]:
        path = self.work / "fixture.log"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


class PassTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.single = Single(cls.addClassCleanup)
        cls.answer = cls.single.run()

    def test_the_fragment_is_copied_into_out_under_the_row_and_device(self):
        self.assertEqual(self.answer.returncode, 0, self.answer.stdout + self.answer.stderr)
        self.assertEqual([p.name for p in self.single.out.iterdir()], [FRAGMENT_NAME])
        published = json.loads((self.single.out / FRAGMENT_NAME).read_text())
        self.assertEqual((published["id"], published["device"], published["value"]), ("sync_3000_no_oom", "pixel8a_2g", True))
        self.assertIn("MemTotal", published["method"])
        self.assertIn("OK (1 test)", (self.single.work / "sync-3000.txt").read_text())

    def test_the_device_copy_is_removed_before_the_step_and_pulled_after_it_between_install_and_uninstall(self):
        kinds = self.single.kinds()
        self.assertEqual(kinds[0], "emu")
        order = [kinds.index(kind) for kind in ("install", "shell pm", "shell rm", "shell am", "pull", "uninstall")]
        self.assertEqual(order, sorted(order))
        self.assertEqual(kinds.count("install"), 2)
        self.assertEqual(kinds.count("uninstall"), 2)
        self.assertGreater(min(i for i, kind in enumerate(kinds) if kind == "uninstall"), kinds.index("pull"))
        pull = [call for call in self.single.calls() if call[2] == "pull"][0]
        self.assertEqual(pull[3:], [sync_3000.DEVICE_FRAGMENT, str(self.single.out / FRAGMENT_NAME)])

    def test_the_device_copy_is_gone_after_the_run(self):
        self.assertFalse(self.single.device_fragment().exists())
        calls = self.single.calls()
        pull = [i for i, call in enumerate(calls) if call[2] == "pull"][0]
        self.assertIn(["shell", "rm", "-f", sync_3000.DEVICE_FRAGMENT], [call[2:] for call in calls[pull:]])

    def test_the_step_gets_the_device_id_and_the_sign_in_and_no_file_holds_the_password(self):
        instrument = [call for call in self.single.calls() if call[2:5] == ["shell", "am", "instrument"]]
        self.assertEqual(len(instrument), 1)
        call = instrument[0]
        self.assertEqual(call[call.index("saikou.sync.pair.step") + 1], "sync-3000")
        self.assertEqual(call[call.index("saikou.device.id") + 1], "pixel8a_2g")
        port = [line for line in self.single.fixture_log() if line.get("event") == "start"][0]["port"]
        self.assertEqual(call[call.index("saikou.sync.pair.url") + 1], f"http://10.0.2.2:{port}/dav/")
        password = call[call.index("saikou.sync.pair.password") + 1]
        for path in list(self.single.out.rglob("*")) + list(self.single.work.rglob("*")):
            if path.is_file():
                self.assertNotIn(password, path.read_text(), str(path))
        self.assertNotIn(password, self.answer.stdout + self.answer.stderr)
        self.assertEqual(self.single.fixture_log()[-1]["event"], "stop")


class FailureTest(unittest.TestCase):

    def ended(self, single: Single, answer, why: str, code: int = 1):
        self.assertEqual(answer.returncode, code, answer.stdout + answer.stderr)
        self.assertIn(why, answer.stderr)
        self.assertEqual(single.kinds().count("uninstall"), 2)
        self.assertEqual(single.fixture_log()[-1]["event"], "stop")

    def nothing_kept(self, single: Single):
        self.assertEqual(list(single.out.iterdir()), [])

    def test_a_failing_step_is_exit_1_and_no_fragment_it_published_is_kept(self):
        for value in (True, False):
            with self.subTest(value=value):
                single = Single(self.addCleanup, {"fail": True, "fragment": {"value": value}})
                answer = single.run()
                self.ended(single, answer, "the step did not pass")
                self.assertIn("the run did not pass, so no fragment is kept", answer.stderr)
                self.nothing_kept(single)

    def test_a_failure_after_the_true_fragment_was_copied_leaves_none_and_exits_1(self):
        # Unit H stage 2 fix round 1 item 4: the uninstall after the step raises (the stand-in
        # answers bytes that are not text), so the fragment is already in --out when the run fails,
        # and only main's last clean-up removes it.
        single = Single(self.addCleanup, uninstallUndecodable=True)
        answer = single.run()
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn("sync_3000_no_oom = true, copied to", answer.stdout)
        self.nothing_kept(single)
        self.assertIn("UnicodeDecodeError", answer.stderr)
        self.assertIn("the run did not pass, so no fragment is kept", answer.stderr)
        self.assertEqual(single.fixture_log()[-1]["event"], "stop")

    def test_a_result_line_naming_another_step_is_exit_1_and_its_true_fragment_is_not_kept(self):
        single = Single(self.addCleanup, {"result": {"step": "digest"}})
        self.ended(single, single.run(), "no saikou.sync.pair.result line naming sync-3000")
        self.nothing_kept(single)


    def test_a_step_that_published_nothing_is_exit_1_and_an_earlier_host_fragment_is_gone(self):
        single = Single(self.addCleanup, {"noFragment": True})
        single.out.mkdir(parents=True)
        (single.out / FRAGMENT_NAME).write_text('{"id": "sync_3000_no_oom", "device": "pixel8a_2g", "value": true}')
        self.ended(single, single.run(), "published None")
        self.assertEqual(list(single.out.iterdir()), [])

    def test_a_fragment_of_another_row_or_device_is_exit_1_and_not_kept(self):
        for fragment in ({"id": "restore_3000_oom_count"}, {"device": "pixel9_4g"}):
            with self.subTest(fragment=fragment):
                single = Single(self.addCleanup, {"fragment": fragment})
                self.ended(single, single.run(), "is not sync_3000_no_oom on pixel8a_2g")
                self.assertEqual(list(single.out.iterdir()), [])

    def test_a_step_that_passed_with_a_false_fragment_is_exit_1(self):
        single = Single(self.addCleanup, {"fragment": {"value": False}})
        self.ended(single, single.run(), "the step passed but published False")
        self.nothing_kept(single)

    def test_a_step_with_no_result_line_naming_it_is_exit_1(self):
        single = Single(self.addCleanup, {"noResult": True})
        self.ended(single, single.run(), "no saikou.sync.pair.result line naming sync-3000")
        self.nothing_kept(single)

    def test_an_earlier_device_fragment_that_stays_is_exit_1_before_the_step(self):
        single = Single(self.addCleanup, stuck=[sync_3000.DEVICE_FRAGMENT])
        single.device_fragment().write_text("{}")
        self.ended(single, single.run(), "an earlier fragment is still at")
        self.assertNotIn("shell am", single.kinds())

    def test_a_clear_that_fails_is_exit_1_before_the_step(self):
        single = Single(self.addCleanup, clearFails=True)
        self.ended(single, single.run(), "pm clear answered Failed")
        self.assertNotIn("shell am", single.kinds())

    def test_a_device_copy_that_stays_after_the_pull_is_exit_1_and_not_kept_on_the_host(self):
        # rm answers, but the file stays: the stand-in's device keeps it only once the step wrote it.
        single = Single(self.addCleanup, stuckAfterStep=sync_3000.DEVICE_FRAGMENT)
        self.ended(single, single.run(), "is still at /data/local/tmp/saikou-gate/sync_3000_no_oom.json after its copy")
        self.assertEqual(list(single.out.iterdir()), [])

    def test_a_device_that_is_not_the_2g_avd_is_refused_before_anything_starts(self):
        single = Single(self.addCleanup, avd="Pixel_9")
        answer = single.run()
        self.assertEqual(answer.returncode, 2, answer.stderr)
        self.assertIn("emulator-5560 is Pixel_9, not saikou_pixel8a_2g", answer.stderr)
        self.assertEqual(set(single.kinds()), {"emu"})
        self.assertEqual(single.fixture_log(), [])

    def test_a_missing_serial_is_a_usage_error(self):
        single = Single(self.addCleanup)
        answer = single.run(serial="")
        self.assertEqual(answer.returncode, 2)
        self.assertIn("missing: --serial-2g or SAIKOU_SERIAL_2G", answer.stderr)
        self.assertEqual(single.calls(), [])

    def test_a_failed_install_is_exit_3_with_what_was_installed_removed(self):
        single = Single(self.addCleanup, installFails=SERIAL)
        answer = single.run()
        self.ended(single, answer, "installing app-debug.apk on emulator-5560 failed", code=3)
        self.assertNotIn("shell am", single.kinds())


if __name__ == "__main__":
    unittest.main()
