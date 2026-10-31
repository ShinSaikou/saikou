#!/usr/bin/env bash
# Page turn jank: 40 taps in the right tap zone (contract 01, section 7.5).
#
# Ruling R40 changed what this row counts. It publishes the frames this application was late for:
# the per frame total with the SwapBuffers to GpuCompleted wait taken back out of it. The row used
# to publish gfxinfo's own Janky frames percent, which read 8.53 percent for the reader, 8.61 for
# the system launcher's app drawer and 8.31 for the Phase 0 baseline subject on the same AVD, all
# while the application itself spent 0.12 ms of the 16.67 ms budget. See tools/bench/framestats.py
# for the arithmetic and for why one dump at the end of the loop is not enough to compute it.
# usage: page_turn.sh [reader-open-steps-file]
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

STEPS=${1:-}
set_orientation
if [ -n "$STEPS" ]; then
    adb_shell am force-stop "$SAIKOU_PACKAGE"
    launch
    run_steps "$STEPS"
fi

raw="$SAIKOU_BENCH_OUT/gfxinfo-page-turn.$SAIKOU_DEVICE_ID.txt"
trace="$SAIKOU_BENCH_OUT/page_turn_jank_pct.$SAIKOU_DEVICE_ID.pftrace"
gfxinfo_reset
framestats_poll_start "$SAIKOU_BENCH_OUT/framestats-page-turn.$SAIKOU_DEVICE_ID"
[ "$SAIKOU_PERFETTO" = "1" ] && perfetto_start 32 "$trace"
tap_x=$(( SCREEN_W * 5 / 6 ))
tap_y=$(( SCREEN_H / 2 ))
GESTURES=40
# Every second tap, which is about 22 frames of the 120 the platform holds in the measured cadence
# and 90 to 96 in the worst case. The margin, and the cost the polling itself adds to the measured
# window, are both worked out over framestats_poll_start in common.sh.
POLL_EVERY=2
for gesture in $(seq 1 "$GESTURES"); do
    adb_shell input tap "$tap_x" "$tap_y"
    sleep 0.45
    if [ $((gesture % POLL_EVERY)) -eq 0 ]; then framestats_poll; fi
done
# The subject has to still be the thing on screen. A reader that died part way through
# leaves the launcher drawing the rest of the taps, and gfxinfo answers for it without
# saying so, which is a clean percentage for a run that measured nothing.
assert_still_foreground

# The closing dump, which is both the last poll of the window and the gfxinfo log the two checks
# below read. One `dumpsys gfxinfo <package> framestats` carries the summary block and the frames.
framestats_capture "$raw"
# This check runs before the parser. Both fail on a window the gestures never reached, but only this
# one says why: the parser can report no finished frame, which reads as a tooling problem, where the
# real problem is forty taps that landed on nothing. That is a failure this project has already paid
# for once.
assert_frames_drawn "$raw" "$GESTURES"
framestats_read page_turn "$raw"
assert_frames_covered
total=$(gfxinfo_jank_pct "$raw")

# framestats_read exits 3 if the parser failed, and the parser exits 5 rather than returning a null
# percentage, so there is no empty case left here to guard against.
value=${FRAMESTATS_APP_JANK_PCT:-}
# The method note carries the two numbers that keep this value honest: the total janky percent, which
# is what the row published before R40 and what every earlier run and the baseline recorded, and the
# median GPU wait, which is the term the subtraction removed. A run whose app attributed number falls
# while its total stays put has not improved anything, and the note is where that shows.
method="R40 app attributed janky frames over $GESTURES taps: per frame FrameCompleted minus"
method="$method IntendedVsync, less the SwapBuffers to GpuCompleted wait, against FrameDeadline."
method="$method Total janky frames ${total:-unavailable} percent."
method="$method Median GPU wait ${FRAMESTATS_MEDIAN_GPU_WAIT_MS:-unavailable} ms over all counted"
method="$method frames, and $(framestats_janky_wait). $(framestats_coverage)."
if [ "$SAIKOU_PERFETTO" = "1" ]; then
    # Recorded and reported, never substituted. Perfetto's android_frame_timeline calls a frame App
    # Deadline Missed when it finished late for any reason, the GPU wait included, so letting it
    # replace this value would restore the definition R40 retired under a different name.
    perfetto_jank_pct "$trace"
    if [ -n "$PERFETTO_VALUE" ]; then
        method="$method (perfetto read $PERFETTO_VALUE percent janky over the same window, counting"
        method="$method the GPU wait; the trace is kept beside this fragment)"
    else
        method="$method (perfetto: ${PERFETTO_NOTE:-unavailable}; the trace is kept beside this fragment)"
    fi
fi
fragment page_turn_jank_pct "$value" percent "$method"
echo "page_turn_jank_pct=$value"
