"""What the release scripts read off an APK and a device (phase 3 unit R1, Q15 and Q19).

Shared by release_version.py (the gate's versionCode check and the collect subject),
install_release.py (the owner's install) and pre_switch.py (the owner's signer check before M's
switch). Nothing here prints: a certificate is returned as its DER bytes for the caller to compare,
and no caller prints one, its digest or its fingerprint.

The SDK tools are found the way the rest of tools/ finds them: ANDROID_SDK_ROOT, then ANDROID_HOME,
then `sdk.dir` in the repository's local.properties, and the newest build-tools directory that has the
tool. Every caller also takes an explicit path, which is how the tests hand in a stub.

Python 3.11+, standard library only.
"""

from __future__ import annotations

import base64
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAST_RELEASE = ROOT / "tools" / "gate" / "last-release.json"
PACKAGE = "com.shinsaikou"

# `aapt2 dump badging` starts with: package: name='com.shinsaikou' versionCode='1' versionName='0.1.0' ...
BADGING = re.compile(r"^package: name='([^']*)' versionCode='(\d+)' versionName='([^']*)'", re.MULTILINE)
PEM = re.compile(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", re.DOTALL)


class ToolError(Exception):
    """A tool or an input the script needs is missing, or a tool failed. Exit code 2 in every caller."""


@dataclass(frozen=True)
class ApkVersion:
    package: str
    version_code: int
    version_name: str


def sdk_root() -> Path | None:
    for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        value = os.environ.get(name, "").strip()
        if value:
            return Path(value)
    local = ROOT / "local.properties"
    if local.is_file():
        for line in local.read_text().splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "sdk.dir" and value.strip():
                return Path(value.strip())
    return None


def build_tool(name: str) -> Path:
    """The newest build-tools copy of `name` (apksigner, aapt2)."""
    sdk = sdk_root()
    if sdk is None:
        raise ToolError("no SDK: set ANDROID_SDK_ROOT or ANDROID_HOME")
    def version(path: Path) -> tuple[int, ...]:
        return tuple(int(part) if part.isdigit() else 0 for part in path.parent.name.split("."))
    candidates = sorted((sdk / "build-tools").glob(f"*/{name}"), key=version, reverse=True)
    found = next((c for c in candidates if c.is_file()), None)
    if found is None:
        raise ToolError(f"no {name} under {sdk}/build-tools")
    return found


def adb() -> Path:
    sdk = sdk_root()
    if sdk is None:
        raise ToolError("no SDK: set ANDROID_SDK_ROOT or ANDROID_HOME")
    return sdk / "platform-tools" / "adb"


def run(command: list[str], what: str) -> str:
    """Runs `command` and returns its stdout; a failure is a ToolError naming `what`, not the output."""
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError as exc:
        raise ToolError(f"{what}: cannot run {command[0]}: {exc.strerror}") from exc
    if result.returncode != 0:
        raise ToolError(f"{what}: {Path(command[0]).name} exited {result.returncode}")
    return result.stdout


def apk_version(apk: Path, aapt2: Path) -> ApkVersion:
    if not apk.is_file():
        raise ToolError(f"no APK at {apk}")
    found = BADGING.search(run([str(aapt2), "dump", "badging", str(apk)], f"reading the version of {apk.name}"))
    if found is None:
        raise ToolError(f"aapt2 printed no package line for {apk.name}")
    return ApkVersion(found.group(1), int(found.group(2)), found.group(3))


def signer_certificates(apk: Path, apksigner: Path, min_sdk: int | None = None, max_sdk: int | None = None) -> list[bytes]:
    """Every signer certificate apksigner reports for `apk`, as DER bytes, for the API range given."""
    command = [str(apksigner), "verify", "--print-certs-pem"]
    if min_sdk is not None:
        command += ["--min-sdk-version", str(min_sdk)]
    if max_sdk is not None:
        command += ["--max-sdk-version", str(max_sdk)]
    output = run(command + [str(apk)], f"reading the signer of {apk.name}")
    return [base64.b64decode("".join(block.split())) for block in PEM.findall(output)]


def same_signer(expected: list[bytes], found: list[bytes]) -> bool:
    """True when both name at least one certificate and every certificate found is one expected."""
    return bool(expected) and bool(found) and set(found) <= set(expected)


def describe_device(adb_path: Path, serial: str) -> str:
    """One line saying what a named serial is, for the owner to read before a script acts on it.

    The serial, its `ro.product.model`, and `emulator` when `ro.kernel.qemu` is 1 or the serial starts
    with `emulator-`, `physical device` otherwise; `not attached` when adb cannot reach it. Nothing is
    refused on this: the line is there so a wrong serial is seen before anything is installed or read.
    """
    def prop(name: str) -> str | None:
        result = subprocess.run([str(adb_path), "-s", serial, "shell", "getprop", name],
                                capture_output=True, text=True, check=False)
        return result.stdout.strip() if result.returncode == 0 else None

    model = prop("ro.product.model")
    if model is None:
        return f"{serial}  not attached"
    emulator = serial.startswith("emulator-") or prop("ro.kernel.qemu") == "1"
    return f"{serial}  {model or 'no model name'}  {'emulator' if emulator else 'physical device'}"


def read_last_release(path: Path = LAST_RELEASE) -> int:
    """The recorded versionCode. A file without a positive integer is refused, never read as 0."""
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError as exc:
        raise ToolError(f"no {path}") from exc
    except json.JSONDecodeError as exc:
        raise ToolError(f"{path} is not JSON: {exc}") from exc
    code = data.get("versionCode") if isinstance(data, dict) else None
    if not isinstance(code, int) or isinstance(code, bool) or code < 1:
        raise ToolError(f"{path} holds no positive integer versionCode")
    return code


def write_last_release(code: int, path: Path = LAST_RELEASE) -> None:
    """Rewrites the recorded versionCode, keeping every other key the file has."""
    data = json.loads(path.read_text())
    data["versionCode"] = code
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(data, indent=2) + "\n")
    temporary.replace(path)
