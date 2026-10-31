#!/usr/bin/env python3
"""Every type the sampled extensions ask Injekt for, against what InjektBootstrap registers.

The compatibility job and CompatReplayTest only load extensions. An extension that asks Injekt for
a type the host never registered loads fine and then throws on first use, typically from a
file-level initializer, so neither of them sees it. This reads the requests out of the APKs
instead, with no device.

Extension bytecode reaches Injekt only through `InjektScope.getInstance(FullTypeReference.getType())`
(the reified `Injekt.get<T>()` and `injectLazy<T>()`), and each call site compiles to an anonymous
`FullTypeReference<T>` subclass whose only record of T is its Signature annotation. `dexdump -a`
prints that annotation, so the requested types are read from there. The registered types are read
from the `Injekt.addSingleton<T>` and `Injekt.addSingletonFactory<T>` calls in InjektBootstrap.kt,
resolved through its imports.

Run:

    python3 tools/compat/injekt_types.py                  # <saikou.fixtures.dir>/compat/*.apk
    python3 tools/compat/injekt_types.py --apks <dir>

Standard library only; the one external program is the SDK's dexdump, found under the newest
build-tools of ANDROID_SDK_ROOT (or sdk.dir in local.properties) unless --dexdump names it. Prints
type names and fixture file names, never a package name: a requested type outside the packages
the host provides is printed as a placeholder, since its name would be the extension's own.

Exit codes: 0 every requested type is registered; 1 at least one is not; 2 missing or invalid
input; 4 dexdump is missing.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from compat_job import REPO_ROOT, env  # noqa: E402

BOOTSTRAP = REPO_ROOT / "extensions" / "src" / "main" / "kotlin" / "com" / "shinsaikou" / "extensions" / "di" / "InjektBootstrap.kt"
FULL_TYPE_REFERENCE = re.compile(
    r'Ldalvik/annotation/Signature; value=\{ "Luy/kohesive/injekt/api/FullTypeReference<" (.*) ">;" \}'
)
REGISTRATION = re.compile(r"Injekt\.addSingleton(?:Factory)?<\s*([\w.]+)\s*>")
IMPORT = re.compile(r"^import\s+([\w.]+)\s*$", re.MULTILINE)
PACKAGE = re.compile(r"^package\s+([\w.]+)\s*$", re.MULTILINE)
# The packages whose classes the host itself provides to extensions. A type outside them is one the
# extension defines, and its name is printed as EXTENSION_TYPE instead.
HOST_PACKAGES = (
    "android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.", "okhttp3.", "okio.", "rx.",
    "org.jsoup.", "uy.kohesive.", "nl.adaptivity.", "app.cash.", "com.squareup.", "tachiyomi.",
    "eu.kanade.tachiyomi.network.", "eu.kanade.tachiyomi.source.", "eu.kanade.tachiyomi.util.",
)
EXTENSION_TYPE = "<a type the extension defines>"


def die(code: int, message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def registered_types(kotlin_source: str) -> set[str]:
    """The fully qualified types a Kotlin file registers with Injekt, resolved through its imports."""
    imports = {name.rsplit(".", 1)[-1]: name for name in IMPORT.findall(kotlin_source)}
    package_match = PACKAGE.search(kotlin_source)
    package = package_match.group(1) if package_match else ""
    types = set()
    for name in REGISTRATION.findall(kotlin_source):
        if "." in name:
            types.add(name)
        elif name in imports:
            types.add(imports[name])
        else:
            types.add(f"{package}.{name}" if package else name)
    return types


def signature_to_name(signature: str) -> str:
    """`Ljava/util/List<Ljava/lang/String;>;` to `java.util.List<java.lang.String>`."""
    name = re.sub(r"L([\w/$]+)", lambda m: m.group(1).replace("/", "."), signature)
    name = name.replace(";>", ">").rstrip(";")
    return name.replace(";", ", ")


def requested_types(dexdump_output: str) -> set[str]:
    """The T of every `FullTypeReference<T>` subclass in one APK's `dexdump -a` output."""
    types = set()
    for match in FULL_TYPE_REFERENCE.finditer(dexdump_output):
        pieces = re.findall(r'"([^"]*)"', match.group(1))
        types.add(signature_to_name("".join(pieces)))
    return types


def printable(type_name: str) -> str:
    return type_name if type_name.startswith(HOST_PACKAGES) else EXTENSION_TYPE


def find_dexdump() -> Path | None:
    sdk = env("ANDROID_SDK_ROOT") or env("ANDROID_HOME")
    if not sdk:
        return None
    build_tools = Path(sdk) / "build-tools"
    if not build_tools.is_dir():
        return None

    def version_key(path: Path) -> list[int]:
        return [int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", path.name)]

    for directory in sorted(build_tools.iterdir(), key=version_key, reverse=True):
        candidate = directory / "dexdump"
        if candidate.is_file():
            return candidate
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apks", type=Path, help="directory of extension APKs (default <saikou.fixtures.dir>/compat)")
    parser.add_argument("--bootstrap", type=Path, default=BOOTSTRAP, help="the Kotlin file that registers the types")
    parser.add_argument("--dexdump", type=Path, help="the dexdump binary (default: newest SDK build-tools)")
    args = parser.parse_args(argv)

    apk_dir = args.apks
    if apk_dir is None:
        fixtures = env("SAIKOU_FIXTURES_DIR")
        if not fixtures:
            die(2, "no --apks and no SAIKOU_FIXTURES_DIR or saikou.fixtures.dir to find <fixtures>/compat in")
        apk_dir = Path(fixtures) / "compat"
    apks = sorted(apk_dir.glob("*.apk")) if apk_dir.is_dir() else []
    if not apks:
        die(2, f"no APKs in {apk_dir}")
    if not args.bootstrap.is_file():
        die(2, f"no registry source at {args.bootstrap}")
    dexdump = args.dexdump or find_dexdump()
    if dexdump is None or not dexdump.is_file():
        die(4, "dexdump not found: pass --dexdump or set ANDROID_SDK_ROOT")

    registered = registered_types(args.bootstrap.read_text())
    requesters: dict[str, list[str]] = defaultdict(list)
    for apk in apks:
        # Dex strings are modified UTF-8, which Python's codec refuses for some surrogates and NULs;
        # the signatures read here are plain ASCII, so a replaced byte elsewhere costs nothing.
        result = subprocess.run([str(dexdump), "-a", str(apk)], capture_output=True, encoding="utf-8",
                                errors="replace")
        if result.returncode != 0:
            die(2, f"dexdump failed on {apk.name} (exit {result.returncode})")
        for type_name in requested_types(result.stdout):
            requesters[type_name].append(apk.name)

    missing = sorted(t for t in requesters if t not in registered)
    print(f"{len(apks)} APKs; {len({a for names in requesters.values() for a in names})} ask Injekt for "
          f"{len(requesters)} types; InjektBootstrap registers {len(registered)}")
    for type_name, names in sorted(requesters.items(), key=lambda item: (-len(item[1]), item[0])):
        state = "registered" if type_name in registered else "NOT REGISTERED"
        print(f"  {len(names):4d}  {printable(type_name)}  {state}")
    for type_name in missing:
        print(f"{printable(type_name)} is not registered; asked for by {', '.join(requesters[type_name])}")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
