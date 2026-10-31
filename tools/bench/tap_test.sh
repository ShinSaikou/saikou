#!/usr/bin/env bash
# Frame extraction around a tap, adapted from an earlier research harness (outside this repository).
# usage: tap_test.sh <name> <tap_x> <tap_y> [time_limit]
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

NAME=${1:-}; TX=${2:-}; TY=${3:-}; TL=${4:-3}
[ -n "$NAME" ] && [ -n "$TX" ] && [ -n "$TY" ] || { echo "usage: tap_test.sh <name> <x> <y>" >&2; exit 2; }

$ADB shell rm -f "/sdcard/rec_$NAME.mp4" || exit 3
( $ADB shell screenrecord --bit-rate 12000000 --time-limit "$TL" "/sdcard/rec_$NAME.mp4" ) &
recorder=$!
sleep 0.6
adb_shell input tap "$TX" "$TY"
wait $recorder
$ADB pull "/sdcard/rec_$NAME.mp4" "$SAIKOU_BENCH_OUT/rec_$NAME.mp4" >/dev/null || exit 3
mkdir -p "$SAIKOU_BENCH_OUT/frames_$NAME"
ffmpeg -y -loglevel error -i "$SAIKOU_BENCH_OUT/rec_$NAME.mp4" -vf fps=30 "$SAIKOU_BENCH_OUT/frames_$NAME/f_%04d.png"
echo "frames: $(find "$SAIKOU_BENCH_OUT/frames_$NAME" -type f | wc -l)"
