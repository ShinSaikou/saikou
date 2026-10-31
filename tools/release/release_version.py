#!/usr/bin/env python3
"""The release build's versionCode against tools/gate/last-release.json, and its versionName.

Phase 3 unit R1, Q19. gate.sh's phase 3 step 2b runs `--check` on the release APK it just built:

    versionCode below the recorded one   fails: the owner's devices hold a higher code and would
                                         refuse the install;
    equal                                passes, as Android allows an equal code, so an ordinary run
                                         after M's switch stays green;
    above                                passes.

A release cut for the owner has to be above the recorded code, and that is install_release.py's rule,
not this one's. `--name` prints the versionName alone, which is what the gate's collect subject reads
in phase 3 instead of the literal it used before.

Both read the APK itself with `aapt2 dump badging`, so the answer is the build's and not a source
file's.

usage: release_version.py --check <apk> [--last FILE] [--aapt2 PATH]
       release_version.py --name <apk> [--aapt2 PATH]
Exit codes: 0 passed, or the name was printed; 1 below the recorded code; 2 a missing input or tool.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk as apks  # noqa: E402

PASSED, BELOW, BAD_INPUT = 0, 1, 2


def verdict(version_code: int, recorded: int) -> tuple[int, str]:
    """The exit code and the line to print for a build of `version_code` against `recorded`."""
    if version_code < recorded:
        return BELOW, (f"versionCode {version_code} is below {recorded}, the code recorded as on the owner's "
                       f"devices, which would refuse this build as a downgrade")
    relation = "equal to" if version_code == recorded else "above"
    return PASSED, f"versionCode {version_code} is {relation} the recorded {recorded}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="The release's versionCode against last-release.json.")
    what = parser.add_mutually_exclusive_group(required=True)
    what.add_argument("--check", type=Path, metavar="APK", help="fail when its versionCode is below the recorded one")
    what.add_argument("--name", type=Path, metavar="APK", help="print its versionName")
    parser.add_argument("--last", type=Path, default=apks.LAST_RELEASE, help="the recorded versionCode")
    parser.add_argument("--aapt2", type=Path, help="default: the newest build-tools aapt2")
    args = parser.parse_args(argv)

    try:
        aapt2 = args.aapt2 or apks.build_tool("aapt2")
        version = apks.apk_version(args.check or args.name, aapt2)
        if version.package != apks.PACKAGE:
            raise apks.ToolError(f"the APK is {version.package}, not {apks.PACKAGE}")
        if args.name:
            if not version.version_name.strip():
                raise apks.ToolError("the APK has no versionName")
            print(version.version_name)
            return PASSED
        recorded = apks.read_last_release(args.last)
    except apks.ToolError as exc:
        print(f"release_version.py: {exc}", file=sys.stderr)
        return BAD_INPUT
    code, line = verdict(version.version_code, recorded)
    print(f"{line} ({version.package} {version.version_name})", file=sys.stdout if code == PASSED else sys.stderr)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
