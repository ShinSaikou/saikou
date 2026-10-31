#!/usr/bin/env python3
"""Extension compatibility job.

Contract 01, section 7.2. Python 3.11+, standard library only. External programs: jdeps (JDK 17)
on every run, git when a theme map is built, adb and ./gradlew under --replay, and the Android
SDK's apksigner, which is found by path rather than on PATH. Archives are read with zipfile and
downloads with urllib, so neither unzip nor curl nor openssl is invoked any more.

Package names appear only inside the output directory; stdout prints counts.

Exit codes: 0 pass; 1 a pass criterion failed (report written); 2 missing or invalid input;
3 network failure after 3 retries; 4 a required program is missing.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree

SCHEMA_VERSION = 1
FIELDS = json.loads((Path(__file__).with_name("index_fields.json")).read_text())["messages"]
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SAMPLE_SIZE = 320
DEFAULT_SEED = 20260911
# Step 2 of tools/ci/phase0.sh builds this before step 6 runs it, so the job needs nothing built
# that the gate does not already build.
DEFAULT_APK = REPO_ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
# Only what every run invokes, because a refusal for a program the run would never have called
# is a false negative. git is reached solely when a theme map is built and adb solely under
# --replay, which checks for it at that point. unzip sat in this tuple until zipfile took over
# every archive read, and curl and openssl were listed in the docstring long after urllib and
# apksigner replaced them; none of the three is called anywhere in this file.
REQUIRED_PROGRAMS = ("jdeps",)
CLASS_NAME = re.compile(r"^[a-zA-Z_$][\w$]*(\.[a-zA-Z_$][\w$]*)+$")


def die(code: int, message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


# Contract 01 section 10: the environment wins, local.properties is the fallback, and no input is
# ever read from a file inside the repository.
LOCAL_PROPERTIES_KEYS = {
    "SAIKOU_REPO_URL": "saikou.repo.url",
    "SAIKOU_EXT_SOURCE_REPO_URL": "saikou.ext.source.repo.url",
    "SAIKOU_CATALOG_SIGNING_KEY_SHA256": "saikou.catalog.signing.key.sha256",
    "SAIKOU_COMPAT_SAMPLE_SIZE": "saikou.compat.sample.size",
    "SAIKOU_COMPAT_SEED": "saikou.compat.seed",
    "SAIKOU_FIXTURES_DIR": "saikou.fixtures.dir",
    "ANDROID_SDK_ROOT": "sdk.dir",
}


def local_properties() -> dict[str, str]:
    path = Path(os.environ.get("SAIKOU_LOCAL_PROPERTIES", REPO_ROOT / "local.properties"))
    if not path.is_file():
        return {}
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def env(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name)
    if value:
        return value
    key = LOCAL_PROPERTIES_KEYS.get(name)
    if key:
        value = local_properties().get(key)
        if value:
            return value
    return default


def platform_android_jar(sdk_root: Path) -> Path | None:
    """android.jar of platform 37, so android.* resolves for real (contract 01, decision 19).

    The SDK installs point releases as `android-37.2`, so the exact name is not assumed.
    """
    platforms = sdk_root / "platforms"
    candidates = sorted(platforms.glob("android-37*"), reverse=True) if platforms.is_dir() else []
    for candidate in candidates:
        jar = candidate / "android.jar"
        if jar.is_file():
            return jar
    return None


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# --- protobuf wire decoding (only the fields index_fields.json names) ----------------------------

def read_varint(buf: bytes, pos: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = buf[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, pos
        shift += 7


def decode_message(buf: bytes) -> dict[int, list]:
    """Returns {field number: [value, ...]}; length-delimited values stay bytes."""
    fields: dict[int, list] = {}
    pos = 0
    end = len(buf)
    while pos < end:
        key, pos = read_varint(buf, pos)
        number, wire = key >> 3, key & 0x07
        if wire == 0:
            value, pos = read_varint(buf, pos)
        elif wire == 2:
            length, pos = read_varint(buf, pos)
            value = buf[pos:pos + length]
            pos += length
        elif wire == 5:
            value = buf[pos:pos + 4]
            pos += 4
        elif wire == 1:
            value = buf[pos:pos + 8]
            pos += 8
        else:
            raise ValueError(f"unsupported wire type {wire}")
        fields.setdefault(number, []).append(value)
    return fields


def text(fields: dict[int, list], number: int, default: str = "") -> str:
    values = fields.get(number)
    if not values:
        return default
    value = values[-1]
    return value.decode("utf-8") if isinstance(value, bytes) else str(value)


def decode_index(raw: bytes, base_url: str = "") -> tuple[str, list[dict]]:
    index = decode_message(raw)
    # The signing key is field 3 of the index itself, which is where the app reads it from
    # (IndexDocument.signingKey) and what TrustStore compares an APK's signers against. It used to
    # be read out of a repo.json fetched from a base URL guessed off the configured value, which
    # was wrong twice over: the configured value is the index, and the descriptor nests the
    # fingerprint under "meta" rather than at the top level.
    signing_key = text(index, FIELDS["Index"]["signingKey"], "")
    extension_lists = index.get(FIELDS["Index"]["extensionList"], [])
    if not extension_lists:
        die(2, "malformed index: no inline extension list")
    entries = []
    for blob in extension_lists:
        for extension in decode_message(blob).get(FIELDS["ExtensionList"]["extensions"], []):
            fields = decode_message(extension)
            resources = fields.get(FIELDS["Extension"]["resources"], [b""])[-1]
            resource_fields = decode_message(resources) if resources else {}
            version_codes = fields.get(FIELDS["Extension"]["versionCode"], [0])
            # A resource url may be relative, and the app resolves it against the index url
            # (ExtensionRepoClient.resolveAgainst). urljoin against an empty base leaves an
            # absolute url alone, which is what every entry in practice already is.
            entries.append({
                "package": text(fields, FIELDS["Extension"]["packageName"]),
                "versionCode": int(version_codes[-1]),
                "libVersion": text(fields, FIELDS["Extension"]["extensionLib"]),
                "jarUrl": urllib.parse.urljoin(base_url, text(resource_fields, FIELDS["Resources"]["jarUrl"])),
                "apkUrl": urllib.parse.urljoin(base_url, text(resource_fields, FIELDS["Resources"]["apkUrl"])),
                "sources": len(fields.get(FIELDS["Extension"]["sources"], [])),
            })
    return signing_key, entries


# --- network ------------------------------------------------------------------------------------

# A full run fetches one index and then a JAR and an APK for every extension in the sample, so a
# single pass makes several hundred requests against a CDN. The old policy was three attempts with
# 1s and 2s of backoff between them, which tolerates a three second outage; two Phase 0 runs died
# on a single 500 on one file out of 640, after thirty minutes of device work had already passed.
# Retry long enough to outlive a brief 5xx, and fail immediately on a status that will never
# succeed so a wrong URL still reports in a second rather than in a minute.
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})


def fetch(url: str, retries: int = 6) -> bytes:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as exc:
            # HTTPError is a URLError, so this clause has to come first or a 404 gets retried.
            if exc.code not in RETRYABLE_STATUS:
                die(3, f"{url}: HTTP {exc.code} {exc.reason}, which no retry will fix")
            last = exc
        except (urllib.error.URLError, TimeoutError) as exc:
            last = exc
        if attempt + 1 < retries:
            time.sleep(min(2 ** attempt, 30))
    die(3, f"{url}: network failure after {retries} attempts: {last}")


def maybe_gunzip(raw: bytes) -> bytes:
    return gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw


def resolve_index(url: str, followed_redirect: bool = False) -> tuple[str, list[dict]]:
    """
    Resolves the one configured URL the way ExtensionRepoClient.fetchIndex does: gunzip, then
    dispatch on the first byte. `[` is a legacy flat index.min.json, `{` is a repo.json descriptor
    or a JSON-encoded index, anything else is the protobuf index, and a descriptor naming an
    index_v2 is followed exactly once.

    saikou.repo.url has one meaning on both sides now: the index URL, the same string the app is
    configured with. This job used to treat it as a base and append /index.pb and /repo.json, so
    the single value could not be right for both, and saikou.index.url existed to paper over it.
    """
    raw = maybe_gunzip(fetch(url))
    first = raw[:1]
    if first == b"[":
        die(2, f"{url} serves a legacy flat index.min.json, which carries no lib version and no "
               "jar url; point saikou.repo.url at the repository's repo.json or its v2 index")
    if first == b"{":
        document = json.loads(raw.decode("utf-8"))
        target = document.get("index_v2")
        if target:
            if followed_redirect:
                die(2, f"{url} redirects to another descriptor; the app follows exactly one hop")
            return resolve_index(target, followed_redirect=True)
        if "meta" in document:
            die(2, f"{url} is a repo.json descriptor with no index_v2, so the repository is legacy "
                   "and serves no lib version or jar url; this job needs a v2 index")
        die(2, f"{url} decoded as JSON but is neither a descriptor nor something this job reads")
    return decode_index(raw, base_url=url)


# --- signature verification -----------------------------------------------------------------------

def build_tools_apksigner(sdk_root: Path) -> Path | None:
    """The newest installed build-tools `apksigner`. Point releases sort correctly as strings."""
    build_tools = sdk_root / "build-tools"
    candidates = sorted(build_tools.glob("*/apksigner"), reverse=True) if build_tools.is_dir() else []
    return next((c for c in candidates if c.is_file()), None)


APK_CERTIFICATE_DIGEST = re.compile(r"certificate SHA-256 digest: ([0-9a-f]{64})")


def apk_certificate_sha256(apksigner: Path, apk: Path) -> list[str]:
    """
    Every signer certificate's SHA-256 digest, which is what the index carries as its signing key
    and what `ExtensionSignatures.fingerprints` computes on the device (sha256 of the certificate's
    DER bytes).

    This used to read `META-INF/*.RSA` out of the JAR with openssl, which was wrong twice. The JAR
    is a separate build artifact signed with a separate key, so its certificate never matched the
    index fingerprint and the signature check could not pass; and the APKs carry a v2 or v3
    signature with no PKCS#7 file in META-INF at all, so the same reader would have returned
    nothing had it been pointed at the right file. `apksigner` reads every scheme.
    """
    result = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk)],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        return []
    return APK_CERTIFICATE_DIGEST.findall(result.stdout)


# --- theme map -------------------------------------------------------------------------------------

def build_theme_map(source_repo_url: str, workdir: Path) -> dict[str, str]:
    clone = workdir / "ext-source"
    subprocess.run(
        ["git", "clone", "--depth", "1", "--filter=blob:none", "--sparse", source_repo_url, str(clone)],
        check=True, capture_output=True,
    )
    subprocess.run(["git", "-C", str(clone), "sparse-checkout", "set", "src"], check=True, capture_output=True)
    themes: dict[str, str] = {}
    for build_file in clone.rglob("build.gradle.kts"):
        match = re.search(r'theme\s*=\s*"([^"]+)"', build_file.read_text(errors="ignore"))
        themes[build_file.parent.name] = match.group(1) if match else "standalone"
    return themes


# --- the recorded sample the device replays ---------------------------------------------------------

REPLAY_SAMPLE_DIRECTORY = "compat"
REPLAY_EXPECTATIONS_FILE = "expected.json"


def write_replay_sample(sample: list[dict], fixtures_dir: Path) -> list[dict]:
    """
    Writes what CompatReplayTest reads: `<fixtures>/compat/` holding one APK per sampled entry
    plus an `expected.json` keyed by file name. Nothing this writes names a package, which is why
    the files are numbered and why the expectations are keyed by file name rather than by package.

    The APKs are the ones already fetched and checked against the index fingerprint above, which
    is the assumption the device test states: it trusts the recorded entries because the host
    verified their signatures before recording them.

    The job used to write neither, so the device test found no sample directory and returned
    without replaying anything while the run reported success.

    The numbering runs over the files actually written rather than over the sample, because the
    device sorts the directory by name and labels each entry by its position in that sorted list.
    An entry skipped here would otherwise shift every index after it and the report would blame
    the wrong extension.
    """
    directory = fixtures_dir / REPLAY_SAMPLE_DIRECTORY
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    expectations: dict[str, dict] = {}
    recorded: list[dict] = []
    for entry in sample:
        if not entry.get("apkPath"):
            print(f"warning: {entry['package']} has no verified apk and is not in the replay sample",
                  file=sys.stderr)
            continue
        name = f"{len(recorded):04d}.apk"
        shutil.copyfile(entry["apkPath"], directory / name)
        expectation: dict = {"sources": entry["sources"]}
        try:
            expectation["libVersion"] = float(entry["libVersion"])
        except ValueError:
            pass
        expectations[name] = expectation
        recorded.append({"index": len(recorded), "file": name})
    (directory / REPLAY_EXPECTATIONS_FILE).write_text(
        json.dumps(expectations, indent=2, sort_keys=True) + "\n"
    )
    return recorded


def failed_replay_indices(results_dir: Path) -> tuple[bool, set[int] | None]:
    """
    Reads the connected-test XML and answers two things: whether the replay ran at all, and which
    sample entries failed. `None` for the second means the test failed for a reason that names no
    entry (an empty sample, a crash before the loop), and every call is then scored as failed
    rather than as passed, because a run that failed for an unnamed reason proved nothing about
    any entry.
    """
    reports = sorted(results_dir.glob("*.xml")) if results_dir.is_dir() else []
    ran = False
    failed: set[int] = set()
    unnamed = False
    for report in reports:
        root = ElementTree.parse(report).getroot()
        for case in root.iter("testcase"):
            if "Replay" not in (case.get("classname") or ""):
                continue
            ran = True
            for child in case:
                if child.tag not in ("failure", "error"):
                    continue
                text = (child.get("message") or "") + (child.text or "")
                found = {int(m) for m in re.findall(r"sample entry #(\d+)", text)}
                if found:
                    failed |= found
                else:
                    unnamed = True
    return ran, None if unnamed else failed


# --- sampling ---------------------------------------------------------------------------------------

def stratify(entries: list[dict], themes: dict[str, str], size: int, seed: int) -> tuple[list[dict], dict]:
    random.seed(seed)
    pairs: dict[tuple[str, str], list[dict]] = {}
    for entry in entries:
        theme = themes.get(entry["package"].rsplit(".", 1)[-1], "unknown") if themes else "unknown"
        pairs.setdefault((theme, entry["libVersion"]), []).append(entry)
    sample: list[dict] = []
    for key in sorted(pairs):
        sample.append(random.choice(pairs[key]))
    remainder = [e for e in entries if e not in sample]
    random.shuffle(remainder)
    sample.extend(remainder[: max(0, size - len(sample))])
    sample = sample[:size]
    # A sample smaller than the number of live pairs drops coverage, which is a failure rather than
    # something to discover later: the dropped pairs are named in the report.
    covered = set()
    for entry in sample:
        theme = themes.get(entry["package"].rsplit(".", 1)[-1], "unknown") if themes else "unknown"
        covered.add((theme, entry["libVersion"]))
    stratification = {
        "themes": len({k[0] for k in pairs}),
        "libVersions": sorted({k[1] for k in pairs}),
        "missingPairs": [list(pair) for pair in sorted(set(pairs) - covered)],
    }
    return sample, stratification


# --- what the host provides -------------------------------------------------------------------------

# The set of classes an extension may reach is read out of the APK that ships, not out of any
# Gradle classpath, for two reasons.
#
# It is the truth. The APK is what an installed extension loads against, after R8 has removed and
# renamed everything the consumer rules did not keep, so a class an extension names that R8
# stripped is reported here as the real gap it is. A classpath taken from the module graph would
# hide exactly that defect, because the class is on the graph either way.
#
# And the classpath that used to be read came from `:extensions` alone, while `:app` carries
# classes `:extensions` does not. The last run of that shape reported two of them (a decoder class
# and its companion object, which reach the app through its dependency on the image view fork) as
# unresolved although both really do ship. `compat_sample_loaded_ratio` has an abs_min of 1.0, so
# the single sampled jar that named them took the ratio to 0.996875 and failed the row.
#
# The index is built here rather than handed to jdeps because jdeps does not resolve against
# `-classpath`: it reports a dependency as resolved only when the archive providing it is in its
# INPUT set, so with the extension jar as the only input every destination comes back "not found"
# however complete the classpath is. The old code read that word as the answer, and all 320 sampled
# entries reported the same 26 classes unresolved, every one of them sitting in an archive on the
# classpath jdeps had been handed.

DEX_MAGIC = b"dex\n"
DEX_HEADER_SIZE = 0x70
CLASS_DEF_ITEM_SIZE = 32
CLASSES_DEX = re.compile(r"^classes\d*\.dex$")


def dex_string(dex: bytes, offset: int, origin: str) -> str:
    """
    The `string_data_item` at `offset`: a ULEB128 length followed by MUTF-8 bytes and a NUL.

    ULEB128 and the protobuf varint decoded above are the same encoding for unsigned values, so
    `read_varint` reads both. The length it carries counts UTF-16 code units rather than bytes,
    which is why the NUL rather than the length is what ends the read. Class descriptors are ASCII
    in practice; `replace` keeps a supplementary character written the MUTF-8 way from raising,
    since a name that cannot be decoded cannot match anything either way.

    A string_id that points outside the file, a length that runs off the end of it, and a string
    with no NUL before the end of it all mean the string table is not where the header says it is,
    so each is refused by name. This function is where a file with valid dex magic and a garbage
    body used to die: `bytes.index` raises ValueError and `read_varint` raises IndexError, and a
    traceback out of either names neither the APK nor the entry inside it that could not be read.
    """
    if offset >= len(dex):
        die(2, f"{origin} is malformed: a string_id points at offset {offset}, past the end of "
               f"its {len(dex)} bytes")
    # The length prefix runs on while the high bit is set, so a prefix that reaches the end of the
    # file never terminates. Walking it here rather than catching IndexError out of read_varint is
    # what lets the refusal say which string was unreadable.
    scan = offset
    while scan < len(dex) and dex[scan] & 0x80:
        scan += 1
    if scan >= len(dex):
        die(2, f"{origin} is malformed: the string at offset {offset} carries a length that runs "
               "off the end of the file")
    _, pos = read_varint(dex, offset)
    end = dex.find(b"\x00", pos)
    if end < 0:
        die(2, f"{origin} is malformed: the string at offset {offset} reaches the end of the file "
               "with no NUL terminator")
    return dex[pos:end].decode("utf-8", errors="replace")


def dex_class_names(dex: bytes, origin: str) -> set[str]:
    """
    Every class defined by one dex file, as `foo.bar.Baz`.

    The walk is `class_defs` to `type_ids` to `string_ids` to the descriptor, using only the six
    header words the walk needs. `origin` names the file in any refusal, because a multi-dex APK
    has several and the reader is worthless if it cannot say which one it choked on.

    Those six words are checked against the size of the file before the walk reads a byte through
    them. Every offset the walk then computes lands inside one of the three validated tables, which
    is what stops `u32` answering 0 for a slice past the end of the file and turning a garbage
    header into a plausible-looking answer.

    The line those checks draw is between a file whose structure is impossible, which is refused
    whole, and a single class_def whose index no longer resolves, which is skipped in the loop
    below. Refusing the whole file is the common case for a truncated one, and deliberately so: the
    tables sit near the front, so a cut usually lands in the string data a table points into, and
    the reader cannot then tell a name it lost from a name that was never there. An index quietly
    missing a few thousand classes is the worst outcome available here, because every extension
    that names one is reported as a compatibility gap. A build artifact that arrives truncated is
    a broken build, and saying so by name costs one run and no confusion.
    """
    if dex[:4] != DEX_MAGIC:
        die(2, f"{origin} is not a dex file: it begins with {dex[:8]!r}, not the dex magic")
    if len(dex) < DEX_HEADER_SIZE:
        die(2, f"{origin} is not a dex file: {len(dex)} bytes is shorter than a dex header")

    def u32(offset: int) -> int:
        return int.from_bytes(dex[offset:offset + 4], "little")

    def table(name: str, size_offset: int, item_size: int) -> tuple[int, int]:
        """One header table's (size, offset), refused unless the whole table lies in the file."""
        size, off = u32(size_offset), u32(size_offset + 4)
        # An empty table may carry offset 0, which is why the check is skipped for one. A populated
        # table has to begin after the header and end inside the file, or the walk would be reading
        # whatever bytes happen to sit at that address. The arithmetic is Python's, so a size of
        # 0xffffffff read out of a garbage header overflows nothing: the comparison simply fails.
        if size and (off < DEX_HEADER_SIZE or off + size * item_size > len(dex)):
            die(2, f"{origin} is malformed: its header puts {size} {name} entries at offset {off}, "
                   f"which does not fit inside its {len(dex)} bytes")
        return size, off

    string_ids_size, string_ids_off = table("string_ids", 0x38, 4)
    type_ids_size, type_ids_off = table("type_ids", 0x40, 4)
    class_defs_size, class_defs_off = table("class_defs", 0x60, CLASS_DEF_ITEM_SIZE)
    names: set[str] = set()
    for item in range(class_defs_size):
        # class_idx is the first uint32 of a 32-byte class_def_item. The two bounds checks below
        # are defensive: a well-formed dex cannot index past either table, so an entry that does is
        # one the file can no longer explain, and dropping that entry is cheaper than refusing a
        # file whose remaining entries are all readable.
        class_idx = u32(class_defs_off + item * CLASS_DEF_ITEM_SIZE)
        if class_idx >= type_ids_size:
            continue
        descriptor_idx = u32(type_ids_off + class_idx * 4)
        if descriptor_idx >= string_ids_size:
            continue
        descriptor = dex_string(dex, u32(string_ids_off + descriptor_idx * 4), origin)
        # A class_def never names an array or a primitive, so this only ever drops a descriptor
        # from a malformed file, where `[Lfoo;` or `I` would otherwise enter the index as a name
        # no reference can match.
        if descriptor.startswith("L") and descriptor.endswith(";"):
            names.add(descriptor[1:-1].replace("/", "."))
    return names


def apk_class_index(apk: Path) -> set[str]:
    """Every class the shipped APK defines, read out of each of its `classes*.dex` entries."""
    names: set[str] = set()
    try:
        archive = zipfile.ZipFile(apk)
    except (zipfile.BadZipFile, OSError) as error:
        # An APK half written by a build that was interrupted reaches here as a bare library
        # traceback naming neither the file nor what was wrong with it, which is the same defect
        # the dex reader's own checks exist to avoid one layer down.
        die(2, f"{apk} cannot be read as an application package ({error}); "
               f"run ./gradlew :app:assembleRelease to write it again")
    with archive:
        entries = sorted(entry for entry in archive.namelist() if CLASSES_DEX.match(entry))
        if not entries:
            die(2, f"{apk} holds no classes.dex, so it is not an application package")
        for entry in entries:
            defined = dex_class_names(archive.read(entry), f"{apk.name}!{entry}")
            # A dex that parses cleanly and names nothing is refused here rather than allowed to
            # contribute nothing. The index is unioned with android.jar, so an empty contribution
            # still leaves thousands of names and the run carries on; what surfaces later is a
            # collapsed loadedRatio with thousands of unresolved classes, a gate row that fails for
            # a reason nobody reading the report can trace back to this entry. Failing loudly at
            # the entry that went wrong is worth the run it costs.
            if not defined:
                die(2, f"{apk.name}!{entry} is a dex file that defines no classes, so the class "
                       "index it contributes is empty")
            names |= defined
    return names


def jar_class_index(jar: Path) -> set[str]:
    """
    Every class a jar defines. Used for `android.jar`, so `android.*` resolves against the real
    platform rather than being allowlisted by prefix (contract 01 section 11, decision 19).
    """
    with zipfile.ZipFile(jar) as archive:
        return {
            entry[: -len(".class")].replace("/", ".")
            for entry in archive.namelist() if entry.endswith(".class")
        }


# --- jdeps ------------------------------------------------------------------------------------------


def run_jdeps(jar: Path, out_file: Path) -> tuple[bool, list[str]]:
    # --multi-release base is required, not optional. Several artifacts on the runtime classpath are
    # multi-release jars, and without the flag jdeps refuses the whole run with
    # "is a multi-release jar file but --multi-release option is not set". It printed that refusal
    # on STDOUT and exited 2, which the old guard below read as a successful run with no
    # dependencies, so every sample entry reported zero unresolved classes and the loaded ratio
    # published 1.0 off an analysis that never happened. `base` rather than a version number
    # because Android never reads META-INF/versions: the base classes are the ones that run.
    # -filter:archive drops dependencies inside the jar being analysed. jdeps filters same-package
    # dependencies by default, so without it every cross-package reference an extension makes to its
    # own minified classes is reported: a packed extension carries several one-letter packages of its
    # own, and since the jar is not on its own classpath they all read as unresolved. Over 20 sampled
    # jars that is 113 phantom names against 0 with the filter.
    result = subprocess.run(
        ["jdeps", "-verbose:class", "--multi-release", "base", "-filter:archive", str(jar)],
        capture_output=True, text=True, check=False,
    )
    out_file.write_text(result.stdout + result.stderr)
    # A non-zero exit is a failure whatever was printed. The old condition also required empty
    # stdout, which is exactly how the refusal above passed for success.
    if result.returncode != 0:
        return False, []
    destinations = []
    for line in result.stdout.splitlines():
        match = re.match(r"^\s*(\S+)\s+->\s+(\S+)\s+(.+)$", line)
        if match and CLASS_NAME.match(match.group(2)):
            destinations.append((match.group(2), match.group(3).strip()))
    # An extension jar that references nothing is not a thing. Zero destinations means jdeps ran
    # and read nothing, so it is reported as an error rather than as a clean result.
    if not destinations:
        return False, []
    return True, destinations


# --- main --------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Extension compatibility job.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--full", action="store_true")
    mode.add_argument("--smoke", action="store_true")
    parser.add_argument("--replay", action="store_true")
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK,
                        help="the shipped APK whose classes the sample is resolved against "
                             "(default app/build/outputs/apk/release/app-release.apk)")
    parser.add_argument("--jars-dir", type=Path)
    args = parser.parse_args()

    for program in REQUIRED_PROGRAMS:
        if shutil.which(program) is None:
            die(4, f"required program not found: {program}")

    repo_url = env("SAIKOU_REPO_URL")
    if not repo_url:
        die(2, "missing: SAIKOU_REPO_URL")
    sdk_root = env("ANDROID_SDK_ROOT") or env("ANDROID_HOME")
    if not sdk_root:
        die(2, "missing: ANDROID_SDK_ROOT")
    android_jar = platform_android_jar(Path(sdk_root))
    if android_jar is None:
        die(2, f"missing: {sdk_root}/platforms/android-37*/android.jar "
               "(install it with sdkmanager 'platforms;android-37')")
    apksigner = build_tools_apksigner(Path(sdk_root))
    if apksigner is None:
        die(2, f"missing: {sdk_root}/build-tools/*/apksigner "
               "(install it with sdkmanager 'build-tools;36.1.0')")
    # Checked here rather than at the point of use, so a missing release build costs nothing: the
    # sample below fetches 320 jars and 320 APKs before the index is ever read.
    if not args.apk.is_file():
        die(2, f"missing: {args.apk} (run ./gradlew :app:assembleRelease first)")

    seed = int(env("SAIKOU_COMPAT_SEED", str(DEFAULT_SEED)))
    size = int(env("SAIKOU_COMPAT_SAMPLE_SIZE", str(DEFAULT_SAMPLE_SIZE)))
    if args.smoke:
        # Smoke mode cannot satisfy the stratification criterion and is not meant to: 40 entries is
        # fewer than the live (theme, lib version) pairs, so missingPairs is non-empty by
        # construction and `passed` is false however healthy the catalog is. The warning below says
        # so once the real pair count is known.
        size = 40
    out = Path(env("SAIKOU_COMPAT_OUT", str(REPO_ROOT / "tools" / "out" / "compat" / utc_now())))
    out.mkdir(parents=True, exist_ok=True)
    (out / "jdeps").mkdir(exist_ok=True)

    index_signing_key, entries = resolve_index(repo_url)
    # Colons and case are how a fingerprint gets written by hand; jar_certificate_sha256 produces
    # neither, so both sides are flattened to the same shape before they are ever compared.
    fingerprint = (env("SAIKOU_CATALOG_SIGNING_KEY_SHA256") or index_signing_key).replace(":", "").strip().lower()
    if not fingerprint:
        die(2, "the index carries no signing key in field 3 and none is set in the environment")

    themes: dict[str, str] = {}
    source_repo_url = env("SAIKOU_EXT_SOURCE_REPO_URL")
    with tempfile.TemporaryDirectory() as tmp:
        if source_repo_url:
            themes = build_theme_map(source_repo_url, Path(tmp))
        elif args.full:
            die(2, "missing: SAIKOU_EXT_SOURCE_REPO_URL (required by --full)")
        else:
            print("warning: no theme map, stratifying by lib version only", file=sys.stderr)

        sample, stratification = stratify(entries, themes, size, seed)
        if stratification["missingPairs"]:
            print(f"warning: a sample of {size} covers "
                  f"{len(stratification['missingPairs'])} fewer (theme, lib version) pairs than the "
                  "catalog holds, so this run cannot pass; use --full", file=sys.stderr)
        (out / "sample.txt").write_text("\n".join(e["package"] for e in sample) + "\n")

        jars_dir = args.jars_dir or (out / "jars")
        jars_dir.mkdir(parents=True, exist_ok=True)
        apks_dir = out / "apks"
        apks_dir.mkdir(parents=True, exist_ok=True)
        signature = {"checked": 0, "passed": 0, "failed": []}
        jars: list[tuple[dict, Path]] = []
        # The JAR is what jdeps reads and the APK is what is signed with the key the index names
        # and what the device replays, so both are fetched. Neither substitutes for the other.
        for entry in sample:
            jar = jars_dir / f"{entry['package']}-{entry['versionCode']}.jar"
            if not jar.is_file():
                jar.write_bytes(fetch(entry["jarUrl"]))
            jars.append((entry, jar))
            if not entry["apkUrl"]:
                signature["failed"].append(entry["package"])
                signature["checked"] += 1
                continue
            apk = apks_dir / f"{entry['package']}-{entry['versionCode']}.apk"
            if not apk.is_file():
                apk.write_bytes(fetch(entry["apkUrl"]))
            entry["apkPath"] = str(apk)
            signature["checked"] += 1
            if fingerprint in apk_certificate_sha256(apksigner, apk):
                signature["passed"] += 1
            else:
                signature["failed"].append(entry["package"])

        provided = apk_class_index(args.apk) | jar_class_index(android_jar)
        jdeps_result = {"invoked": 0, "errors": 0, "providedClasses": len(provided)}
        unresolved: dict[str, set[str]] = {}
        unresolved_jars: set[str] = set()
        pkg_freq: dict[str, int] = {}
        class_freq: dict[str, int] = {}
        for entry, jar in jars:
            ok, destinations = run_jdeps(jar, out / "jdeps" / f"{entry['package']}.txt")
            jdeps_result["invoked"] += 1
            if not ok:
                jdeps_result["errors"] += 1
                continue
            for destination, _ in destinations:
                class_freq[destination] = class_freq.get(destination, 0) + 1
                package = destination.rsplit(".", 1)[0]
                pkg_freq[package] = pkg_freq.get(package, 0) + 1
                # android.* resolves for real against the indexed android.jar; only java.* is
                # allowlisted by prefix (contract 01 section 11, decision 19).
                if destination not in provided and not destination.startswith("java."):
                    unresolved.setdefault(destination, set()).add(entry["package"])
                    unresolved_jars.add(entry["package"])

    (out / "unresolved.txt").write_text(
        "".join(f"{name} {len(users)}\n" for name, users in sorted(unresolved.items()))
    )

    replay: dict = {"ran": False, "calls": []}
    if args.replay:
        serial = env("SAIKOU_SERIAL")
        fixtures = env("SAIKOU_FIXTURES_DIR")
        if not serial or not fixtures:
            die(2, "missing: SAIKOU_SERIAL or SAIKOU_FIXTURES_DIR (required by --replay)")
        if shutil.which("adb") is None:
            die(4, "required program not found: adb")
        recorded = write_replay_sample(sample, Path(fixtures))
        if not recorded:
            die(2, "the sample carries no apk url, so there is nothing for the device to replay")
        # `adb push dir dest` nests the source inside dest when dest already exists, so a second
        # run lands the tree at /data/local/tmp/saikou-fixtures/<basename> and CompatReplayTest
        # reports its sample missing. Removing the target first is what keeps the path stable.
        device_fixtures = "/data/local/tmp/saikou-fixtures"
        subprocess.run(["adb", "-s", serial, "shell", "rm", "-rf", device_fixtures], check=False)
        subprocess.run(["adb", "-s", serial, "push", fixtures, device_fixtures], check=True)
        # check=False on purpose. A failed replay is the result this job exists to record, and
        # aborting here would leave the report unwritten and the gate row unpublished, which reads
        # as "not measured" rather than as "measured and failed".
        subprocess.run(
            [str(REPO_ROOT / "gradlew"), ":app:connectedDebugAndroidTest",
             "-Pandroid.testInstrumentationRunnerArguments.class=com.shinsaikou.compat.CompatReplayTest",
             f"-Pandroid.testInstrumentationRunnerArguments.saikou.fixtures.dir={device_fixtures}"],
            cwd=REPO_ROOT, check=False, env={**os.environ, "ANDROID_SERIAL": serial},
        )
        ran, failed = failed_replay_indices(
            REPO_ROOT / "app" / "build" / "outputs" / "androidTest-results" / "connected" / "debug"
        )
        # `ran` comes from the XML rather than from Gradle's exit code, because a class filter that
        # matches nothing is a silent pass and would otherwise publish a passing row over a replay
        # that never happened.
        replay["ran"] = ran
        replay["calls"] = [
            {**call, "ok": ran and failed is not None and call["index"] not in failed}
            for call in recorded
        ]

    passed = (
        jdeps_result["invoked"] > 0
        and jdeps_result["errors"] == 0
        and not signature["failed"]
        and not unresolved
        and not stratification["missingPairs"]
    )
    report = {
        "schemaVersion": SCHEMA_VERSION,
        "ranAt": utc_now(),
        "mode": "full" if args.full else ("smoke" if args.smoke else "default"),
        "sampleSize": len(sample),
        "seed": seed,
        "stratification": stratification,
        "signature": signature,
        "jdeps": jdeps_result,
        # The share of the sample whose every class reference resolves against what the shipped
        # APK provides; collect.py turns this into the compat_sample_loaded_ratio gate row.
        "loadedRatio": (len(sample) - len(unresolved_jars)) / len(sample) if sample else 0.0,
        "unresolvedJars": sorted(unresolved_jars),
        "unresolved": [
            {"class": name, "referencedBy": sorted(users), "count": len(users)}
            for name, users in sorted(unresolved.items())
        ],
        "pkgFreq": pkg_freq,
        "classFreq": class_freq,
        "replay": replay,
        "pass": passed,
    }
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    summary = "\n".join([
        f"ran at {report['ranAt']}",
        f"mode {report['mode']}, sample {report['sampleSize']}, seed {seed}",
        f"signatures checked {signature['checked']}, passed {signature['passed']}, failed {len(signature['failed'])}",
        f"jdeps invoked {jdeps_result['invoked']}, errors {jdeps_result['errors']}, "
        f"provided classes {jdeps_result['providedClasses']}",
        f"unresolved classes {len(unresolved)}",
        f"replay ran {replay['ran']}",
        f"pass {passed}",
    ])
    (out / "summary.txt").write_text(summary + "\n")
    print(summary)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
