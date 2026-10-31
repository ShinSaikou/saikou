#!/usr/bin/env bash
# Cold start: `am start -W` TotalTime, median of N runs (contract 01, section 7.5).
# usage: coldstart.sh [runs=10] [--extensions <loaded>]
#
# With --extensions this is the other half of PLAN.md's cold start row instead: "no ANR with 76
# extensions installed; zero PackageManager or OkHttpClient work on the main thread in the trace".
# The extensions are already installed by then (com.shinsaikou.bench.BenchExtensionsTest, which
# tools/ci/phase0.sh step 8 runs first), and <loaded> is how many of them the extension manager
# loaded, as that step read it back. Every run's trace must also hold at least that many extension
# archive parses, so each measured launch, not only the seed, ran with them all. The pass traces
# every cold start and publishes `main_thread_pm_okhttp_slices` and `cold_start_anr_count`, summed
# over the runs, and never `cold_start_ms`: that row keeps the definition its baseline was taken
# under, with nothing installed.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

RUNS=${1:-10}
# PLAN.md's number. A pass with fewer loaded measures a lighter start than the row describes.
EXTENSIONS_REQUIRED=76
EXTENSIONS_LOADED=
case "${2:-}" in
    "") ;;
    --extensions)
        EXTENSIONS_LOADED=${3:-}
        # Blank is absent (R29), and here absent means the seed step's count never reached this
        # script, so the pass cannot say what it measured with.
        case "$EXTENSIONS_LOADED" in
            ""|*[!0-9]*)
                echo "coldstart: --extensions needs the count the seed step read back, got '${EXTENSIONS_LOADED}'" >&2
                exit 3 ;;
        esac
        if [ "$EXTENSIONS_LOADED" -lt "$EXTENSIONS_REQUIRED" ]; then
            echo "coldstart: $EXTENSIONS_LOADED extension(s) loaded, fewer than the $EXTENSIONS_REQUIRED the row is defined with" >&2
            exit 3
        fi
        [ "$SAIKOU_PERFETTO" = "1" ] ||
            { echo "coldstart: the extension pass reads every run's trace; set SAIKOU_PERFETTO=1" >&2; exit 2; }
        ;;
    *) echo "usage: coldstart.sh [runs=10] [--extensions <loaded>]" >&2; exit 2 ;;
esac
# Cold start is dominated by the dexopt state: the same APK measures about twice as slow at
# `status=verify` as at `status=speed`. Both the baseline app and Saikou are compiled the same way
# before the run so the ratio row compares like with like. Set SAIKOU_COMPILE=none to skip it.
SAIKOU_COMPILE=${SAIKOU_COMPILE:-speed}
if [ -n "$EXTENSIONS_LOADED" ]; then
    raw="$SAIKOU_BENCH_OUT/coldstart-extensions.$SAIKOU_DEVICE_ID.txt"
else
    raw="$SAIKOU_BENCH_OUT/coldstart.$SAIKOU_DEVICE_ID.txt"
fi
: > "$raw"

if [ "$SAIKOU_COMPILE" != none ]; then
    adb_shell cmd package compile -m "$SAIKOU_COMPILE" -f "$SAIKOU_PACKAGE" >/dev/null
fi

start_once() { # start_once <label>; appends the am start -W output and prints TotalTime
    local out total
    adb_shell am force-stop "$SAIKOU_PACKAGE"
    # Dropping caches needs root; a Play Store system image has none, so the run just proceeds.
    $ADB root >/dev/null 2>&1 && $ADB shell 'echo 3 > /proc/sys/vm/drop_caches' >/dev/null 2>&1 || true
    sleep 2
    out=$($ADB shell am start -W -n "$SAIKOU_PACKAGE/$SAIKOU_LAUNCH_ACTIVITY" | tr -d '\r') || exit 3
    { echo "=== $1 ==="; printf '%s\n' "$out"; } >> "$raw"
    assert_launched "$out"
    total=$(sed -n 's/^TotalTime: *//p' <<< "$out" | tail -1)
    # Zero is what `am start -W` reports when it delivered the intent to something already on top
    # rather than launching anything, and no real launch takes no time. Reading it as a sample is
    # the worst available outcome: `release_gate.py` scores a smaller cold start as an improvement,
    # so a published zero is the fastest launch ever recorded and passes the row every time. A
    # measurement this script cannot take has to end the run, not become a number.
    [ -n "$total" ] && [ "$total" -gt 0 ] 2>/dev/null || return 1
    printf '%s\n' "$total"
}

# The first launch after a boot pays for dex verification and reports `Status: timeout`, so it is
# thrown away rather than recorded, and it is allowed to fail for the same reason.
start_once warmup >/dev/null || true

# The extension pass. Each run brackets one cold start with a cleared events buffer and a trace, and
# reads both before the next run starts, so a buffer or a trace that cannot be read ends the pass at
# the run that produced it rather than becoming a zero in the sum (R36).
extension_pass() {
    local run trace events errors slices anrs parses slices_total=0 anrs_total=0
    local slice_samples=() anr_samples=() parse_samples=()
    errors="$SAIKOU_BENCH_OUT/coldstart-extensions.err"
    # The application and binder slices the slices metric reads; see perfetto_record.sh.
    export SAIKOU_PERFETTO_ATRACE=1
    for run in $(seq 1 "$RUNS"); do
        trace="$SAIKOU_BENCH_OUT/coldstart-extensions-$run.pftrace"
        events="$SAIKOU_BENCH_OUT/coldstart-extensions-$run.events.txt"
        # The output directory outlives the run that wrote it, so a trace left by an earlier run
        # must not be read as this one's when the recording fails.
        rm -f "$trace" "$events"
        $ADB logcat -b events -c || { echo "adb: cannot clear the events buffer on $SAIKOU_SERIAL" >&2; exit 3; }
        perfetto_start 10 "$trace"
        # TotalTime is not published by this pass, and a launch that ANRs never reports one, so a
        # missing one does not end the run here. The launch is still asserted: start_once exits on
        # a launch that resumed somebody else's activity, and the events dump below has to show
        # the process starting or the count refuses it.
        start_once "extensions run $run" >/dev/null || true
        wait "$PERFETTO_PID" || { echo "perfetto: the recording for run $run failed" >&2; exit 3; }
        $ADB logcat -b events -d > "$events" || { echo "adb: cannot dump the events buffer on $SAIKOU_SERIAL" >&2; exit 3; }
        [ -s "$trace" ] || { echo "perfetto: run $run recorded no trace" >&2; exit 3; }
        if ! slices=$("$SAIKOU_PYTHON" "$BENCH_HERE/perfetto_metrics.py" "$trace" --package "$SAIKOU_PACKAGE" \
                --metric main_thread_pm_okhttp_slices 2>"$errors"); then
            echo "perfetto: the trace of run $run cannot answer main_thread_pm_okhttp_slices: $(tr '\n' ' ' < "$errors")" >&2
            exit 3
        fi
        # The loader parses each archive it loads inside a `PackageManager.getPackageArchiveInfo`
        # section, on its IO dispatcher, so the count is over every thread of the process. Fewer
        # than the seed loaded means this launch measured a lighter start than the row describes.
        if ! parses=$("$SAIKOU_PYTHON" "$BENCH_HERE/perfetto_metrics.py" "$trace" --package "$SAIKOU_PACKAGE" \
                --metric extension_archive_parses 2>"$errors"); then
            echo "perfetto: the trace of run $run cannot count the extension archive parses: $(tr '\n' ' ' < "$errors")" >&2
            exit 3
        fi
        echo "=== extensions run $run: $parses extension archive parses ===" >> "$raw"
        if [ "$parses" -lt "$EXTENSIONS_LOADED" ]; then
            echo "coldstart: run $run parsed $parses extension archive(s), fewer than the $EXTENSIONS_LOADED loaded, so it did not launch with them all" >&2
            exit 3
        fi
        parse_samples+=("$parses")
        if ! anrs=$(python3 "$BENCH_HERE/anr_count.py" --package "$SAIKOU_PACKAGE" "$events" 2>"$errors"); then
            echo "anr: the events dump of run $run cannot answer cold_start_anr_count: $(tr '\n' ' ' < "$errors")" >&2
            exit 3
        fi
        slice_samples+=("$slices")
        anr_samples+=("$anrs")
        slices_total=$((slices_total + slices))
        anrs_total=$((anrs_total + anrs))
    done
    local note="$EXTENSIONS_LOADED extensions installed and loaded (read back from the extension manager by the seed step, and at least that many archive parse sections in every run's trace: $(printf '%s\n' "${parse_samples[@]}" | paste -sd, -)), $RUNS cold starts, dexopt $SAIKOU_COMPILE, each run's trace and events dump kept beside it"
    fragment main_thread_pm_okhttp_slices "$slices_total" count \
        "main thread PackageManager and OkHttpClient trace sections plus main thread IPackageManager binder calls outside the platform's own launch calls, summed over the runs; $note" \
        "[$(printf '%s\n' "${slice_samples[@]}" | paste -sd, -)]"
    fragment cold_start_anr_count "$anrs_total" count \
        "am_anr entries for $SAIKOU_PACKAGE in the events buffer, cleared before and dumped after each run, summed over the runs; $note" \
        "[$(printf '%s\n' "${anr_samples[@]}" | paste -sd, -)]"
    echo "main_thread_pm_okhttp_slices=$slices_total"
    echo "cold_start_anr_count=$anrs_total"
}

if [ -n "$EXTENSIONS_LOADED" ]; then
    extension_pass
    exit 0
fi

samples=()
for run in $(seq 1 "$RUNS"); do
    trace="$SAIKOU_BENCH_OUT/coldstart-$run.pftrace"
    [ "$SAIKOU_PERFETTO" = "1" ] && perfetto_start 10 "$trace"
    total=$(start_once "run $run") || total=$(start_once "run $run retry") ||
        { echo "adb: am start -W reported no usable TotalTime for run $run; see $raw" >&2; exit 3; }
    samples+=("$total")
    [ "$SAIKOU_PERFETTO" = "1" ] && { wait "$PERFETTO_PID" 2>/dev/null || true; }
done
value=$(printf '%s\n' "${samples[@]}" | median)
json_samples=$(printf '%s\n' "${samples[@]}" | paste -sd, -)
method="am start -W TotalTime, median of $RUNS, dexopt $SAIKOU_COMPILE"
[ "$SAIKOU_PERFETTO" = "1" ] && method="$method, perfetto trace of each run kept beside it"
fragment cold_start_ms "$value" ms "$method" "[$json_samples]"
echo "cold_start_ms=$value"
