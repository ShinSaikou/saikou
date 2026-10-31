#!/usr/bin/env bash
# Shared inputs for every bench script (contract 01, section 7.5). Source this file.
# Every script here drives whatever package SAIKOU_PACKAGE names, so the same scripts record the
# upstream baseline in Phase 0 and Saikou's own numbers later.
# Exit codes for all bench scripts: 0 ok; 2 missing input; 3 adb or device error.

BENCH_HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../common/localprops.sh
source "$BENCH_HERE/../common/localprops.sh"

SAIKOU_PACKAGE=${SAIKOU_PACKAGE:-com.shinsaikou}
SAIKOU_BENCH_OUT=${SAIKOU_BENCH_OUT:-$BENCH_HERE/../out/bench/$(date -u +%Y%m%dT%H%M%SZ)}
SAIKOU_PERFETTO=${SAIKOU_PERFETTO:-0}
SAIKOU_ORIENTATION=${SAIKOU_ORIENTATION:-portrait}
# Interpreter that can import the `perfetto` trace processor package, when it is not python3 itself.
SAIKOU_PYTHON=${SAIKOU_PYTHON:-python3}

require_env SAIKOU_SERIAL SAIKOU_DEVICE_ID
SDK=$(sdk_root)
[ -n "$SDK" ] || { echo "missing: ANDROID_SDK_ROOT" >&2; exit 2; }
ADB="$SDK/platform-tools/adb -s $SAIKOU_SERIAL"
mkdir -p "$SAIKOU_BENCH_OUT"

# The failing command is named, because this is the only thing many of the bench scripts say when
# they die. A phase 0 run on 2026-09-20 lost `webtoon scroll on pixel9_4g` to the host adb server
# dying mid run, and the gate log carried the word FAIL and nothing else: the reason had to be
# reconstructed from a truncated hold log and two `daemon not running` lines further up.
adb_shell() { $ADB shell "$@" || { echo "adb: \`$*\` failed on ${SAIKOU_SERIAL:-?}" >&2; exit 3; }; }

# The launch activity is read from the device when it is not given, so no package is hard-coded.
# It is the package's one launcher entry once classes in a `debug` package are set aside. A debug
# build of Saikou has a second launcher entry, phase 3 unit O's round trip screen
# (`com.shinsaikou.debug.GoogleRoundTripActivity`), and with two entries `cmd package resolve-activity`
# answers with the platform's chooser, so the tools took no activity at all (the coordinator's phase
# 3 dry run on 5183bc1, step 5e on API 26). Reads the output of `cmd package query-activities
# --brief` for MAIN and LAUNCHER on stdin; prints the activity, or names the candidates and fails.
launcher_activity() {
    local candidates chosen package_re=${SAIKOU_PACKAGE//./\\.}
    candidates=$(tr -d '\r' | sed -n 's|^[[:space:]]*'"$package_re"'/\([^[:space:]]*\)[[:space:]]*$|\1|p' | sort -u)
    # `.debug.X` and `<package>.debug.X` both carry `.debug.`; grep finding nothing is not an error.
    chosen=$(printf '%s\n' "$candidates" | { grep -v '\.debug\.' || true; } | sed '/^$/d')
    if [ "$(printf '%s\n' "$chosen" | sed '/^$/d' | wc -l)" -ne 1 ]; then
        echo "launcher activities of $SAIKOU_PACKAGE outside a debug package: $(printf '%s ' $chosen)(all: $(printf '%s ' $candidates))" >&2
        return 1
    fi
    printf '%s\n' "$chosen"
}

if [ -z "${SAIKOU_LAUNCH_ACTIVITY:-}" ]; then
    resolved=$($ADB shell cmd package query-activities --brief -a android.intent.action.MAIN \
        -c android.intent.category.LAUNCHER "$SAIKOU_PACKAGE" 2>/dev/null | launcher_activity) || resolved=
    [ -n "$resolved" ] || { echo "missing: SAIKOU_LAUNCH_ACTIVITY (and no single launcher activity on device)" >&2; exit 2; }
    SAIKOU_LAUNCH_ACTIVITY=$resolved
fi

# The subject's own instrumentation component, read off the device so no steps file has to spell it
# out. Left empty when the subject has no test package installed, which is the baseline's case: a
# steps file that asks for {{SAIKOU_INSTRUMENTATION}} then fails by name in expand_placeholders
# rather than running `am instrument` against an empty component and reporting nothing.
if [ -z "${SAIKOU_INSTRUMENTATION:-}" ]; then
    SAIKOU_INSTRUMENTATION=$($ADB shell pm list instrumentation 2>/dev/null | tr -d '\r' |
        sed -n 's|^instrumentation:\([^ ]*\) (target='"$SAIKOU_PACKAGE"')$|\1|p' | tail -1)
fi

# Screen geometry drives the gestures, so the same script works on a phone and on a tablet.
# `wm size` reports the size in the display's natural orientation, which on a tablet is landscape
# even while the device is held in portrait, so the current size comes from `cur=WxH` instead.
read_screen_size() {
    read -r SCREEN_W SCREEN_H < <($ADB shell dumpsys window displays | tr -d '\r' |
        sed -n 's/.*cur=\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1)
    [ -n "${SCREEN_W:-}" ] && [ -n "${SCREEN_H:-}" ] ||
        read -r SCREEN_W SCREEN_H < <($ADB shell wm size | tr -d '\r' |
            sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
    [ -n "${SCREEN_W:-}" ] && [ -n "${SCREEN_H:-}" ] || { echo "adb: cannot read the screen size" >&2; exit 3; }
}
read_screen_size

set_orientation() { # portrait or landscape; the tablet row is recorded in landscape
    local rotation
    case "$SAIKOU_ORIENTATION" in
        landscape|portrait) ;;
        *) echo "unknown SAIKOU_ORIENTATION: $SAIKOU_ORIENTATION" >&2; exit 2 ;;
    esac
    $ADB shell settings put system accelerometer_rotation 0 >/dev/null 2>&1 || true
    # Rotation 0 is portrait on a phone and landscape on a tablet, so the rotation that gives the
    # asked-for shape is found by trying, not assumed.
    for rotation in 0 1; do
        $ADB shell settings put system user_rotation "$rotation" >/dev/null 2>&1 || true
        sleep 2
        read_screen_size
        if [ "$SAIKOU_ORIENTATION" = landscape ] && [ "$SCREEN_W" -gt "$SCREEN_H" ]; then return 0; fi
        if [ "$SAIKOU_ORIENTATION" = portrait ] && [ "$SCREEN_W" -lt "$SCREEN_H" ]; then return 0; fi
    done
    echo "adb: cannot rotate the display to $SAIKOU_ORIENTATION" >&2
    exit 3
}

# `am start -W` prints `Status: ok` even when it started nothing at all. The warning it prints
# instead is the only signal, and the case that produces it here is a runtime permission dialog
# owning the foreground: that dialog belongs to the permission controller, not to the package under
# test, so `am force-stop` on the package does not dismiss it and every later start is delivered to
# it as the top-most instance. The `Activity:` line names whatever ended up resumed, so an activity
# outside the package means the window measured was somebody else's.
assert_launched() { # assert_launched <the am start -W output>
    local out=$1 activity
    case "$out" in
        *"Activity not started"*)
            echo "adb: am start delivered the intent to the running top-most instance instead of" \
                "launching $SAIKOU_PACKAGE; something else owns the foreground" >&2
            exit 3 ;;
    esac
    activity=$(sed -n 's/^Activity: *//p' <<< "$out" | tail -1)
    case "$activity" in
        "$SAIKOU_PACKAGE"/*|"") ;;
        *) echo "adb: $activity was resumed instead of $SAIKOU_PACKAGE" >&2; exit 3 ;;
    esac
}

launch() { # cold-launches the package and waits for it to be resumed
    local out
    out=$($ADB shell am start -W -n "$SAIKOU_PACKAGE/$SAIKOU_LAUNCH_ACTIVITY" | tr -d '\r') || exit 3
    assert_launched "$out"
}

# An ungranted runtime permission is asked for on the first launch, and the dialog that asks lands
# on top of the activity being measured. Left alone it costs a cold start its number and every
# gesture bench its target. The set is read off the device rather than named here, because these
# scripts drive the baseline app as well and its permission list is not ours to hard-code.
# Granting before measuring is also what makes the two subjects comparable: a run that stops to ask
# and a run that does not are not the same launch.
# The outcome is asserted rather than assumed, because every `pm grant` below is allowed to fail and
# an empty listing grants nothing at all, so this function could do no work whatsoever and say so
# nowhere. That is not hypothetical. A phase 0 run on 2026-09-19 reached `coldstart` on the 4 GB AVD
# with the permission still ungranted and timed the dialog, which `assert_launched` caught one layer
# down as "GrantPermissionsActivity was resumed", a message that names the symptom and not the step
# that was meant to prevent it. The cause was never reproduced: on that same device afterwards the
# grant took every time, immediately after an install and with the dialog already on screen (which
# it dismisses). So what is fixed here is the part that is certainly wrong, that a failure to grant
# was silent, and the run now stops at the grant naming the permission instead of somewhere later
# naming a window.
grant_runtime_permissions() {
    local perm attempt listed pending
    for attempt in 1 2 3; do
        listed=$(permission_lines granted=)
        if [ -n "$listed" ]; then
            for perm in $listed; do
                # Install-time permissions appear in the same listing and `pm grant` refuses them,
                # which is harmless and expected.
                $ADB shell pm grant "$SAIKOU_PACKAGE" "$perm" >/dev/null 2>&1 || true
            done
            pending=$(permission_lines granted=false runtime)
            [ -n "$pending" ] || return 0
            pending="ungranted after $attempt attempt(s): $(echo $pending)"
        else
            # Not "nothing to grant". The package manager did not answer for this package, so the
            # loop above would have granted nothing and returned as though the device were ready.
            pending="the package manager listed no permissions for $SAIKOU_PACKAGE"
        fi
        sleep 2
    done
    echo "adb: $SAIKOU_PACKAGE is not ready to measure on $SAIKOU_SERIAL: $pending" >&2
    echo "measuring from here would record whatever the device shows, a permission dialog" \
         "included, rather than the application" >&2
    exit 3
}

# The permission names the device reports for the package, filtered by the state wanted: pass
# `granted=` for all of them and `granted=false runtime` for the ones still to grant. Only the
# runtime section can be still to grant. An install-time permission that an app op gates reads
# `granted=false` in the install section once the user has set the op, whichever way: upstream's
# REQUEST_INSTALL_PACKAGES does after its first run turns on "Install unknown apps" (unit
# phase2-bench-harness), and `pm grant` can never change it.
permission_lines() { # permission_lines <granted= or granted=false> [runtime]
    $ADB shell dumpsys package "$SAIKOU_PACKAGE" 2>/dev/null | tr -d '\r' |
        awk -v state="$1" -v only="${2:-}" '
            /^ *install permissions:$/ { section = "install" }
            /^ *runtime permissions:$/ { section = "runtime" }
            (only == "" || section == only) && match($0, "^ *[a-zA-Z0-9_.]*: " state) {
                sub(/^ */, ""); sub(/:.*/, ""); print
            }' | sort -u
}

# Called once on source, so every bench starts from a device that will not stop to ask.
grant_runtime_permissions

# What is actually on screen, read from the window manager rather than inferred from an exit code.
# This is the only check available for the reader: it is not an exported activity, so the shell
# cannot start it and `am start -W` never runs, and it is opened by an instrumentation test instead
# (carry-over 17). It is the stronger check anyway. It names the activity the device is showing at
# the moment the benchmark is about to start measuring, so a permission dialog, a reader that
# finished itself on a bad id, and a hold whose test failed before it opened anything are all caught
# here rather than recorded as a jank figure for whatever screen was behind it.
#
# It waits, because the activity is started by a test process that has to build its session first.
assert_foreground() { # assert_foreground <package/activity> [timeout seconds, default 90]
    local want=$1 timeout=${2:-90} focus= waited=0
    while [ "$waited" -lt "$timeout" ]; do
        focus=$($ADB shell dumpsys window 2>/dev/null | tr -d '\r' |
            sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^}]*\)}.*/\1/p' | tail -1)
        if [ "$focus" = "$want" ]; then
            # Remembered so the gesture loops can re-assert it without naming it again.
            SAIKOU_FOREGROUND_EXPECTED=$want
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    echo "adb: the device is showing '${focus:-nothing}' where $want was expected" >&2
    if [ -n "${SAIKOU_HOLD_LOG:-}" ] && [ -r "${SAIKOU_HOLD_LOG:-}" ]; then
        echo "the instrumentation that was to open it ended with:" >&2
        tail -20 "$SAIKOU_HOLD_LOG" >&2
    fi
    exit 3
}

# Re-asserts whatever assert_foreground last matched. Called after a gesture loop, where it catches
# the run that measured frames the subject did not draw: a reader that died at swipe five leaves the
# launcher on screen, and gfxinfo answers for the launcher without saying so.
# A no op when no steps file opened anything, which is the baseline subject's path.
assert_still_foreground() {
    [ -n "${SAIKOU_FOREGROUND_EXPECTED:-}" ] || return 0
    assert_foreground "$SAIKOU_FOREGROUND_EXPECTED" 1
}

# `am instrument` blocks until the test method returns, and the test that opens the reader holds it
# open on purpose, so this one runs in the background: the gestures being measured have to happen
# while the reader is still up. Ending the instrumentation kills the application process with it,
# which is why the test sleeps rather than returning.
#
# The output is kept rather than discarded. A hold that failed on its fixture says so there and
# nowhere else, and assert_foreground prints the tail of this file when the reader never appears.
hold_instrumentation() { # hold_instrumentation <am instrument arguments>
    SAIKOU_HOLD_LOG=$SAIKOU_BENCH_OUT/hold.$SAIKOU_DEVICE_ID.txt
    # Unquoted on purpose: the steps line is a command, and it has to split into adb arguments.
    # shellcheck disable=SC2086
    $ADB shell $1 > "$SAIKOU_HOLD_LOG" 2>&1 < /dev/null &
}

# A {{NAME}} in a steps line is replaced by the environment variable NAME. That is how a steps
# file shipped in the repository names the row ids the benchmark seeder assigned without knowing
# them in advance: SQLite picks them, and pinning them to literals in the file would either collide
# with a row the owner's own library already holds or overwrite one.
#
# An unset or empty variable is an error rather than an empty substitution. An `am start` missing
# its manga id opens the reader on nothing, the reader finishes itself, and the forty gestures that
# follow then measure whatever screen is behind it. That reads as a jank number, not as a failure.
expand_placeholders() { # expand_placeholders <line>
    local line=$1 name value
    while [[ $line =~ \{\{([A-Z0-9_]+)\}\} ]]; do
        name=${BASH_REMATCH[1]}
        value=${!name:-}
        [ -n "$value" ] || { echo "steps file needs $name, which is unset or empty" >&2; return 1; }
        line=${line//\{\{$name\}\}/$value}
    done
    printf '%s\n' "$line"
}

# Each line of a steps file is one of:
#   sleep <seconds>
#   am start <arguments>     an activity launch, asserted the same way a cold launch is
#   am instrument <arguments>          started in the background, because it is expected to hold
#   am <arguments>           any other activity manager command
#   assert-foreground <pkg/activity> [timeout]   waits for that activity, and fails if it never comes
#   <arguments of `input`>   for example `tap 540 1200`
# Comments start with #, blank lines are skipped, and {{NAME}} placeholders are expanded first.
run_steps() { # run_steps <steps file>
    local file=$1 step out
    [ -n "$file" ] && [ -r "$file" ] || { echo "missing: steps file" >&2; exit 2; }
    while IFS= read -r step; do
        step=${step%%$'\r'}
        [ -z "$step" ] && continue
        case "$step" in \#*) continue ;; esac
        step=$(expand_placeholders "$step") || exit 2
        case "$step" in
            sleep\ *) sleep "${step#sleep }" ;;
            # -W is inserted here rather than left to the steps file. Without it `am start` prints
            # nothing for assert_launched to read, so a file that forgot the flag would silently
            # give up the R36 guard: `am start` answers "Status: ok" for a launch it did not
            # perform, and the benchmark would then measure the screen that stole the foreground.
            am\ start\ *) out=$(adb_shell am start -W ${step#am start } < /dev/null | tr -d '\r'); assert_launched "$out" ;;
            am\ instrument\ *) hold_instrumentation "$step" ;;
            am\ *) adb_shell $step < /dev/null ;;
            # shellcheck disable=SC2086
            assert-foreground\ *) assert_foreground ${step#assert-foreground } ;;
            # stdin is the steps file, and `adb shell` would drain it, so it gets /dev/null.
            *) adb_shell input $step < /dev/null ;;
        esac
    done < "$file"
}

gfxinfo_reset() { $ADB shell dumpsys gfxinfo "$SAIKOU_PACKAGE" reset >/dev/null 2>&1 || exit 3; }

gfxinfo_jank_pct() { # gfxinfo_jank_pct <dump this window just took>; empty when it reported no frames
    local raw=$1 frames
    # The dump the caller took for this window, and never one this function goes and fetches. A
    # second dumpsys would answer for a later window than the rest of the fragment describes. This
    # used to fall back to taking one when the path held nothing, which was worse than either: the
    # path comes from SAIKOU_BENCH_OUT, which tools/ci/phase0.sh sets to tools/out/bench/<device>
    # with no timestamp in it, so gfxinfo-page-turn.<device>.txt outlives the run that wrote it and
    # a caller reaching here without capturing would have published the previous run's percentage as
    # this one's. Both callers capture immediately above; anything else is a bug and says so.
    [ -s "$raw" ] || { echo "gfxinfo_jank_pct: $raw was not written by this window" >&2; exit 3; }
    frames=$(sed -n 's/^Total frames rendered: \([0-9]*\).*/\1/p' "$raw" | head -1)
    [ -n "$frames" ] && [ "$frames" -gt 0 ] || return 0
    sed -n 's/.*Janky frames: [0-9]* (\([0-9.]*\)%).*/\1/p' "$raw" | head -1
}

# gfxinfo's own frame count, which is the only evidence that the subject drew anything at all.
# A jank percentage is a ratio, so it comes out just as clean over two frames as over two thousand,
# and a gesture loop that reached nothing publishes a number that reads like a pass. This is not
# hypothetical: forty taps into a reader that was showing no page produced one frame and the
# fragment said 0.00 percent janky.
#
# Every reader bench performs one gesture at a time and waits, so one frame per gesture is a floor
# that no working run misses (the same loops draw tens of frames per gesture) and no broken one
# reaches. It reads gfxinfo even when the published number comes from perfetto, because the
# question here is whether the subject drew, not which clock measured it.
assert_frames_drawn() { # assert_frames_drawn <gfxinfo raw log> <minimum>
    local raw=$1 minimum=$2 frames
    frames=$(sed -n 's/^Total frames rendered: \([0-9]*\).*/\1/p' "$raw" | head -1)
    [ -n "$frames" ] && [ "$frames" -ge "$minimum" ] && return 0
    echo "the subject drew ${frames:-no} frame(s) over the measured window, fewer than $minimum" >&2
    echo "the gestures reached nothing, so any jank percentage from this run is a ratio of noise" >&2
    exit 3
}

# --- framestats, ruling R40 ----------------------------------------------------------------------
#
# One `dumpsys gfxinfo <package> framestats` dump carries the summary block that gfxinfo_jank_pct and
# assert_frames_drawn read AND the per frame PROFILEDATA rows that tools/bench/framestats.py reads,
# so a whole gesture window needs no second kind of dump anywhere.
framestats_capture() { # framestats_capture <path>
    $ADB shell dumpsys gfxinfo "$SAIKOU_PACKAGE" framestats > "$1" || exit 3
}

# THE 120 FRAME WINDOW. The PROFILEDATA block only ever holds the last 120 frames, and a 40 gesture
# window draws about 438 of them. A single dump taken after the loop would compute its percentage
# over the final quarter of the run while appearing to answer for all of it, so the loops poll while
# they gesture and framestats.py unions the dumps on the columns that identify a frame, so an
# overlap between two consecutive dumps costs nothing.
#
# The margin, worst case first, because that is the one that governs. A subject drawing at every
# vsync of a 60 Hz display fills the 120 frame buffer in 2.0 s. A page turn poll interval is two
# taps: 0.9 s of sleep, roughly 0.4 s of `adb shell input` round trip, and now one
# `dumpsys gfxinfo <package> framestats` round trip of its own, which on the 2 GB AVD is of the same
# order as an input, so call the interval 1.5 to 1.6 s, or 90 to 96 frames of 120. That is a margin
# of a fifth, not the factor of five the observed cadence suggests (434 frames over 40 taps is about
# 11 a tap, so a real interval holds 22). A webtoon swipe interval is 0.3 s of gesture plus 0.45 s
# of sleep plus the same round trips, about three quarters of the page turn interval, and that loop
# polls on every gesture rather than every second one, which is why its margin is the wider of the
# two despite drawing more. Whatever the cadence, the frames actually counted are published beside
# the value by framestats_coverage rather than assumed to be the whole window.
#
# THE POLLING IS INSIDE THE MEASUREMENT. `dumpsys gfxinfo <package> framestats` is serviced on the
# measured process's own RenderThread, so every poll stalls the thread whose lateness this row
# attributes to the application. That is not free and it is not hidden: the same 40 tap window,
# measured unpolled, reported 8.53 percent total janky over 434 frames, and measured with 20 polls
# in it reported 8.32 percent over 457. The perturbation is smaller than the run to run spread and
# in the direction of fewer janky frames, not more, so it does not manufacture the result. Anyone
# changing the cadence should repeat that comparison rather than assume it still holds.
framestats_poll_start() { # framestats_poll_start <directory>
    FRAMESTATS_DIR=$1
    mkdir -p "$FRAMESTATS_DIR"
    # Only the poll files are cleared, and only by name. A stale poll from an earlier run in the
    # same directory would be unioned into this window's frames, and the directory itself is built
    # from SAIKOU_BENCH_OUT, which a caller can point anywhere.
    rm -f "$FRAMESTATS_DIR"/poll-*.txt
    FRAMESTATS_POLLS=0
}

framestats_poll() {
    FRAMESTATS_POLLS=$((FRAMESTATS_POLLS + 1))
    framestats_capture "$(printf '%s/poll-%04d.txt' "$FRAMESTATS_DIR" "$FRAMESTATS_POLLS")"
}

# Runs framestats.py over every dump of the window and sets the FRAMESTATS_* variables it prints:
# APP_JANK_PCT (the R40 value), TOTAL_JANK_PCT, PROFILE_JANK_PCT, MEDIAN_GPU_WAIT_MS,
# MEDIAN_GPU_WAIT_JANKY_MS, FRAMES_COUNTED, FRAMES_RENDERED, FRAMES_MISSED, FRAMES_EXEMPT,
# DUMPS_EMPTY, ROWS_MALFORMED and the rest of the parse tallies.
# The printed file is kept beside the fragment, because it is the evidence for the number published.
# It runs python3 rather than $SAIKOU_PYTHON: framestats.py is standard library only, while
# SAIKOU_PYTHON exists for the one interpreter that can import the Perfetto trace processor.
framestats_read() { # framestats_read <id> <final dump>; unions it with every poll of the window
    local id=$1 final=$2 out name value dumps=()
    out="$SAIKOU_BENCH_OUT/framestats-$id.$SAIKOU_DEVICE_ID.env"
    shopt -s nullglob
    dumps=("${FRAMESTATS_DIR:-}"/poll-*.txt)
    shopt -u nullglob
    dumps+=("$final")
    if ! python3 "$BENCH_HERE/framestats.py" --format sh "${dumps[@]}" > "$out"; then
        echo "framestats: ${#dumps[@]} dump(s) of this window yielded no value; see $out" >&2
        exit 3
    fi
    while IFS='=' read -r name value; do
        # The names come from framestats.py, and the pattern keeps a mangled line from naming
        # anything else in this shell.
        [[ $name =~ ^FRAMESTATS_[A-Z_]+$ ]] || continue
        printf -v "$name" '%s' "$value"
    done < "$out"
}

# The sentence both benches put in their method note about what the percentage was computed over.
# A number computed over an unknown fraction of a run is exactly the class of defect this project
# keeps paying for, so the frames counted go next to the value, and so does any gap between them and
# the frames the subject says it rendered.
# The median SwapBuffers to GpuCompleted wait on the frames the total rule called janky, which is
# the term ruling R40 subtracts and therefore the number that explains the gap between the two
# percentages in the same note. A window with no janky frame in it says so rather than printing a
# median of nothing.
framestats_janky_wait() {
    if [ -n "${FRAMESTATS_MEDIAN_GPU_WAIT_JANKY_MS:-}" ]; then
        printf '%s ms on the frames the total rule called janky' "$FRAMESTATS_MEDIAN_GPU_WAIT_JANKY_MS"
    else
        printf 'no frame janky by the total rule to measure one on'
    fi
}

framestats_coverage() {
    local note="${FRAMESTATS_FRAMES_COUNTED:-0} frames counted over ${FRAMESTATS_DUMPS:-0} polled dumps"
    if [ -n "${FRAMESTATS_FRAMES_RENDERED:-}" ]; then
        note="$note of ${FRAMESTATS_FRAMES_RENDERED} the subject rendered"
        if [ "${FRAMESTATS_FRAMES_MISSED:-0}" -gt 0 ]; then
            note="$note, ${FRAMESTATS_FRAMES_MISSED} of them never seen by a poll"
        elif [ "${FRAMESTATS_FRAMES_MISSED:-0}" -lt 0 ]; then
            # One window cannot draw fewer frames than were counted in it, so this says the dumps
            # span a `gfxinfo reset` or two runs and the percentage answers for neither.
            note="$note, which is $(( -FRAMESTATS_FRAMES_MISSED )) fewer than were counted, so these"
            note="$note dumps do not answer for one window"
        fi
    fi
    # Everything that removed frames from the count above is named here rather than left in the .env
    # file beside it. A dump that came back with no frames in it, a row that did not parse, a frame
    # the platform exempts and a frame that had not finished when the dump was taken each shrink the
    # population this percentage was computed over, and a note that omits them reads like full
    # coverage of the window. The unfinished frame is the one that hides best: the summary block
    # does not count it either, so frames counted and frames rendered still agree and the sentence
    # reads as though the window was covered end to end.
    if [ "${FRAMESTATS_DUMPS_EMPTY:-0}" -gt 0 ]; then
        note="$note, ${FRAMESTATS_DUMPS_EMPTY} dump(s) carried no frame"
    fi
    if [ "${FRAMESTATS_ROWS_MALFORMED:-0}" -gt 0 ]; then
        note="$note, ${FRAMESTATS_ROWS_MALFORMED} row(s) did not parse"
    fi
    if [ "${FRAMESTATS_FRAMES_EXEMPT:-0}" -gt 0 ]; then
        note="$note, ${FRAMESTATS_FRAMES_EXEMPT} frame(s) exempt under the platform's own mask"
    fi
    if [ "${FRAMESTATS_FRAMES_INCOMPLETE:-0}" -gt 0 ]; then
        note="$note, ${FRAMESTATS_FRAMES_INCOMPLETE} frame(s) unfinished when the dump was taken"
    fi
    printf '%s' "$note"
}

# Ruling R50, under R36: a row that publishes the R40 figure ends the run when the polls did not see
# every frame the subject rendered. The figure is janky frames over frames counted, so a poll that
# fell behind shrinks the denominator and reads as a better number rather than as a missing one. A
# negative gap (dumps that span two windows) and an empty one (no summary block to count against)
# answer for no single window either. framestats_coverage still writes the sentence; this decides.
assert_frames_covered() {
    [ "${FRAMESTATS_FRAMES_MISSED:-}" = 0 ] && return 0
    echo "framestats: ${FRAMESTATS_FRAMES_COUNTED:-0} frame(s) counted against" \
        "${FRAMESTATS_FRAMES_RENDERED:-an unknown number} rendered (missed" \
        "${FRAMESTATS_FRAMES_MISSED:-unknown}), so the R40 figure would answer for part of the" \
        "window as if it were all of it" >&2
    exit 3
}

# Records a trace over the window a caller is about to run, then reports the janky percent from
# android_frame_timeline. Both callers record that percentage in the method note and never publish
# it (rulings R40 and R50: it counts the GPU wait); when no trace processor is installed the note
# says so and the trace file is left in place for later analysis.
perfetto_start() { # perfetto_start <seconds> <out.pftrace>
    bash "$BENCH_HERE/perfetto_record.sh" "$1" "$2" >/dev/null 2>&1 &
    PERFETTO_PID=$!
    sleep 2
}

# The trace covers exactly the gesture window, so the metric reads the whole trace rather than
# filtering on a host clock the trace does not share. When the metric cannot produce a number the
# reason is kept in PERFETTO_NOTE and the caller records that reason instead.
PERFETTO_NOTE=""
PERFETTO_VALUE=""
perfetto_jank_pct() { # perfetto_jank_pct <out.pftrace>; sets PERFETTO_VALUE or PERFETTO_NOTE
    local trace=$1 errors="$SAIKOU_BENCH_OUT/perfetto-metrics.err"
    PERFETTO_NOTE=""
    PERFETTO_VALUE=""
    wait "$PERFETTO_PID" 2>/dev/null || true
    if [ ! -s "$trace" ]; then PERFETTO_NOTE="no trace was recorded"; return 0; fi
    if PERFETTO_VALUE=$("$SAIKOU_PYTHON" "$BENCH_HERE/perfetto_metrics.py" "$trace" \
            --package "$SAIKOU_PACKAGE" --metric jank_pct 2>"$errors"); then
        return 0
    fi
    PERFETTO_VALUE=""
    PERFETTO_NOTE=$(tr '\n' ' ' < "$errors")
}

# fragment <id> <value> <unit> [method] [samples json]
fragment() {
    local id=$1 value=$2 unit=$3 method=${4:-} samples=${5:-null}
    cat > "$SAIKOU_BENCH_OUT/$id.$SAIKOU_DEVICE_ID.json" <<JSON
{
  "id": "$id",
  "device": "$SAIKOU_DEVICE_ID",
  "value": $value,
  "unit": "$unit",
  "samples": $samples,
  "method": "$method",
  "recordedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON
}

median() { # reads numbers on stdin
    sort -n | awk '{ v[NR] = $1 } END { print (NR % 2 ? v[(NR + 1) / 2] : (v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}
