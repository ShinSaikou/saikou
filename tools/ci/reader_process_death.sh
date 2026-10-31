#!/usr/bin/env bash
# Contract 07 part 2 section 9.2's ReaderProcessDeathTest, driven from the host: the reader is
# opened on a known chapter and page, the application is backgrounded and killed with `am kill`
# the way the system kills a cached process under memory pressure, the task is brought back, and
# the reader has to come back on the same chapter and page.
#
# Why the host and not an instrumentation, established on the API 26 AVD and recorded in unit K's
# report: a process with an instrumentation attached is held at the foreground adjustment, so
# `am kill` never selects it, killing it by other means ends the run, and the start and end of every
# run force-stop the package, which removes the task and the saved state with it. What this checks
# only exists with no instrumentation attached. The instrumentation here is used once, before any of
# it, to seed the entry (ReaderProcessDeathTest), and it has ended before the reader is opened.
#
# The reader is reached the way an owner reaches it: the entry's screen through the application's
# own OPEN_ENTRY intent, a tap on the first chapter, taps in the right tap zone through the first
# chapter and its transition page into the second, two more taps to its third page. The second
# chapter matters: a restore that only kept the chapter the activity was started on would land on
# the first. The two chapters have different lengths, so the page indicator names the chapter by
# its total as well as the page, and the menu's subtitle names it again after the restore.
#
# Needs SAIKOU_SERIAL and SAIKOU_DEVICE_ID (tools/bench/common.sh), with the debug application and its
# test APK installed. Exit codes: 0 restored; 1 restored somewhere else; 2 missing input; 3 the check
# could not be made (adb or device error, a seed that failed, a reader that never reached the page,
# a kill that did not take), under ruling R36.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SAIKOU_BENCH_OUT=${SAIKOU_BENCH_OUT:-$HERE/../out/reader-restore/${SAIKOU_DEVICE_ID:-unknown}}
# shellcheck source=../bench/common.sh
source "$HERE/../bench/common.sh"

PARSE="python3 $HERE/reader_process_death.py"
# The window manager names the reader in full, the activity manager in the short form.
READER="$SAIKOU_PACKAGE/com.shinsaikou.reader.ReaderActivity"
READER_SHORT="$SAIKOU_PACKAGE/.reader.ReaderActivity"
SEED_TEST="com.shinsaikou.reader.ReaderProcessDeathTest#anEntryIsSeededForTheHostToKillAndRestore"
# ReaderFixture.chapterName: the Local source names each chapter after its folder.
chapter_name() { printf 'Chapter %s' "$1"; }
# The page the reader is left on in the second chapter, 1-based. Not its first page, which is where
# a restore that dropped the page would land, and not its last.
TARGET_PAGE=3
UNMEASURABLE=3

say() { echo "reader_process_death: $*"; }
unmeasurable() { echo "reader_process_death: $* (the check could not be made)" >&2; exit "$UNMEASURABLE"; }

[ -n "${SAIKOU_INSTRUMENTATION:-}" ] || { echo "missing: the test APK for $SAIKOU_PACKAGE on $SAIKOU_SERIAL" >&2; exit 2; }

DUMPS=$SAIKOU_BENCH_OUT/dumps
mkdir -p "$DUMPS"
rm -f "$DUMPS"/*.xml
# The screen as uiautomator reads it, saved under the time it was taken so the dumps a failure
# quotes stay beside the run, in order. Named by the clock rather than by a counter, because every
# caller runs this in a command substitution, where a counter would never move. Prints the path.
dump() {
    local out
    out="$DUMPS/$(date +%H%M%S.%N).xml"
    adb_shell uiautomator dump /sdcard/saikou-reader-restore.xml > /dev/null
    $ADB shell cat /sdcard/saikou-reader-restore.xml > "$out" || unmeasurable "cannot read the uiautomator dump"
    printf '%s\n' "$out"
}

# The page indicator as "<page> <pages>", or nothing when the reader is not showing it.
read_indicator() {
    local file status=0 reading
    file=$(dump)
    reading=$($PARSE indicator "$file") || status=$?
    case "$status" in
        0) printf '%s\n' "$reading" ;;
        1) ;;
        *) unmeasurable "the page indicator in $file cannot be read" ;;
    esac
}

# Waits for the indicator to read "<page> <pages>". Prints the last reading either way, and fails
# when it never matched.
await_indicator() { # await_indicator <page> <pages> <seconds>
    local want="$1 $2" deadline=$(( $(date +%s) + $3 )) seen=
    while :; do
        seen=$(read_indicator)
        [ "$seen" = "$want" ] && { printf '%s\n' "$seen"; return 0; }
        [ "$(date +%s)" -ge "$deadline" ] && { printf '%s\n' "$seen"; return 1; }
        sleep 1
    done
}

# Waits for a node with this exact text and prints its centre.
await_text() { # await_text <text> <seconds>
    local deadline=$(( $(date +%s) + $2 )) file point status
    while :; do
        file=$(dump)
        status=0
        point=$($PARSE find --text "$1" "$file") || status=$?
        [ "$status" -eq 0 ] && { printf '%s\n' "$point"; return 0; }
        [ "$status" -eq 1 ] || unmeasurable "the dump $file cannot be read"
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 1
    done
}

tap() { adb_shell input tap "$1" "$2"; }
forward_x=$(( SCREEN_W * 5 / 6 ))
middle_x=$(( SCREEN_W / 2 ))
middle_y=$(( SCREEN_H / 2 ))

# The chapter the reader's menu names, checked with the menu open and then closed again, so the
# indicator is back on screen afterwards.
assert_menu_names() { # assert_menu_names <chapter number> <what> <exit code on a mismatch>
    local other=1
    [ "$1" -eq 1 ] && other=2
    tap "$middle_x" "$middle_y"
    if ! await_text "$(chapter_name "$1")" 10 > /dev/null; then
        if await_text "$(chapter_name "$other")" 1 > /dev/null; then
            echo "reader_process_death: $2: the reader's menu names $(chapter_name "$other")," \
                "where $(chapter_name "$1") was expected" >&2
            exit "$3"
        fi
        unmeasurable "$2: the reader's menu named neither chapter"
    fi
    tap "$middle_x" "$middle_y"
}

set_orientation
adb_shell am force-stop "$SAIKOU_PACKAGE"
# The first launch asks for the battery exemption in a dialog over the entry's screen, and the
# application never asks when it is already exempt, so it is made exempt, the state an owner who
# answered is in. Checked below by the entry's screen showing its chapters.
adb_shell dumpsys deviceidle whitelist +"$SAIKOU_PACKAGE" > /dev/null

# --- seed ------------------------------------------------------------------------------------
nonce="$(date +%s)$RANDOM"
seed_log=$SAIKOU_BENCH_OUT/seed.$SAIKOU_DEVICE_ID.txt
$ADB shell am instrument -w -r -e class "$SEED_TEST" -e saikou.reader.restore.nonce "$nonce" \
    "$SAIKOU_INSTRUMENTATION" > "$seed_log" 2>&1 < /dev/null || true
if ! grep -q '^OK (1 test)' "$seed_log"; then
    tail -20 "$seed_log" >&2
    unmeasurable "the seed test did not pass"
fi
seed_logcat=$SAIKOU_BENCH_OUT/seed-logcat.$SAIKOU_DEVICE_ID.txt
$ADB logcat -d -s SaikouReaderRestore:I > "$seed_logcat" || unmeasurable "cannot read logcat"
seed_vars=$($PARSE seed --nonce "$nonce" "$seed_logcat") || unmeasurable "the seed logged no ids for nonce $nonce"
while IFS='=' read -r name value; do
    [[ $name =~ ^SEED_[A-Z0-9]+$ ]] && printf -v "$name" '%s' "$value"
done <<< "$seed_vars"
say "seeded entry $SEED_MANGA: chapter 1 ($SEED_PAGES1 pages), chapter 2 ($SEED_PAGES2 pages)"

# --- open the reader on chapter 2, page TARGET_PAGE ------------------------------------------
open_entry() {
    adb_shell am start -W -n "$SAIKOU_PACKAGE/$SAIKOU_LAUNCH_ACTIVITY" -a com.shinsaikou.OPEN_ENTRY \
        --el com.shinsaikou.extra.MANGA_ID "$SEED_MANGA" < /dev/null | tr -d '\r'
}
out=$(open_entry)
assert_launched "$out"
point=$(await_text "$(chapter_name 1)" 30) || unmeasurable "the entry's screen never listed $(chapter_name 1)"
# shellcheck disable=SC2086
tap $point
assert_foreground "$READER" 30
await_indicator 1 "$SEED_PAGES1" 30 > /dev/null || unmeasurable "the reader never showed page 1 of $(chapter_name 1)"

# Every page of the first chapter, its transition page and TARGET_PAGE pages of the second, one tap
# at a time, each waited for: a tap sent before the pager settles can be taken for a fling.
taps=0
limit=$(( SEED_PAGES1 + 1 + TARGET_PAGE + 2 ))
reached=
while [ "$taps" -lt "$limit" ]; do
    tap "$forward_x" "$middle_y"
    taps=$((taps + 1))
    sleep 1.2
    reached=$(read_indicator)
    [ "$reached" = "$TARGET_PAGE $SEED_PAGES2" ] && break
done
[ "$reached" = "$TARGET_PAGE $SEED_PAGES2" ] ||
    unmeasurable "$taps taps forward left the reader at '${reached:-no indicator}', not page $TARGET_PAGE of $(chapter_name 2)"
assert_menu_names 2 "before the kill" "$UNMEASURABLE"
await_indicator "$TARGET_PAGE" "$SEED_PAGES2" 10 > /dev/null ||
    unmeasurable "the reader left page $TARGET_PAGE of $(chapter_name 2) when its menu closed"
say "reader on page $TARGET_PAGE of $(chapter_name 2)"

# --- background, kill, bring back ------------------------------------------------------------
pid_before=$($ADB shell pidof "$SAIKOU_PACKAGE" | tr -d '\r') || true
[ -n "$pid_before" ] || unmeasurable "no process of $SAIKOU_PACKAGE while the reader was open"
adb_shell input keyevent KEYCODE_HOME
# The saved state is written by the time the activity is stopped, so the kill waits for that.
# A dump is read whole into a variable before it is searched, never piped into `grep -q`: grep
# stops reading at its first match, the stage writing to it dies of SIGPIPE, and under pipefail the
# search then fails although it matched. That turned a task holding the reader into "no longer
# holds" 20 times in 20 once API 26's recents list outgrew one pipe write (the coordinator's phase
# 3 dry run on 8e610f5, step 5e).
stopped=
for _ in $(seq 1 15); do
    # The reader's own history record, from its line up to the next record's. API 26 prints a
    # record's line as "Hist #1:" and API 36 as "Hist  #1:", so the match takes any run of spaces.
    record=$($ADB shell dumpsys activity activities | tr -d '\r' |
        awk -v reader=" $READER_SHORT " '/Hist +#/ { mine = index($0, reader) > 0 } mine') || record=
    if grep -q 'state=STOPPED' <<< "$record"; then
        stopped=1
        break
    fi
    sleep 1
done
[ -n "$stopped" ] || unmeasurable "the reader was never stopped after HOME"

# `am kill` only kills a process the system considers safe to kill, which a stopped reader's is.
# The user is named: with none, API 26 treats the package as belonging to every user and refuses it.
adb_shell am kill --user current "$SAIKOU_PACKAGE" < /dev/null
dead=
for _ in $(seq 1 10); do
    if [ -z "$($ADB shell pidof "$SAIKOU_PACKAGE" | tr -d '\r')" ]; then dead=1; break; fi
    sleep 1
done
[ -n "$dead" ] || unmeasurable "am kill left process $pid_before running"
recents=$($ADB shell dumpsys activity recents | tr -d '\r') ||
    unmeasurable "adb could not read the recent tasks after the kill"
grep -q "Activities=.*$READER_SHORT" <<< "$recents" ||
    unmeasurable "the task no longer holds the reader after the kill"
say "process $pid_before killed; the task still holds the reader"

# The same intent again. The task is brought to the front rather than started anew, which am start
# reports as "Activity not started", so assert_launched is not used here: the foreground check is
# what says the reader came back.
open_entry > "$SAIKOU_BENCH_OUT/bring-back.$SAIKOU_DEVICE_ID.txt"
assert_foreground "$READER" 30
pid_after=$($ADB shell pidof "$SAIKOU_PACKAGE" | tr -d '\r') || true
[ -n "$pid_after" ] && [ "$pid_after" != "$pid_before" ] ||
    unmeasurable "the reader came back in process '${pid_after:-none}', not a new one"

# --- the verdict -----------------------------------------------------------------------------
if ! restored=$(await_indicator "$TARGET_PAGE" "$SEED_PAGES2" 20); then
    if [ -z "$restored" ]; then
        unmeasurable "the restored reader showed no page indicator"
    fi
    read -r page pages <<< "$restored"
    chapter="the chapter of $pages pages"
    [ "$pages" = "$SEED_PAGES1" ] && chapter=$(chapter_name 1)
    [ "$pages" = "$SEED_PAGES2" ] && chapter=$(chapter_name 2)
    echo "reader_process_death: FAIL: the reader came back on page $page of $chapter," \
        "where it was killed on page $TARGET_PAGE of $(chapter_name 2)" >&2
    adb_shell am force-stop "$SAIKOU_PACKAGE"
    exit 1
fi
assert_menu_names 2 "after the restore" 1
adb_shell am force-stop "$SAIKOU_PACKAGE"
say "PASS: process $pid_before killed in the background, and process $pid_after restored page" \
    "$TARGET_PAGE of $(chapter_name 2)"
