#!/usr/bin/env bash
# The two library update rows (unit F of phase2-start.md), measured from the host with unit A's
# frozen harness (tools/bench/library_update.sh, ruling R66) against the build under test, and
# published as bench fragments by library_update_rows.py. gate.sh's phase 2 runs it in step 8c on the
# 4 GB AVD, the device rows.json binds both rows to.
# usage: library_update_rows.sh [--check]
#
# For each scenario, 2000x20 then 500x2:
#   1. load update-bench-saikou-<scenario>, the snapshot library_update_seed.sh saved after Saikou
#      restored the scenario's library; install the build under test over it with -r, so the data
#      stays and with it the library, the settings R66 fixes and the trusted fixture extension;
#      compile it as the seed compiles each app (`cmd package compile -m speed -f`, which an update
#      discards), check that the device holds the APK's own bytes, stop it, and save the result as
#      gate-update-saikou-<scenario>. The harness loads its snapshot itself, which would undo an
#      install made before it, so the build reaches the run through a snapshot of its own. The name
#      is outside update-bench-*, which only a scenario's library as seeded may carry, and the
#      update-bench-* snapshots are only ever loaded here, never saved.
#   2. run library_update.sh saikou <scenario> with SAIKOU_UPDATE_SNAPSHOT naming that snapshot;
#   3. publish the scenario's row: duration_ms of 2000x20 as library_update_2000x20_ms, and
#      min_rate_ratio of 500x2 as library_update_throughput_ratio. A run that exits other than 0
#      publishes nothing and ends this script, and library_update_rows.py refuses an ended run's
#      JSON as well, so no row is ever published from a run that did not measure (R36).
# Both fragments are removed first, so a run that stops part way leaves no earlier value behind.
#
# --check tests the inputs only (the APK, the SDK and both seeded snapshots on the device) and exits
# 2 naming the first one missing, so gate.sh can skip the step by name and step 8d then ends the run
# on the two rows (R36).
#
# Environment: SAIKOU_SERIAL and SAIKOU_DEVICE_ID as for every bench script; SAIKOU_BENCH_OUT, where
# the harness keeps each run's files and the fragments go (default tools/out/bench/<device>);
# SAIKOU_APK, the build under test (default app/build/outputs/apk/release/app-release.apk).
# Exit codes: 0 both rows published; 2 missing input; 3 adb or device error, or a run that ended.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PACKAGE=com.shinsaikou
SCENARIOS=(2000x20 500x2)
CHECK_ONLY=0
case "${1:-}" in
    "") ;;
    --check) CHECK_ONLY=1 ;;
    *) echo "usage: library_update_rows.sh [--check]" >&2; exit 2 ;;
esac

# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"
require_env SAIKOU_SERIAL SAIKOU_DEVICE_ID
SDK=$(sdk_root) || { echo "missing: ANDROID_SDK_ROOT" >&2; exit 2; }
export ANDROID_SDK_ROOT=$SDK
BASE_ADB="$SDK/platform-tools/adb -s $SAIKOU_SERIAL"
APK=${SAIKOU_APK:-$ROOT/app/build/outputs/apk/release/app-release.apk}
export SAIKOU_BENCH_OUT=${SAIKOU_BENCH_OUT:-$ROOT/tools/out/bench/$SAIKOU_DEVICE_ID}
say() { echo "library_update_rows: $*"; }

seeded_snapshot() { echo "update-bench-saikou-$1"; }
gate_snapshot() { echo "gate-update-saikou-$1"; }
snapshot_exists() {
    $BASE_ADB emu avd snapshot list 2>/dev/null | tr -d '\r' | awk '{print $2}' | grep -qx "$1"
}
snapshot() { # snapshot <save|load> <name>
    local answer
    answer=$($BASE_ADB emu avd snapshot "$1" "$2" 2>&1 | tr -d '\r')
    case "$answer" in
        *OK*) ;;
        *) echo "adb: snapshot $1 $2 on $SAIKOU_SERIAL answered: $answer" >&2; exit 3 ;;
    esac
}
wait_booted() {
    local waited=0
    $BASE_ADB wait-for-device || exit 3
    until [ "$($BASE_ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
        [ "$waited" -lt 120 ] || { echo "adb: $SAIKOU_SERIAL did not come back from a snapshot load" >&2; exit 3; }
        sleep 1
        waited=$((waited + 1))
    done
}

[ -s "$APK" ] || { echo "missing: $APK, the build under test" >&2; exit 2; }
for scenario in "${SCENARIOS[@]}"; do
    # Unit E's carry-over 3: the restore steps the seed runs stop on a system file picker that
    # remembers its last folder, so a seed starts from a picker with no memory.
    snapshot_exists "$(seeded_snapshot "$scenario")" || {
        echo "missing: AVD snapshot $(seeded_snapshot "$scenario") on $SAIKOU_SERIAL (seed it with" \
            "\`adb shell pm clear com.google.android.documentsui\`, then tools/bench/library_update_seed.sh" \
            "saikou base and saikou $scenario)" >&2
        exit 2
    }
done
[ "$CHECK_ONLY" = 0 ] || exit 0

APK_SHA256=$(sha256sum "$APK" | awk '{print $1}')
mkdir -p "$SAIKOU_BENCH_OUT"
rm -f "$SAIKOU_BENCH_OUT/library_update_2000x20_ms.$SAIKOU_DEVICE_ID.json" \
    "$SAIKOU_BENCH_OUT/library_update_throughput_ratio.$SAIKOU_DEVICE_ID.json"

for scenario in "${SCENARIOS[@]}"; do
    seeded=$(seeded_snapshot "$scenario")
    gate=$(gate_snapshot "$scenario")
    say "installing $(basename "$APK") (SHA-256 ${APK_SHA256:0:12}) over $seeded"
    snapshot load "$seeded"
    wait_booted
    $BASE_ADB install -r "$APK" >/dev/null || { echo "adb: installing $APK over $seeded on $SAIKOU_SERIAL failed" >&2; exit 3; }
    installed=$($BASE_ADB shell pm path "$PACKAGE" | tr -d '\r' | sed -n 's/^package://p' | head -1)
    on_device=$($BASE_ADB shell sha256sum "$installed" 2>/dev/null | tr -d '\r' | awk '{print $1}')
    [ "$on_device" = "$APK_SHA256" ] || {
        echo "adb: $PACKAGE on $SAIKOU_SERIAL is ${on_device:-unreadable} after the install, not $APK_SHA256" >&2; exit 3; }
    $BASE_ADB shell cmd package compile -m speed -f "$PACKAGE" >/dev/null || exit 3
    # As the seed leaves it: stopped and in the background, so the run's launch is a cold start.
    $BASE_ADB shell am force-stop "$PACKAGE" || exit 3
    $BASE_ADB shell input keyevent KEYCODE_HOME || exit 3
    sleep 2
    snapshot save "$gate"
    say "saved $gate on $SAIKOU_SERIAL"

    run_json=$SAIKOU_BENCH_OUT/update-saikou-$scenario.$SAIKOU_DEVICE_ID.json
    rm -f "$run_json"
    status=0
    SAIKOU_UPDATE_SNAPSHOT=$gate bash "$ROOT/tools/bench/library_update.sh" saikou "$scenario" || status=$?
    echo
    if [ "$status" -ne 0 ]; then
        echo "library_update.sh saikou $scenario exited $status on $SAIKOU_SERIAL; nothing is published for it" >&2
        [ "$status" -eq 2 ] && exit 2
        exit 3
    fi
    python3 "$HERE/library_update_rows.py" "$run_json" --scenario "$scenario" --device "$SAIKOU_DEVICE_ID" \
        --snapshot "$gate" --out "$SAIKOU_BENCH_OUT" --apk-sha256 "$APK_SHA256"
done
