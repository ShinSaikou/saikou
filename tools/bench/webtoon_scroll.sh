#!/usr/bin/env bash
# Webtoon scroll jank over a 30 second window (contract 01, section 7.5).
# usage: webtoon_scroll.sh <reader-open-steps-file>
# With SAIKOU_ORIENTATION=landscape the run is the tablet row, id tablet_landscape_webtoon_jank_pct.
#
# Ruling R50 extends ruling R40 to these rows. They publish the frames this application was late for:
# the per frame total with the SwapBuffers to GpuCompleted wait taken back out of it, the figure
# page_turn.sh publishes. The total janky percent they used to compare is kept in the method note,
# so every earlier run stays comparable. A swipe draws about 46 frames where a tap draws about 11,
# and the emulated display drops about one per gesture on its own, which is why the total read about
# 2 percent here and about 8.7 on page turn while the application was late for none of them.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

STEPS=${1:-}
ID=webtoon_jank_pct
[ "$SAIKOU_ORIENTATION" = landscape ] && ID=tablet_landscape_webtoon_jank_pct

set_orientation
adb_shell am force-stop "$SAIKOU_PACKAGE"
launch
run_steps "$STEPS"

raw="$SAIKOU_BENCH_OUT/gfxinfo-$ID.$SAIKOU_DEVICE_ID.txt"
trace="$SAIKOU_BENCH_OUT/$ID.$SAIKOU_DEVICE_ID.pftrace"
gfxinfo_reset
framestats_poll_start "$SAIKOU_BENCH_OUT/framestats-$ID.$SAIKOU_DEVICE_ID"
[ "$SAIKOU_PERFETTO" = "1" ] && perfetto_start 32 "$trace"
from_y=$(( SCREEN_H * 2 / 3 ))
to_y=$(( SCREEN_H / 6 ))
mid_x=$(( SCREEN_W / 2 ))
GESTURES=40
# Every gesture, unlike the page turn loop. A scroll draws through the whole 300 ms swipe and on
# through the settle after it, so a swipe is worth several times a tap in frames and the 120 frame
# window the platform holds is that much closer. The margin is worked out over framestats_poll_start.
for _ in $(seq 1 "$GESTURES"); do
    adb_shell input swipe "$mid_x" "$from_y" "$mid_x" "$to_y" 300
    sleep 0.45
    framestats_poll
done
# The subject has to still be the thing on screen. A reader that died part way through
# leaves the launcher drawing the rest of the swipes, and gfxinfo answers for it without
# saying so, which is a clean percentage for a run that measured nothing.
assert_still_foreground

# The closing dump, which is both the last poll of the window and the gfxinfo log the two checks
# below read. One `dumpsys gfxinfo <package> framestats` carries the summary block and the frames.
framestats_capture "$raw"
# Before the parser, for the reason written out in page_turn.sh: a window the gestures never reached
# has to fail naming that rather than naming the parser's empty result.
assert_frames_drawn "$raw" "$GESTURES"
framestats_read "$ID" "$raw"
assert_frames_covered
total=$(gfxinfo_jank_pct "$raw")

# framestats_read exits 3 if the parser failed, and the parser exits 5 rather than returning a null
# percentage, so there is no empty case left here to guard against.
value=${FRAMESTATS_APP_JANK_PCT:-}
# The same two numbers page_turn.sh keeps beside its value: the total janky percent, which is what
# these rows published before R50 and what every earlier run and the baseline recorded, and the
# median GPU wait, which is the term the subtraction removed.
method="R40 app attributed janky frames over $GESTURES swipes: per frame FrameCompleted minus"
method="$method IntendedVsync, less the SwapBuffers to GpuCompleted wait, against FrameDeadline."
method="$method Total janky frames ${total:-unavailable} percent."
method="$method Median GPU wait ${FRAMESTATS_MEDIAN_GPU_WAIT_MS:-unavailable} ms over all counted"
method="$method frames, and $(framestats_janky_wait). $(framestats_coverage)."
if [ "$SAIKOU_PERFETTO" = "1" ]; then
    # Recorded and reported, never substituted. Perfetto's android_frame_timeline calls a frame App
    # Deadline Missed when it finished late for any reason, the GPU wait included, so letting it
    # replace this value would restore the definition R40 retired under a different name. Before R50
    # this script did substitute it, which left the row's definition hanging on whether the trace
    # processor happened to be installed.
    perfetto_jank_pct "$trace"
    if [ -n "$PERFETTO_VALUE" ]; then
        method="$method (perfetto read $PERFETTO_VALUE percent janky over the same window, counting"
        method="$method the GPU wait; the trace is kept beside this fragment)"
    else
        method="$method (perfetto: ${PERFETTO_NOTE:-unavailable}; the trace is kept beside this fragment)"
    fi
fi
fragment "$ID" "$value" percent "$method"
echo "$ID=$value"
