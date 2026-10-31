#!/usr/bin/env bash
# The row release_build_owner_signed (unit R1 of phase3-start.md, Q18), measured on the host: the
# release was built with -Psaikou.ownerSigning=true and verifyReleaseSigning reports a match in every
# API range. gate.sh's phase 3 runs it in step 2b, after step 2 built the release with the flag.
# usage: release_signing_row.sh <out dir>
#
#   1. remove the task's report and the row's fragment, so nothing an earlier run left can be read
#      as this run's;
#   2. run `:app:verifyReleaseSigning -Psaikou.ownerSigning=true`. The task never reads as up to date,
#      and it builds the release first when the flag or the key changed since step 2;
#   3. hand the report, the second step 2 started and Gradle's exit status to release_signing_row.py,
#      which writes the fragment into <out dir> only when the report is this run's, says the flag was
#      on, and holds a match for each of the three ranges.
#
# A refused run publishes nothing and exits 1, which fails the gate step; the Gradle log is kept in
# <out dir> as release-signing-gradle.txt either way. It holds match or no match per range and never a
# certificate or a digest.
# Exit codes: 0 the row is published; 1 refused; 2 usage.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
case $# in
    1) OUT=$1 ;;
    *) echo "usage: release_signing_row.sh <out dir>" >&2; exit 2 ;;
esac
[ -n "$OUT" ] || { echo "usage: release_signing_row.sh <out dir>, and the directory is not blank" >&2; exit 2; }
REPORT=$ROOT/app/build/outputs/release-signing/verifyReleaseSigning.json

mkdir -p "$OUT" && OUT=$(cd "$OUT" && pwd) || { echo "cannot create $OUT" >&2; exit 2; }
rm -f "$OUT/release_build_owner_signed.any.json" "$REPORT"

cd "$ROOT"
since=$(date -u +%s)
./gradlew :app:verifyReleaseSigning -Psaikou.ownerSigning=true > "$OUT/release-signing-gradle.txt" 2>&1
gradle_exit=$?
grep -E '^API [0-9]' "$OUT/release-signing-gradle.txt" | sed 's/^/    /'
[ "$gradle_exit" -eq 0 ] || tail -30 "$OUT/release-signing-gradle.txt" >&2
python3 "$HERE/release_signing_row.py" "$REPORT" --out "$OUT" --since "$since" --gradle-exit "$gradle_exit"
