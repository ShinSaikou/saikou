#!/usr/bin/env python3
"""The two APKs a GitHub release attaches, checked against each other and named as the release carries them.

A release carries the app as `app-release.apk` (the site's Download button links to
`releases/latest/download/app-release.apk`, and the release app's updater takes only that file) and
the publicDebug build as `app-debug.apk`, the debug APK that installs next to the app as
com.shinsaikou.debug and that the debug app's updater takes. Gradle names the second
`app-publicDebug.apk`, so this script gives both their release names in one directory, after checking
that they belong together:

    the release APK is com.shinsaikou and the debug APK com.shinsaikou.debug;
    the release's versionName is a plain version, which the tag is made from (v<versionName>);
    both have the same versionCode, and the debug APK's versionName is the release's plus -debug;
    both carry the same signer certificates, so when the release is owner-signed (verifyReleaseSigning)
    the debug APK is too, and a debug APK from one release updates the one from the last.

The updater of 0.1.0 and 0.1.1 takes the only `.apk` of a release, so it finds none in a release that
carries two. Until tools/gate/last-release.json records FIRST_TWO_APK_UPDATER or more, and for the
release at that versionCode itself, only `app-release.apk` is copied (the debug APK is still checked)
and the script says why; from the release after it the pair is copied.

Nothing is copied unless every check passes, and every failed run (a refused pair, a missing APK, a
tool that failed) also removes the two copies an earlier run left in the output directory, so no stale
pair is uploaded by mistake. On success it prints the `gh release create` command that attaches what
was copied. Like the other release scripts it never prints a certificate, a digest or a fingerprint.

usage: release_assets.py [--release APK] [--debug APK] [--out DIR] [--last PATH] [--repo OWNER/NAME]
                         [--aapt2 PATH] [--apksigner PATH]
Exit codes: 0 copied; 1 a check failed and nothing was copied; 2 a missing input or tool, nothing copied.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apk as apks  # noqa: E402

COPIED, REFUSED, BAD_INPUT = 0, 1, 2

OUTPUTS = apks.ROOT / "app" / "build" / "outputs"
DEFAULT_RELEASE = OUTPUTS / "apk" / "release" / "app-release.apk"
DEFAULT_DEBUG = OUTPUTS / "apk" / "publicDebug" / "app-publicDebug.apk"
DEFAULT_OUT = OUTPUTS / "release-assets"
DEFAULT_REPO = "ShinSaikou/saikou"

RELEASE_NAME = "app-release.apk"
DEBUG_NAME = "app-debug.apk"
DEBUG_PACKAGE = apks.PACKAGE + ".debug"
DEBUG_SUFFIX = "-debug"
PLAIN_VERSION = re.compile(r"\d+(\.\d+)*")

# The versionCode of 0.1.2, the first release whose updater tells app-release.apk from app-debug.apk.
# Every earlier updater finds no APK in a release that carries both, so app-debug.apk is attached
# only once last-release.json records this code or more, and never to the release at this code.
FIRST_TWO_APK_UPDATER = 5


def problems(release: apks.ApkVersion, debug: apks.ApkVersion, same_signer: bool) -> list[str]:
    """Every reason the two APKs do not belong to one release; empty when they do."""
    found = []
    if release.package != apks.PACKAGE:
        found.append(f"the release APK is {release.package}, not {apks.PACKAGE}")
    if debug.package != DEBUG_PACKAGE:
        found.append(f"the debug APK is {debug.package}, not {DEBUG_PACKAGE}")
    if not PLAIN_VERSION.fullmatch(release.version_name):
        found.append(f"the release's versionName {release.version_name} is not a plain version for a tag")
    if debug.version_code != release.version_code:
        found.append(f"the debug APK's versionCode {debug.version_code} is not the release's {release.version_code}")
    if debug.version_name != release.version_name + DEBUG_SUFFIX:
        found.append(f"the debug APK's versionName {debug.version_name} is not {release.version_name}{DEBUG_SUFFIX}")
    if not same_signer:
        found.append("the two APKs are not signed with the same key")
    return found


def ships_debug(recorded: int, release_code: int) -> bool:
    """Whether the release at `release_code` attaches app-debug.apk, with `recorded` the code in last-release.json."""
    return recorded >= FIRST_TWO_APK_UPDATER and release_code > FIRST_TWO_APK_UPDATER


def remove_outputs(out: Path) -> None:
    """Removes the APKs an earlier run left in `out`, so a failed run leaves nothing to upload."""
    for name in (RELEASE_NAME, DEBUG_NAME):
        (out / name).unlink(missing_ok=True)


def failed(out: Path, code: int) -> int:
    remove_outputs(out)
    print(f"release_assets.py: nothing was copied, and {display(out)} holds neither APK", file=sys.stderr)
    return code


def display(path: Path) -> str:
    """`path` from the repository's root when it is inside it, which is where the owner runs gh from."""
    try:
        return str(path.resolve().relative_to(apks.ROOT))
    except ValueError:
        return str(path)


def gh_command(version_name: str, out: Path, names: list[str], repo: str) -> str:
    assets = " ".join(display(out / name) for name in names)
    return f"gh release create v{version_name} {assets} -R {repo} --notes-file <notes>"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check the release and debug APKs and name them for the release.")
    parser.add_argument("--release", type=Path, default=DEFAULT_RELEASE, help="the release build's APK")
    parser.add_argument("--debug", type=Path, default=DEFAULT_DEBUG, help="the publicDebug build's APK")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="where app-release.apk and app-debug.apk go")
    parser.add_argument("--last", type=Path, default=apks.LAST_RELEASE, help="the recorded versionCode")
    parser.add_argument("--repo", default=DEFAULT_REPO, help="the repository the printed command publishes to")
    parser.add_argument("--aapt2", type=Path, help="default: the newest build-tools aapt2")
    parser.add_argument("--apksigner", type=Path, help="default: the newest build-tools apksigner")
    args = parser.parse_args(argv)

    for path, task in ((args.release, "assembleRelease"), (args.debug, "assemblePublicDebug")):
        if not path.is_file():
            print(f"release_assets.py: no APK at {path}; run ./gradlew :app:{task}", file=sys.stderr)
            return failed(args.out, BAD_INPUT)
    try:
        aapt2 = args.aapt2 or apks.build_tool("aapt2")
        apksigner = args.apksigner or apks.build_tool("apksigner")
        release = apks.apk_version(args.release, aapt2)
        debug = apks.apk_version(args.debug, aapt2)
        release_signers = set(apks.signer_certificates(args.release, apksigner))
        debug_signers = set(apks.signer_certificates(args.debug, apksigner))
        recorded = apks.read_last_release(args.last)
    except apks.ToolError as exc:
        print(f"release_assets.py: {exc}", file=sys.stderr)
        return failed(args.out, BAD_INPUT)

    found = problems(release, debug, bool(release_signers) and release_signers == debug_signers)
    if found:
        for problem in found:
            print(f"release_assets.py: {problem}", file=sys.stderr)
        return failed(args.out, REFUSED)

    copies = [(args.release, RELEASE_NAME, release)]
    if ships_debug(recorded, release.version_code):
        copies.append((args.debug, DEBUG_NAME, debug))
    remove_outputs(args.out)
    try:
        args.out.mkdir(parents=True, exist_ok=True)
        for source, name, _ in copies:
            shutil.copyfile(source, args.out / name)
    except OSError as exc:
        print(f"release_assets.py: copying into {display(args.out)} failed: {exc.strerror}", file=sys.stderr)
        return failed(args.out, BAD_INPUT)
    for _, name, version in copies:
        print(f"{version.package} {version.version_name} ({version.version_code}) -> {display(args.out / name)}")
    if len(copies) == 1:
        print(f"{DEBUG_NAME} is left out of this release: an install below versionCode {FIRST_TWO_APK_UPDATER} "
              f"(0.1.0, 0.1.1) has an updater that finds no APK in a release with two. {display(args.last)} records "
              f"{recorded}; {DEBUG_NAME} is attached from the first release above {FIRST_TWO_APK_UPDATER} once it "
              f"records {FIRST_TWO_APK_UPDATER} or more.")
    print(gh_command(release.version_name, args.out, [name for _, name, _ in copies], args.repo))
    return COPIED


if __name__ == "__main__":
    raise SystemExit(main())
