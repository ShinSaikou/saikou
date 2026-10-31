#!/usr/bin/env bash
# The row export_reimports_upstream_lossless (unit R2 of phase3-start.md, Q18), measured on the host:
# R2's two :backup JVM tests run in this gate run and pass. gate.sh's phase 3 runs it in step 3b.
# usage: upstream_lossless_row.sh <out dir>
#
#   1. remove both classes' JUnit XML and the row's fragment, so nothing an earlier run left can be
#      read as this run's;
#   2. run `:backup:testDebugUnitTest --rerun` filtered to the two classes. --rerun forces the task
#      to execute rather than be served from the build cache or skipped as up to date;
#   3. hand the results directory, the second step 2 started and Gradle's exit status to
#      upstream_lossless_row.py, which writes the fragment into <out dir> only when both classes wrote
#      fresh XML with at least one test, none failed, errored or skipped, and Gradle exited 0.
#
# A refused run publishes nothing and exits 1, which fails the gate step; the Gradle log is kept in
# <out dir> as upstream-lossless-gradle.txt either way.
# Exit codes: 0 the row is published; 1 refused; 2 usage.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
case $# in
    1) OUT=$1 ;;
    *) echo "usage: upstream_lossless_row.sh <out dir>" >&2; exit 2 ;;
esac
[ -n "$OUT" ] || { echo "usage: upstream_lossless_row.sh <out dir>, and the directory is not blank" >&2; exit 2; }
CLASSES=(com.shinsaikou.backup.UpstreamBackupFieldsTest com.shinsaikou.backup.UpstreamLosslessExportTest)
RESULTS=$ROOT/backup/build/test-results/testDebugUnitTest

mkdir -p "$OUT" && OUT=$(cd "$OUT" && pwd) || { echo "cannot create $OUT" >&2; exit 2; }
rm -f "$OUT/export_reimports_upstream_lossless.any.json"
filters=()
for cls in "${CLASSES[@]}"; do
    rm -f "$RESULTS/TEST-$cls.xml"
    filters+=(--tests "$cls")
done

cd "$ROOT"
since=$(date -u +%s)
./gradlew :backup:testDebugUnitTest --rerun "${filters[@]}" > "$OUT/upstream-lossless-gradle.txt" 2>&1
gradle_exit=$?
[ "$gradle_exit" -eq 0 ] || tail -30 "$OUT/upstream-lossless-gradle.txt" >&2
python3 "$HERE/upstream_lossless_row.py" "$RESULTS" --out "$OUT" --since "$since" --gradle-exit "$gradle_exit"
