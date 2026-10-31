#!/usr/bin/env bash
# Two reader behaviours unit K2 fixed (ruling R74), checked on a device from the host against the
# installed debug build, on the entry ReaderProcessDeathTest seeds. Both are always checked, so one
# run answers for both:
#  1. resume: Chapter 1 is opened from the entry's screen, read to page 3, closed with BACK, and
#     opened again from the entry's screen, which names no page. It has to open on page 3.
#  2. Next chapter once the next chapter is preloaded: with Chapter 1 on page 4 (fewer pages left
#     than the preload window, so the page selection asks for the next chapter's preload), the
#     menu's Next chapter button has to move the reader to page 1 of Chapter 2 (the indicator, then
#     the menu opened again, which has to name Chapter 2), and the reader has to log its
#     reader.chapter marker for Chapter 2's id after the tap.
# The marker is read without clearing logcat, which failed on the API 26 image: every SaikouBench
# line is dumped and reader_resume_next.py keeps only the lines stamped at or after the device's own
# clock reading, taken just before the tap. A preload logs no reader.chapter marker (it is an
# AdjacentChapterLoaded event), so the marker answers for the switch alone, and it names this run's
# seeded chapter id, so an earlier run's line cannot match.
#
# Moved from unit K2's evidence (evidence/phase2-reader-fixes/reader_resume_next.sh) by unit L,
# which runs it in gate.sh's phase 2 step 5e after reader_process_death.sh, on the same install.
# The device proof that it fails on the defects it checks for is K2's report, section 7.3.
#
# Needs SAIKOU_SERIAL and SAIKOU_DEVICE_ID (tools/bench/common.sh), with the debug application and its
# test APK installed. Exit codes: 0 both pass; 1 a check failed; 2 missing input; 3 the check could
# not be made (adb or device error, a seed that failed, a reader that never reached the page), under
# ruling R36.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SAIKOU_BENCH_OUT=${SAIKOU_BENCH_OUT:-$HERE/../out/reader-resume/${SAIKOU_DEVICE_ID:-unknown}}
# shellcheck source=../bench/common.sh
source "$HERE/../bench/common.sh"

PARSE="python3 $HERE/reader_process_death.py"
RESUME="python3 $HERE/reader_resume_next.py"
READER="$SAIKOU_PACKAGE/com.shinsaikou.reader.ReaderActivity"
SEED_TEST="com.shinsaikou.reader.ReaderProcessDeathTest#anEntryIsSeededForTheHostToKillAndRestore"
chapter_name() { printf 'Chapter %s' "$1"; }
say() { echo "reader_resume_next: $*"; }
unmeasurable() { echo "reader_resume_next: $* (the check could not be made)" >&2; exit 3; }
failed=0
fail() { echo "reader_resume_next: FAIL: $*" >&2; failed=1; }

[ -n "${SAIKOU_INSTRUMENTATION:-}" ] || { echo "missing: the test APK for $SAIKOU_PACKAGE on $SAIKOU_SERIAL" >&2; exit 2; }
DUMPS=$SAIKOU_BENCH_OUT/dumps
mkdir -p "$DUMPS"
rm -f "$DUMPS"/*.xml

dump() {
    local out
    out="$DUMPS/$(date +%H%M%S.%N).xml"
    adb_shell uiautomator dump /sdcard/saikou-reader-resume.xml > /dev/null
    $ADB shell cat /sdcard/saikou-reader-resume.xml > "$out" || unmeasurable "cannot read the uiautomator dump"
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

open_entry() {
    adb_shell am start -W -n "$SAIKOU_PACKAGE/$SAIKOU_LAUNCH_ACTIVITY" -a com.shinsaikou.OPEN_ENTRY \
        --el com.shinsaikou.extra.MANGA_ID "$SEED_MANGA" < /dev/null | tr -d '\r'
}
open_chapter_1() {
    local point
    point=$(await_text "$(chapter_name 1)" 30) || unmeasurable "the entry's screen never listed $(chapter_name 1)"
    # shellcheck disable=SC2086
    tap $point
    assert_foreground "$READER" 30
}

# --- 1. resume -------------------------------------------------------------------------------
out=$(open_entry)
assert_launched "$out"
open_chapter_1
await_indicator 1 "$SEED_PAGES1" 30 > /dev/null || unmeasurable "the first open never showed page 1 of $(chapter_name 1)"
forward_to 3 "$SEED_PAGES1" 4
say "first open: read to page 3 of $(chapter_name 1)"
adb_shell input keyevent KEYCODE_BACK
open_chapter_1
if seen=$(await_indicator 3 "$SEED_PAGES1" 20); then
    say "PASS resume: the second open of $(chapter_name 1), with no page given, showed page 3 of $SEED_PAGES1"
else
    fail "resume: the partly read $(chapter_name 1) opened again on '${seen:-no indicator}', not page 3 of $SEED_PAGES1"
fi

# --- 2. Next chapter once the next chapter is preloaded --------------------------------------
# To page 4: fewer pages than the preload window remain, so the page selection asks for the next
# chapter's preload. The preload logs no marker of its own, so it is given 4 seconds; a local
# chapter's page list is read in well under one.
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
# The switch selects a page in the pager, which closes the menu, as upstream's PagerViewer does on a
# page selection the slider did not cause. So the switch shows as page 1 of Chapter 2 on the
# indicator, and a switch that never happened leaves the menu open on Chapter 1 with no indicator.
seen=
deadline=$(( $(date +%s) + 15 ))
while :; do
    seen=$(read_indicator)
    [ "$seen" = "1 $SEED_PAGES2" ] && break
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 1
done
bench_lines_since "$since" "$SAIKOU_BENCH_OUT/next-logcat.txt"
switches=$(grep -c "reader.chapter " "$SAIKOU_BENCH_OUT/next-logcat.txt" || true)
if [ "$seen" != "1 $SEED_PAGES2" ]; then
    menu=neither
    await_text "$(chapter_name 1)" 1 > /dev/null && menu="$(chapter_name 1)"
    fail "next: 15 seconds after Next chapter the reader shows '${seen:-no indicator}'" \
        "(the menu names $menu), not page 1 of $SEED_PAGES2; $switches reader.chapter line(s) since $since"
elif ! grep -q "$marker" "$SAIKOU_BENCH_OUT/next-logcat.txt"; then
    fail "next: the reader shows page 1 of $SEED_PAGES2 but no '$marker' was logged since $since"
else
    # The chapter by name as well as by its length: the menu, opened again, names Chapter 2.
    tap "$middle_x" "$middle_y"
    if await_text "$(chapter_name 2)" 10 > /dev/null; then
        say "PASS next: the menu's Next chapter moved the reader from page 4 of $(chapter_name 1)" \
            "to page 1 of $(chapter_name 2), which the menu names, and '$marker' was logged after $since"
    else
        fail "next: the reader shows page 1 of $SEED_PAGES2, but its menu does not name $(chapter_name 2)"
    fi
fi
adb_shell am force-stop "$SAIKOU_PACKAGE"
exit "$failed"
