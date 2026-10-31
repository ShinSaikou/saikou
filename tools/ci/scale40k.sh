#!/usr/bin/env bash
# Phase 5's scale rows (design/plans/phase5-start.md section 4.1), measured by ScaleRowsTest at
# 40,000 entries of 50 chapters on the AVDs that section names. Unit SC0 wrote it and recorded the
# first run; SC1 to SC6 run it again after each fix. Nothing here asserts a target, and nothing
# reaches rows.json, measured.json or the release gate: the output is one scale40k-<date>.json in
# measured.json's shape, in the output folder.
#
# usage: scale40k.sh [--dry-run] <device set>
#   <device set>  the AVDs to run, by API level or device id, comma separated: 36 or pixel9_4g,
#                 26 or api26_2g, 28 or lowram_1_5g, or all. They run in that order whatever order
#                 they are named in: the 4 GB phone pushes the export the API 26 phone joins, and its
#                 restore time decides whether the low-RAM phone restores at all.
#   --dry-run     prints the plan, every step and the rows each one publishes, and touches nothing:
#                 no build, no file, no lock, no adb call.
#
# For each AVD, one at a time: wait for MemAvailable, take its lock in the shared lock folder, boot
# it, remove any leftover install with pm uninstall (never -wipe-data: saikou_pixel9_4g keeps R66's
# named snapshots), disable the Play Store for the session, install the debug app and its test APK
# (built once), push the backup, then run each method of the device's plan in an `am instrument` of
# its own, so a process that dies takes one row with it. Before each method the app is force-stopped
# and logcat cleared; after it the `am instrument` output and logcat are kept beside the rows, and a
# row the method did not publish is published by the runner as "failed" with how the process ended
# (scale40k.py), an out-of-memory death named as one. Then collect.py --instrumentation pulls the fragments, the app is uninstalled, the
# Play Store enabled again, the emulator stopped and the lock released.
#
# The restore runs first on the 4 GB phone, on an empty install, and the app's data is cleared after
# it: its 40,000 entries are favourites, which deleteNonLibraryManga never removes. The library the
# other rows read is then seeded once, timed as seed_40k_ms. library_open_ms_40k is three cold starts,
# one `am instrument` each with saikou.scale.sample k/3; the third publishes the median. The first
# push and then the fingerprint run last: once a sign-in is stored and no sync has succeeded, every
# process start begins a sync (SyncTriggers.onAppStart), and at 40,000 entries that run fills the
# heap, so no row after the sign-in measures alone (the third attempt's queue, download and stats
# rows ran beside it). The push is timed first, on an idle library. The write's stall during the
# fingerprint (unit SC6) reads the fingerprint with no sign-in, so it runs before both. The join
# runs on the API 26 phone after the 4 GB phone has stopped, against the same WebDAV fixture, which
# lives for the whole run: one emulator at a time, and the locks are never taken out of the shared
# order. The low-RAM phone runs the rows that fit a 384 MB class, the
# fingerprint last (R131 item 4 expects its death), the restore only when the 4 GB restore took
# under 20 minutes, and then oom_count_40k from what its methods counted and how they ended.
#
# Environment:
#   SAIKOU_AVD_LOCKS     the shared AVD lock folder; a lock is the folder <name> made in it (required)
#   SAIKOU_AVD_BOOT      a command that boots an AVD by name and prints its serial last (required)
#   ANDROID_SDK_ROOT or ANDROID_HOME, and whatever ANDROID_ADB_SERVER_PORT the host's adb uses
#   SAIKOU_SCALE_OUT     the output folder (default tools/out/scale40k/<UTC time>)
#   SAIKOU_SCALE_WORK    the backup file and the fixture's state (default tools/out/scale40k-work)
#   SAIKOU_SCALE_ENTRIES, SAIKOU_SCALE_CHAPTERS           the library (40000 and 50)
#   SAIKOU_SCALE_LOWRAM_ENTRIES, SAIKOU_SCALE_LOWRAM_CHAPTERS   the low-RAM phone's (the same)
#   SAIKOU_SCALE_SKIP_BUILD=1   install the APKs already built
#   SAIKOU_SCALE_METHOD_TIMEOUT_S   how long one method may run before the host stops waiting (1800:
#                 the sync rows' targets are 10 minutes, and a wedged process holds the AVD's lock
#                 for 30 minutes, not the 4 hours this was, which let a wedged export hold the 4 GB
#                 phone's lock for 17 minutes in SC5's run)
#   SAIKOU_SCALE_OWNER   the lock's owner line (default scale40k.sh)
#   SAIKOU_SCALE_LATENCY_MS   the fixture's --latency-ms, held by every request (default 0; phase 5
#                       unit SC3 measures the sync rows at 0 and at 60, a server reached over the internet)
#   SAIKOU_SCALE_PLAN    all (default), or sync: only the sync rows and the seed the push needs, so the
#                       4 GB phone seeds and pushes, the API 26 phone joins and the low-RAM phone runs nothing
# Exit codes: 0 the run ended and the JSON is written (rows inside it may be "failed"); 2 usage or
# missing input; 3 the build, the fixture or the collect failed.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PACKAGE=com.shinsaikou
CLASS=com.shinsaikou.gate.ScaleRowsTest
GATE_DIR=/data/local/tmp/saikou-gate
SCALE_DIR=/data/local/tmp/saikou-scale
DAV_USER=saikou
DAV_PASSWORD=fixture-dav-password
LOWRAM_RESTORE_LIMIT_MS=1200000
MEM_FLOOR_KB=8388608
APP_APK=$ROOT/app/build/outputs/apk/debug/app-debug.apk
TEST_APK=$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

usage() { echo "usage: scale40k.sh [--dry-run] <device set: 36,26,28 | pixel9_4g,api26_2g,lowram_1_5g | all>" >&2; exit 2; }

DRY_RUN=0
SET=
for arg in "$@"; do
    case $arg in
        --dry-run) DRY_RUN=1 ;;
        -*) usage ;;
        *) [ -z "$SET" ] || usage; SET=$arg ;;
    esac
done
[ -n "$SET" ] || usage

PLAN=${SAIKOU_SCALE_PLAN:-all}
case $PLAN in
    all | sync) ;;
    *) echo "scale40k: SAIKOU_SCALE_PLAN is all or sync, not $PLAN" >&2; exit 2 ;;
esac

want_pixel9=0 want_api26=0 want_lowram=0
for token in ${SET//,/ }; do
    case $token in
        all) want_pixel9=1 want_api26=1 want_lowram=1 ;;
        36 | pixel9_4g) want_pixel9=1 ;;
        26 | api26_2g) want_api26=1 ;;
        28 | lowram_1_5g) want_lowram=1 ;;
        *) echo "scale40k: no device $token in the set" >&2; usage ;;
    esac
done
DEVICES=()
[ $want_pixel9 = 1 ] && DEVICES+=(pixel9_4g)
[ $want_api26 = 1 ] && DEVICES+=(api26_2g)
# The low-RAM phone runs no sync row.
[ $want_lowram = 1 ] && [ "$PLAN" = all ] && DEVICES+=(lowram_1_5g)
[ ${#DEVICES[@]} -gt 0 ] || { echo "scale40k: no device of the set runs a row of the $PLAN plan" >&2; usage; }

ENTRIES=${SAIKOU_SCALE_ENTRIES:-40000}
CHAPTERS=${SAIKOU_SCALE_CHAPTERS:-50}
LOWRAM_ENTRIES=${SAIKOU_SCALE_LOWRAM_ENTRIES:-$ENTRIES}
LOWRAM_CHAPTERS=${SAIKOU_SCALE_LOWRAM_CHAPTERS:-$CHAPTERS}
for size in "$ENTRIES" "$CHAPTERS" "$LOWRAM_ENTRIES" "$LOWRAM_CHAPTERS"; do
    [[ $size =~ ^[1-9][0-9]*$ ]] || { echo "scale40k: a library size must be a positive number: $size" >&2; exit 2; }
done
METHOD_TIMEOUT_S=${SAIKOU_SCALE_METHOD_TIMEOUT_S:-1800}
LATENCY_MS=${SAIKOU_SCALE_LATENCY_MS:-0}
[[ $LATENCY_MS =~ ^(0|[1-9][0-9]*)$ ]] || { echo "scale40k: the latency must be 0 or more milliseconds: $LATENCY_MS" >&2; exit 2; }
OUT=${SAIKOU_SCALE_OUT:-$ROOT/tools/out/scale40k/$(date -u +%Y%m%dT%H%M%SZ)}
WORK=${SAIKOU_SCALE_WORK:-$ROOT/tools/out/scale40k-work}
OWNER=${SAIKOU_SCALE_OWNER:-scale40k.sh}
DATE=$(date -u +%Y-%m-%d)
RESULT=$OUT/scale40k-$DATE.json
BACKUP=$WORK/backup.tachibk

avd_of() {
    case $1 in
        pixel9_4g) echo saikou_pixel9_4g ;;
        api26_2g) echo saikou_api26_2g ;;
        lowram_1_5g) echo saikou_lowram_1_5g ;;
    esac
}

# One device's plan, a step per line: `<kind> <method> <rows, comma separated> [<sample>]`.
#   run              the method, its rows published by it or, when it did not, by the runner;
#   restore-if-fast  run, when the 4 GB phone's restore_40k_ms is under LOWRAM_RESTORE_LIMIT_MS;
#   join-if-pushed   run, when the 4 GB phone's sync_first_push_40k_ms is a number;
#   clear            pm clear the app (method and rows are "-");
#   oom              oom_count_40k from the device's rows and deaths (method "-").
steps_of() {
    if [ "$PLAN" = sync ]; then
        all_steps_of "$1" | awk '$2 == "theSeedIsTimed" || $2 == "theFirstPushIsTimed" || $2 == "theJoinIsTimed"'
    else
        all_steps_of "$1"
    fi
}

all_steps_of() {
    local open=(
        "run theLibraryOpenIsTimed library_open_ms_40k 1/3"
        "run theLibraryOpenIsTimed library_open_ms_40k 2/3"
        "run theLibraryOpenIsTimed library_open_ms_40k 3/3"
    )
    case $1 in
        pixel9_4g) printf '%s\n' \
            "run theRestoreIsTimed restore_40k_ms,restore_40k_oom_count" \
            "clear - -" \
            "run theSeedIsTimed seed_40k_ms" \
            "${open[@]}" \
            "run theLibraryChangeIsTimed library_change_ms_40k" \
            "run theLibrarySearchIsTimed library_search_ms_40k" \
            "run theExportIsTimed export_40k_ms" \
            "run theUpdateQueueBuildIsTimed update_queue_build_ms_40k" \
            "run theDownloadNextRowIsTimed download_next_ms_at_5000" \
            "run theStatsOpenIsTimed stats_open_ms_40k" \
            "run theFingerprintWriteStallIsTimed fingerprint_write_stall_ms" \
            "run theFirstPushIsTimed sync_first_push_40k_ms" \
            "run theFingerprintIsTimed fingerprint_40k_ms,fingerprint_40k_oom" ;;
        api26_2g) printf '%s\n' \
            "join-if-pushed theJoinIsTimed sync_join_40k_ms" ;;
        lowram_1_5g) printf '%s\n' \
            "restore-if-fast theRestoreIsTimed restore_40k_ms,restore_40k_oom_count" \
            "clear - -" \
            "run theSeedIsTimed seed_40k_ms" \
            "${open[@]}" \
            "run theLibraryChangeIsTimed library_change_ms_40k" \
            "run theLibrarySearchIsTimed library_search_ms_40k" \
            "run theFingerprintWriteStallIsTimed fingerprint_write_stall_ms" \
            "run theExportIsTimed export_40k_ms" \
            "run theFingerprintIsTimed fingerprint_40k_ms,fingerprint_40k_oom" \
            "oom - oom_count_40k" ;;
    esac
}

# Every row id a device's plan names, one per line.
rows_of() { steps_of "$1" | awk '$3 != "-" { n = split($3, ids, ","); for (i = 1; i <= n; i++) print ids[i] }' | sort -u; }

sizes_of() { if [ "$1" = lowram_1_5g ]; then echo "$LOWRAM_ENTRIES $LOWRAM_CHAPTERS"; else echo "$ENTRIES $CHAPTERS"; fi; }

describe() { # describe <device> <kind> <method> <rows> [<sample>]
    local rows=${4//,/ } sample=${5:+ sample $5}
    case $2 in
        run) echo "$1: $3$sample -> $rows" ;;
        restore-if-fast) echo "$1: $3$sample -> $rows (only when pixel9_4g's restore_40k_ms is under $LOWRAM_RESTORE_LIMIT_MS ms)" ;;
        join-if-pushed) echo "$1: $3$sample -> $rows (only when pixel9_4g's sync_first_push_40k_ms is a number)" ;;
        clear) echo "$1: pm clear $PACKAGE" ;;
        oom) echo "$1: the methods' out-of-memory counts and deaths -> $rows" ;;
    esac
}

if [ $DRY_RUN = 1 ]; then
    echo "plan: devices ${DEVICES[*]}, one at a time"
    echo "plan: library $ENTRIES entries of $CHAPTERS chapters; lowram_1_5g $LOWRAM_ENTRIES of $LOWRAM_CHAPTERS"
    echo "plan: rows $PLAN"
    echo "plan: output $RESULT"
    echo "plan: each method may run $METHOD_TIMEOUT_S s before the host stops waiting"
    echo "build: ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest${SAIKOU_SCALE_SKIP_BUILD:+ (skipped: SAIKOU_SCALE_SKIP_BUILD)}"
    echo "backup: tools/bench/big_backup.py $BACKUP --entries $ENTRIES --chapters $CHAPTERS"
    echo "fixture: tools/bench/sync_fixture_server.py --port 0 --latency-ms $LATENCY_MS, the devices' url http://10.0.2.2:<port>/dav/ as $DAV_USER"
    for device in "${DEVICES[@]}"; do
        avd=$(avd_of "$device")
        echo "$device: MemAvailable at least $((MEM_FLOOR_KB / 1048576)) GB, lock $avd, boot, pm uninstall leftovers, Play Store off, install both APKs, push the backup"
        while read -r kind method rows sample; do
            describe "$device" "$kind" "$method" "$rows" "$sample"
        done < <(steps_of "$device")
        echo "$device: collect.py --instrumentation, uninstall, Play Store on, emu kill, release $avd"
    done
    echo "collect: collect.py --bench $OUT/rows -> $RESULT"
    exit 0
fi

[ -n "${SAIKOU_AVD_LOCKS:-}" ] || { echo "missing: SAIKOU_AVD_LOCKS, the shared AVD lock folder" >&2; exit 2; }
[ -n "${SAIKOU_AVD_BOOT:-}" ] || { echo "missing: SAIKOU_AVD_BOOT, the command that boots an AVD and prints its serial" >&2; exit 2; }
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
[ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ] || { echo "missing: ANDROID_SDK_ROOT with platform-tools/adb" >&2; exit 2; }
export ANDROID_SDK_ROOT=$SDK ANDROID_HOME=$SDK
ADB_BIN=$SDK/platform-tools/adb

mkdir -p "$OUT/rows" "$WORK" || { echo "cannot create $OUT or $WORK" >&2; exit 2; }
STATUS=$OUT/status
note() { echo "$(date -Iseconds) $*" | tee -a "$STATUS"; }

# MemAvailable at least the floor before a build or a boot; the wait is recorded.
await_memory() { # await_memory <what>
    local waited=0 available
    while :; do
        available=$(awk '/^MemAvailable:/ { print $2 }' /proc/meminfo)
        [ "$available" -ge "$MEM_FLOOR_KB" ] && break
        sleep 60
        waited=$((waited + 60))
    done
    note "MemAvailable ${available} kB before $1, after waiting ${waited} s"
}

SERIAL= AVD= VENDING=0 FIXTURE_PID=
adb_() { "$ADB_BIN" -s "$SERIAL" "$@" < /dev/null; }

stop_device() {
    [ -n "$AVD" ] || return 0
    if [ -n "$SERIAL" ]; then
        adb_ uninstall "$PACKAGE.test" >> "$DEVICE_OUT/teardown.log" 2>&1
        adb_ uninstall "$PACKAGE" >> "$DEVICE_OUT/teardown.log" 2>&1
        adb_ shell "rm -rf $SCALE_DIR /storage/emulated/0/Android/data/$PACKAGE*" >> "$DEVICE_OUT/teardown.log" 2>&1
        if [ $VENDING = 1 ]; then
            adb_ shell pm enable com.android.vending >> "$DEVICE_OUT/teardown.log" 2>&1
            note "$AVD: Play Store enabled again, exit=$?"
        fi
        adb_ emu kill >> "$DEVICE_OUT/teardown.log" 2>&1
        local waited=0
        while "$ADB_BIN" devices < /dev/null | grep -q "^$SERIAL[[:space:]]" && [ $waited -lt 60 ]; do
            sleep 2
            waited=$((waited + 2))
        done
    fi
    rm -rf "${SAIKOU_AVD_LOCKS:?}/$AVD"
    note "$AVD: stopped, lock released"
    SERIAL= AVD= VENDING=0
}

stop_fixture() {
    [ -n "$FIXTURE_PID" ] || return 0
    kill "$FIXTURE_PID" 2> /dev/null
    wait "$FIXTURE_PID" 2> /dev/null
    note "fixture stopped"
    FIXTURE_PID=
}

trap 'stop_device; stop_fixture' EXIT
trap 'exit 130' INT TERM

note "scale40k.sh at $(git -C "$ROOT" rev-parse --short HEAD) on ${DEVICES[*]}, library $ENTRIES x $CHAPTERS, lowram $LOWRAM_ENTRIES x $LOWRAM_CHAPTERS, rows $PLAN, fixture latency $LATENCY_MS ms"

if [ -z "${SAIKOU_SCALE_SKIP_BUILD:-}" ]; then
    await_memory "the build"
    (cd "$ROOT" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest) > "$OUT/build.log" 2>&1
    build_exit=$?
    note "build exit=$build_exit"
    [ $build_exit = 0 ] || exit 3
fi
[ -f "$APP_APK" ] && [ -f "$TEST_APK" ] || { echo "missing: $APP_APK or $TEST_APK" >&2; exit 2; }

python3 "$ROOT/tools/bench/big_backup.py" "$BACKUP" --entries "$ENTRIES" --chapters "$CHAPTERS" > "$OUT/backup.txt" 2>&1 \
    || { note "big_backup.py failed: $(tail -1 "$OUT/backup.txt")"; exit 3; }
note "backup: $(cat "$OUT/backup.txt")"

mkdir -p "$OUT/fixture"
python3 "$ROOT/tools/bench/sync_fixture_server.py" --log "$OUT/fixture/requests.log" --port 0 \
    --state-root "$WORK/fixture" --dav-user "$DAV_USER" --dav-password "$DAV_PASSWORD" --latency-ms "$LATENCY_MS" \
    > "$OUT/fixture/stdout.txt" 2>&1 &
FIXTURE_PID=$!
PORT=
for _ in $(seq 1 300); do
    PORT=$(sed -n 's/^ready \([0-9][0-9]*\)$/\1/p' "$OUT/fixture/stdout.txt")
    [ -n "$PORT" ] && break
    kill -0 "$FIXTURE_PID" 2> /dev/null || break
    sleep 0.1
done
[ -n "$PORT" ] || { note "the fixture did not start: $(cat "$OUT/fixture/stdout.txt")"; exit 3; }
note "fixture ready on port $PORT"
PAIR=(-e saikou.sync.pair.url "http://10.0.2.2:$PORT/dav/" -e saikou.sync.pair.user "$DAV_USER"
    -e saikou.sync.pair.password "$DAV_PASSWORD")

start_device() { # start_device <device>; sets SERIAL, AVD, DEVICE_OUT, RUNNER
    local device=$1 started
    AVD=$(avd_of "$device")
    DEVICE_OUT=$OUT/$device
    mkdir -p "$DEVICE_OUT"
    : > "$DEVICE_OUT/deaths.txt"
    await_memory "booting $AVD"
    started=$(date +%s)
    until mkdir "$SAIKOU_AVD_LOCKS/$AVD" 2> /dev/null; do sleep 30; done
    echo "$OWNER $(date -Iseconds)" > "$SAIKOU_AVD_LOCKS/$AVD/owner"
    note "$AVD: lock taken after $(($(date +%s) - started)) s"
    SERIAL=$($SAIKOU_AVD_BOOT "$AVD" 2>> "$DEVICE_OUT/boot.log" | tail -1)
    if [ -z "$SERIAL" ]; then note "$AVD: boot failed"; return 1; fi
    note "$AVD: up as $SERIAL"
    local package
    for package in $(adb_ shell pm list packages "$PACKAGE" | tr -d '\r' | sed 's/^package://'); do
        adb_ uninstall "$package" >> "$DEVICE_OUT/boot.log" 2>&1
    done
    # An uninstall right after boot can leave Android/data/com.shinsaikou* owned by the old app id (R136).
    adb_ shell "rm -rf /storage/emulated/0/Android/data/$PACKAGE* /storage/emulated/0/Android/obb/$PACKAGE* /storage/emulated/0/Android/media/$PACKAGE*" \
        >> "$DEVICE_OUT/boot.log" 2>&1
    # The Play Store updates WebView in the background and can kill a test process (R122 CO6).
    if adb_ shell pm list packages com.android.vending | grep -q vending; then
        adb_ shell pm disable-user --user 0 com.android.vending >> "$DEVICE_OUT/boot.log" 2>&1 && VENDING=1
    fi
    adb_ logcat -G 16M >> "$DEVICE_OUT/boot.log" 2>&1
    adb_ install -r -t "$APP_APK" >> "$DEVICE_OUT/boot.log" 2>&1 || { note "$AVD: the app did not install"; return 1; }
    adb_ install -r -t "$TEST_APK" >> "$DEVICE_OUT/boot.log" 2>&1 || { note "$AVD: the test APK did not install"; return 1; }
    RUNNER=$(adb_ shell pm list instrumentation | tr -d '\r' | sed -n "s/^instrumentation:\([^ ]*\) (target=${PACKAGE//./\\.})$/\1/p" | head -1)
    [ -n "$RUNNER" ] || { note "$AVD: no instrumentation targets $PACKAGE"; return 1; }
    local id
    for id in $(rows_of "$device"); do adb_ shell rm -f "$GATE_DIR/$id.json"; done
    note "$AVD: installed, runner $RUNNER, Play Store disabled: $VENDING"
}

# One method in its own am instrument, and the runner's "failed" for every row it left unpublished.
run_method() { # run_method <device> <method> <rows> [<sample>]
    local device=$1 method=$2 rows=$3 sample=${4:-} tag status started since id entries chapters
    read -r entries chapters <<< "$(sizes_of "$device")"
    tag=$method${sample:+.${sample/\//of}}
    local sampled=()
    [ -n "$sample" ] && sampled=(-e saikou.scale.sample "$sample")
    adb_ shell am force-stop "$PACKAGE"
    # The method's logcat starts at the device's clock now (-T), whether or not the clear below
    # took: API 26 answered `logcat -b all -c` with "failed to clear the 'main' log" in the smoke
    # run, and on API 28 the first run's low-RAM fingerprint logcat held the export's death.
    since=$(adb_ shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
    adb_ logcat -b all -c 2> /dev/null || adb_ logcat -b main -b system -b crash -c
    started=$(date +%s)
    note "$device: $tag started"
    timeout "$METHOD_TIMEOUT_S" "$ADB_BIN" -s "$SERIAL" shell am instrument -w -r -e class "$CLASS#$method" \
        -e saikou.scale.40k true -e saikou.device.id "$device" -e saikou.scale.entries "$entries" \
        -e saikou.scale.chapters "$chapters" "${sampled[@]}" "${PAIR[@]}" "$RUNNER" \
        < /dev/null > "$DEVICE_OUT/$tag.instrument.txt" 2>&1
    status=$?
    [ $status = 124 ] && adb_ shell am force-stop "$PACKAGE"
    adb_ logcat -d -b main,system,crash -v threadtime -T "$since" > "$DEVICE_OUT/$tag.logcat.txt" 2>&1
    grep -a 'SaikouScaleRows' "$DEVICE_OUT/$tag.logcat.txt" > "$DEVICE_OUT/$tag.scale-log.txt"
    grep -q '^INSTRUMENTATION_CODE: -1' "$DEVICE_OUT/$tag.instrument.txt" || echo "$tag" >> "$DEVICE_OUT/deaths.txt"
    note "$device: $tag ended, exit=$status, $(($(date +%s) - started)) s"
    # The sampled method publishes on its last sample only.
    if [ -n "$sample" ] && [ "${sample%/*}" != "${sample#*/}" ]; then return 0; fi
    for id in ${rows//,/ }; do
        [ "$(adb_ shell "[ -f $GATE_DIR/$id.json ] && echo published" | tr -d '\r')" = published ] && continue
        python3 "$HERE/scale40k.py" failed "$OUT/rows/$device/$id.json" --id "$id" --device "$device" --method "$method" \
            --instrument "$DEVICE_OUT/$tag.instrument.txt" --logcat "$DEVICE_OUT/$tag.logcat.txt" --status "$status" \
            | while read -r line; do note "$line"; done
    done
}

skip_rows() { # skip_rows <device> <method> <rows> <reason>
    local id
    for id in ${3//,/ }; do
        python3 "$HERE/scale40k.py" failed "$OUT/rows/$1/$id.json" --id "$id" --device "$1" --method "$2" --reason "$4" \
            | while read -r line; do note "$line"; done
    done
}

# The device's fragments, pulled by collect.py into its folder; each row of the plan copied to
# rows/<device>/ unless the runner already published it there.
collect_device() { # collect_device <device>
    local device=$1 id found subject
    subject="$PACKAGE:debug:$(git -C "$ROOT" rev-parse --short HEAD)"
    mkdir -p "$OUT/rows/$device"
    python3 "$ROOT/tools/bench/collect.py" --bench "$OUT/rows/$device" --instrumentation "$SERIAL" \
        --subject "$subject" --out "$DEVICE_OUT/collected.json" > "$DEVICE_OUT/collect.log" 2>&1
    note "$device: collect.py exit=$?"
    for id in $(rows_of "$device"); do
        [ -f "$OUT/rows/$device/$id.json" ] && continue
        found=$(find "$DEVICE_OUT/instrumentation/$SERIAL" -name "$id.json" | head -1)
        if [ -n "$found" ]; then cp "$found" "$OUT/rows/$device/$id.json"; fi
    done
}

run_device() { # run_device <device>
    local device=$1 kind method rows sample steps step value
    if ! start_device "$device"; then
        mkdir -p "$OUT/rows/$device"
        while read -r kind method rows sample; do
            [ "$rows" = - ] || [ "$kind" = clear ] || skip_rows "$device" "$method" "$rows" "the AVD $(avd_of "$device") did not start"
        done < <(steps_of "$device")
        stop_device
        return
    fi
    adb_ shell mkdir -p "$SCALE_DIR"
    adb_ push "$BACKUP" "$SCALE_DIR/backup.tachibk" >> "$DEVICE_OUT/boot.log" 2>&1 \
        || note "$device: the backup did not push"
    mapfile -t steps < <(steps_of "$device")
    for step in "${steps[@]}"; do
        read -r kind method rows sample <<< "$step"
        case $kind in
            run) run_method "$device" "$method" "$rows" "$sample" ;;
            restore-if-fast)
                value=$(python3 "$HERE/scale40k.py" value "$OUT/rows/pixel9_4g/restore_40k_ms.json")
                if [ -n "$value" ] && awk -v v="$value" -v l="$LOWRAM_RESTORE_LIMIT_MS" 'BEGIN { exit !(v < l) }'; then
                    run_method "$device" "$method" "$rows" "$sample"
                else
                    skip_rows "$device" "$method" "$rows" \
                        "not run: pixel9_4g's restore_40k_ms was ${value:-not a number in this run (failed or not run)}, and the low-RAM restore runs only under $LOWRAM_RESTORE_LIMIT_MS ms"
                fi ;;
            join-if-pushed) run_method "$device" "$method" "$rows" "$sample" ;;
            clear) note "$device: pm clear: $(adb_ shell pm clear "$PACKAGE" | tr -d '\r')" ;;
            oom) ;;
        esac
    done
    collect_device "$device"
    if steps_of "$device" | grep -q '^oom '; then
        python3 "$HERE/scale40k.py" oom "$OUT/rows/$device/oom_count_40k.json" --rows "$OUT/rows/$device" \
            --device "$device" --logs "$DEVICE_OUT" --deaths "$DEVICE_OUT/deaths.txt" | while read -r line; do note "$line"; done
    fi
    stop_device
}

for device in "${DEVICES[@]}"; do
    if [ "$device" = api26_2g ]; then
        pushed=$(python3 "$HERE/scale40k.py" value "$OUT/rows/pixel9_4g/sync_first_push_40k_ms.json")
        if [ -z "$pushed" ]; then
            skip_rows api26_2g theJoinIsTimed sync_join_40k_ms \
                "not run: no first push from pixel9_4g in this run to join (sync_first_push_40k_ms was not a number)"
            continue
        fi
    fi
    run_device "$device"
done

stop_fixture
python3 "$ROOT/tools/bench/collect.py" --bench "$OUT/rows" --subject "$PACKAGE:debug:$(git -C "$ROOT" rev-parse --short HEAD)" \
    --out "$RESULT" > "$OUT/collect.log" 2>&1
collect_exit=$?
note "collect.py exit=$collect_exit: $(tail -1 "$OUT/collect.log")"
[ $collect_exit = 0 ] || exit 3
python3 - "$RESULT" <<'PY' | tee -a "$STATUS"
import json, sys
for row in sorted(json.load(open(sys.argv[1]))["rows"], key=lambda r: (r["device"], r["id"])):
    print(f"{row['device']:12} {row['id']:28} {row['value']} {row['unit']}")
PY
exit 0
