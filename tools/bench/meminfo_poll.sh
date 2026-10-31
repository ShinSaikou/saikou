#!/usr/bin/env bash
# Peak PSS and process death count over a window (contract 01, section 7.5).
# usage: meminfo_poll.sh <seconds> <interval>
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

WINDOW=${1:-120}
INTERVAL=${2:-2}
raw="$SAIKOU_BENCH_OUT/meminfo.$SAIKOU_DEVICE_ID.txt"
: > "$raw"
series=()
deaths=0
previous_pid=$($ADB shell pidof "$SAIKOU_PACKAGE" | tr -d '\r' || true)
elapsed=0
while [ "$elapsed" -lt "$WINDOW" ]; do
    # `TOTAL PSS:` is the App Summary line; the `TOTAL` table row is the older format.
    dump=$($ADB shell dumpsys meminfo "$SAIKOU_PACKAGE" 2>/dev/null | tr -d '\r' || true)
    pss=$(printf '%s\n' "$dump" | sed -n 's/.*TOTAL PSS: *\([0-9]*\).*/\1/p' | head -1)
    [ -n "$pss" ] || pss=$(printf '%s\n' "$dump" | sed -n 's/^ *TOTAL  *\([0-9]*\).*/\1/p' | head -1)
    if [ -n "$pss" ]; then
        series+=("$((pss / 1024))")
        echo "$(date -u +%H:%M:%S) $pss" >> "$raw"
    fi
    pid=$($ADB shell pidof "$SAIKOU_PACKAGE" | tr -d '\r' || true)
    if [ -n "$previous_pid" ] && [ "$pid" != "$previous_pid" ]; then deaths=$((deaths + 1)); fi
    previous_pid=$pid
    sleep "$INTERVAL"
    elapsed=$((elapsed + INTERVAL))
done
[ "${#series[@]}" -gt 0 ] || { echo "adb: dumpsys meminfo reported no PSS" >&2; exit 3; }
peak=$(printf '%s\n' "${series[@]}" | sort -n | tail -1)
json_series=$(printf '%s\n' "${series[@]}" | paste -sd, -)
fragment peak_pss_mb "$peak" mb "dumpsys meminfo TOTAL PSS, peak over ${WINDOW}s at ${INTERVAL}s" "[$json_series]"
fragment reader_crash_count "$deaths" count "process id changes over the meminfo window"
echo "peak_pss_mb=$peak reader_crash_count=$deaths"
