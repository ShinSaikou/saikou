#!/usr/bin/env python3
"""Installs a release build on the devices the owner names, and records its versionCode (Q19).

Phase 3 unit R1. From M's switch on, every build the owner installs raises versionCode by hand
(contract 01 decision 14), and tools/gate/last-release.json records the code on the owner's devices.
This is the one script that moves that record:

  1. it reads the APK's package, versionCode and versionName with `aapt2 dump badging`, and refuses
     anything that is not com.shinsaikou, or whose versionCode is not above the recorded one;
  2. it prints one line per named serial with the device's `ro.product.model` and whether it is an
     emulator or a physical device, so a wrong serial is seen before anything is installed (nothing
     is refused on that line), and checks every named device is attached before it installs on any
     of them, so a missing device does not leave the others half switched;
  3. it runs `adb -s <serial> install -r <apk>` on each named device in turn, and reads the installed
     versionCode back from `dumpsys package`, since an install that printed Success is not proof;
  4. only when every named device took the build does it rewrite last-release.json with the new
     code. After a partial failure the file is left as it was, so the same APK can be installed again
     on the devices that failed, and the output names them.

It runs on the devices named with --serial and on nothing else: there is no "every device" default,
because a machine with an emulator running would otherwise update it as if it were the owner's.

usage: install_release.py --serial SERIAL [--serial SERIAL ...] [--apk APK] [--last FILE]
                          [--adb PATH] [--aapt2 PATH]
Exit codes: 0 installed everywhere and recorded; 1 refused, or an install failed; 2 a missing input
or tool. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk as apks  # noqa: E402

INSTALLED, REFUSED, BAD_INPUT = 0, 1, 2
DEFAULT_APK = apks.ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
INSTALLED_CODE = re.compile(r"^\s*versionCode=(\d+)", re.MULTILINE)


def adb(adb_path: Path, serial: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([str(adb_path), "-s", serial, *args], capture_output=True, text=True, check=False)


def attached(adb_path: Path, serial: str) -> bool:
    result = adb(adb_path, serial, "get-state")
    return result.returncode == 0 and result.stdout.strip() == "device"


def install(adb_path: Path, serial: str, apk: Path, version_code: int) -> str | None:
    """Installs on one device; None on success, or why it failed."""
    result = adb(adb_path, serial, "install", "-r", str(apk))
    output = (result.stdout + result.stderr).strip()
    if result.returncode != 0 or "Success" not in output.splitlines():
        last = output.splitlines()[-1] if output else f"adb exited {result.returncode}"
        return f"the install failed: {last}"
    dump = adb(adb_path, serial, "shell", "dumpsys", "package", apks.PACKAGE)
    codes = {int(code) for code in INSTALLED_CODE.findall(dump.stdout)}
    if version_code not in codes:
        return f"adb said Success but the device reports versionCode {sorted(codes) or 'none'} for {apks.PACKAGE}"
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Install a release on the named devices and record its versionCode.")
    parser.add_argument("--serial", action="append", required=True, help="a device to install on; repeat for each")
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--last", type=Path, default=apks.LAST_RELEASE, help="the recorded versionCode")
    parser.add_argument("--adb", type=Path, help="default: the SDK's platform-tools adb")
    parser.add_argument("--aapt2", type=Path, help="default: the newest build-tools aapt2")
    args = parser.parse_args(argv)

    serials = [serial.strip() for serial in args.serial]
    if any(not serial for serial in serials) or len(set(serials)) != len(serials):
        print("install_release.py: every --serial names one device, once", file=sys.stderr)
        return BAD_INPUT
    try:
        adb_path = args.adb or apks.adb()
        version = apks.apk_version(args.apk, args.aapt2 or apks.build_tool("aapt2"))
        recorded = apks.read_last_release(args.last)
    except apks.ToolError as exc:
        print(f"install_release.py: {exc}", file=sys.stderr)
        return BAD_INPUT

    if version.package != apks.PACKAGE:
        print(f"refused: {args.apk} is {version.package}, not {apks.PACKAGE}", file=sys.stderr)
        return REFUSED
    if version.version_code <= recorded:
        print(f"refused: versionCode {version.version_code} ({version.version_name}) is not above {recorded}, the "
              f"code recorded in {args.last}. Raise versionCode in app/build.gradle.kts and build again.",
              file=sys.stderr)
        return REFUSED
    for serial in serials:
        print(apks.describe_device(adb_path, serial))
    missing = [serial for serial in serials if not attached(adb_path, serial)]
    if missing:
        print(f"refused: not attached: {', '.join(missing)}. Nothing was installed.", file=sys.stderr)
        return REFUSED

    print(f"installing {version.package} {version.version_name} (versionCode {version.version_code}) over {recorded}")
    failed: dict[str, str] = {}
    for serial in serials:
        problem = install(adb_path, serial, args.apk, version.version_code)
        if problem is None:
            print(f"  {serial}: installed, versionCode {version.version_code}")
        else:
            failed[serial] = problem
            print(f"  {serial}: {problem}", file=sys.stderr)
    if failed:
        print(f"{args.last} left at {recorded}: {len(failed)} of {len(serials)} device(s) did not take the "
              f"install ({', '.join(failed)}). Run again naming those devices.", file=sys.stderr)
        return REFUSED
    apks.write_last_release(version.version_code, args.last)
    print(f"{args.last} now records versionCode {version.version_code}; commit it.")
    return INSTALLED


if __name__ == "__main__":
    raise SystemExit(main())
