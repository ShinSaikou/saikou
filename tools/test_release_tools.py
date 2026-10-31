#!/usr/bin/env python3
"""Unit tests for tools/release/: release_version.py, install_release.py and pre_switch.py.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

adb, aapt2 and apksigner are stubs written into a temporary directory, each a small Python script
that answers from a JSON state file and logs every call, so nothing here reaches a device or the SDK.
The stub adb knows one device more than any test names, `owner-phone`, and every test that runs a
script checks that no call went to it: the scripts run only on the devices the caller names (unit R1
of phase3-start.md). The real tools are exercised in unit R1's report (the gate's check on a built
APK, and the spike's installs on the AVDs).

The cases that matter, and why each one is here:
  - release_version.py: below the recorded code fails, equal and above pass (Q19), --name prints the
    build's versionName, and an APK that is not com.shinsaikou or a recorded file without a positive integer
    is an input error rather than a verdict; the committed file records 1;
  - install_release.py: a code not above the recorded one, or another package, is refused before any
    device is touched; a named device that is not attached stops the run before any install; on
    success every named device gets `install -r`, the installed code is read back, and the file is
    rewritten keeping its comment; after a partial failure, or a Success that the device's own
    versionCode contradicts, the file is left as it was and the failed device is named;
  - pre_switch.py: match, no match, not installed and not attached per named device, each device's own
    API level passed to apksigner for both APKs, base.apk chosen among split APKs, and no certificate
    or digest in what it prints;
  - both device scripts print each named serial's model and whether it is an emulator (ro.kernel.qemu
    is 1 or the serial starts with emulator-) before they install or read anything, and refuse
    nothing on it;
  - gate.sh runs release_version.py in phase 3 only, and the collect subject keeps 0.1.0 otherwise.
"""

from __future__ import annotations

import base64
import contextlib
import io
import json
import os
import re
import stat
import sys
import tempfile
import textwrap
import unittest
import unittest.mock
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS / "release"))
import apk as apks  # noqa: E402
import install_release  # noqa: E402
import pre_switch  # noqa: E402
import release_version  # noqa: E402

OWNER_PHONE = "owner-phone"

ADB_STUB = r'''
import json, os, shutil, sys
state_path = os.environ["STUB_STATE"]
state = json.load(open(state_path))
args = sys.argv[1:]
with open(os.environ["STUB_LOG"], "a") as log:
    log.write("adb " + " ".join(args) + "\n")
if args[:1] != ["-s"]:
    sys.exit("the stub adb takes -s <serial> first")
serial, rest = args[1], args[2:]
device = state["devices"].get(serial)
if device is None or not device.get("attached", True):
    print(f"adb: device '{serial}' not found", file=sys.stderr)
    sys.exit(1)
if rest == ["get-state"]:
    print("device")
elif rest[:2] == ["install", "-r"]:
    outcome = device.get("install", "Success")
    print("Performing Streamed Install")
    print(outcome)
    if outcome != "Success":
        sys.exit(1)
    if not device.get("ignoreInstall"):
        device["installedCode"] = state["apks"][os.path.basename(rest[2])]["code"]
        json.dump(state, open(state_path, "w"))
elif rest[:3] == ["shell", "dumpsys", "package"]:
    if device.get("installedCode") is not None:
        print("Packages:")
        print(f"    versionCode={device['installedCode']} minSdk=26 targetSdk=36")
elif rest == ["shell", "getprop", "ro.build.version.sdk"]:
    print(device["api"])
elif rest == ["shell", "getprop", "ro.product.model"]:
    print(device.get("model", "Stub Phone"))
elif rest == ["shell", "getprop", "ro.kernel.qemu"]:
    print(device.get("qemu", ""))
elif rest[:3] == ["shell", "pm", "path"]:
    for path in device.get("paths", []):
        print("package:" + path)
elif rest[:1] == ["pull"]:
    shutil.copyfile(device["apkFile"], rest[2])
    print("1 file pulled")
else:
    sys.exit("the stub adb does not know: " + " ".join(rest))
'''

AAPT2_STUB = r'''
import json, os, sys
state = json.load(open(os.environ["STUB_STATE"]))
with open(os.environ["STUB_LOG"], "a") as log:
    log.write("aapt2 " + " ".join(sys.argv[1:]) + "\n")
entry = state["apks"][os.path.basename(sys.argv[-1])]
print(f"package: name='{entry['package']}' versionCode='{entry['code']}' versionName='{entry['name']}' platformBuildVersionName='16'")
print("sdkVersion:'26'")
'''

# A certificate here is the bytes "CERT-<signer>", read from the APK file's text `signer:<a,b>`. The
# scripts only compare certificates, so real ones would test nothing more.
APKSIGNER_STUB = r'''
import base64, os, sys
with open(os.environ["STUB_LOG"], "a") as log:
    log.write("apksigner " + " ".join(sys.argv[1:]) + "\n")
text = open(sys.argv[-1]).read()
signers = text.split("signer:", 1)[1].strip().split(",")
for index, signer in enumerate(signers, 1):
    print(f"Signer #{index} certificate DN: CN={signer}")
    print("-----BEGIN CERTIFICATE-----")
    print(base64.b64encode(("CERT-" + signer).encode()).decode())
    print("-----END CERTIFICATE-----")
'''


class Stubs:
    """The three stubs, their shared state file and the call log, in a temporary directory."""

    def __init__(self, test: unittest.TestCase) -> None:
        directory = tempfile.TemporaryDirectory()
        test.addCleanup(directory.cleanup)
        self.dir = Path(directory.name)
        self.state_path = self.dir / "state.json"
        self.log_path = self.dir / "calls.log"
        self.log_path.write_text("")
        self.state = {"devices": {OWNER_PHONE: {"api": 36}}, "apks": {}}
        self.save()
        self.adb = self.tool("adb", ADB_STUB)
        self.aapt2 = self.tool("aapt2", AAPT2_STUB)
        self.apksigner = self.tool("apksigner", APKSIGNER_STUB)
        self.last = self.dir / "last-release.json"
        self.last.write_text(json.dumps({"comment": "kept as it is", "versionCode": 1}, indent=2) + "\n")
        environment = {"STUB_STATE": str(self.state_path), "STUB_LOG": str(self.log_path)}
        patcher = unittest.mock.patch.dict(os.environ, environment)
        patcher.start()
        test.addCleanup(patcher.stop)

    def tool(self, name: str, body: str) -> Path:
        path = self.dir / name
        path.write_text(f"#!{sys.executable}\n" + textwrap.dedent(body))
        path.chmod(path.stat().st_mode | stat.S_IXUSR)
        return path

    def save(self) -> None:
        self.state_path.write_text(json.dumps(self.state))

    def load(self) -> dict:
        return json.loads(self.state_path.read_text())

    def apk(self, name: str, code: int, version_name: str = "1.0.0", package: str = "com.shinsaikou", signer: str = "debug") -> Path:
        path = self.dir / name
        path.write_text(f"signer:{signer}\n")
        self.state["apks"][name] = {"package": package, "code": code, "name": version_name}
        self.save()
        return path

    def device(self, serial: str, **fields) -> None:
        self.state["devices"][serial] = {"api": 34, **fields}
        self.save()

    @property
    def calls(self) -> list[str]:
        return self.log_path.read_text().splitlines()

    def assert_owner_phone_untouched(self, test: unittest.TestCase) -> None:
        test.assertFalse([call for call in self.calls if f"-s {OWNER_PHONE}" in call],
                         "a script reached a device it was not given")


def run_main(main, argv: list[str]) -> tuple[int, str, str]:
    stdout, stderr = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
        try:
            code = main(argv)
        except SystemExit as exc:
            code = exc.code
    return code, stdout.getvalue(), stderr.getvalue()


class ReleaseVersionTest(unittest.TestCase):

    def setUp(self) -> None:
        self.stubs = Stubs(self)

    def check(self, code: int) -> tuple[int, str, str]:
        apk = self.stubs.apk("app-release.apk", code, "0.1.0")
        return run_main(release_version.main, ["--check", str(apk), "--last", str(self.stubs.last),
                                               "--aapt2", str(self.stubs.aapt2)])

    def test_below_the_recorded_code_fails(self):
        self.stubs.last.write_text(json.dumps({"versionCode": 100}))
        code, _, err = self.check(99)
        self.assertEqual(code, release_version.BELOW, err)
        self.assertIn("versionCode 99 is below 100", err)

    def test_equal_to_the_recorded_code_passes(self):
        code, out, err = self.check(1)
        self.assertEqual(code, release_version.PASSED, err)
        self.assertIn("versionCode 1 is equal to the recorded 1 (com.shinsaikou 0.1.0)", out)

    def test_above_the_recorded_code_passes(self):
        code, out, err = self.check(2)
        self.assertEqual(code, release_version.PASSED, err)
        self.assertIn("versionCode 2 is above the recorded 1", out)

    def test_the_name_is_the_build_s_version_name(self):
        apk = self.stubs.apk("app-release.apk", 100, "1.0.0")
        code, out, err = run_main(release_version.main, ["--name", str(apk), "--aapt2", str(self.stubs.aapt2)])
        self.assertEqual((code, out), (0, "1.0.0\n"), err)
        self.assertIn(f"aapt2 dump badging {apk}", self.stubs.calls)

    def test_another_package_is_an_input_error(self):
        apk = self.stubs.apk("app-release.apk", 2, package="app.other")
        code, _, err = run_main(release_version.main, ["--check", str(apk), "--last", str(self.stubs.last),
                                                       "--aapt2", str(self.stubs.aapt2)])
        self.assertEqual(code, release_version.BAD_INPUT)
        self.assertIn("the APK is app.other, not com.shinsaikou", err)

    def test_a_recorded_file_without_a_positive_integer_is_refused_not_read_as_zero(self):
        for body in ('{"versionCode": 0}', '{"versionCode": "1"}', '{"versionCode": true}', '{}', "[1]", "not json"):
            with self.subTest(body=body):
                self.stubs.last.write_text(body)
                code, _, err = self.check(5)
                self.assertEqual(code, release_version.BAD_INPUT, err)
        self.stubs.last.unlink()
        code, _, err = self.check(5)
        self.assertEqual(code, release_version.BAD_INPUT)
        self.assertIn("no ", err)

    def test_a_missing_apk_is_an_input_error(self):
        code, _, err = run_main(release_version.main, ["--check", str(self.stubs.dir / "gone.apk"),
                                                       "--aapt2", str(self.stubs.aapt2)])
        self.assertEqual(code, release_version.BAD_INPUT)
        self.assertIn("no APK at", err)

    def test_the_committed_file_records_2_the_code_on_the_owners_phones_since_c681931(self):
        self.assertEqual(apks.read_last_release(), 2)


class InstallReleaseTest(unittest.TestCase):

    def setUp(self) -> None:
        self.stubs = Stubs(self)
        self.stubs.device("phone-1")
        self.stubs.device("tablet-1")

    def install(self, apk: Path, *serials: str) -> tuple[int, str, str]:
        argv = [arg for serial in serials for arg in ("--serial", serial)]
        argv += ["--apk", str(apk), "--last", str(self.stubs.last), "--adb", str(self.stubs.adb),
                 "--aapt2", str(self.stubs.aapt2)]
        return run_main(install_release.main, argv)

    def installs(self) -> list[str]:
        return [call for call in self.stubs.calls if " install -r " in call]

    def test_a_code_above_the_recorded_one_installs_on_every_named_device_and_is_recorded(self):
        apk = self.stubs.apk("app-release.apk", 100, "1.0.0")
        code, out, err = self.install(apk, "phone-1", "tablet-1")
        self.assertEqual(code, install_release.INSTALLED, err)
        self.assertEqual(self.installs(), [f"adb -s phone-1 install -r {apk}", f"adb -s tablet-1 install -r {apk}"])
        self.assertIn("phone-1: installed, versionCode 100", out)
        recorded = json.loads(self.stubs.last.read_text())
        self.assertEqual(recorded, {"comment": "kept as it is", "versionCode": 100})
        self.stubs.assert_owner_phone_untouched(self)

    def test_each_named_serial_is_described_before_any_install_and_an_emulator_is_not_refused(self):
        self.stubs.device("emulator-5556", model="sdk_gphone64_x86_64", qemu="1")
        self.stubs.device("phone-1", model="Pixel 8a")
        apk = self.stubs.apk("app-release.apk", 100)
        code, out, err = self.install(apk, "emulator-5556", "phone-1")
        self.assertEqual(code, install_release.INSTALLED, err)
        self.assertEqual(out.splitlines()[:2], ["emulator-5556  sdk_gphone64_x86_64  emulator",
                                                "phone-1  Pixel 8a  physical device"])
        adb_calls = [call for call in self.stubs.calls if call.startswith("adb ")]
        described = max(index for index, call in enumerate(adb_calls) if "getprop ro.product.model" in call)
        first_install = min(index for index, call in enumerate(adb_calls) if " install -r " in call)
        self.assertLess(described, first_install, adb_calls)
        self.assertEqual(len(self.installs()), 2)
        self.stubs.assert_owner_phone_untouched(self)

    def test_an_equal_or_lower_code_is_refused_before_any_device_is_touched(self):
        for version_code in (1, 0):
            with self.subTest(version_code=version_code):
                apk = self.stubs.apk("app-release.apk", version_code)
                code, _, err = self.install(apk, "phone-1")
                self.assertEqual(code, install_release.REFUSED)
                self.assertIn(f"versionCode {version_code} (1.0.0) is not above 1", err)
                self.assertFalse([call for call in self.stubs.calls if call.startswith("adb ")], self.stubs.calls)
                self.assertEqual(apks.read_last_release(self.stubs.last), 1)

    def test_another_package_is_refused(self):
        apk = self.stubs.apk("app-release.apk", 100, package="com.shinsaikou.test")
        code, _, err = self.install(apk, "phone-1")
        self.assertEqual(code, install_release.REFUSED)
        self.assertIn("is com.shinsaikou.test, not com.shinsaikou", err)
        self.assertEqual(self.installs(), [])

    def test_a_named_device_that_is_not_attached_stops_the_run_before_any_install(self):
        self.stubs.device("tablet-1", attached=False)
        apk = self.stubs.apk("app-release.apk", 100)
        code, out, err = self.install(apk, "phone-1", "tablet-1")
        self.assertEqual(code, install_release.REFUSED)
        self.assertIn("not attached: tablet-1. Nothing was installed.", err)
        self.assertIn("tablet-1  not attached\n", out)
        self.assertEqual(self.installs(), [])
        self.assertEqual(apks.read_last_release(self.stubs.last), 1)

    def test_a_partial_failure_leaves_the_file_and_names_the_device(self):
        self.stubs.device("tablet-1", install="Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]")
        apk = self.stubs.apk("app-release.apk", 100)
        code, out, err = self.install(apk, "phone-1", "tablet-1")
        self.assertEqual(code, install_release.REFUSED)
        self.assertIn("phone-1: installed", out)
        self.assertIn("tablet-1: the install failed: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE", err)
        self.assertIn("left at 1: 1 of 2 device(s) did not take the install (tablet-1)", err)
        self.assertEqual(apks.read_last_release(self.stubs.last), 1)

    def test_a_success_the_device_s_version_contradicts_is_a_failure(self):
        self.stubs.device("phone-1", ignoreInstall=True, installedCode=1)
        apk = self.stubs.apk("app-release.apk", 100)
        code, _, err = self.install(apk, "phone-1")
        self.assertEqual(code, install_release.REFUSED)
        self.assertIn("adb said Success but the device reports versionCode [1] for com.shinsaikou", err)
        self.assertEqual(apks.read_last_release(self.stubs.last), 1)

    def test_a_second_run_naming_the_failed_device_completes_the_switch(self):
        self.stubs.device("tablet-1", install="Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]")
        apk = self.stubs.apk("app-release.apk", 100)
        self.assertEqual(self.install(apk, "phone-1", "tablet-1")[0], install_release.REFUSED)
        self.stubs.state = self.stubs.load()
        self.stubs.device("tablet-1")
        code, _, err = self.install(apk, "tablet-1")
        self.assertEqual(code, install_release.INSTALLED, err)
        self.assertEqual(apks.read_last_release(self.stubs.last), 100)

    def test_no_serial_or_a_repeated_one_is_a_usage_error(self):
        apk = self.stubs.apk("app-release.apk", 100)
        self.assertEqual(self.install(apk)[0], 2)
        code, _, err = self.install(apk, "phone-1", "phone-1")
        self.assertEqual(code, install_release.BAD_INPUT)
        self.assertIn("once", err)
        self.assertEqual(self.installs(), [])


class PreSwitchTest(unittest.TestCase):

    def setUp(self) -> None:
        self.stubs = Stubs(self)
        self.debug_apk = self.stubs.apk("app-debug.apk", 1, "0.1.0-debug", signer="debug")

    def installed(self, serial: str, api: int, signer: str, paths: list[str] | None = None) -> None:
        apk_file = self.stubs.dir / f"{serial}-installed.apk"
        apk_file.write_text(f"signer:{signer}\n")
        self.stubs.device(serial, api=api, apkFile=str(apk_file),
                          paths=paths or [f"/data/app/~~x/com.shinsaikou-y/base.apk"])

    def check(self, *serials: str, debug_apk: Path | None = None) -> tuple[int, str, str]:
        argv = [arg for serial in serials for arg in ("--serial", serial)]
        argv += ["--debug-apk", str(debug_apk or self.debug_apk), "--adb", str(self.stubs.adb),
                 "--apksigner", str(self.stubs.apksigner)]
        return run_main(pre_switch.main, argv)

    def test_a_device_whose_install_carries_the_debug_key_matches(self):
        self.installed("phone-1", 34, "debug")
        code, out, err = self.check("phone-1")
        self.assertEqual((code, out), (pre_switch.ALL_MATCH,
                                       "phone-1  Stub Phone  physical device\nphone-1  API 34  match\n"), err)
        self.stubs.assert_owner_phone_untouched(self)

    def test_another_key_is_no_match_and_the_run_says_not_all(self):
        self.installed("phone-1", 34, "debug")
        self.installed("tablet-1", 28, "elsewhere")
        code, out, _ = self.check("phone-1", "tablet-1")
        self.assertEqual(code, pre_switch.NOT_ALL)
        self.assertEqual(out.splitlines()[2:], ["phone-1  API 34  match", "tablet-1  API 28  no match"])

    def test_a_second_signer_beside_the_debug_key_is_no_match(self):
        self.installed("phone-1", 33, "debug,elsewhere")
        self.assertEqual(self.check("phone-1")[1].splitlines()[-1], "phone-1  API 33  no match")

    def test_no_install_and_no_device_are_named_as_such(self):
        self.stubs.device("tablet-1", api=36, paths=[])
        code, out, _ = self.check("tablet-1", "phone-9")
        self.assertEqual(code, pre_switch.NOT_ALL)
        self.assertEqual(out, "tablet-1  Stub Phone  physical device\nphone-9  not attached\n"
                              "tablet-1  API 36  com.shinsaikou not installed\nphone-9  API ?  not attached\n")

    def test_each_named_serial_is_described_before_anything_is_read_from_a_device(self):
        self.installed("emulator-5554", 26, "debug")
        self.installed("127.0.0.1:5555", 36, "debug")
        self.stubs.device("127.0.0.1:5555", **{**self.stubs.state["devices"]["127.0.0.1:5555"],
                                               "model": "sdk_gphone64_x86_64", "qemu": "1"})
        self.installed("phone-1", 34, "debug")
        self.stubs.device("phone-1", **{**self.stubs.state["devices"]["phone-1"], "model": "Pixel 8a"})
        code, out, err = self.check("emulator-5554", "127.0.0.1:5555", "phone-1")
        self.assertEqual(code, pre_switch.ALL_MATCH, err)
        self.assertEqual(out.splitlines()[:3], ["emulator-5554  Stub Phone  emulator",
                                                "127.0.0.1:5555  sdk_gphone64_x86_64  emulator",
                                                "phone-1  Pixel 8a  physical device"])
        adb_calls = [call for call in self.stubs.calls if call.startswith("adb ")]
        described = max(index for index, call in enumerate(adb_calls) if "getprop ro.product.model" in call)
        first_read = min(index for index, call in enumerate(adb_calls) if "ro.build.version.sdk" in call)
        self.assertLess(described, first_read, adb_calls)
        self.stubs.assert_owner_phone_untouched(self)

    def test_the_device_s_own_api_level_is_the_range_both_signers_are_read_for(self):
        self.installed("phone-1", 27, "debug")
        self.check("phone-1")
        reads = [call for call in self.stubs.calls if call.startswith("apksigner ")]
        self.assertEqual(len(reads), 2, reads)
        for call in reads:
            self.assertIn("verify --print-certs-pem --min-sdk-version 27 --max-sdk-version 27 ", call)
        self.assertTrue(any(call.endswith(str(self.debug_apk)) for call in reads))

    def test_base_apk_is_the_one_pulled_among_split_apks(self):
        self.installed("phone-1", 34, "debug", paths=["/data/app/t/split_config.arm64_v8a.apk",
                                                      "/data/app/t/base.apk"])
        self.check("phone-1")
        pulls = [call for call in self.stubs.calls if " pull " in call]
        self.assertEqual(len(pulls), 1)
        self.assertIn("pull /data/app/t/base.apk ", pulls[0])

    def test_nothing_printed_is_a_certificate_or_a_digest(self):
        self.installed("phone-1", 34, "debug")
        self.installed("tablet-1", 34, "elsewhere")
        _, out, err = self.check("phone-1", "tablet-1")
        for signer in ("debug", "elsewhere"):
            encoded = base64.b64encode(f"CERT-{signer}".encode()).decode()
            self.assertNotIn(encoded, out + err)
        self.assertNotIn("CN=", out + err)
        self.assertNotIn("CERTIFICATE", out + err)
        self.assertFalse(re.search(r"[0-9a-f]{40}", out + err))

    def test_no_serial_or_no_debug_build_is_a_usage_error(self):
        self.assertEqual(self.check()[0], 2)
        self.installed("phone-1", 34, "debug")
        code, _, err = self.check("phone-1", debug_apk=self.stubs.dir / "gone.apk")
        self.assertEqual(code, pre_switch.BAD_INPUT)
        self.assertIn("run ./gradlew :app:assembleDebug", err)
        self.assertFalse([call for call in self.stubs.calls if call.startswith("adb ")])


class GateWiringTest(unittest.TestCase):
    """release_version.py in gate.sh: phase 3 only, and the subject's literal kept for phases 0 to 2."""

    def setUp(self) -> None:
        self.gate = (TOOLS / "ci" / "gate.sh").read_text()

    def test_step_2b_checks_the_code_and_takes_the_name_inside_phase_3(self):
        step = self.gate[self.gate.index('step "2b.'):self.gate.index('step "3. unit tests"')]
        self.assertIn('python3 tools/release/release_version.py --check "$RELEASE_APK"', step)
        self.assertIn('SUBJECT_VERSION=$(python3 tools/release/release_version.py --name "$RELEASE_APK")', step)
        self.assertIn("RELEASE_APK=app/build/outputs/apk/release/app-release.apk", step)
        guard = self.gate[:self.gate.index('step "2b.')].rsplit("\n", 2)[-2]
        self.assertEqual(guard.strip(), "if phase3; then")

    def test_the_subject_is_0_1_0_until_phase_3_reads_the_build(self):
        before = self.gate[:self.gate.index('step "2b.')]
        self.assertIn("\nSUBJECT_VERSION=0.1.0\n", before)
        self.assertEqual(self.gate.count("SUBJECT_VERSION="), 2)
        self.assertIn('--subject "com.shinsaikou:$SUBJECT_VERSION:$(git rev-parse --short HEAD)"', self.gate)
        self.assertNotIn("com.shinsaikou:0.1.0:", self.gate)


if __name__ == "__main__":
    unittest.main()
