#!/usr/bin/env bash
# Unit K3's device check (plan unit K3 item 1, ruling R75), run from the host against the installed
# debug build with the helpers of reader_process_death.sh, as reader_resume_next.sh is.
#
# An unread chapter with progress, reached inside a session by the menu's Next chapter button, has to
# open at the page it was left on. On the entry ReaderProcessDeathTest seeds (Chapter 1 of 5 pages,
# Chapter 2 of 4):
#  1. Chapter 2 is opened from the entry's screen, read to page 3 of 4 and closed with BACK, which
#     writes its progress: page 3, not read.
#  2. Chapter 1 is opened from the entry's screen and read to page 4 of 5, where the next chapter is
#     preloaded, and the menu's Next chapter button is pressed.
#  3. The reader has to show page 3 of 4, the menu opened again has to name Chapter 2, and the reader
#     has to log its reader.chapter marker for Chapter 2's id after the tap. Before K3 it showed page
#     1 of 4 (R74 rule 4).
# The marker is read without clearing logcat, by the device clock read just before the tap, through
# reader_resume_next.py's timestamp filter. The filter matters more here than in reader_resume_next.sh:
# step 1 opens Chapter 2 and logs the same marker before the reading, so a filter that let that line
# through would pass a Next chapter button that did nothing.
#
# Moved from unit K3's evidence (evidence/phase2-reader-resume/reader_resume_in_session.sh) by unit
# K4, which runs it in gate.sh's phase 2 step 5e after reader_resume_next.sh, on the same install.
# The changes are reader_resume_next.sh's: its helpers are found beside it rather than through
# SAIKOU_REPO, SAIKOU_BENCH_OUT has a default, and the timestamp filter and the description finder are
# reader_resume_next.py's, tested there. The device proof that it fails without in-session resume is
# K3's report, section 7.4.
#
# Needs SAIKOU_SERIAL and SAIKOU_DEVICE_ID (tools/bench/common.sh), with the debug application and its
# test APK installed. Exit codes: 0 pass; 1 the check failed; 2 missing input; 3 the check could not
# be made (adb or device error, a seed that failed, a reader that never reached the page), under
# ruling R36.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SAIKOU_BENCH_OUT=${SAIKOU_BENCH_OUT:-$HERE/../out/reader-resume-in-session/${SAIKOU_DEVICE_ID:-unknown}}
# shellcheck source=../bench/common.sh
source "$HERE/../bench/common.sh"

PARSE="python3 $HERE/reader_process_death.py"
RESUME="python3 $HERE/reader_resume_next.py"
READER="$SAIKOU_PACKAGE/com.shinsaikou.reader.ReaderActivity"
SEED_TEST="com.shinsaikou.reader.ReaderProcessDeathTest#anEntryIsSeededForTheHostToKillAndRestore"
chapter_name() { printf 'Chapter %s' "$1"; }
say() { echo "reader_resume_in_session: $*"; }
unmeasurable() { echo "reader_resume_in_session: $* (the check could not be made)" >&2; exit 3; }
fail() { echo "reader_resume_in_session: FAIL: $*" >&2; exit 1; }

[ -n "${SAIKOU_INSTRUMENTATION:-}" ] || { echo "missing: the test APK for $SAIKOU_PACKAGE on $SAIKOU_SERIAL" >&2; exit 2; }
DUMPS=$SAIKOU_BENCH_OUT/dumps
mkdir -p "$DUMPS"
rm -f "$DUMPS"/*.xml

dump() {
    local out
    out="$DUMPS/$(date +%H%M%S.%N).xml"
    adb_shell uiautomator dump /sdcard/saikou-reader-in-session.xml > /dev/null
    $ADB shell cat /sdcard/saikou-reader-in-session.xml > "$out" || unmeasurable "cannot read the uiautomator dump"
    printf '%s\n' "$out"
}
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
await_indicator() { # <page> <pages> <seconds>
    local want="$1 $2" deadline=$(( $(date +%s) + $3 )) seen=
    while :; do
        seen=$(read_indicator)
        [ "$seen" = "$want" ] && { printf '%s\n' "$seen"; return 0; }
        [ "$(date +%s)" -ge "$deadline" ] && { printf '%s\n' "$seen"; return 1; }
        sleep 1
    done
}
await_text() { # <text> <seconds>
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
# Waits for a node whose content description is exactly $1 and prints its centre.
await_description() { # <description> <seconds>
    local deadline=$(( $(date +%s) + $2 )) file point status
    while :; do
        file=$(dump)
        status=0
        point=$($RESUME find-description --description "$1" "$file") || status=$?
        [ "$status" -eq 0 ] && { printf '%s\n' "$point"; return 0; }
        [ "$status" -eq 1 ] || unmeasurable "the dump $file cannot be read"
        [ "$(date +%s)" -ge "$deadline" ] && return 1
        sleep 1
    done
}
# Taps forward until the indicator reads "<page> <pages>", one tap at a time, each waited for.
forward_to() { # <page> <pages> <taps at most>
    local reached= taps=0
    while [ "$taps" -lt "$3" ]; do
        reached=$(read_indicator)
        [ "$reached" = "$1 $2" ] && return 0
        tap "$forward_x" "$middle_y"
        taps=$((taps + 1))
        sleep 1.2
    done
    reached=$(read_indicator)
    [ "$reached" = "$1 $2" ] || unmeasurable "$3 taps forward left the reader at '${reached:-no indicator}', not page $1 of $2"
}
# The SaikouBench lines the device stamped at or after $1 ("MM-DD hh:mm:ss", the device's clock).
bench_lines_since() { # <since> <file>
    $ADB logcat -d -v threadtime -s SaikouBench:I > "$2.all" || unmeasurable "cannot read logcat"
    $RESUME since --since "$1" "$2.all" > "$2" || unmeasurable "the logcat in $2.all cannot be filtered from '$1'"
}
tap() { adb_shell input tap "$1" "$2"; }
forward_x=$(( SCREEN_W * 5 / 6 ))
middle_x=$(( SCREEN_W / 2 ))
middle_y=$(( SCREEN_H / 2 ))

set_orientation
adb_shell am force-stop "$SAIKOU_PACKAGE"
adb_shell dumpsys deviceidle whitelist +"$SAIKOU_PACKAGE" > /dev/null

# --- seed ------------------------------------------------------------------------------------
nonce="$(date +%s)$RANDOM"
seed_log=$SAIKOU_BENCH_OUT/seed.txt
$ADB shell am instrument -w -r -e class "$SEED_TEST" -e saikou.reader.restore.nonce "$nonce" \
    "$SAIKOU_INSTRUMENTATION" > "$seed_log" 2>&1 < /dev/null || true
grep -q '^OK (1 test)' "$seed_log" || { tail -20 "$seed_log" >&2; unmeasurable "the seed test did not pass"; }
$ADB logcat -d -s SaikouReaderRestore:I > "$SAIKOU_BENCH_OUT/seed-logcat.txt" || unmeasurable "cannot read logcat"
seed_vars=$($PARSE seed --nonce "$nonce" "$SAIKOU_BENCH_OUT/seed-logcat.txt") || unmeasurable "no seed ids"
while IFS='=' read -r name value; do
    [[ $name =~ ^SEED_[A-Z0-9]+$ ]] && printf -v "$name" '%s' "$value"
done <<< "$seed_vars"
say "seeded entry $SEED_MANGA: chapter 1 id $SEED_CHAPTER1 ($SEED_PAGES1 pages), chapter 2 id $SEED_CHAPTER2 ($SEED_PAGES2 pages)"
[ "$SEED_PAGES1" -ge 5 ] || unmeasurable "chapter 1 has too few pages to leave page 4 before its end"
[ "$SEED_PAGES2" -ge 4 ] || unmeasurable "chapter 2 has too few pages to leave it on page 3 unread"

open_entry() {
    adb_shell am start -W -n "$SAIKOU_PACKAGE/$SAIKOU_LAUNCH_ACTIVITY" -a com.shinsaikou.OPEN_ENTRY \
        --el com.shinsaikou.extra.MANGA_ID "$SEED_MANGA" < /dev/null | tr -d '\r'
}
open_chapter() { # <chapter number>
    local point
    point=$(await_text "$(chapter_name "$1")" 30) || unmeasurable "the entry's screen never listed $(chapter_name "$1")"
    # shellcheck disable=SC2086
    tap $point
    assert_foreground "$READER" 30
}

# --- 1. Chapter 2 left on page 3, unread -----------------------------------------------------
out=$(open_entry)
assert_launched "$out"
open_chapter 2
await_indicator 1 "$SEED_PAGES2" 30 > /dev/null || unmeasurable "the first open never showed page 1 of $(chapter_name 2)"
forward_to 3 "$SEED_PAGES2" 4
adb_shell input keyevent KEYCODE_BACK
say "$(chapter_name 2) read to page 3 of $SEED_PAGES2 and closed"

# --- 2. Chapter 1 to page 4, where Chapter 2 is preloaded, then Next chapter ------------------
open_chapter 1
await_indicator 1 "$SEED_PAGES1" 30 > /dev/null || unmeasurable "$(chapter_name 1) never opened on page 1 of $SEED_PAGES1"
forward_to 4 "$SEED_PAGES1" 5
sleep 4
tap "$middle_x" "$middle_y"
point=$(await_description "Next chapter" 10) || unmeasurable "the menu shows no Next chapter button"
await_text "$(chapter_name 1)" 5 > /dev/null || unmeasurable "the menu does not name $(chapter_name 1) before the switch"
since=$($ADB shell date +'%m-%d\ %H:%M:%S' | tr -d '\r') || unmeasurable "cannot read the device clock"
[[ $since =~ ^[0-9]{2}-[0-9]{2}\ [0-9]{2}:[0-9]{2}:[0-9]{2}$ ]] || unmeasurable "the device clock read '$since'"
# shellcheck disable=SC2086
tap $point
marker="reader.chapter id=$SEED_CHAPTER2 pages=$SEED_PAGES2"

# --- 3. the verdict ----------------------------------------------------------------------------
# The switch selects a page in the pager, which closes the menu, so the indicator shows it. The
# wait ends on either page of Chapter 2 it might show, so a wrong page is reported as soon as it is
# on screen, and the marker says whether the switch happened at all.
seen=
deadline=$(( $(date +%s) + 15 ))
while :; do
    seen=$(read_indicator)
    [ "$seen" = "3 $SEED_PAGES2" ] && break
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 1
done
bench_lines_since "$since" "$SAIKOU_BENCH_OUT/next-logcat.txt"
switches=$(grep -c "reader.chapter " "$SAIKOU_BENCH_OUT/next-logcat.txt" || true)
grep -q "$marker" "$SAIKOU_BENCH_OUT/next-logcat.txt" ||
    fail "15 seconds after Next chapter the reader shows '${seen:-no indicator}' and no '$marker' was logged since $since ($switches reader.chapter line(s))"
[ "$seen" = "3 $SEED_PAGES2" ] ||
    fail "Next chapter moved the reader to $(chapter_name 2) ('$marker' logged after $since), which shows '${seen:-no indicator}', not page 3 of $SEED_PAGES2 where it was left"
tap "$middle_x" "$middle_y"
await_text "$(chapter_name 2)" 10 > /dev/null ||
    fail "the reader shows page 3 of $SEED_PAGES2, but its menu does not name $(chapter_name 2)"
adb_shell am force-stop "$SAIKOU_PACKAGE"
say "PASS: the menu's Next chapter moved the reader from page 4 of $(chapter_name 1) to page 3 of" \
    "$(chapter_name 2), where the unread chapter was left, which the menu names, and '$marker' was logged after $since"
