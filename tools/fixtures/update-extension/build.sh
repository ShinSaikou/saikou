#!/usr/bin/env bash
# Builds the library update fixture extension (ruling R66) and writes what the fixture server's
# repository serves: the APK and its signer's SHA-256, the fingerprint both hosts compare against a
# repository's signing key to trust an extension with no prompt.
# usage: build.sh [out dir, default tools/out/update-fixture]
# Exit codes: 0 ok; 2 missing input; 4 the build or the signer check failed.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
# shellcheck source=../../common/localprops.sh
source "$ROOT/tools/common/localprops.sh"

OUT=${1:-$ROOT/tools/out/update-fixture}
SDK=$(sdk_root) || { echo "missing: ANDROID_SDK_ROOT" >&2; exit 2; }
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK
# pipefail makes a glob that matches nothing end the script at the assignment, with no message,
# so both tool lookups here end in `|| true` and the test after each says what is missing.
APKSIGNER=$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
[ -x "$APKSIGNER" ] || { echo "missing: apksigner under $SDK/build-tools" >&2; exit 2; }

# A build of its own, run with the product's wrapper. -p makes this directory the root, so the
# product's settings file is never read and this build is never part of it.
"$ROOT/gradlew" -p "$HERE" --console=plain :extension:assembleRelease || exit 4

APK="$HERE/extension/build/outputs/apk/release/extension-release.apk"
[ -s "$APK" ] || { echo "the build left no APK at $APK" >&2; exit 4; }

# The host classes (the ABI, OkHttp, the Kotlin runtime) are compile only. A copy of any of them in
# the dex would be loaded ahead of the host's by both hosts' child-first class loaders, so the APK is
# refused here rather than found out as a linkage error inside an app. The check has no skip: no
# dexdump, a dexdump that fails, and a dump that lists no class each end the build, because each
# would otherwise read the same as a dex with nothing foreign in it.
DEXDUMP=$(ls -d "$SDK"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true)
[ -x "$DEXDUMP" ] || { echo "missing: dexdump under $SDK/build-tools" >&2; exit 2; }
dump_err=$(mktemp)
trap 'rm -f "$dump_err"' EXIT
dump=$("$DEXDUMP" "$APK" 2>"$dump_err") || {
    echo "dexdump failed on $APK:" >&2
    head -20 "$dump_err" >&2
    exit 4
}
classes=$(printf '%s\n' "$dump" | sed -n "s/^  Class descriptor  : 'L\([^;]*\);'/\1/p")
[ -n "$classes" ] || { echo "dexdump listed no classes in $APK" >&2; exit 4; }
foreign=$(printf '%s\n' "$classes" | grep -v '^app/fixture/libraryupdate/' || true)
if [ -n "$foreign" ]; then
    echo "the APK carries classes outside the fixture's package:" >&2
    # A here-string, not a pipe: head closing early would end a pipe with SIGPIPE (exit 141) under
    # pipefail before the exit code below.
    head -20 <<<"$foreign" >&2
    exit 4
fi
echo "dex $(printf '%s\n' "$classes" | wc -l) classes, all under app/fixture/libraryupdate/"

fingerprint=$("$APKSIGNER" verify --print-certs "$APK" |
    sed -n 's/^Signer #1 certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p')
[ -n "$fingerprint" ] || { echo "apksigner printed no signer digest for $APK" >&2; exit 4; }

mkdir -p "$OUT"
cp "$APK" "$OUT/update-fixture.apk"
printf '%s\n' "$fingerprint" > "$OUT/signer.sha256"
sha256sum "$OUT/update-fixture.apk" | cut -d' ' -f1 > "$OUT/apk.sha256"
# The signer's digest stays in the file the server reads and is not printed.
echo "$OUT/update-fixture.apk"
echo "apk $(cat "$OUT/apk.sha256")"
