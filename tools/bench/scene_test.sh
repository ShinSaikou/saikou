#!/usr/bin/env bash
# Tap to first visible frame, adapted from an earlier research harness (outside this repository):
# the SDK root comes from the environment and the output directory from SAIKOU_BENCH_OUT.
# Cross-check, not a gate row.
# usage: scene_test.sh <name> <tap_x> <tap_y> [time_limit] [pre_sleep]
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

NAME=${1:-}; TX=${2:-}; TY=${3:-}; TL=${4:-3}; PRE=${5:-0.6}
[ -n "$NAME" ] && [ -n "$TX" ] && [ -n "$TY" ] || { echo "usage: scene_test.sh <name> <x> <y>" >&2; exit 2; }

$ADB shell rm -f "/sdcard/rec_$NAME.mp4" || exit 3
( $ADB shell screenrecord --bit-rate 12000000 --time-limit "$TL" "/sdcard/rec_$NAME.mp4" ) &
recorder=$!
sleep "$PRE"
adb_shell input tap "$TX" "$TY"
wait $recorder
$ADB pull "/sdcard/rec_$NAME.mp4" "$SAIKOU_BENCH_OUT/rec_$NAME.mp4" >/dev/null || exit 3
ffmpeg -y -loglevel info -i "$SAIKOU_BENCH_OUT/rec_$NAME.mp4" \
    -vf "select='gt(scene\,0.06)',showinfo" -fps_mode vfr -f null - 2> "$SAIKOU_BENCH_OUT/scene_$NAME.log"
echo "=== scene changes for $NAME (tap injected at ~${PRE}s) ==="
grep -oE "pts_time:[0-9.]+" "$SAIKOU_BENCH_OUT/scene_$NAME.log" | sed 's/pts_time://'
