#!/usr/bin/env python3
"""Before M's switch: whether each named device's installed Saikou is signed with this machine's debug key.

Phase 3 unit R1, Q15. The release key is the build machine's debug key, so the owner-signed release
installs in place with `adb install -r` only on a device whose installed com.shinsaikou carries that key.
Before it reads anything from a device, it prints one line per named serial: the serial, the device's
`ro.product.model`, and whether it is an emulator or a physical device, so a wrong serial is seen at
once. Nothing is refused on that line. Then, for each device named with --serial, it prints the
serial, its API level and one of:

    match                   the installed com.shinsaikou is signed with this machine's debug key: switch
                            in place, through install_release.py;
    no match                it is signed with some other key: Q15's fallback, not an in-place install;
    com.shinsaikou not installed nothing to switch;
    not attached            adb cannot reach the device.

Never a certificate, a digest or a fingerprint. The installed APK is read from the path
`adb shell pm path com.shinsaikou` names for `base.apk`, pulled to a temporary directory, and its signer is
read with apksigner for that device's own API level, so a key the device does not verify is not
compared. This machine's debug certificate is read the same way from the debug build this tree
produced (app/build/outputs/apk/debug/app-debug.apk by default, `./gradlew :app:assembleDebug`), which
AGP signs with the machine's debug key; the key store itself is never opened here.

It runs on the devices named with --serial and on nothing else: there is no "every device" default.

`pm path com.shinsaikou` answers for the device's current user only, so a device where com.shinsaikou is
installed under a secondary profile alone reads "com.shinsaikou not installed". That fails safe: the run
exits 1 and nothing is switched.

usage: pre_switch.py --serial SERIAL [--serial SERIAL ...] [--debug-apk APK] [--adb PATH]
                     [--apksigner PATH]
Exit codes: 0 every named device matches; 1 at least one does not, has no install, or is not
attached; 2 a missing input or tool. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk as apks  # noqa: E402

ALL_MATCH, NOT_ALL, BAD_INPUT = 0, 1, 2
DEFAULT_DEBUG_APK = apks.ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"

MATCH = "match"
NO_MATCH = "no match"
NOT_INSTALLED = f"{apks.PACKAGE} not installed"
NOT_ATTACHED = "not attached"


def adb(adb_path: Path, serial: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run([str(adb_path), "-s", serial, *args], capture_output=True, text=True, check=False)


def api_level(adb_path: Path, serial: str) -> int | None:
    result = adb(adb_path, serial, "shell", "getprop", "ro.build.version.sdk")
    value = result.stdout.strip()
    return int(value) if result.returncode == 0 and value.isdigit() else None


def base_apk_path(adb_path: Path, serial: str) -> str | None:
    """The device path of the installed base.apk, from `pm path` (one `package:<path>` line per APK)."""
    result = adb(adb_path, serial, "shell", "pm", "path", apks.PACKAGE)
    paths = [line.strip()[len("package:"):] for line in result.stdout.splitlines() if line.strip().startswith("package:")]
    return next((path for path in paths if path.endswith("/base.apk")), None)


def check(adb_path: Path, apksigner: Path, serial: str, debug_apk: Path, scratch: Path) -> tuple[int | None, str]:
    """The device's API level and its verdict."""
    api = api_level(adb_path, serial)
    if api is None:
        return None, NOT_ATTACHED
    remote = base_apk_path(adb_path, serial)
    if remote is None:
        return api, NOT_INSTALLED
    local = scratch / f"{serial.replace(':', '_').replace('/', '_')}-base.apk"
    pulled = adb(adb_path, serial, "pull", remote, str(local))
    if pulled.returncode != 0 or not local.is_file():
        raise apks.ToolError(f"{serial}: could not pull the installed {apks.PACKAGE} from the device")
    expected = apks.signer_certificates(debug_apk, apksigner, api, api)
    found = apks.signer_certificates(local, apksigner, api, api)
    return api, MATCH if apks.same_signer(expected, found) else NO_MATCH


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Whether each named device's com.shinsaikou carries this machine's debug key.",
        epilog="pm path reads the device's current user only: com.shinsaikou installed under a secondary "
               "profile alone reads as not installed.")
    parser.add_argument("--serial", action="append", required=True, help="a device to check; repeat for each")
    parser.add_argument("--debug-apk", type=Path, default=DEFAULT_DEBUG_APK,
                        help="a debug build from this machine, whose signer is the debug certificate")
    parser.add_argument("--adb", type=Path, help="default: the SDK's platform-tools adb")
    parser.add_argument("--apksigner", type=Path, help="default: the newest build-tools apksigner")
    args = parser.parse_args(argv)

    serials = [serial.strip() for serial in args.serial]
    if any(not serial for serial in serials) or len(set(serials)) != len(serials):
        print("pre_switch.py: every --serial names one device, once", file=sys.stderr)
        return BAD_INPUT
    if not args.debug_apk.is_file():
        print(f"pre_switch.py: no debug build at {args.debug_apk}; run ./gradlew :app:assembleDebug", file=sys.stderr)
        return BAD_INPUT
    verdicts: list[str] = []
    try:
        adb_path = args.adb or apks.adb()
        apksigner = args.apksigner or apks.build_tool("apksigner")
        for serial in serials:
            print(apks.describe_device(adb_path, serial))
        with tempfile.TemporaryDirectory(prefix="saikou-pre-switch-") as scratch:
            for serial in serials:
                api, verdict = check(adb_path, apksigner, serial, args.debug_apk, Path(scratch))
                verdicts.append(verdict)
                print(f"{serial}  API {api if api is not None else '?'}  {verdict}")
    except apks.ToolError as exc:
        print(f"pre_switch.py: {exc}", file=sys.stderr)
        return BAD_INPUT
    return ALL_MATCH if all(verdict == MATCH for verdict in verdicts) else NOT_ALL


if __name__ == "__main__":
    raise SystemExit(main())
