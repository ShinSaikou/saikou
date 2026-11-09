#!/usr/bin/env python3
"""Unit tests for tools/bench/sync_pair.py, the two-device sync suite's orchestrator.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

adb is a stand-in (a small Python script written for each test) that logs every call and answers
as the two AVDs would, from a JSON plan the test writes: which AVD each serial is, and how any step
on any device answers (a failure, zero tests, no result line, other result values). The sync fixture
is the real tools/bench/sync_fixture_server.py on a free port, so its ready line, its reset through
the control path, its log and its stop are the real ones. Unit I's report records the device runs.

The cases that matter, and why each one is here:
  - a run where every step passes writes every scenario's row, true on `any`, in common.sh's
    fragment() shape (its keys read out of common.sh), each a row of rows.json with its unit; each
    device receives exactly its scenario steps in order, interleaved as SCENARIOS writes them, after
    both installs and before both uninstalls; every adb call names its serial; the fixture is reset
    once per scenario and stopped at the end; and no file the run wrote holds the password;
  - a step that fails, runs zero tests, prints no result line or one naming another step, leaves a
    row latched, misses an expectation, or a digest that disagrees (between the devices at the end, or with a digest a
    step saved) ends the run: its scenario's row and every later one are not written, an earlier
    fragment of the row is removed, no step runs after it, and both packages are still removed;
  - a serial that is not the AVD it must be (the owner's AVD, say) is refused before anything is
    started or installed, and so is a taken port;
  - a failed install ends the run with exit 3, the fixture stopped and what was installed removed;
  - missing inputs and one device named twice are usage errors;
  - no fixture outlives the run that started it: every run here fails when a fixture logging under
    its directory is still running after it returned, and stops it, and a run killed outright with
    SIGKILL takes its fixture with it through the parent-death signal;
  - killed_mid_apply's kill steps: the stand-in prints the kill line, writes R73's outcome file and
    waits for the host's `run-as com.shinsaikou kill -9 <pid>` on that device, and its export case puts
    four parts on the real fixture and reads the first before the kill. Its run writes no row. A
    kill step that never says it is ready, passes after the kill, leaves the process alive, leaves no
    outcome file or one naming another pid, or cannot remove an earlier one, ends the run, and so do a
    failed run-as, an after-kill step reading another pid, a part at or below the mark read again, a
    part above it never read, a mark that tests no resume, a run the stopped process finished before
    the kill, and a segment's rows written since the kill below the segment's size;
  - every step a scenario names is one SyncPairTest handles, and the class, argument, result key
    and skip line this script relies on are the ones in the Kotlin source;
  - new_entry_backlog (phase 4 unit NB) runs only when named, never in a run that names none, and
    writes no row; it passes on its expectations (one chapter queued, B's digest after the pull
    equal to A's) and fails on a queue of the back catalogue or a digest that differs; it is
    refused over Drive; its steps and its entry's size are SyncPairTest's and SyncPairBacklog's.
  - manual_sort_pair (phase 4 device coverage unit DC2) runs only when named, never in a run that
    names none, and writes no row; it passes on the dropped order coming back on B and fails when
    B's order differs or its digest after the pull is unlike A's; it is refused over Drive.
  - the sub-category scenarios (phase 5 unit CT, item 8) run in the gate's run and each writes its
    row (phase 5 unit TG3, item 3); each passes on both devices reporting the expected tree and fails
    when one device reports another (a flattened tree after the restore and join, item 7's defect,
    among them), writing no row; the race's expected tree puts the category with the lower sync key
    at the top, the key computed as CategorySyncKey computes it; the category names are
    SyncPairLibrary's; over Drive they run as evidence, as every row scenario does.
  - restore_then_join (phase 5 unit TG3, item 1) writes sync_restore_join_keeps_progress when every
    step passes, and nothing when a device's check fails or a further sync writes something; its
    backup's sizes are SyncPairRestore's.
  - edit_details_race (phase 5 unit ED) writes sync_edit_details_race when both devices show A's edit
    over B's new source text and then, after A's reset, the source's new text; a device that lost the
    edit to the refresh (the merge's defect) or still shows it after the reset writes no row; its
    texts are SyncPairEditDetails' and the seed's.
The stand-in answers each step with what its scenario expects of it (passing_answers), so a test
changes only the answer it is about.
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import shlex
import shutil
import signal
import socket
import subprocess
import time
import sys
import tempfile
import textwrap
import types
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
SCRIPT = TOOLS / "bench" / "sync_pair.py"
_spec = importlib.util.spec_from_file_location("sync_pair", SCRIPT)
sync_pair = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = sync_pair
_spec.loader.exec_module(sync_pair)
sys.path.insert(0, str(TOOLS))
from test_bench_launch import extract_function  # noqa: E402

PAIR_TEST_SOURCE = ROOT / "app" / "src" / "androidTest" / "kotlin" / "com" / "shinsaikou" / "sync" / "SyncPairTest.kt"
SERIAL_A, SERIAL_B = "emulator-5554", "emulator-5556"
DIGEST = "d" * 64

STUB = textwrap.dedent(r'''
    #!/usr/bin/env python3
    import base64, json, os, sys, time, urllib.request
    plan = json.load(open(os.environ["STUB_ADB_PLAN"]))
    state = os.environ["STUB_ADB_STATE"]
    args = sys.argv[1:]
    with open(os.environ["STUB_ADB_LOG"], "a") as log:
        log.write(json.dumps(args) + "\n")
    if len(args) < 3 or args[0] != "-s":
        print("stub adb: every call names its serial", file=sys.stderr)
        sys.exit(1)
    serial, rest = args[1], args[2:]
    device = plan["devices"].get(serial)
    if device is None:
        print(f"adb: device '{serial}' not found", file=sys.stderr)
        sys.exit(1)

    def remote(path):
        """A file on this device, kept in the state directory."""
        return os.path.join(state, serial + path.replace("/", "_"))

    def mine(name):
        return os.path.join(state, f"{serial}-{name}")

    def dav(method, path, body=None):
        """One request to the real fixture, as the app would make it, with the sign-in the kill step got."""
        sign_in = json.load(open(mine("sign-in.json")))
        request = urllib.request.Request(sign_in["url"].replace("10.0.2.2", "127.0.0.1") + path, data=body,
                                         method=method)
        token = base64.b64encode(f"{sign_in['user']}:{sign_in['password']}".encode()).decode()
        request.add_header("Authorization", "Basic " + token)
        with urllib.request.urlopen(request, timeout=10) as answer:
            answer.read()

    PARTS = "saikou-sync/v1/devices/stub-device-b/full-stub-export/"

    def emit(*lines):
        print("\r\n".join(lines), flush=True)

    if rest == ["emu", "avd", "name"]:
        print(device["avd"] + "\r\nOK\r")
    elif rest == ["shell", "getprop", "ro.build.version.sdk"]:
        print(device["api"] + "\r")
    elif rest[0] == "install":
        if plan.get("installFails") == serial:
            print("Performing Streamed Install\nadb: failed to install: INSTALL_FAILED_INSUFFICIENT_STORAGE")
            sys.exit(1)
        print("Performing Streamed Install\nSuccess")
    elif rest == ["shell", "pm", "list", "instrumentation"]:
        print("instrumentation:com.shinsaikou.test/androidx.test.runner.AndroidJUnitRunner (target=com.shinsaikou)\r")
    elif rest == ["shell", "pm", "clear", "com.shinsaikou"]:
        if plan.get("clearFails"):
            print("Failed\r")
            sys.exit(1)
        if os.path.exists(mine("seeded")):
            os.remove(mine("seeded"))
        print("Success\r")
    elif rest[0] == "uninstall":
        if plan.get("uninstallUndecodable"):
            # Bytes that are not UTF-8: the caller's subprocess.run(text=True) raises while reading
            # them, so the run fails after everything before its last clean-up has happened.
            sys.stdout.buffer.write(b"\xff\xfe not text\n")
            sys.exit(0)
        print("Success")
    elif rest[:3] == ["shell", "rm", "-f"]:
        for path in rest[3:]:
            stuck = plan.get("stuck", []) + ([plan["stuckAfterStep"]] if os.path.exists(mine("stepped")) else [])
            if os.path.exists(remote(path)) and path not in stuck:
                os.remove(remote(path))
    elif rest[:2] == ["shell", "ls"]:
        if os.path.exists(remote(rest[2])):
            print(rest[2] + "\r")
        else:
            print(f"ls: {rest[2]}: No such file or directory\r")
            sys.exit(1)
    elif rest[0] == "pull":
        if not os.path.exists(remote(rest[1])):
            print(f"adb: error: failed to stat remote object '{rest[1]}': No such file or directory", file=sys.stderr)
            sys.exit(1)
        open(rest[2], "wb").write(open(remote(rest[1]), "rb").read())
        print(f"{rest[1]}: 1 file pulled.")
    elif rest[:5] == ["shell", "run-as", "com.shinsaikou", "kill", "-9"]:
        if plan.get("killFails"):
            print("run-as: package not debuggable: com.shinsaikou\r")
            sys.exit(1)
        open(mine("killed-" + rest[5]), "w").close()
    elif rest == ["shell", "pidof", "com.shinsaikou"]:
        print(plan.get("pidof", "") + "\r")
    elif rest[:3] == ["shell", "am", "instrument"]:
        step = rest[rest.index("saikou.sync.pair.step") + 1]
        counts_path = os.environ["STUB_ADB_COUNTS"]
        counts = json.load(open(counts_path)) if os.path.exists(counts_path) else {}
        key = f"{device['role']}:{step}"
        counts[key] = counts.get(key, 0) + 1
        json.dump(counts, open(counts_path, "w"))
        answer = dict(plan["steps"].get(key, {}))
        answer.update(plan["steps"].get(f"{key}:{counts[key]}", {}))
        if answer.get("hang"):
            time.sleep(120)
        header = ["INSTRUMENTATION_STATUS: class=com.shinsaikou.sync.SyncPairTest",
                  "INSTRUMENTATION_STATUS: test=step", "INSTRUMENTATION_STATUS_CODE: 1"]
        passed = ["INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_RESULT: stream=", "", "Time: 1.2", "",
                  "OK (1 test)", "", "", "INSTRUMENTATION_CODE: -1"]
        if "saikou.sync.pair.url" in rest:
            json.dump({name: rest[rest.index(f"saikou.sync.pair.{name}") + 1] for name in ("url", "user", "password")},
                      open(mine("sign-in.json"), "w"))
        if step in ("kill-in-export", "kill-in-segment"):
            # SyncPairTest's kill steps: the outcome file, then one kill line, then nothing until the
            # host's run-as kill, after which the runner reports the crash as am instrument does.
            pid = str(4000 + sum(counts.values()))
            case = "export" if step == "kill-in-export" else "segment"
            fields = {"pid": pid, "case": case}
            if case == "export":
                # B's export of four parts, the first of which A read before it was stopped.
                folder = ""
                for part in PARTS.split("/")[:-1]:
                    folder += part + "/"
                    dav("MKCOL", folder)
                for part in range(1, 5):
                    dav("PUT", f"{PARTS}part-{part}.jsonl.gz", b"part")
                dav("GET", f"{PARTS}part-1.jsonl.gz")
                fields.update({"device": "stub-device-b", "export": "stub-export", "mark": "1"})
            fields.update(answer.get("kill", {}))
            outcome = {"schema": 1, "row": "killed_mid_apply", "package": "com.shinsaikou", "pid": int(pid),
                       "writtenAt": 1790398873239, "case": case}
            outcome.update(answer.get("outcome", {}))
            if not answer.get("noOutcome"):
                json.dump(outcome, open(remote("/data/local/tmp/saikou-force-stop/killed_mid_apply.json"), "w"))
            json.dump({"pid": pid, "case": case}, open(mine("last-kill.json"), "w"))
            emit(*header)
            if answer.get("noKillLine"):
                emit(*passed)
                sys.exit(0)
            emit("INSTRUMENTATION_STATUS: saikou.sync.pair.kill=" + " ".join(f"{k}={v}" for k, v in fields.items()),
                 "INSTRUMENTATION_STATUS_CODE: 2")
            deadline = time.monotonic() + 30
            while not os.path.exists(mine("killed-" + fields["pid"])) and time.monotonic() < deadline:
                time.sleep(0.02)
            if answer.get("passesAfterKill"):
                emit(*passed)
            else:
                emit("INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "INSTRUMENTATION_CODE: 0")
            sys.exit(0)
        result = {"step": step}
        if step == "seed":
            open(mine("seeded"), "w").close()
        if step in ("sign-in", "sync", "after-kill"):
            first = step == "sign-in"
            seeded = os.path.exists(mine("seeded"))
            result.update({"outcome": "success", "written": "0", "conflicts": "0",
                           "exported": "true" if first else "false",
                           "entriesBefore": "30" if seeded or not first else "0", "entriesAfter": "30"})
        if step == "after-kill":
            last = json.load(open(mine("last-kill.json")))
            if last["case"] == "export":
                for part in answer.get("resumed", [2, 3, 4]):
                    dav("GET", f"{PARTS}part-{part}.jsonl.gz")
                # A read whose log line lands after the step returned, as the fixture writes a line
                # after it answers: made from a child that lets go of the output first.
                if answer.get("lateRead") and os.fork() == 0:
                    os.close(1)
                    os.close(2)
                    time.sleep(1)
                    for part in answer["lateRead"]:
                        dav("GET", f"{PARTS}part-{part}.jsonl.gz")
                    os._exit(0)
            result.update({"case": last["case"], "pid": last["pid"], "exitReason": "signaled", "exitStatus": "9",
                           "runsBeforeKill": "0", "runsSinceKill": "1", "marks": "0",
                           "writtenSinceKill": "8545" if last["case"] == "export" else "4510"})
        if step == "bulk-chapters":
            result["added"] = "14640"
        if step == "bulk-read":
            result["marked"] = "4500"
        if step == "sync-3000":
            fragment = {"id": "sync_3000_no_oom", "device": rest[rest.index("saikou.device.id") + 1],
                        "value": True, "unit": "bool", "method": "stand-in: oom 0, MemTotal 2000000 kB"}
            fragment.update(answer.get("fragment", {}))
            if not answer.get("noFragment"):
                json.dump(fragment, open(remote("/data/local/tmp/saikou-gate/sync_3000_no_oom.json"), "w"))
            if plan.get("stuckAfterStep"):
                open(mine("stepped"), "w").close()
            result.update({"value": json.dumps(fragment["value"]), "oom": "0"})
        result.update({"digest": plan["digest"], "lines": "400", "latched": "0"})
        # What the scenario expects of this step, as a device that passed would answer; a test's own
        # answer for the step comes after it and wins.
        result.update(plan.get("defaults", {}).get(f"{key}:{counts[key]}", {}))
        result.update(answer.get("result", {}))
        lines = list(header)
        if not answer.get("noResult"):
            lines += ["INSTRUMENTATION_STATUS: saikou.sync.pair.result="
                      + " ".join(f"{k}={v}" for k, v in result.items()),
                      "INSTRUMENTATION_STATUS_CODE: 2"]
        if answer.get("fail"):
            lines += ["INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: the stand-in says no",
                      "INSTRUMENTATION_STATUS_CODE: -2", "INSTRUMENTATION_RESULT: stream=", "",
                      "FAILURES!!!", "Tests run: 1,  Failures: 1", "", "INSTRUMENTATION_CODE: -1"]
        else:
            tests = answer.get("tests", 1)
            lines += ["INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_RESULT: stream=", "", "Time: 1.2", "",
                      f"OK ({tests} test{'' if tests == 1 else 's'})", "", "", "INSTRUMENTATION_CODE: -1"]
        emit(*lines)
    else:
        print(f"stub adb: unexpected {rest}", file=sys.stderr)
        sys.exit(1)
''').lstrip()


def fixtures_under(directory: Path) -> list[int]:
    """The pids of sync_fixture_server.py processes whose --log is under [directory], read from /proc."""
    found = []
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        try:
            args = [arg.decode(errors="replace") for arg in (proc / "cmdline").read_bytes().split(b"\0") if arg]
        except OSError:
            continue
        if any(arg.endswith("sync_fixture_server.py") for arg in args) and "--log" in args[:-1]:
            if args[args.index("--log") + 1].startswith(str(directory)):
                found.append(int(proc.name))
    return found


def stop_leftover_fixtures(directory: Path) -> list[int]:
    """Stops every fixture logging under [directory] (SIGTERM, then SIGKILL after 5 s) and returns
    the pids it found: a run that returns has stopped its own, so any found here leaked."""
    leaked = fixtures_under(directory)
    for pid in leaked:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + 5
    while fixtures_under(directory) and time.monotonic() < deadline:
        time.sleep(0.05)
    for pid in fixtures_under(directory):
        os.kill(pid, signal.SIGKILL)
    return leaked


def run_checked(command: list[str], env: dict, directory: Path, timeout: float) -> subprocess.CompletedProcess:
    """Runs a script that starts a fixture, and fails when a fixture it started is still running
    after it returned. A run past [timeout] gets SIGTERM first, so its own clean-up stops the
    fixture, and SIGKILL 30 s later; a fixture left either way is stopped and the run fails. Output
    goes through files, not pipes: a leaked fixture would hold a pipe open and the read never end."""
    with tempfile.TemporaryFile("w+") as stdout, tempfile.TemporaryFile("w+") as stderr:
        process = subprocess.Popen(command, stdout=stdout, stderr=stderr, text=True, env=env)
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.send_signal(signal.SIGTERM)
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            leaked = stop_leftover_fixtures(directory)
            raise AssertionError(f"{Path(command[1]).name} did not end in {timeout} s; fixtures left: {leaked}") from None
        leaked = stop_leftover_fixtures(directory)
        if leaked:
            raise AssertionError(f"{Path(command[1]).name} returned with fixture processes {leaked} still running")
        stdout.seek(0)
        stderr.seek(0)
        return subprocess.CompletedProcess(command, process.returncode, stdout.read(), stderr.read())


def fragment_keys() -> list[str]:
    """The keys tools/bench/common.sh's fragment() writes, in its order."""
    body = extract_function((TOOLS / "bench" / "common.sh").read_text(), "fragment")
    keys = re.findall(r'^\s*"(\w+)":', body, re.MULTILINE)
    if not keys:
        raise AssertionError("common.sh's fragment() writes no key this test can read")
    return keys


class Pair:
    """One orchestrator run against the stand-in adb, in a temporary directory."""

    def __init__(self, cleanup, steps: dict | None = None, avd_a: str = "saikou_pixel9_4g",
                 install_fails: str | None = None, **device: object):
        """device: how the stand-in's devices behave beyond their steps (stuck, killFails, pidof)."""
        self.dir = Path(tempfile.mkdtemp(prefix="sync-pair-test-"))
        cleanup(shutil.rmtree, self.dir, True)
        self.state = self.dir / "state"
        self.state.mkdir()
        self.adb = self.dir / "adb"
        self.adb.write_text(STUB)
        self.adb.chmod(0o755)
        self.log = self.dir / "adb.log"
        self.plan = self.dir / "plan.json"
        self.plan.write_text(json.dumps({
            "devices": {
                SERIAL_A: {"role": "a", "avd": avd_a, "api": "36"},
                SERIAL_B: {"role": "b", "avd": "saikou_api26_2g", "api": "26"},
            },
            "digest": DIGEST,
            "steps": steps or {},
            "installFails": install_fails,
            **device,
        }))
        self.apk = self.dir / "app-debug.apk"
        self.apk.write_bytes(b"debug app")
        self.test_apk = self.dir / "app-debug-androidTest.apk"
        self.test_apk.write_bytes(b"test app")
        self.out = self.dir / "out"
        self.work = self.dir / "work"

    def run(self, *extra: str, port: int | None = None) -> subprocess.CompletedProcess:
        plan = json.loads(self.plan.read_text())
        plan["defaults"] = passing_answers(chosen(extra))
        self.plan.write_text(json.dumps(plan))
        return run_checked(self.command(*extra, port=port), self.env(), self.dir, timeout=300)

    def env(self) -> dict:
        return dict(os.environ, STUB_ADB_PLAN=str(self.plan), STUB_ADB_LOG=str(self.log),
                    STUB_ADB_COUNTS=str(self.dir / "counts.json"), STUB_ADB_STATE=str(self.state))

    def command(self, *extra: str, port: int | None = None) -> list[str]:
        return [sys.executable, str(SCRIPT), "--serial-4g", SERIAL_A, "--serial-api26", SERIAL_B,
                "--adb", str(self.adb), "--apk", str(self.apk), "--test-apk", str(self.test_apk),
                "--out", str(self.out), "--work", str(self.work), "--port", str(port if port is not None else 0), *extra]

    def calls(self) -> list[list[str]]:
        if not self.log.exists():
            return []
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def steps_run(self) -> list[tuple[str, str]]:
        """(role, step) for every am instrument, and (role, pm-clear) for every clear, in order."""
        roles = {SERIAL_A: "a", SERIAL_B: "b"}
        found = []
        for call in self.calls():
            rest = call[2:]
            if rest[:3] == ["shell", "am", "instrument"]:
                found.append((roles[call[1]], rest[rest.index("saikou.sync.pair.step") + 1]))
            elif rest == ["shell", "pm", "clear", "com.shinsaikou"]:
                found.append((roles[call[1]], sync_pair.CLEAR))
        return found

    def fragment(self, row: str) -> Path:
        return self.out / f"{row}.any.json"

    def fixture_log(self) -> list[dict]:
        path = self.work / "fixture.log"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def chosen(extra: tuple[str, ...]) -> list:
    """The scenarios a run with [extra] arguments runs, in order, as sync_pair.main chooses them."""
    names = [extra[i + 1] for i, arg in enumerate(extra[:-1]) if arg == "--scenario"]
    drive = any(arg == "--backend" and extra[i + 1] == sync_pair.DRIVE for i, arg in enumerate(extra[:-1]))
    scenarios = [s for s in sync_pair.SCENARIOS if not names or s.name in names]
    scenarios += [s for s in sync_pair.NAMED_ONLY if names and s.name in names]
    return [s for s in scenarios if s.row] if drive else scenarios


def passing_answers(scenarios) -> dict[str, dict[str, str]]:
    """Each step's expectations, keyed `role:step:n` for its n-th run in the whole run, as the
    stand-in's answer of a device that passed: the stand-in's own answers cover the JOIN, the kill
    and the sizes, and these cover every value a scenario names, a restore's counts and a tree's
    shape among them."""
    counts: dict[str, int] = {}
    answers = {}
    for scenario in scenarios:
        for step in scenario.steps:
            if step.name == sync_pair.CLEAR:
                continue
            key = f"{step.device}:{step.name}"
            counts[key] = counts.get(key, 0) + 1
            if step.expect:
                answers[f"{key}:{counts[key]}"] = dict(step.expect)
    return answers


def expected_steps(scenarios) -> list[tuple[str, str]]:
    found = []
    for scenario in scenarios:
        found += [("a", sync_pair.CLEAR), ("b", sync_pair.CLEAR)]
        found += [(step.device, step.name) for step in scenario.steps]
    return found


class FullRunTest(unittest.TestCase):
    """Every step passes: every row, the order, the installs, the fixture and the password."""

    @classmethod
    def setUpClass(cls):
        cls.pair = Pair(cls.addClassCleanup)
        cls.answer = cls.pair.run()

    def test_every_scenario_passes_and_writes_its_row_true_on_any_in_the_fragment_shape(self):
        self.assertEqual(self.answer.returncode, 0, self.answer.stderr)
        rows = {r["id"]: r for r in json.loads((TOOLS / "gate" / "rows.json").read_text())["rows"]}
        self.assertEqual([s.row for s in sync_pair.SCENARIOS], [
            "sync_read_and_bookmark_survive", "sync_rename_no_duplicate", "sync_deleted_stays_deleted",
            "sync_fresh_device_categories", "sync_restore_join_keeps_progress", "sync_subcategory_race_one_tree",
            "sync_subcategory_orphan_top_level", "sync_subcategory_restore_keeps_tree", "sync_edit_details_race", None])
        self.assertEqual(sorted(path.name for path in self.pair.out.iterdir()),
                         sorted(f"{s.row}.any.json" for s in sync_pair.SCENARIOS if s.row))
        self.assertIn("scenario killed_mid_apply passed; it writes no row", self.answer.stdout)
        for scenario in (s for s in sync_pair.SCENARIOS if s.row):
            with self.subTest(row=scenario.row):
                published = json.loads(self.pair.fragment(scenario.row).read_text())
                self.assertEqual(list(published), fragment_keys())
                self.assertEqual((published["id"], published["device"], published["value"], published["unit"]),
                                 (scenario.row, "any", True, "bool"))
                self.assertIn(f"scenario {scenario.name}", published["method"])
                self.assertIn("saikou_pixel9_4g (API 36, emulator-5554)", published["method"])
                self.assertIn("saikou_api26_2g (API 26, emulator-5556)", published["method"])
                self.assertIn(DIGEST[:12], published["method"])
                self.assertIn(scenario.row, rows)
                self.assertEqual((rows[scenario.row]["unit"], rows[scenario.row]["devices"]), ("bool", ["any"]))

    def test_each_device_receives_its_steps_in_the_scenarios_order_between_install_and_uninstall(self):
        self.assertEqual(self.pair.steps_run(), expected_steps(sync_pair.SCENARIOS))
        calls = self.pair.calls()
        for serial in (SERIAL_A, SERIAL_B):
            with self.subTest(serial=serial):
                mine = [call[2:] for call in calls if call[1] == serial]
                kinds = [rest[0] if rest[0] != "shell" else " ".join(rest[1:3]) for rest in mine]
                installs = [i for i, kind in enumerate(kinds) if kind == "install"]
                uninstalls = [i for i, kind in enumerate(kinds) if kind == "uninstall"]
                instruments = [i for i, kind in enumerate(kinds) if kind == "am instrument"]
                self.assertEqual(len(installs), 2)
                self.assertEqual([mine[i][-1] for i in installs], [str(self.pair.apk), str(self.pair.test_apk)])
                self.assertEqual([mine[i][1] for i in uninstalls], ["com.shinsaikou.test", "com.shinsaikou"])
                self.assertLess(max(installs), min(instruments))
                self.assertGreater(min(uninstalls), max(instruments))
                self.assertEqual(kinds[0], "emu")

    def test_every_adb_call_names_one_of_the_two_serials(self):
        calls = self.pair.calls()
        self.assertTrue(calls)
        for call in calls:
            self.assertEqual(call[0], "-s")
            self.assertIn(call[1], (SERIAL_A, SERIAL_B))

    def test_the_fixture_is_reset_once_per_scenario_and_stopped(self):
        log = self.pair.fixture_log()
        self.assertEqual(log[0]["event"], "start")
        self.assertEqual(log[-1]["event"], "stop")
        resets = [line for line in log if line.get("path") == "/_fixture/reset"]
        self.assertEqual(len(resets), len(sync_pair.SCENARIOS))
        self.assertTrue(all(line["status"] == 200 for line in resets))

    def test_the_sign_in_reaches_the_fixture_from_the_emulator_and_no_written_file_holds_the_password(self):
        sign_ins = [call for call in self.pair.calls() if "saikou.sync.pair.url" in call]
        self.assertEqual([call[call.index("saikou.sync.pair.step") + 1] for call in sign_ins],
                         [step.name for s in sync_pair.SCENARIOS for step in s.steps if step.name in sync_pair.SIGN_IN_STEPS])
        passwords = set()
        for call in sign_ins:
            url = call[call.index("saikou.sync.pair.url") + 1]
            port = [line for line in self.pair.fixture_log() if line.get("event") == "start"][0]["port"]
            self.assertEqual(url, f"http://10.0.2.2:{port}/dav/")
            passwords.add(call[call.index("saikou.sync.pair.password") + 1])
        self.assertEqual(len(passwords), 1)
        password = passwords.pop()
        # 128 bits of hex: a value that began with "-" would read as a flag to the fixture's argparse.
        self.assertRegex(password, r"^[0-9a-f]{32}$")
        for path in list(self.pair.out.rglob("*")) + list(self.pair.work.rglob("*")):
            if path.is_file():
                self.assertNotIn(password, path.read_text(), str(path))
        self.assertNotIn(password, self.answer.stdout + self.answer.stderr)

    def test_each_step_output_is_kept_in_the_work_directory(self):
        race = sorted(path.name for path in (self.pair.work / "race").iterdir())
        self.assertEqual(race[0], "01-a-seed.txt")
        self.assertIn("OK (1 test)", (self.pair.work / "race" / "01-a-seed.txt").read_text())


class FailureTest(unittest.TestCase):

    def assertEnded(self, pair: Pair, answer, failed: str, written: list[str]):
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"scenario {failed} failed", answer.stderr)
        for scenario in (s for s in sync_pair.SCENARIOS if s.row):
            with self.subTest(row=scenario.row):
                self.assertEqual(pair.fragment(scenario.row).exists(), scenario.row in written)
        kinds = [call[2] for call in pair.calls()]
        self.assertEqual(kinds.count("uninstall"), 4, "both packages from both devices")
        self.assertEqual(pair.fixture_log()[-1]["event"], "stop")

    def test_a_failing_step_ends_the_run_with_no_row_for_its_scenario_or_any_after_it(self):
        pair = Pair(self.addCleanup, steps={"b:rename-check": {"fail": True}})
        answer = pair.run()
        self.assertEnded(pair, answer, "rename", ["sync_read_and_bookmark_survive"])
        # Nothing runs after the failing step.
        ran = pair.steps_run()
        self.assertEqual(ran[-1], ("b", "rename-check"))
        self.assertIn("rename step 9 (rename-check on saikou_api26_2g)", answer.stderr)

    def test_zero_tests_is_a_failure(self):
        pair = Pair(self.addCleanup, steps={"a:race-check": {"tests": 0}})
        self.assertEnded(pair, pair.run(), "race", [])

    def test_a_step_with_no_result_line_is_a_failure(self):
        pair = Pair(self.addCleanup, steps={"a:race-read": {"noResult": True}})
        answer = pair.run()
        self.assertEnded(pair, answer, "race", [])
        self.assertIn("no saikou.sync.pair.result line", answer.stderr)

    def test_a_result_line_naming_another_step_is_a_failure(self):
        # A result line from some other step (a stale one, say) is not this step's answer.
        pair = Pair(self.addCleanup, steps={"b:race-bookmark": {"result": {"step": "digest"}}})
        answer = pair.run()
        self.assertEnded(pair, answer, "race", [])
        self.assertIn("the result line names step 'digest'", answer.stderr)

    def test_a_latched_row_is_a_failure(self):
        pair = Pair(self.addCleanup, steps={"b:sync:1": {"result": {"latched": "3"}}})
        answer = pair.run()
        self.assertEnded(pair, answer, "race", [])
        self.assertIn("3 rows still have is_syncing raised", answer.stderr)

    def test_an_expectation_not_met_is_a_failure(self):
        # JOIN's last step: A's second run must write nothing, since B's export is the same library.
        pair = Pair(self.addCleanup, steps={"a:sync:1": {"result": {"written": "2"}}})
        answer = pair.run("--scenario", "rename")
        self.assertEnded(pair, answer, "rename", [])
        self.assertIn("written is '2', where the scenario expects '0'", answer.stderr)

    def test_devices_that_end_on_different_digests_did_not_converge(self):
        pair = Pair(self.addCleanup, steps={"b:delete-check:2": {"result": {"digest": "e" * 64}}})
        answer = pair.run("--scenario", "delete")
        self.assertEnded(pair, answer, "delete", [])
        self.assertIn("did not converge", answer.stderr)

    def test_a_digest_that_differs_from_the_one_a_step_saved_is_a_failure(self):
        # fresh_device_joins: B's join after its data is cleared must reach A's library as it stood.
        pair = Pair(self.addCleanup, steps={"b:sign-in:2": {"result": {"digest": "e" * 64}}})
        answer = pair.run("--scenario", "fresh_device_joins")
        self.assertEnded(pair, answer, "fresh_device_joins", [])
        self.assertIn("is not a-before-join's", answer.stderr)

    def test_an_earlier_fragment_of_a_failed_row_is_removed(self):
        pair = Pair(self.addCleanup, steps={"a:race-check": {"fail": True}})
        pair.out.mkdir(parents=True)
        pair.fragment("sync_read_and_bookmark_survive").write_text('{"id": "sync_read_and_bookmark_survive", "value": true}')
        self.assertEnded(pair, pair.run(), "race", [])

    def test_one_scenario_runs_alone_from_its_join(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--scenario", "delete")
        self.assertEqual(answer.returncode, 0, answer.stderr)
        delete = [s for s in sync_pair.SCENARIOS if s.name == "delete"]
        self.assertEqual(pair.steps_run(), expected_steps(delete))
        self.assertEqual([p.name for p in pair.out.iterdir()], ["sync_deleted_stays_deleted.any.json"])


class KillTest(unittest.TestCase):
    """killed_mid_apply: the kill, the outcome file, the process gone and the resume after the mark."""

    KILLED = "killed_mid_apply"

    def killed(self, answer, pair: Pair, why: str):
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"scenario {self.KILLED} failed, so the run ends", answer.stderr)
        self.assertIn(why, answer.stderr)
        self.assertEqual([call[2] for call in pair.calls()].count("uninstall"), 4)
        self.assertEqual(pair.fixture_log()[-1]["event"], "stop")
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_both_kills_land_on_the_pid_each_step_named_on_device_a_and_the_export_resumes_after_its_mark(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--scenario", self.KILLED)
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        calls = pair.calls()
        kills = [call for call in calls if call[2:6] == ["shell", "run-as", "com.shinsaikou", "kill"]]
        self.assertEqual([call[1] for call in kills], [SERIAL_A, SERIAL_A])
        pids = re.findall(r"stopped process (\d+) with kill -9", answer.stdout)
        self.assertEqual([call[-1] for call in kills], pids)
        self.assertEqual(len(set(pids)), 2)
        # Each kill step first removes an earlier outcome file and then pulls the one it wrote.
        outcome = sync_pair.KILL_OUTCOME
        a = [call[2:] for call in calls if call[1] == SERIAL_A]
        self.assertEqual([rest for rest in a if outcome in rest and rest[0] != "shell" or rest[:2] == ["shell", "rm"]],
                         [["shell", "rm", "-f", outcome], ["pull", outcome, str(pair.work / self.KILLED / "04-a-kill-in-export.outcome.pulled")],
                          ["shell", "rm", "-f", outcome], ["pull", outcome, str(pair.work / self.KILLED / "10-a-kill-in-segment.outcome.pulled")]])
        self.assertIn("export stub-exp of 4 parts, killed at mark 1; read after the kill: parts 2, 3, 4; "
                      "none at or below the mark", answer.stdout)
        self.assertIn("INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
                      (pair.work / self.KILLED / "04-a-kill-in-export.txt").read_text())

    def test_a_read_whose_log_line_lands_after_the_step_returned_is_waited_for(self):
        pair = Pair(self.addCleanup, steps={"a:after-kill:1": {"resumed": [2, 3], "lateRead": [4]}})
        answer = pair.run("--scenario", self.KILLED)
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        self.assertIn("killed at mark 1; read after the kill: parts 2, 3, 4; none at or below the mark", answer.stdout)

    def test_the_reads_after_the_kill_are_listed_in_the_order_the_fixture_received_them(self):
        # The fixture writes a request's line after it answers, so a later read's line can land first:
        # under load this test's scenario once printed parts 2, 4, 3 (phase 4 unit GH). t0 is receipt.
        directory = Path(tempfile.mkdtemp(prefix="sync-pair-test-"))
        self.addCleanup(shutil.rmtree, directory, True)
        log = directory / "fixture.log"
        path = "/dav/saikou/devices/stub-device-b/full-stub-export/part-{}.jsonl.gz"
        lines = [{"t0": 1.0, "method": "PUT", "status": 201, "path": path.format(part)} for part in (1, 2, 3, 4)]
        lines += [{"t0": 5.0 + part, "method": "GET", "status": 200, "path": path.format(part)} for part in (2, 4, 3)]
        log.write_text("".join(json.dumps(line) + "\n" for line in lines))
        run = types.SimpleNamespace(fixture=types.SimpleNamespace(log=log))
        killed = {"device": "stub-device-b", "export": "stub-export", "mark": "1", "killedAt": "4.0"}
        self.assertEqual(sync_pair.Run.check_resumed_parts(run, killed),
                         "export stub-exp of 4 parts, killed at mark 1; read after the kill: parts 2, 3, 4; "
                         "none at or below the mark")

    def test_a_kill_step_that_never_says_it_is_ready_and_passes_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:kill-in-export": {"noKillLine": True}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "the step never said it was ready to be stopped, and passed")

    def test_a_kill_step_that_passes_after_the_kill_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:kill-in-segment": {"passesAfterKill": True}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "the step passed after its process was stopped")

    def test_a_kill_line_without_a_pid_ends_the_run_before_any_kill(self):
        pair = Pair(self.addCleanup, steps={"a:kill-in-export": {"kill": {"pid": "none"}}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "the kill line names no pid")
        self.assertNotIn("run-as", {call[3] for call in pair.calls() if len(call) > 3})

    def test_a_failed_run_as_kill_ends_the_run(self):
        pair = Pair(self.addCleanup, killFails=True)
        self.killed(pair.run("--scenario", self.KILLED), pair, "run-as kill -9")

    def test_a_process_still_alive_after_the_kill_ends_the_run(self):
        # pid 4000 plus the steps run so far: the export's kill is the fourth step on either device.
        pair = Pair(self.addCleanup, pidof="4004 999")
        self.killed(pair.run("--scenario", self.KILLED), pair, "process 4004 is still alive after kill -9")

    def test_no_outcome_file_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:kill-in-export": {"noOutcome": True}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "no readable outcome file")

    def test_an_outcome_file_of_another_pid_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:kill-in-segment": {"outcome": {"pid": 1}}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "the outcome file names pid 1 of killed_mid_apply")

    def test_an_earlier_outcome_file_that_stays_ends_the_run_before_the_step(self):
        pair = Pair(self.addCleanup, stuck=[sync_pair.KILL_OUTCOME])
        (pair.state / (SERIAL_A + sync_pair.KILL_OUTCOME.replace("/", "_"))).write_text("{}")
        self.killed(pair.run("--scenario", self.KILLED), pair, "an earlier outcome file is still at")
        self.assertNotIn(("a", "kill-in-export"), pair.steps_run())

    def test_an_after_kill_step_that_reads_another_pid_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:after-kill:1": {"result": {"pid": "1"}}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "it read pid 1 (export), where the kill stopped 4004")

    def test_a_part_at_or_below_the_mark_read_again_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:after-kill:1": {"resumed": [1, 2, 3, 4]}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "parts [1] at or below the mark 1 were read again")

    def test_a_part_above_the_mark_never_read_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:after-kill:1": {"resumed": [2, 3]}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "parts [4] above the mark 1 were not read")

    def test_a_mark_that_tests_no_resume_ends_the_run(self):
        for mark in ("0", "4"):
            with self.subTest(mark=mark):
                pair = Pair(self.addCleanup, steps={"a:kill-in-export": {"kill": {"mark": mark}}})
                self.killed(pair.run("--scenario", self.KILLED), pair, f"the kill left mark {mark} of 4 parts")

    def test_a_segment_rewritten_less_than_whole_after_the_kill_ends_the_run(self):
        # Fewer rows written since the kill than the segment holds: the kill landed after the commit.
        pair = Pair(self.addCleanup, steps={"a:after-kill:2": {"result": {"writtenSinceKill": "4499"}}})
        self.killed(pair.run("--scenario", self.KILLED), pair,
                    "writtenSinceKill is '4499', where the scenario expects at least 4500")

    def test_a_run_the_stopped_process_finished_before_the_kill_ends_the_run(self):
        # R29 proof D1 on the devices: a kill after the commit, whose run reported before it.
        pair = Pair(self.addCleanup, steps={"a:after-kill:2": {"result": {"runsBeforeKill": "1"}}})
        self.killed(pair.run("--scenario", self.KILLED), pair,
                    "runsBeforeKill is '1', where the scenario expects '0'")

    def test_an_exit_that_was_not_the_signal_ends_the_run(self):
        pair = Pair(self.addCleanup, steps={"a:after-kill:1": {"result": {"exitReason": "user-requested"}}})
        self.killed(pair.run("--scenario", self.KILLED), pair, "exitReason is 'user-requested', where the scenario expects 'signaled'")


class DriveBackendTest(unittest.TestCase):
    """--backend drive (phase 3 unit H): every row scenario over the fixture's Google Drive, as
    evidence: every step names the backend and the fixture's Google address, no WebDAV sign-in is
    sent, and --out is neither cleared nor written."""

    ROW_SCENARIOS = [s for s in sync_pair.SCENARIOS if s.row]

    @classmethod
    def setUpClass(cls):
        cls.pair = Pair(cls.addClassCleanup)
        cls.pair.out.mkdir()
        cls.earlier = cls.pair.fragment(cls.ROW_SCENARIOS[0].row)
        cls.earlier.write_text('{"value": true, "method": "an earlier WebDAV run"}\n')
        cls.answer = cls.pair.run("--backend", "drive")

    def test_the_row_scenarios_pass_and_no_fragment_is_removed_or_written(self):
        self.assertEqual(self.answer.returncode, 0, self.answer.stderr)
        self.assertEqual(self.pair.steps_run(), expected_steps(self.ROW_SCENARIOS))
        self.assertEqual([path.name for path in self.pair.out.iterdir()], [self.earlier.name])
        self.assertIn("an earlier WebDAV run", self.earlier.read_text())
        # Left out of the default run, and said so once, before anything else.
        self.assertEqual(self.answer.stdout.splitlines()[0],
                         "sync_pair: killed_mid_apply is left out over Drive: its check reads WebDAV part paths "
                         "from the fixture's log")
        self.assertEqual(self.answer.stdout.count("killed_mid_apply"), 1)
        self.assertFalse({"kill-in-export", "kill-in-segment", "after-kill"} & {name for _, name in self.pair.steps_run()})
        for scenario in self.ROW_SCENARIOS:
            with self.subTest(scenario=scenario.name):
                self.assertIn(f"scenario {scenario.name} passed over Drive, evidence only: {scenario.row} is not written",
                              self.answer.stdout)
                note = (self.pair.work / scenario.name / "passed-over-drive.txt").read_text()
                self.assertIn(f"scenario {scenario.name}", note)
                self.assertIn("against the sync fixture's Google Drive on port", note)
                self.assertIn(DIGEST[:12], note)

    def test_every_step_names_drive_and_the_fixtures_google_address_and_none_the_webdav_sign_in(self):
        port = [line for line in self.pair.fixture_log() if line.get("event") == "start"][0]["port"]
        instruments = [call for call in self.pair.calls() if call[2:5] == ["shell", "am", "instrument"]]
        self.assertTrue(instruments)
        for call in instruments:
            self.assertEqual(call[call.index(sync_pair.BACKEND_ARGUMENT) + 1], "drive")
            self.assertEqual(call[call.index(sync_pair.GOOGLE_BASE_ARGUMENT) + 1], f"http://10.0.2.2:{port}")
            for argument in ("saikou.sync.pair.url", "saikou.sync.pair.user", "saikou.sync.pair.password"):
                self.assertNotIn(argument, call)

    def test_killed_mid_apply_is_refused_over_drive_before_any_device_is_asked(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--backend", "drive", "--scenario", "killed_mid_apply")
        self.assertEqual(answer.returncode, 2)
        self.assertIn("does not run over Drive", answer.stderr)
        self.assertEqual(pair.calls(), [])

    def test_the_arguments_match_the_pair_class_and_the_test_base(self):
        source = PAIR_TEST_SOURCE.read_text()
        self.assertIn(f'BACKEND_ARGUMENT = "{sync_pair.BACKEND_ARGUMENT}"', source)
        self.assertIn(f'BACKEND_DRIVE = "{sync_pair.DRIVE}"', source)
        base = (ROOT / "app/src/main/kotlin/com/shinsaikou/signin/GoogleTestBaseSetting.kt").read_text()
        self.assertIn(f'EXTRA_GOOGLE_BASE = "{sync_pair.GOOGLE_BASE_ARGUMENT}"', base)

    def test_a_drive_step_requires_the_fixtures_base_and_a_webdav_step_leaves_it_alone(self):
        # The one place the pair class sets the test base: inside the Drive branch, through the
        # required() failure and FixtureGoogleSignIn.fixtureBase, before runBlocking builds anything.
        source = PAIR_TEST_SOURCE.read_text()
        sets = [line.strip() for line in source.splitlines() if "googleTestBase.value" in line]
        self.assertEqual(sets, ["graph.googleTestBase.value = "
                                "FixtureGoogleSignIn.fixtureBase(required(GoogleTestBaseSetting.EXTRA_GOOGLE_BASE))"])
        step = source[source.index("    fun step() {"):]
        guard = step.index("if (arguments.getString(BACKEND_ARGUMENT) == BACKEND_DRIVE) {")
        self.assertLess(guard, step.index("googleTestBase.value"))
        self.assertLess(step.index("googleTestBase.value"), step.index("runBlocking("))

    def test_the_fixture_sign_in_refuses_any_host_but_the_fixtures_before_its_request(self):
        helper = (PAIR_TEST_SOURCE.parent / "FixtureGoogleSignIn.kt").read_text()
        hosts = re.search(r'FIXTURE_HOSTS = setOf\(([^)]*)\)', helper).group(1)
        self.assertEqual(sorted(re.findall(r'"([^"]+)"', hosts)), ["10.0.2.2", "127.0.0.1", "localhost"])
        self.assertIn('url.scheme != "http"', helper)
        code = helper[helper.index("private fun code("):]
        self.assertLess(code.index("fixtureBase(url)"), code.index("newCall("))
        token = (PAIR_TEST_SOURCE.parent / "DriveTokenDeviceTest.kt").read_text()
        self.assertIn("graph.googleTestBase.value = "
                      "FixtureGoogleSignIn.fixtureBase(required(GoogleTestBaseSetting.EXTRA_GOOGLE_BASE))",
                      token)


class RefusalTest(unittest.TestCase):

    def test_a_serial_that_is_not_the_avd_it_must_be_is_refused_before_anything_starts(self):
        pair = Pair(self.addCleanup, avd_a="Pixel_9")
        answer = pair.run()
        self.assertEqual(answer.returncode, 2, answer.stderr)
        self.assertIn("emulator-5554 is Pixel_9, not saikou_pixel9_4g", answer.stderr)
        self.assertEqual({call[2] for call in pair.calls()}, {"emu"})
        self.assertEqual(pair.fixture_log(), [])

    def test_earlier_true_fragments_are_removed_up_front_even_from_a_refused_run(self):
        pair = Pair(self.addCleanup, avd_a="Pixel_9")
        pair.out.mkdir(parents=True)
        rows = [scenario.row for scenario in sync_pair.SCENARIOS if scenario.row]
        for row in rows:
            pair.fragment(row).write_text(json.dumps({"id": row, "device": "any", "value": True}))
        self.assertEqual(pair.run().returncode, 2)
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_a_taken_port_ends_the_run_before_any_install(self):
        with socket.socket() as taken:
            taken.bind(("127.0.0.1", 0))
            taken.listen()
            pair = Pair(self.addCleanup)
            answer = pair.run(port=taken.getsockname()[1])
        self.assertEqual(answer.returncode, 3, answer.stderr)
        self.assertIn("the sync fixture exited 3", answer.stderr)
        self.assertNotIn("install", {call[2] for call in pair.calls()})

    def test_a_failed_install_ends_the_run_and_removes_what_was_installed(self):
        pair = Pair(self.addCleanup, install_fails=SERIAL_B)
        answer = pair.run()
        self.assertEqual(answer.returncode, 3, answer.stderr)
        self.assertIn("installing app-debug.apk on emulator-5556 failed", answer.stderr)
        uninstalled = {(call[1], call[3]) for call in pair.calls() if call[2] == "uninstall"}
        self.assertEqual(uninstalled, {(s, p) for s in (SERIAL_A, SERIAL_B) for p in ("com.shinsaikou.test", "com.shinsaikou")})
        self.assertEqual(pair.steps_run(), [])
        self.assertEqual(pair.fixture_log()[-1]["event"], "stop")

    def test_missing_inputs_and_one_device_twice_are_usage_errors(self):
        pair = Pair(self.addCleanup)
        pair.apk.unlink()
        answer = pair.run()
        self.assertEqual(answer.returncode, 2)
        self.assertIn("missing: the debug APK", answer.stderr)
        self.assertEqual(pair.calls(), [])
        twice = run_checked([sys.executable, str(SCRIPT), "--serial-4g", SERIAL_A, "--serial-api26", SERIAL_A,
                             "--adb", str(pair.adb), "--apk", str(pair.test_apk), "--test-apk", str(pair.test_apk),
                             "--out", str(pair.out), "--work", str(pair.work)], dict(os.environ), pair.dir, timeout=60)
        self.assertEqual(twice.returncode, 2)
        self.assertIn("one device", twice.stderr)


class GateStepTest(unittest.TestCase):
    """Step 5g as gate.sh runs it: its block cut out of gate.sh and run by bash with gate.sh's own
    step, ok, skip, fail and run, in a directory where ./gradlew and the two scripts are stand-ins
    that log their arguments and exit as told. gate.sh does not accept phase 3 until unit L, so this
    is how the block's path is taken before then."""

    gate = (TOOLS / "ci" / "gate.sh").read_text()
    SERIALS = {"SERIAL_4G": "emulator-5556", "SERIAL_API26": "emulator-5554", "SERIAL_2G": "emulator-5558"}

    def block(self) -> str:
        start = self.gate.index('if phase3; then\n    step "5g.')
        # To the block's own closing fi: step 5h (unit H) follows it, with its own test.
        return self.gate[start:self.gate.index("\nfi\n", start) + len("\nfi\n")]

    def helpers(self) -> str:
        start = self.gate.index("step() {")
        return self.gate[start:self.gate.index("\n}\n", self.gate.index("run() {")) + 3]

    def gate_run(self, phase: int = 3, gradle_tasks: str = "assembleDebug", exits: dict | None = None,
                 **serials: str) -> tuple[subprocess.CompletedProcess, list[list[str]]]:
        directory = Path(tempfile.mkdtemp(prefix="sync-gate-step-"))
        self.addCleanup(shutil.rmtree, directory, True)
        (directory / "tools" / "bench").mkdir(parents=True)
        calls = directory / "calls.log"
        stand_in = textwrap.dedent('''
            #!/usr/bin/env python3
            import json, os, sys
            name = os.path.basename(sys.argv[0])
            with open(os.environ["CALLS"], "a") as log:
                log.write(json.dumps([name, *sys.argv[1:]]) + "\\n")
            sys.exit(int(os.environ.get("EXIT_" + name.split(".")[0].upper(), "0")))
        ''').lstrip()
        for path in (directory / "gradlew", directory / "tools/bench/sync_pair.py", directory / "tools/bench/sync_3000.py"):
            path.write_text(stand_in)
            path.chmod(0o755)
        values = dict(self.SERIALS, **serials)
        script = "\n".join([
            f"PHASE={phase}", "phase3() { [ \"$PHASE\" -ge 3 ]; }", "passed=0; skipped=0", self.helpers(),
            f"GRADLE_TASKS={shlex.quote(gradle_tasks)}", "ADB=/sdk/platform-tools/adb", "BENCH_OUT=tools/out/bench",
            "DEVICE_ID_4G=pixel9_4g", "DEVICE_ID_2G=pixel8a_2g",
            *(f"{name}={shlex.quote(value)}" for name, value in values.items()),
            self.block(), 'echo "passed=$passed skipped=$skipped"'])
        env = dict(os.environ, CALLS=str(calls), **{f"EXIT_{k}": str(v) for k, v in (exits or {}).items()})
        answer = subprocess.run(["bash", "-c", script], cwd=directory, capture_output=True, text=True, env=env, timeout=60)
        logged = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
        return answer, logged

    PAIR = ["sync_pair.py", "--serial-4g", "emulator-5556", "--serial-api26", "emulator-5554",
            "--adb", "/sdk/platform-tools/adb", "--out", "tools/out/bench/any", "--work", "tools/out/sync-pair"]
    S3000 = ["sync_3000.py", "--serial-2g", "emulator-5558", "--adb", "/sdk/platform-tools/adb",
             "--out", "tools/out/bench/pixel8a_2g", "--work", "tools/out/sync-3000"]
    GRADLE = ["gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest"]

    def test_phase_3_builds_then_runs_the_pair_into_any_and_the_3000_run_into_the_2g_output(self):
        answer, calls = self.gate_run()
        self.assertEqual(answer.returncode, 0, answer.stdout + answer.stderr)
        self.assertEqual(calls, [self.GRADLE, self.PAIR, self.S3000])
        self.assertIn("passed=3 skipped=0", answer.stdout)
        self.assertIn("=== 5g. the two-device sync rows and sync_3000_no_oom", answer.stdout)

    def test_a_failed_pair_run_fails_the_step_whatever_its_exit_code(self):
        for code in (1, 2, 3):
            with self.subTest(code=code):
                answer, calls = self.gate_run(exits={"SYNC_PAIR": code})
                self.assertEqual(answer.returncode, 1)
                self.assertIn("phase 3 failed at: the two-device sync rows", answer.stderr)
                self.assertEqual(calls, [self.GRADLE, self.PAIR])

    def test_a_failed_3000_run_fails_the_step_whatever_its_exit_code(self):
        for code in (1, 2, 3):
            with self.subTest(code=code):
                answer, calls = self.gate_run(exits={"SYNC_3000": code})
                self.assertEqual(answer.returncode, 1)
                self.assertIn("phase 3 failed at: sync_3000_no_oom published true on pixel8a_2g", answer.stderr)
                self.assertEqual(calls, [self.GRADLE, self.PAIR, self.S3000])

    def test_a_failed_build_fails_the_step_before_either_run(self):
        answer, calls = self.gate_run(exits={"GRADLEW": 1})
        self.assertEqual(answer.returncode, 1)
        self.assertEqual(calls, [self.GRADLE])

    def test_each_run_is_skipped_without_its_serials_and_nothing_runs_without_any(self):
        answer, calls = self.gate_run(SERIAL_2G="")
        self.assertEqual((answer.returncode, calls), (0, [self.GRADLE, self.PAIR]))
        self.assertIn("SKIP  sync_3000_no_oom (set SAIKOU_SERIAL_2G", answer.stdout)
        for missing in ("SERIAL_4G", "SERIAL_API26"):
            with self.subTest(missing=missing):
                answer, calls = self.gate_run(**{missing: ""})
                self.assertEqual((answer.returncode, calls), (0, [self.GRADLE, self.S3000]))
                self.assertIn("SKIP  the two-device sync rows", answer.stdout)
        answer, calls = self.gate_run(SERIAL_4G="", SERIAL_API26="", SERIAL_2G="")
        self.assertEqual((answer.returncode, calls), (0, []))
        self.assertIn("passed=0 skipped=1", answer.stdout)
        answer, calls = self.gate_run(gradle_tasks="")
        self.assertEqual((answer.returncode, calls), (0, []))
        self.assertIn("SKIP  sync rows (no Gradle wrapper", answer.stdout)

    def test_phase_2_takes_nothing_from_the_step(self):
        answer, calls = self.gate_run(phase=2)
        self.assertEqual((answer.returncode, calls), (0, []))
        self.assertEqual(answer.stdout, "passed=0 skipped=0\n")

    def test_the_step_sits_after_5f_and_before_7_and_the_scripts_are_the_ones_tested_here(self):
        self.assertLess(self.gate.index('step "5f.'), self.gate.index('step "5g.'))
        self.assertLess(self.gate.index('step "5g.'), self.gate.index('step "7.'))
        self.assertTrue(SCRIPT.is_file())
        self.assertTrue((TOOLS / "bench" / "sync_3000.py").is_file())


class FixtureLeakTest(unittest.TestCase):
    """No fixture outlives the run that started it (a proof that broke Fixture.stop left 47 behind)."""

    def test_the_leak_check_finds_and_stops_a_fixture_left_running(self):
        directory = Path(tempfile.mkdtemp(prefix="sync-pair-test-"))
        self.addCleanup(shutil.rmtree, directory, True)
        fixture = subprocess.Popen([sys.executable, str(sync_pair.FIXTURE), "--log", str(directory / "fixture.log"),
                                    "--port", "0"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(fixture.kill)
        deadline = time.monotonic() + 10
        while not fixtures_under(directory) and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertEqual(fixtures_under(directory), [fixture.pid])
        self.assertEqual(stop_leftover_fixtures(directory), [fixture.pid])
        fixture.wait(timeout=5)  # it ended: SIGTERM, whether or not its handler was installed yet
        self.assertEqual(fixtures_under(directory), [])
        with self.assertRaisesRegex(AssertionError, "still running"):
            leak = directory / "leak"
            run_checked([sys.executable, "-c", "import subprocess, sys; subprocess.Popen([sys.executable, "
                         f"{str(sync_pair.FIXTURE)!r}, '--log', {str(leak)!r}, '--port', '0']); "
                         "import time; time.sleep(2)"], dict(os.environ), directory, timeout=60)

    def test_a_run_killed_outright_takes_its_fixture_with_it(self):
        # SIGKILL runs no clean-up: only the parent-death signal the fixture was started with stops it.
        pair = Pair(self.addCleanup, steps={"a:seed": {"hang": True}})
        process = subprocess.Popen(pair.command(), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=pair.env())
        deadline = time.monotonic() + 60
        while ["a", "seed"] not in [step for step in map(list, pair.steps_run())] and time.monotonic() < deadline:
            time.sleep(0.1)
        self.assertTrue(fixtures_under(pair.dir), "no fixture was running when the step hung")
        process.kill()
        process.wait()
        deadline = time.monotonic() + 10
        while fixtures_under(pair.dir) and time.monotonic() < deadline:
            time.sleep(0.05)
        self.assertEqual(stop_leftover_fixtures(pair.dir), [])


class ParseTest(unittest.TestCase):

    def test_the_result_line_is_read_as_pairs_and_the_last_one_wins(self):
        output = ("INSTRUMENTATION_STATUS: saikou.sync.pair.result=step=sync written=4\n"
                  "INSTRUMENTATION_STATUS_CODE: 2\n"
                  "INSTRUMENTATION_STATUS: saikou.sync.pair.result=step=sync written=0 digest=abc\n")
        self.assertEqual(sync_pair.parse_result(output), {"step": "sync", "written": "0", "digest": "abc"})
        self.assertIsNone(sync_pair.parse_result("OK (1 test)\n"))

    def test_only_one_passing_test_passes(self):
        self.assertIsNone(sync_pair.verdict("Time: 1\n\nOK (1 test)\n"))
        self.assertIn("did not end", sync_pair.verdict("Time: 1\n\nOK (0 tests)\n"))
        self.assertIn("ran 2 tests", sync_pair.verdict("OK (2 tests)\n"))
        self.assertIn("did not end", sync_pair.verdict("FAILURES!!!\nTests run: 1,  Failures: 1\n"))


class NewEntryBacklogTest(unittest.TestCase):
    """new_entry_backlog: named only, no row, and its expectations are the unit's acceptance."""

    NAME = "new_entry_backlog"
    BACKLOG = [s for s in sync_pair.NAMED_ONLY if s.name == "new_entry_backlog"]

    # The stand-in's answers as the fixed device answers: A's add logs every chapter once, and B's
    # update queues the new chapter alone.
    PASSING = {
        "a:backlog-add": {"result": {"entryChapters": "100", "logged": "100", "loggedChapters": "100"}},
        "b:backlog-update": {"result": {"chaptersBefore": "100", "updatesBefore": "0", "queuedBefore": "0",
                                        "inserted": "1", "queued": "1",
                                        "queuedChapters": "/saikou-sync-pair/backlog/c101",
                                        "chaptersAfter": "101", "updatesAfter": "1", "failures": "30",
                                        "failuresOutsidePair": "0"}},
    }

    def test_it_passes_when_named_and_writes_no_row(self):
        pair = Pair(self.addCleanup, steps=self.PASSING)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(pair.steps_run(), expected_steps(self.BACKLOG))
        self.assertIn(f"scenario {self.NAME} passed; it writes no row", answer.stdout)
        self.assertIn("queuedChapters=/saikou-sync-pair/backlog/c101", answer.stdout)
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_a_run_that_names_no_scenario_leaves_it_out(self):
        self.assertNotIn(self.NAME, [s.name for s in sync_pair.SCENARIOS])
        pair = Pair(self.addCleanup, steps=self.PASSING)
        answer = pair.run("--scenario", "delete")
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertFalse({"backlog-add", "backlog-update"} & {name for _, name in pair.steps_run()})

    def test_the_back_catalogue_queued_on_b_is_a_failure(self):
        # The defect: B holds the entry with no chapters and queues every one its update inserts.
        steps = dict(self.PASSING)
        steps["b:backlog-update"] = {"result": dict(self.PASSING["b:backlog-update"]["result"], chaptersBefore="0",
                                                    inserted="101", queued="101")}
        pair = Pair(self.addCleanup, steps=steps)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"scenario {self.NAME} failed, so the run ends", answer.stderr)
        self.assertIn("chaptersBefore is '0', where the scenario expects '100'", answer.stderr)
        self.assertEqual(pair.steps_run()[-1], ("b", "backlog-update"))
        self.assertEqual([call[2] for call in pair.calls()].count("uninstall"), 4)

    def test_a_digest_on_b_after_its_pull_unlike_a_s_is_a_failure(self):
        # JOIN's B sign-in is B's first sync step; the scenario's own pull is its second.
        steps = dict(self.PASSING, **{"b:sync:1": {"result": {"digest": "e" * 64}}})
        pair = Pair(self.addCleanup, steps=steps)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn("is not a-after-add's", answer.stderr)

    def test_it_is_refused_over_drive_before_any_device_is_asked(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--backend", "drive", "--scenario", self.NAME)
        self.assertEqual(answer.returncode, 2)
        self.assertIn(f"{self.NAME} runs over WebDAV only; it does not run over Drive", answer.stderr)
        self.assertEqual(pair.calls(), [])


class ManualSortPairTest(unittest.TestCase):
    """manual_sort_pair (phase 4 device coverage unit DC2, item 2): named only, no row, and its
    dropped order is the unit's acceptance."""

    NAME = "manual_sort_pair"
    REORDER = [s for s in sync_pair.NAMED_ONLY if s.name == "manual_sort_pair"]

    # The stand-in's answers as the fixed device answers: A drops "Pair reading" into the reverse of
    # the seed's own order, and B reads the same ten entries back in that order.
    PASSING = {
        "a:reorder-drag": {"result": {"moved": str(sync_pair.READING_COUNT), "order": sync_pair.READING_DROPPED_ORDER}},
        "b:reorder-check": {"result": {"ranked": str(sync_pair.READING_COUNT), "order": sync_pair.READING_DROPPED_ORDER}},
    }

    def test_it_passes_when_named_and_writes_no_row(self):
        pair = Pair(self.addCleanup, steps=self.PASSING)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(pair.steps_run(), expected_steps(self.REORDER))
        self.assertIn(f"scenario {self.NAME} passed; it writes no row", answer.stdout)
        self.assertIn(f"order={sync_pair.READING_DROPPED_ORDER}", answer.stdout)
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_a_run_that_names_no_scenario_leaves_it_out(self):
        self.assertNotIn(self.NAME, [s.name for s in sync_pair.SCENARIOS])
        pair = Pair(self.addCleanup, steps=self.PASSING)
        answer = pair.run("--scenario", "delete")
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertFalse({"reorder-drag", "reorder-check"} & {name for _, name in pair.steps_run()})

    def test_a_different_order_on_b_is_a_failure(self):
        # The defect: B's read comes back in the seed's own order, not the dropped one.
        steps = dict(self.PASSING)
        steps["b:reorder-check"] = {"result": dict(self.PASSING["b:reorder-check"]["result"],
                                                    order="0,3,6,9,12,15,18,21,24,27")}
        pair = Pair(self.addCleanup, steps=steps)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"scenario {self.NAME} failed, so the run ends", answer.stderr)
        self.assertIn(f"order is '0,3,6,9,12,15,18,21,24,27', where the scenario expects '{sync_pair.READING_DROPPED_ORDER}'",
                      answer.stderr)
        self.assertEqual(pair.steps_run()[-1], ("b", "reorder-check"))
        self.assertEqual([call[2] for call in pair.calls()].count("uninstall"), 4)

    def test_a_digest_on_b_after_its_pull_unlike_a_s_is_a_failure(self):
        steps = dict(self.PASSING, **{"b:sync:1": {"result": {"digest": "e" * 64}}})
        pair = Pair(self.addCleanup, steps=steps)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn("is not a-after-reorder's", answer.stderr)

    def test_it_is_refused_over_drive_before_any_device_is_asked(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--backend", "drive", "--scenario", self.NAME)
        self.assertEqual(answer.returncode, 2)
        self.assertIn(f"{self.NAME} runs over WebDAV only; it does not run over Drive", answer.stderr)
        self.assertEqual(pair.calls(), [])


class SubcategoryPairTest(unittest.TestCase):
    """The sub-category scenarios (phase 5 unit CT, item 8): rows of the gate's run since phase 5 unit
    TG3, item 3, and each device's tree-check is the acceptance."""

    NAMES = ("subcategory_race", "subcategory_parent_deleted", "subcategory_restore_join")
    ROWS = {"subcategory_race": "sync_subcategory_race_one_tree",
            "subcategory_parent_deleted": "sync_subcategory_orphan_top_level",
            "subcategory_restore_join": "sync_subcategory_restore_keeps_tree"}
    FLAT = sync_pair.tree_line(sync_pair.PAIR_READING, sync_pair.PAIR_OLD_NAME, sync_pair.PAIR_GONE)

    # The stand-in's answers as the fixed device answers, per scenario.
    PASSING = {
        "subcategory_race": {
            "a:tree-nest-reading": {"result": {"nested": sync_pair.NESTED_READING}},
            "b:tree-nest-old-name": {"result": {"nested": sync_pair.NESTED_OLD_NAME}},
            "a:tree-check": {"result": {"tree": sync_pair.TREE_AFTER_RACE, "nested": "2"}},
            "b:tree-check": {"result": {"tree": sync_pair.TREE_AFTER_RACE, "nested": "2"}},
        },
        "subcategory_parent_deleted": {
            "b:tree-nest-reading": {"result": {"nested": sync_pair.NESTED_READING}},
            "a:tree-check": {"result": {"tree": sync_pair.TREE_PARENT_DELETED, "nested": "0"}},
            "b:tree-check": {"result": {"tree": sync_pair.TREE_PARENT_DELETED, "nested": "0"}},
        },
        "subcategory_restore_join": {
            "a:tree-nest-reading": {"result": {"nested": sync_pair.NESTED_READING}},
            "b:tree-restore": {"result": {"created": "3"}},
            "b:tree-check:1": {"result": {"tree": FLAT, "nested": "0"}},
            "b:tree-check:2": {"result": {"tree": sync_pair.TREE_NESTED, "nested": "1"}},
            "a:tree-check": {"result": {"tree": sync_pair.TREE_NESTED, "nested": "1"}},
        },
    }

    def scenario(self, name: str):
        return [s for s in sync_pair.SCENARIOS if s.name == name]

    def test_each_passes_when_named_and_writes_its_row(self):
        for name in self.NAMES:
            with self.subTest(scenario=name):
                pair = Pair(self.addCleanup, steps=self.PASSING[name])
                answer = pair.run("--scenario", name)
                self.assertEqual(answer.returncode, 0, answer.stderr)
                self.assertEqual(pair.steps_run(), expected_steps(self.scenario(name)))
                self.assertEqual([path.name for path in pair.out.iterdir()], [f"{self.ROWS[name]}.any.json"])

    def test_they_are_row_scenarios_of_the_gates_run_before_killed_mid_apply(self):
        names = [s.name for s in sync_pair.SCENARIOS]
        self.assertEqual({s.name: s.row for s in sync_pair.SCENARIOS if s.name in self.NAMES}, self.ROWS)
        self.assertFalse(set(self.NAMES) & {s.name for s in sync_pair.NAMED_ONLY})
        self.assertEqual(names[-1], "killed_mid_apply")

    def test_a_device_showing_another_tree_after_the_race_is_a_failure(self):
        # One device broke the cycle at the other member: the two would show different trees.
        other = sync_pair.tree_line(sync_pair.PAIR_READING, f"{sync_pair.PAIR_READING}>{sync_pair.PAIR_OLD_NAME}",
                                    sync_pair.PAIR_GONE)
        self.assertNotEqual(other, sync_pair.TREE_AFTER_RACE)
        steps = dict(self.PASSING["subcategory_race"], **{"b:tree-check": {"result": {"tree": other, "nested": "2"}}})
        pair = Pair(self.addCleanup, steps=steps)
        answer = pair.run("--scenario", "subcategory_race")
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"tree is '{other}', where the scenario expects '{sync_pair.TREE_AFTER_RACE}'", answer.stderr)
        self.assertEqual(pair.steps_run()[-1], ("b", "tree-check"))
        self.assertIn(f"scenario subcategory_race failed, so {self.ROWS['subcategory_race']} is not written", answer.stderr)
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_a_tree_the_restore_flattened_is_a_failure(self):
        # Item 7's defect: B's restored categories outrank A's nest, and the join flattens A too.
        steps = dict(self.PASSING["subcategory_restore_join"], **{"a:tree-check": {"result": {"tree": self.FLAT, "nested": "0"}}})
        pair = Pair(self.addCleanup, steps=steps)
        answer = pair.run("--scenario", "subcategory_restore_join")
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"tree is '{self.FLAT}', where the scenario expects '{sync_pair.TREE_NESTED}'", answer.stderr)

    def test_a_child_left_under_the_deleted_parent_is_a_failure(self):
        steps = dict(self.PASSING["subcategory_parent_deleted"], **{"a:tree-check": {"result": {"tree": sync_pair.TREE_PARENT_DELETED, "nested": "1"}}})
        pair = Pair(self.addCleanup, steps=steps)
        answer = pair.run("--scenario", "subcategory_parent_deleted")
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn("nested is '1', where the scenario expects '0'", answer.stderr)

    def test_the_race_tree_tops_the_lower_sync_key_and_the_names_are_the_pair_librarys(self):
        keys = {name: sync_pair.category_sync_key(name) for name in (sync_pair.PAIR_READING, sync_pair.PAIR_OLD_NAME)}
        top = min(keys, key=keys.get)
        under = max(keys, key=keys.get)
        self.assertTrue(sync_pair.TREE_AFTER_RACE.startswith(f"{top.replace(' ', '_')}|{top.replace(' ', '_')}>{under.replace(' ', '_')}|"))
        # CategorySyncKey.of trims and lower-cases before hashing.
        self.assertEqual(sync_pair.category_sync_key("  Pair READING "), keys[sync_pair.PAIR_READING])
        library = (PAIR_TEST_SOURCE.parent / "SyncPairLibrary.kt").read_text()
        for constant, name in (("READING", sync_pair.PAIR_READING), ("OLD_NAME", sync_pair.PAIR_OLD_NAME),
                               ("GONE", sync_pair.PAIR_GONE)):
            with self.subTest(constant=constant):
                self.assertIn(f'const val {constant} = "{name}"', library)
        self.assertIn("val CATEGORIES = listOf(READING, OLD_NAME, GONE)", library)

    def test_over_drive_they_run_as_evidence_and_write_no_row(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--backend", "drive", *[arg for name in self.NAMES for arg in ("--scenario", name)])
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(pair.steps_run(), expected_steps([s for s in sync_pair.SCENARIOS if s.name in self.NAMES]))
        for name in self.NAMES:
            self.assertIn(f"scenario {name} passed over Drive, evidence only: {self.ROWS[name]} is not written",
                          answer.stdout)
        self.assertFalse(pair.out.exists() and list(pair.out.iterdir()))


class RestoreThenJoinTest(unittest.TestCase):
    """restore_then_join (phase 5 unit TG3, item 1): a row of the gate's run, written only when both
    devices' checks pass and the further syncs write nothing."""

    NAME = "restore_then_join"
    ROW = "sync_restore_join_keeps_progress"

    def test_it_passes_alone_and_writes_its_row(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(pair.steps_run(), expected_steps([s for s in sync_pair.SCENARIOS if s.name == self.NAME]))
        self.assertEqual([path.name for path in pair.out.iterdir()], [f"{self.ROW}.any.json"])

    def test_a_check_that_fails_on_the_device_that_did_not_restore_writes_no_row(self):
        # The defect the row guards: A lacks what only B's export carries (R29 on the devices).
        pair = Pair(self.addCleanup, steps={"a:restore-check": {"fail": True}})
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"scenario {self.NAME} failed, so {self.ROW} is not written", answer.stderr)
        self.assertEqual(pair.steps_run()[-1], ("a", "restore-check"))
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_a_restore_that_left_the_flag_down_or_a_further_sync_that_writes_is_a_failure(self):
        for steps, why in (
            ({"b:restore-backup": {"result": {"restoreFlag": "false"}}}, "restoreFlag is 'false', where the scenario expects 'true'"),
            ({"b:sync:2": {"result": {"written": "4"}}}, "written is '4', where the scenario expects '0'"),
        ):
            with self.subTest(why=why):
                pair = Pair(self.addCleanup, steps=steps)
                answer = pair.run("--scenario", self.NAME)
                self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
                self.assertIn(why, answer.stderr)
                self.assertEqual(list(pair.out.iterdir()), [])

    def test_the_counts_follow_the_backup_and_the_pair_library(self):
        restore = (PAIR_TEST_SOURCE.parent / "SyncPairRestore.kt").read_text()
        library = (PAIR_TEST_SOURCE.parent / "SyncPairLibrary.kt").read_text()
        self.assertIn(f"val OVERLAP = 0 until {sync_pair.RESTORE_OVERLAP}", restore)
        self.assertIn(f"val RESTORE_ONLY = SyncPairLibrary.ENTRIES until SyncPairLibrary.ENTRIES + {sync_pair.RESTORE_ONLY}",
                      restore)
        self.assertIn(f"const val RESTORE_ONLY_CHAPTERS = {sync_pair.RESTORE_ONLY_CHAPTERS}", restore)
        self.assertIn(f"const val CHAPTERS = {sync_pair.PAIR_CHAPTERS}", library)
        self.assertIn(f"const val READ = {sync_pair.PAIR_READ}", library)
        self.assertEqual(sync_pair.RESTORE_CHECK["checked"], str(6 * 12 + 2 * 6))


class EditDetailsRaceTest(unittest.TestCase):
    """edit_details_race (phase 5 unit ED): a row of the gate's run, written only when both devices
    show A's edit over B's refreshed source text and, after A's reset, the source's new text."""

    NAME = "edit_details_race"
    ROW = "sync_edit_details_race"

    def scenario(self):
        return [s for s in sync_pair.SCENARIOS if s.name == self.NAME]

    def test_it_passes_alone_and_writes_its_row(self):
        pair = Pair(self.addCleanup)
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 0, answer.stderr)
        self.assertEqual(pair.steps_run(), expected_steps(self.scenario()))
        self.assertEqual([path.name for path in pair.out.iterdir()], [f"{self.ROW}.any.json"])

    def test_it_is_a_row_scenario_of_the_gates_run_before_killed_mid_apply(self):
        self.assertEqual([s.row for s in self.scenario()], [self.ROW])
        self.assertNotIn(self.NAME, {s.name for s in sync_pair.NAMED_ONLY})
        self.assertEqual(sync_pair.SCENARIOS[-1].name, "killed_mid_apply")

    def test_an_edit_lost_to_the_refresh_writes_no_row(self):
        # The merge's defect: B keeps its own row, so B shows the source's new title and no edit.
        pair = Pair(self.addCleanup, steps={"b:details-check:1": {"result": sync_pair.DETAILS_REFRESHED_ON_B}})
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"shownTitle is '{sync_pair.details_word(sync_pair.RENAMED_TITLE)}', where the scenario expects "
                      f"'{sync_pair.details_word(sync_pair.EDITED_TITLE)}'", answer.stderr)
        self.assertEqual(pair.steps_run()[-1], ("b", "details-check"))
        self.assertIn(f"scenario {self.NAME} failed, so {self.ROW} is not written", answer.stderr)
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_a_reset_that_did_not_reach_the_other_device_writes_no_row(self):
        pair = Pair(self.addCleanup, steps={"b:details-check:2": {"result": sync_pair.DETAILS_AFTER_RACE}})
        answer = pair.run("--scenario", self.NAME)
        self.assertEqual(answer.returncode, 1, answer.stdout + answer.stderr)
        self.assertIn(f"shownTitle is '{sync_pair.details_word(sync_pair.EDITED_TITLE)}', where the scenario expects "
                      f"'{sync_pair.details_word(sync_pair.RENAMED_TITLE)}'", answer.stderr)
        self.assertEqual(pair.steps_run()[-1], ("b", "details-check"))
        self.assertEqual(list(pair.out.iterdir()), [])

    def test_the_texts_are_the_pair_classes(self):
        details = (PAIR_TEST_SOURCE.parent / "SyncPairEditDetails.kt").read_text()
        for constant, text in (("EDITED_TITLE", sync_pair.EDITED_TITLE), ("EDITED_DESCRIPTION", sync_pair.EDITED_DESCRIPTION),
                               ("RENAMED_TITLE", sync_pair.RENAMED_TITLE), ("NEW_DESCRIPTION", sync_pair.NEW_DESCRIPTION),
                               ("EDITED_AUTHOR", sync_pair.EDITED_AUTHOR), ("EDITED_ARTIST", sync_pair.EDITED_ARTIST)):
            with self.subTest(constant=constant):
                self.assertIn(f'const val {constant} = "{text}"', details)
        self.assertIn("val EDITED_STATUS = MangaStatus.COMPLETED.code.toLong()", details)
        self.assertEqual(sync_pair.EDITED_STATUS, 2)
        genres = ", ".join(f'"{genre}"' for genre in sync_pair.EDITED_GENRES)
        self.assertIn(f"val EDITED_GENRES = listOf({genres})", details)
        self.assertIn('"shownGenres" to word(manga.displayGenre?.joinToString(","))', details)
        self.assertIn('private fun word(text: String?): String = text?.replace(" ", "_") ?: "none"', details)
        library = (PAIR_TEST_SOURCE.parent / "SyncPairLibrary.kt").read_text()
        self.assertIn('title = "Saikou sync pair entry $index",', library)
        self.assertIn("const val SCENARIO_ENTRY = 0", library)
        self.assertEqual(sync_pair.SEED_TITLE, "Saikou sync pair entry 0")


class SourceAgreementTest(unittest.TestCase):
    """What this script names in the pair class is what the pair class says."""

    source = PAIR_TEST_SOURCE.read_text()

    def test_every_step_a_scenario_names_is_a_branch_of_the_pair_class(self):
        branches = set(re.findall(r'^\s+"([a-z0-9-]+)" -> ', self.source, re.M))
        named = {step.name for scenario in sync_pair.SCENARIOS for step in scenario.steps} - {sync_pair.CLEAR}
        self.assertEqual(sorted(named - branches), [])
        self.assertIn("seed", named)

    def test_every_step_a_named_only_scenario_names_is_a_branch_and_the_backlog_matches_its_entry(self):
        branches = set(re.findall(r'^\s+"([a-z0-9-]+)" -> ', self.source, re.M))
        named = {step.name for scenario in sync_pair.NAMED_ONLY for step in scenario.steps} - {sync_pair.CLEAR}
        self.assertEqual(sorted(named - branches), [])
        self.assertIn("backlog-update", named)
        backlog = (PAIR_TEST_SOURCE.parent / "SyncPairBacklog.kt").read_text()
        chapters = re.search(r"const val CHAPTERS = ([0-9_]+)$", backlog, re.M).group(1)
        self.assertEqual(int(chapters.replace("_", "")), sync_pair.BACKLOG_CHAPTERS)
        url = re.search(r'const val ENTRY_URL = "([^"]+)"$', backlog, re.M).group(1)
        self.assertIn('fun chapterUrl(number: Int): String = "$ENTRY_URL/c$number"', backlog)
        self.assertEqual(sync_pair.BACKLOG_NEW_CHAPTER, f"{url}/c{sync_pair.BACKLOG_CHAPTERS + 1}")

    def test_the_class_argument_result_key_and_skip_line_match(self):
        test_class, method = sync_pair.PAIR_TEST.split("#")
        self.assertEqual(PAIR_TEST_SOURCE.relative_to(ROOT / "app" / "src" / "androidTest" / "kotlin").with_suffix("")
                         .as_posix().replace("/", "."), test_class)
        self.assertIn(f"    fun {method}() {{", self.source)
        self.assertIn(f'STEP_ARGUMENT = "{sync_pair.STEP_ARGUMENT}"', self.source)
        self.assertIn(f'RESULT_KEY = "{sync_pair.RESULT_KEY}"', self.source)
        self.assertIn('"Skipped: no sync pair step named"', self.source)
        for argument in ("saikou.sync.pair.url", "saikou.sync.pair.user", "saikou.sync.pair.password"):
            self.assertIn(f'"{argument}"', self.source)
            self.assertIn(f'"{argument}"', SCRIPT.read_text())

    def test_the_kill_line_outcome_file_and_library_sizes_match(self):
        self.assertIn(f'KILL_KEY = "{sync_pair.KILL_KEY}"', self.source)
        row = re.search(r'KILL_OUTCOME = "([a-z_]+)"', self.source).group(1)
        self.assertEqual([s.name for s in sync_pair.SCENARIOS if s.row is None], [row])
        outcome = (PAIR_TEST_SOURCE.parent.parent / "util" / "ForceStopOutcome.kt").read_text()
        directory = re.search(r'DEVICE_DIRECTORY = "([^"]+)"', outcome).group(1)
        self.assertEqual(sync_pair.KILL_OUTCOME, f"{directory}/{row}.json")
        library = (PAIR_TEST_SOURCE.parent / "SyncPairLibrary.kt").read_text()
        for name, value in (("ENTRIES", sync_pair.PAIR_ENTRIES), ("BULK_CHAPTERS", sync_pair.BULK_CHAPTERS),
                            ("BULK_READ", sync_pair.BULK_READ)):
            with self.subTest(name=name):
                found = re.search(rf"const val {name} = ([0-9_]+)$", library, re.M)
                self.assertEqual(int(found.group(1).replace("_", "")), value)

    def test_the_steps_that_sign_in_are_the_ones_that_read_the_sign_in_arguments(self):
        # Each branch that signs in calls startSignIn or storeSignIn, which read the three arguments.
        branches = dict(re.findall(r'^\s+"([a-z0-9-]+)" -> (.*)$', self.source, re.M))
        for step in sync_pair.SIGN_IN_STEPS:
            with self.subTest(step=step):
                self.assertIn(step, branches)
        for step in sync_pair.KILL_STEPS:
            with self.subTest(step=step):
                self.assertIn(step, branches)
                self.assertRegex(branches[step], r"^kill[A-Za-z]+\(\)$")


if __name__ == "__main__":
    unittest.main()
