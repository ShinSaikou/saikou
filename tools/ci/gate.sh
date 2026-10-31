#!/usr/bin/env bash
# Runs a phase's definition of done in order. usage: gate.sh <phase>
#
# Phase 0 is contract 01 section 9's checklist, and tools/ci/phase0.sh is this script with 0.
# Phase 1 is the same checklist plus the steps design/plans/phase1-finish.md section 7 adds, and
# tools/ci/phase1.sh is this script with 1. Every phase 1 addition sits behind `phase1`, so a phase 0
# run takes exactly the steps it always took. Phase 2 is phase 1 plus the steps
# design/plans/phase2-start.md adds, each behind `phase2`, so phases 0 and 1 are unchanged by them,
# and tools/ci/phase2.sh is this script with 2. Phase 3 is phase 2 plus the steps
# design/plans/phase3-start.md adds, each behind `phase3`, so phases 0 to 2 are unchanged by them,
# and tools/ci/phase3.sh is this script with 3. A phase 3 run signs the release with the owner's key
# (ruling R90), so it stops at step 2 until keystore.properties exists (owner item 4).
#
# A step whose prerequisite is absent prints one SKIP line and the run continues: most of the tree
# is still being written, so a checklist that stopped at the first missing Gradle task would say
# nothing about the rest. A step that runs and fails stops the run. Phase 2 ends on one more check
# (step 8d, ruling R36): every row rows.json binds in the phase has to have a value in measured.json,
# so a skipped step whose rows the phase binds still ends the run there, naming each row.
#
# Exit codes: 0 every step that could run passed; 1 a step failed; 2 no phase, or one this script
# does not know.
set -uo pipefail

PHASE=${1:-}
case "$PHASE" in
    0|1|2|3) ;;
    *) echo "usage: gate.sh <phase>, where <phase> is 0, 1, 2 or 3" >&2; exit 2 ;;
esac
phase1() { [ "$PHASE" -ge 1 ]; }
phase2() { [ "$PHASE" -ge 2 ]; }
phase3() { [ "$PHASE" -ge 3 ]; }

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
cd "$ROOT"
# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"

passed=0
skipped=0

step() { echo; echo "=== $* ==="; }
ok() { echo "PASS  $*"; passed=$((passed + 1)); }
skip() { echo "SKIP  $*"; skipped=$((skipped + 1)); }
fail() { echo "FAIL  $*" >&2; echo; echo "phase $PHASE failed at: $*" >&2; exit 1; }
run() { # run <label> <command...>
    local label=$1; shift
    if "$@"; then ok "$label"; else fail "$label"; fi
}

# Never export an empty ANDROID_SDK_ROOT: AGP falls back to local.properties when the variable is
# unset, but honours it when it is set and empty, which makes every Gradle step fail to configure
# and silently report SKIP.
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    _sdk=$(sdk_root || true)
    [ -n "$_sdk" ] && export ANDROID_SDK_ROOT=$_sdk
fi
# Never export this empty, for the same reason ANDROID_SDK_ROOT is guarded above and one more: an
# empty value reaches am instrument as a bare `-e saikou.repo.url ` pair, which shifts the arguments
# after it and makes the runner report "Invalid userId -2" and run no tests while Gradle reports
# BUILD SUCCESSFUL. app/build.gradle.kts now treats blank as absent as well.
SAIKOU_REPO_URL=${SAIKOU_REPO_URL:-$(localprop saikou.repo.url SAIKOU_REPO_URL || true)}
[ -n "$SAIKOU_REPO_URL" ] && export SAIKOU_REPO_URL || unset SAIKOU_REPO_URL
SAIKOU_REPO_URL=${SAIKOU_REPO_URL:-}
export SAIKOU_STRICT=${SAIKOU_STRICT:-true}
# app/build.gradle.kts is the only build file that forwards SAIKOU_STRICT, so every other module's
# device tests ran non-strict, and ImageProbeDeviceTest's four fixture routes passed without
# executing in every run (R49). Phase 1 passes the same value on the command line, which reaches
# every module's instrumentation run alike.
STRICT_ARGS=()
phase1 && STRICT_ARGS=("-Pandroid.testInstrumentationRunnerArguments.saikou.strict=$SAIKOU_STRICT")
# Unit R1 of phase3-start.md: a phase 3 run signs the release with the owner's key, and fails without
# keystore.properties. Every Gradle call below that builds the release variant takes the flag, so no
# later step rebuilds app-release.apk on the debug key behind step 2b's check; phases 0 to 2 pass
# nothing and build as they always did.
SIGNING_ARGS=()
phase3 && SIGNING_ARGS=(-Psaikou.ownerSigning=true)

# The two owner inputs that are files. local.properties records where they sit on THIS machine;
# the instrumentation is handed a device path instead, because the app cannot open a host path and
# a host path pushed through as an argument fails as "no such file" rather than as the wrong side
# of the cable. push_owner_inputs below is the only thing that turns one into the other.
HOST_FIXTURES_DIR=$(localprop saikou.fixtures.dir SAIKOU_FIXTURES_DIR || true)
HOST_BACKUP_FILE=$(localprop saikou.backup.file SAIKOU_BACKUP_FILE || true)
DEVICE_FIXTURES_DIR=/data/local/tmp/saikou-fixtures
DEVICE_BACKUP_FILE=/data/local/tmp/saikou-owner-backup.tachibk
# Step 5f's inputs: unit A's fixture extension, its path on the device, and the outcome file the
# force-stop row's two runs share (ForceStopOutcome in the androidTest source set).
DOWNLOAD_FIXTURE_APK=tools/out/update-fixture/update-fixture.apk
DEVICE_DOWNLOAD_FIXTURE_APK=/data/local/tmp/saikou-download-fixture.apk
DOWNLOAD_FORCE_STOP_OUTCOME=/data/local/tmp/saikou-force-stop/download_queue_survives_force_stop.json
# Step 5i's: the directory browse_fixture_server.py serves its extension store from, the APK above and
# its signer's digest beside it (unit A's, as tools/fixtures/update-extension/build.sh wrote them).
BROWSE_FIXTURE_REPO=tools/out/update-fixture
ADB=${ANDROID_SDK_ROOT:-}/platform-tools/adb
# OwnerBackupSmokeTest is a JVM test and wants the host path under its own variable, so the one
# value in local.properties now serves both halves of the suite instead of only the device half.
[ -r "$HOST_BACKUP_FILE" ] && export SAIKOU_OWNER_BACKUP=$HOST_BACKUP_FILE

# Pushes whichever owner inputs this machine has to <serial> and leaves the matching runner
# arguments in RUNNER_ARGS. A runner argument on the Gradle command line overrides
# defaultConfig.testInstrumentationRunnerArguments, and app/build.gradle.kts no longer forwards
# either key, so this is the only source of both.
RUNNER_ARGS=()
push_owner_inputs() { # push_owner_inputs <serial>
    local serial=$1
    RUNNER_ARGS=()
    if [ -n "$HOST_FIXTURES_DIR" ] && [ -d "$HOST_FIXTURES_DIR" ]; then
        # `adb push dir dest` nests the source inside dest when dest already exists, so the second
        # run of the day would land the tree at $DEVICE_FIXTURES_DIR/<basename> and every fixture
        # would read as missing. Removing the target first is what keeps the path stable.
        "$ADB" -s "$serial" shell rm -rf "$DEVICE_FIXTURES_DIR" >/dev/null 2>&1 || true
        if "$ADB" -s "$serial" push "$HOST_FIXTURES_DIR" "$DEVICE_FIXTURES_DIR" >/dev/null; then
            ok "pushed the fixtures directory to $serial"
            RUNNER_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.saikou.fixtures.dir=$DEVICE_FIXTURES_DIR")
        else
            fail "pushing the fixtures directory to $serial"
        fi
    else
        skip "fixtures push (saikou.fixtures.dir names no directory on this machine)"
    fi
    if [ -n "$HOST_BACKUP_FILE" ] && [ -r "$HOST_BACKUP_FILE" ]; then
        # Beside the fixtures directory and never inside it: Fixtures walks that tree, and a backup
        # sitting in it is an entry nothing there can explain.
        if "$ADB" -s "$serial" push "$HOST_BACKUP_FILE" "$DEVICE_BACKUP_FILE" >/dev/null; then
            ok "pushed the owner backup to $serial"
            RUNNER_ARGS+=("-Pandroid.testInstrumentationRunnerArguments.saikou.backup.file=$DEVICE_BACKUP_FILE")
        else
            fail "pushing the owner backup to $serial"
        fi
    else
        skip "backup push (saikou.backup.file names no readable file on this machine)"
    fi
}

# Gradle task names are probed once; a task the scaffold has not grown yet turns its step into a
# SKIP instead of a Gradle failure.
GRADLE_TASKS=""
if [ -x ./gradlew ]; then
    GRADLE_TASKS=$(./gradlew --quiet --console=plain tasks --all 2>/dev/null | awk '{print $1}')
fi
# `tasks --all` prints per-project names (`app:assembleDebug`), while the checklist calls the
# multi-project name (`assembleDebug`), so a match on the last segment counts.
# Here-strings, not pipes. `grep -q` exits on the first match and closes the pipe, the writer dies
# of SIGPIPE, and `set -o pipefail` then reports the whole pipeline as failed even though the task
# was found. With four thousand task names that race is lost more often than won, which silently
# turned every Gradle step below into a SKIP.
has_task() {
    local want=${1#:}
    grep -qxF "$want" <<< "$GRADLE_TASKS" ||
        grep -qE "(^|:)$(sed 's/[].[^$*\/]/\\&/g' <<< "$want")$" <<< "$GRADLE_TASKS"
}
gradle_step() { # gradle_step <label> <task> [extra args...]
    local label=$1 task=$2; shift 2
    if [ -z "$GRADLE_TASKS" ]; then skip "$label (no Gradle wrapper, or it could not list its tasks)"; return; fi
    if ! has_task "$task"; then skip "$label (no $task task yet)"; return; fi
    run "$label" ./gradlew "$task" "$@"
}

# `am instrument` exits 0 whether the test passed, failed, or matched nothing at all: the verdict is
# only in its output, where a run that matched no class still prints "OK (0 tests)". So the count is
# read back here, the same way ran_tests reads AGP's XML, and a run of zero tests is a failure.
instrument_one() { # instrument_one <serial> <class#method> <log> [am instrument options...]
    local serial=$1 filter=$2 log=$3 runner
    shift 3
    runner=$("$ADB" -s "$serial" shell pm list instrumentation 2>/dev/null | tr -d '\r' |
        sed -n 's|^instrumentation:\([^ ]*\) (target='"$SAIKOU_PACKAGE"')$|\1|p' | tail -1)
    if [ -z "$runner" ]; then
        echo "no instrumentation targeting $SAIKOU_PACKAGE is installed on $serial" >&2
        return 1
    fi
    "$ADB" -s "$serial" shell am instrument -w -r -e class "$filter" "$@" "$runner" > "$log" 2>&1 || return 1
    grep -qE '^OK \([1-9][0-9]* tests?\)' "$log" && return 0
    echo "$filter did not pass on $serial; the runner said:" >&2
    tail -20 "$log" >&2
    return 1
}

# Phase 2 decision Q8 (a)(i): a row about what survives `am force-stop`, run as two instrumentations
# with the force-stop between them. One run cannot do it: the test runs inside the process it would
# stop. The first run leaves its state behind, writes what it left to an outcome file at <outcome>
# on the device and returns. The file is pulled into <dir> as outcome.pulled (a name collect.py does
# not read) and read by force_stop_outcome.py, which prints the state and ends the run when there is
# nothing the second run could compare (R36). Then the package is force-stopped and checked gone,
# and the second run reads the outcome file and compares. The file is removed first, so an earlier
# run's cannot stand in for this one's, and each run goes through instrument_one, so each has to pass
# a test of its own. The row's fragment is the caller's to check.
#
# On the API 36 image the end of an instrumentation run already force-stops the package (the events
# buffer's "stop <package> due to finished inst"), and the start of the next one does it again. The
# explicit force-stop here is the one the row names; that the platform's own came first is recorded
# in unit I's report, and it is the same force-stop path, which is what WorkManager reads.
force_stop_between() { # force_stop_between <serial> <dir> <outcome on device> <part one> <part two> [am instrument options...]
    local serial=$1 dir=$2 outcome=$3 first=$4 second=$5 running
    shift 5
    mkdir -p "$dir"
    "$ADB" -s "$serial" shell rm -f "$outcome" || { echo "adb: could not remove $outcome on $serial" >&2; return 1; }
    if [ -n "$("$ADB" -s "$serial" shell ls "$outcome" 2>/dev/null | tr -d '\r')" ]; then
        echo "an earlier outcome file is still at $outcome on $serial" >&2
        return 1
    fi
    if ! instrument_one "$serial" "$first" "$dir/part-one.txt" "$@"; then
        echo "part one ($first) did not pass on $serial" >&2
        return 1
    fi
    rm -f "$dir/outcome.pulled"
    "$ADB" -s "$serial" pull "$outcome" "$dir/outcome.pulled" >/dev/null 2>&1 || true
    python3 "$HERE/force_stop_outcome.py" "$dir/outcome.pulled" --package "$SAIKOU_PACKAGE" \
        --part-one-log "$dir/part-one.txt" || return 1
    "$ADB" -s "$serial" shell am force-stop "$SAIKOU_PACKAGE" ||
        { echo "adb: am force-stop $SAIKOU_PACKAGE failed on $serial" >&2; return 1; }
    # "none" only comes back from a shell that ran, so an adb that answered nothing is not read as a
    # stopped package.
    running=$("$ADB" -s "$serial" shell "pidof $SAIKOU_PACKAGE || echo none" 2>/dev/null | tr -d '\r')
    if [ "$running" != none ]; then
        echo "$SAIKOU_PACKAGE is still running on $serial after am force-stop: ${running:-adb gave no answer}" >&2
        return 1
    fi
    echo "    force-stopped $SAIKOU_PACKAGE on $serial between the two runs"
    if ! instrument_one "$serial" "$second" "$dir/part-two.txt" "$@"; then
        echo "part two ($second) did not pass on $serial" >&2
        return 1
    fi
}

# A fixture server of tools/bench/ on the port the fixture extension is built with, 18794: step 5f's
# download_hold_server.py (unit A's frozen server wrapped with a hold on every page after a chapter's
# first) and step 5i's browse_fixture_server.py (the same server wrapped as an extension store, phase
# 5 unit TG2), each given --log and then <arguments>. Each exits 3 when that port is taken, and this
# fails with its message rather than waiting, so a gate run never answers another run's requests.
# Logs into <dir>, from empty: the server appends, and a log holding an earlier run's requests would
# read as this run's. One server at a time; stop_fixture_server stops it, and so does the run's EXIT
# trap.
FIXTURE_SERVER_PID=
start_fixture_server() { # start_fixture_server <dir> <script in tools/bench> [arguments...]
    local dir=$1 script=$2 waited=0
    shift 2
    rm -f "$dir/server.requests.jsonl"
    python3 "$HERE/../bench/$script" --log "$dir/server.requests.jsonl" "$@" > "$dir/server.txt" 2>&1 &
    FIXTURE_SERVER_PID=$!
    until grep -q '^ready ' "$dir/server.txt" 2>/dev/null; do
        if ! kill -0 "$FIXTURE_SERVER_PID" 2>/dev/null; then
            cat "$dir/server.txt" >&2
            FIXTURE_SERVER_PID=
            return 1
        fi
        if [ "$waited" -ge 50 ]; then
            echo "the fixture server $script was not ready after 10 s" >&2
            stop_fixture_server
            return 1
        fi
        sleep 0.2
        waited=$((waited + 1))
    done
}
stop_fixture_server() {
    [ -n "$FIXTURE_SERVER_PID" ] || return 0
    kill -TERM "$FIXTURE_SERVER_PID" 2>/dev/null || true
    wait "$FIXTURE_SERVER_PID" 2>/dev/null || true
    FIXTURE_SERVER_PID=
}
# Whether nothing listens on TCP <port> on this machine; an ss that fails counts as not free.
port_free() { # port_free <port>
    local listening
    listening=$(ss -ltnH "sport = :$1") || return 1
    [ -z "$listening" ]
}

# Whether <serial> holds <row>'s fragment with the value true. A second run that passed without
# publishing returned early, which is not the row.
published_true() { # published_true <serial> <row>
    local fragment
    fragment=$("$ADB" -s "$1" shell cat "/data/local/tmp/saikou-gate/$2.json" 2>/dev/null | tr -d '\r')
    case "$fragment" in
        *"\"id\":\"$2\""*'"value":true'*) return 0 ;;
    esac
    echo "no $2 fragment with the value true on $1: ${fragment:-none}" >&2
    return 1
}

# Ruling R58. Reads the platform crash buffer on <serial> and fails on any entry from the package,
# naming the device and quoting the entry. A low-memory kill writes nothing here, so what this sees
# is the application's own defects. The dump is kept beside the device's bench output.
crash_buffer_check() { # crash_buffer_check <serial> <device>
    local serial=$1 device=$2 dump="$BENCH_OUT/$2/crash-buffer.txt"
    mkdir -p "$BENCH_OUT/$device"
    if ! "$ADB" -s "$serial" logcat -b crash -d -v threadtime > "$dump" 2>&1; then
        echo "adb: \`logcat -b crash -d\` failed on $serial" >&2
        return 1
    fi
    python3 "$HERE/crash_buffer.py" "$dump" --package "$SAIKOU_PACKAGE" --device "$device"
}

# Saves the crash, events and main buffers of <serial> into <dir> for lowram_outcome.py, which is
# how the low RAM step tells a death from a never-run test when the repro left no fragment.
save_buffers() { # save_buffers <serial> <dir>
    local serial=$1 dir=$2 buffer
    for buffer in crash events main; do
        if ! "$ADB" -s "$serial" logcat -b "$buffer" -d -v threadtime > "$dir/$buffer-buffer.txt" 2>&1; then
            echo "adb: \`logcat -b $buffer -d\` failed on $serial" >&2
            return 1
        fi
    done
}

# The RAM and the VM heap tools/avd/devices.env gives <avd>, printed as "<ram MB> <heap MB>", with
# the heap an entry that names none gets. Read in a subshell so devices.env's names stay out of this
# script. Fails when devices.env has no such AVD.
avd_memory() { # avd_memory <avd name>
    (
        # shellcheck source=../avd/devices.env
        source "$HERE/../avd/devices.env" || exit 1
        for spec in "${SAIKOU_AVDS[@]}"; do
            IFS='|' read -r name _profile _image ram _w _h _d heap <<<"$spec"
            if [ "$name" = "$1" ]; then echo "$ram ${heap:-$SAIKOU_AVD_HEAP_MB}"; exit 0; fi
        done
        echo "tools/avd/devices.env defines no AVD named $1" >&2
        exit 1
    )
}

# Ruling R36 for the low RAM step: the booted <avd> on <serial> has the MemTotal and the memory
# class devices.env describes, or the repro would measure another device. The two dumps are kept in
# <dir> beside the step's other output; lowram_preflight.py decides and names what does not match.
lowram_preflight() { # lowram_preflight <serial> <avd> <dir>
    local serial=$1 avd=$2 dir=$3 memory
    memory=$(avd_memory "$avd") || return 1
    "$ADB" -s "$serial" shell cat /proc/meminfo > "$dir/meminfo.txt" 2>&1 || return 1
    "$ADB" -s "$serial" shell getprop > "$dir/getprop.txt" 2>&1 || return 1
    python3 "$HERE/lowram_preflight.py" --meminfo "$dir/meminfo.txt" --getprop "$dir/getprop.txt" \
        --avd "$avd" --serial "$serial" --ram-mb "${memory% *}" --heap-mb "${memory#* }"
}

# A connected-test task reports BUILD SUCCESSFUL when its class filter matched nothing, and AGP
# writes tests="0" into the result XML and exits 0. That is how step 7 passed for weeks while
# running no tests at all: it names a class that has never been written. Every instrumentation
# step therefore has to read the count back out rather than trust the exit code.
#
# The count goes into the log because AGP writes one result directory per variant and a later step
# reusing that directory overwrites it. Reading the count back proves a suite ran; printing it is
# the only way anyone can tell afterwards how many it was, and a step that quietly halves its roster
# still satisfies the total > 0 test. So ran_tests.py prints the count per class as well, and the
# `Skipped:` lines the tests logged before an early return, which the XML never shows (R49).
ran_tests() { # ran_tests <results dir> [ran_tests.py options...]
    local dir=$1
    shift
    [ -d "$dir" ] || return 1
    python3 "$HERE/ran_tests.py" "$dir" "$@"
}

# Phase 2 (unit L of phase2-start.md): every device step that runs a class list reads each class it
# expects back out of the XML by name, and fails on a failed test itself rather than leaving that to
# Gradle's exit alone. A total above zero passes a suite that silently lost a class, and a class
# nothing requires can go unrun for weeks: units G, H and J each found a device class of theirs
# that no step named (ARCHITECTURE.md, their carry-overs). These are those names, one list per
# module with a device test source set, every class there with a test in it. They are named here
# rather than read off the tree when the step runs, so a deleted or renamed class fails the step
# instead of quietly shrinking what it expects; tools/test_ran_tests.py holds each list against the
# tree, and step 1 runs it, so a class added without its line here fails the run before any device
# is used. Names only, never counts: R49 keeps counts out of this script.
DEVICE_CLASSES_CORE_ARCHIVE=(
    com.shinsaikou.core.archive.ArchiveReaderTest
)
DEVICE_CLASSES_READER=(
    com.shinsaikou.reader.ChapterCacheTest
    com.shinsaikou.reader.ChapterTransitionViewerTest
    com.shinsaikou.reader.ImageProbeDeviceTest
    com.shinsaikou.reader.LocalPageLoadersTest
    com.shinsaikou.reader.ReaderPageImageViewTest
    com.shinsaikou.reader.SampleSizeDeviceTest
    com.shinsaikou.reader.TallStripSplitterTest
    com.shinsaikou.reader.WebtoonRecyclerViewTest
)
DEVICE_CLASSES_APP=(
    com.shinsaikou.AppGraphTest
    com.shinsaikou.backup.BackupExportRoundTripTest
    com.shinsaikou.backup.BackupImportTest
    com.shinsaikou.backup.BackupNotifierForegroundChannelTest
    com.shinsaikou.bench.BenchContentTest
    com.shinsaikou.bench.BenchExtensionsTest
    com.shinsaikou.bench.DelayedTrackingColdStartTest
    com.shinsaikou.bench.InPlaceUpgradeCheckTest
    com.shinsaikou.bench.InPlaceUpgradeSeedTest
    com.shinsaikou.bench.LowRamReproTest
    com.shinsaikou.bench.SchemaUpgradeBench
    com.shinsaikou.compat.CompatReplayTest
    com.shinsaikou.data.SchemaUpgradeTest
    com.shinsaikou.downloads.DownloadAdoptionDeviceTest
    com.shinsaikou.downloads.DownloadNotifierForegroundChannelTest
    com.shinsaikou.extensions.ExtensionHostTest
    com.shinsaikou.gate.GateRowsTest
    com.shinsaikou.gate.ScaleRowsTest
    com.shinsaikou.libraryupdate.LibraryUpdateNotifierForegroundChannelTest
    com.shinsaikou.security.AppLockCoverTest
    com.shinsaikou.reader.DownloadedChapterReaderTest
    com.shinsaikou.reader.PagerChapterPreloadTest
    com.shinsaikou.reader.ReaderChromeDeviceTest
    com.shinsaikou.reader.ReaderModeSwitchTest
    com.shinsaikou.reader.ReaderProcessDeathTest
    com.shinsaikou.reader.ReaderSmokeTest
    com.shinsaikou.reader.SavedPicturesDeviceTest
    com.shinsaikou.signin.GoogleRoundTripDeviceTest
    com.shinsaikou.sources.local.LocalSourceDeviceTest
    com.shinsaikou.sync.DriveTokenDeviceTest
    com.shinsaikou.sync.RestoredChapterRelistTest
    com.shinsaikou.sync.SecretStoreDeviceTest
    com.shinsaikou.sync.SyncDeviceCheckTest
    com.shinsaikou.sync.SyncLatchDeviceTest
    com.shinsaikou.sync.SyncPairTest
    com.shinsaikou.tracking.MyAnimeListSignInDeviceTest
    com.shinsaikou.tracking.TrackSheetDeviceTest
    com.shinsaikou.tracking.TrackerCompareDeviceTest
    com.shinsaikou.ui.CrashLogShareTest
    com.shinsaikou.ui.CrashScreenDeviceTest
    com.shinsaikou.ui.MainActivitySmokeTest
    com.shinsaikou.ui.SearchFieldTypingTest
    com.shinsaikou.ui.TwoPaneLayoutTest
    com.shinsaikou.ui.backup.ImportReportDeviceTest
    com.shinsaikou.ui.browse.ExtensionFilterDeviceTest
    com.shinsaikou.ui.browse.ExtensionStoresDeviceTest
    com.shinsaikou.ui.browse.ExtensionsSearchFieldTest
    com.shinsaikou.ui.browse.FixtureSourcesDeviceTest
    com.shinsaikou.ui.browse.BrowseSelectionDeviceTest
    com.shinsaikou.ui.browse.SourcesFilterDeviceTest
    com.shinsaikou.ui.browse.extension.ExtensionFilterContentTest
    com.shinsaikou.ui.browse.source.SourcesFilterContentTest
    com.shinsaikou.ui.manga.ChapterRowDeviceTest
    com.shinsaikou.ui.manga.EditDetailsDeviceTest
    com.shinsaikou.ui.components.AdaptiveSheetContentColorTest
    com.shinsaikou.ui.components.SearchFieldSubmitTest
    com.shinsaikou.ui.storage.StorageLocationRowsTest
    com.shinsaikou.util.EvidenceCaptureThemeRuleTest
)
# The ran_tests.py options a device step adds in phase 2, left in CHECK_ARGS: --fail-on-failures,
# and --require-class for each class of <module>'s list the step's runner arguments do not exclude.
# An exclusion is a class, or a package written `<package>.*`, spelled as the step's notClass and
# notPackage arguments spell it. Empty in phases 0 and 1, so their ran_tests calls are unchanged.
CHECK_ARGS=()
device_checks() { # device_checks <module> [excluded class or <package>.*...]
    local module=$1 cls excluded
    local -a classes
    shift
    CHECK_ARGS=()
    phase2 || return 0
    case "$module" in
        core:archive) classes=("${DEVICE_CLASSES_CORE_ARCHIVE[@]}") ;;
        reader) classes=("${DEVICE_CLASSES_READER[@]}") ;;
        app) classes=("${DEVICE_CLASSES_APP[@]}") ;;
        *) echo "gate.sh has no device class list for :$module" >&2; return 1 ;;
    esac
    CHECK_ARGS=(--fail-on-failures)
    for cls in "${classes[@]}"; do
        for excluded in "$@"; do
            [[ $cls == "$excluded" ]] && continue 2
            [[ $excluded == *.\* && $cls == "${excluded%\*}"* ]] && continue 2
        done
        CHECK_ARGS+=(--require-class "$cls")
    done
}
# What a failed read-back means, in the step's own failure line.
RAN_WHAT="ran no tests"
phase2 && RAN_WHAT="ran no tests, ran none of a class it expects, or failed a test"
# Phase 2: a connected run's results directory is emptied before the run, so a run that wrote no XML
# (a failed install can still end in BUILD SUCCESSFUL, unit G's carry-over 9) is not read back from
# the run before it, which in steps 5d and 5e ran the same class.
fresh_results() { # fresh_results <results dir>
    phase2 || return 0
    rm -rf "$1"
}

# R98 carry-over 5, R105 carry-over 5, R109 carry-over 6 and R123 carry-over 1: AGP's installer can
# refuse the install (INSTALL_FAILED_ALREADY_EXISTS from a com.shinsaikou an earlier session left, more than
# once on API 26) and the task still ends in BUILD SUCCESSFUL, exit 0 and no test case. So every
# connected-test step goes through here: when the run leaves no <testcase> in <results dir>, whatever
# Gradle's exit, its INSTALL_FAILED lines are printed, every com.shinsaikou package is uninstalled from
# <serial>, the directory is emptied, and the task runs once more; the step passes or fails on the
# second run's exit, and its ran_tests reads the second run as it reads any. A run that wrote a test
# case is never run again, failed or not. The staging device run (R124 item 6) retries the same way.
CONNECTED_LOG=tools/out/connected-run.log
connected_run() { # connected_run <label> <serial> <results dir> <gradle arguments...>
    local label=$1 serial=$2 results=$3 status package packages
    shift 3
    mkdir -p "$(dirname "$CONNECTED_LOG")"
    ./gradlew "$@" 2>&1 | tee "$CONNECTED_LOG"
    status=${PIPESTATUS[0]}
    if ! grep -rqs --include='*.xml' '<testcase' "$results"; then
        echo "    $label ran no test; it runs once more with no com.shinsaikou package on $serial"
        { grep -o 'INSTALL_FAILED[A-Z_]*' "$CONNECTED_LOG" || true; } | sort -u |
            sed "s/^/    the installer refused the first run on $serial: /"
        packages=$("$ADB" -s "$serial" shell pm list packages com.shinsaikou 2>/dev/null | tr -d '\r') || true
        for package in $(sed -n 's/^package:\(com\.shinsaikou[A-Za-z0-9_.]*\)$/\1/p' <<< "$packages"); do
            "$ADB" -s "$serial" uninstall "$package" >/dev/null 2>&1 ||
                echo "    adb could not uninstall $package from $serial" >&2
        done
        rm -rf "$results"
        ./gradlew "$@" 2>&1 | tee "$CONNECTED_LOG"
        status=${PIPESTATUS[0]}
    fi
    if [ "$status" = 0 ]; then ok "$label"; else fail "$label"; fi
}

# R122 carry-over 6: the Play Store on a Google Play image updates WebView in the background and can
# kill a test process (signal 9; CO's tablet run lost a test that way). So it is off on every AVD this
# run uses, from when step 4 takes the serial, and on again when the run ends, whichever way it ends:
# on_exit below is the run's EXIT trap, and fail() exits. The gate stops no emulator itself, so the
# trap is the last moment the run holds one. An AVD without the package is left alone. The staging
# device run (R124 item 6) does the same around each emulator it boots.
PLAY_STORE=com.android.vending
PLAY_STORE_OFF=()
play_store_off() { # play_store_off <serial>
    local serial=$1 off packages
    for off in ${PLAY_STORE_OFF[@]+"${PLAY_STORE_OFF[@]}"}; do [ "$off" = "$serial" ] && return 0; done
    packages=$("$ADB" -s "$serial" shell pm list packages "$PLAY_STORE" 2>/dev/null | tr -d '\r') || true
    grep -qxF "package:$PLAY_STORE" <<< "$packages" || return 0
    if "$ADB" -s "$serial" shell pm disable-user --user 0 "$PLAY_STORE" >/dev/null 2>&1; then
        PLAY_STORE_OFF+=("$serial")
        echo "    the Play Store is off on $serial for this run"
    else
        echo "    adb could not turn the Play Store off on $serial; the run goes on with it on" >&2
    fi
}
play_store_on() { # turns the Play Store on again on every serial play_store_off turned it off on
    local serial
    for serial in ${PLAY_STORE_OFF[@]+"${PLAY_STORE_OFF[@]}"}; do
        if "$ADB" -s "$serial" shell pm enable "$PLAY_STORE" >/dev/null 2>&1; then
            echo "    the Play Store is on again on $serial"
        else
            echo "    adb could not turn the Play Store on again on $serial" >&2
        fi
    done
    PLAY_STORE_OFF=()
}
# Everything a run leaves running or changed on a device is undone here, on every exit.
on_exit() {
    stop_fixture_server
    play_store_on
}
trap on_exit EXIT

# The deliverable list contract 01 section 9 requires this script to check with `test -f`. It runs
# before anything is built, for two reasons. A missing file costs a second to find here and twenty
# minutes of emulator time to trip over in step 8. And the step reads no variable at all, so there
# is nothing an exported-but-empty SAIKOU_* can quietly switch off the way an empty ANDROID_SDK_ROOT
# once switched off every Gradle step below.
#
# Section 9 also names `data/src/main/sqldelight/migrations/1.sqm`. It is deliberately in neither
# list here, and its absence from the tree is correct rather than outstanding. Ruling R15 in
# design/ARCHITECTURE.md forbids the file outright: in SQLDelight a file named `1.sqm` migrates
# schema version 1 to version 2, so committing one would declare a fresh install's schema to be
# version 2 with no version 1 to migrate from. The snapshot `schemas/1.db`, required below, is what
# the contract actually wanted, and where R15 and a contract part disagree R15 wins. Do not add the
# `.sqm` back to satisfy the contract's wording.
#
# Section 9's "every file in sections 3 through 5" also covers the eighteen module
# `build.gradle.kts` files. They are not enumerated: `settings.gradle.kts` includes every module, so
# a missing one fails Gradle configuration on the first task of step 2 with the path in the message.
# Listed here is the other kind of deliverable, the kind whose absence is silent, where a missing
# script or data file only turns a later step into a SKIP and the run still ends by reporting
# success.
REQUIRED_DELIVERABLE_FILES=(
    # Sections 3 and 4, the build's own roots. The convention plugins are covered by the directory
    # check below.
    settings.gradle.kts
    build.gradle.kts
    gradle.properties
    gradle/wrapper/gradle-wrapper.properties
    .gitignore
    .editorconfig
    build-logic/settings.gradle.kts
    build-logic/build.gradle.kts
    # The repository's three root documents, which hold what section 9's three documents held: the
    # README, CONTRIBUTING.md with the wording and vendoring rules, and the NOTICE for vendored code.
    README.md
    CONTRIBUTING.md
    NOTICE
    # The gate: row definitions, the phase 0 baseline, the comparator, and the comparator's own
    # tests, which step 1 runs by discovery and would silently discover nothing if they vanished.
    tools/gate/rows.json
    tools/gate/baseline.json
    tools/gate/release_gate.py
    tools/test_release_gate.py
    # Guards ruling R36: the launch check that keeps a permission dialog from being timed
    # and a zero from being published as the fastest cold start ever recorded.
    tools/test_bench_launch.py
    # The compatibility job and the field list it scores an index against.
    tools/compat/compat_job.py
    tools/compat/index_fields.json
    # Guards ruling R38: one URL with one meaning, resolved the way the app's client resolves it,
    # and the signing key read from the index rather than from a second fetch.
    tools/test_compat_index.py
    # The R8 reference check step 7 runs before the release instrumentation, so a keep that has
    # gone missing is a list rather than one crash per rebuild, and its own tests, which pin the
    # part of it that has already been wrong once: matching a member by name and descriptor while
    # ignoring whether R8 made it static.
    tools/r8/check_test_references.py
    tools/test_r8_check.py
    # The snapshot `:data:verifyDebugDatabaseMigration` diffs the .sq files against.
    data/src/main/sqldelight/schemas/1.db
    # The AVD harness, step 4.
    tools/avd/create-avds.sh
    tools/avd/boot.sh
    tools/avd/stop.sh
    tools/avd/devices.env
    # The benchmarks and the collector, step 8. `scene_test.sh` and `tap_test.sh` are left out on
    # purpose: they say so themselves, they are cross-checks and no gate row is measured with them.
    tools/bench/common.sh
    tools/bench/coldstart.sh
    tools/bench/webtoon_scroll.sh
    tools/bench/page_turn.sh
    tools/bench/meminfo_poll.sh
    tools/bench/perfetto_record.sh
    tools/bench/perfetto_metrics.py
    tools/bench/collect.py
    # The cold start pass with extensions: the ANR count and the tests of both of its readers.
    tools/bench/anr_count.py
    tools/test_anr_count.py
    tools/test_perfetto_metrics.py
    # Ruling R40's arithmetic, which is what page_turn_jank_pct is now computed from, and its tests.
    # Listed for the same reason as the gate's own tests above: step 1 discovers them, and a suite
    # that vanishes is discovered as nothing at all rather than reported as missing.
    tools/bench/framestats.py
    tools/test_framestats.py
    # The two reader benches drive the reader through these, and the reader cannot be opened any
    # other way: it is not an exported activity, so `am start` from the shell is refused
    # (carry-over 17). A missing steps file is a skipped row, which is why they are listed.
    tools/bench/steps/paged.txt
    tools/bench/steps/webtoon.txt
    # The wording scan and the local.properties reader this script sources.
    tools/scan-forbidden-words.sh
    tools/common/localprops.sh
    # What every instrumentation step reads its result back with, and its tests.
    tools/ci/ran_tests.py
    tools/test_ran_tests.py
    # The instrumentation suites steps 5 and 7 run. These are listed because a missing suite is the
    # one failure this gate cannot otherwise see: a class filter that matches nothing reports
    # tests="0" and exits 0, so a deleted suite reads exactly like a passing one.
    app/src/androidTest/kotlin/com/shinsaikou/AppGraphTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/backup/BackupImportTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/backup/BackupNotifierForegroundChannelTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/bench/BenchContentTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/bench/BenchExtensionsTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/bench/BenchFixtureSeeder.kt
    app/src/androidTest/kotlin/com/shinsaikou/compat/CompatReplayTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/extensions/ExtensionHostTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/gate/GateRowsTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/libraryupdate/LibraryUpdateNotifierForegroundChannelTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/reader/ReaderSmokeTest.kt
    app/src/androidTest/kotlin/com/shinsaikou/ui/MainActivitySmokeTest.kt
)
REQUIRED_DELIVERABLE_DIRS=(
    # Section 3's five convention plugins and their helpers. The directory is the check rather than
    # the twelve paths: a missing plugin file names itself in Gradle's configuration failure, while
    # an absent directory is the one that is worth a second of checking here.
    build-logic/src/main/kotlin
)
# What the phase 1 steps read, appended so the phase 0 list stays the contract's.
if phase1; then
    REQUIRED_DELIVERABLE_FILES+=(
        tools/ci/phase1.sh
        # The crash buffer check every bench device ends with (R58), and its tests.
        tools/ci/crash_buffer.py
        tools/test_crash_buffer.py
        # The six reader/probe images strict mode needs on every device that runs :reader (R49),
        # and the suite that reads them.
        tools/fixtures/make_probe_images.py
        tools/test_make_probe_images.py
        reader/src/androidTest/kotlin/com/shinsaikou/reader/ImageProbeDeviceTest.kt
        # The suite the tablet step exists for (R51). Deleted, it would leave the step passing on
        # whatever else the :app suite holds.
        app/src/androidTest/kotlin/com/shinsaikou/ui/TwoPaneLayoutTest.kt
        # The low RAM repro step 7b runs (R61), and what names its outcome when it left no fragment,
        # with its tests. A deleted class would read as a run of no tests there, never as the row.
        app/src/androidTest/kotlin/com/shinsaikou/bench/LowRamReproTest.kt
        tools/ci/lowram_outcome.py
        tools/test_lowram_outcome.py
        # The profile check step 7b makes before the repro (R36), and its tests.
        tools/ci/lowram_preflight.py
        tools/test_lowram_preflight.py
    )
fi
if phase2; then
    REQUIRED_DELIVERABLE_FILES+=(
        # The Local source on a device (unit D of phase2-start.md). Steps 5, 5b and 5c run it as
        # part of the :app suite; deleted, those steps would still pass on the rest of it.
        app/src/androidTest/kotlin/com/shinsaikou/sources/local/LocalSourceDeviceTest.kt
        # The reader and backup device tests step 5e runs (unit K of phase2-start.md), the host
        # script that kills and restores the reader, and its parser with the parser's tests.
        # Deleted, the connected runs would still pass on a filter that matched nothing else.
        app/src/androidTest/kotlin/com/shinsaikou/reader/ReaderModeSwitchTest.kt
        app/src/androidTest/kotlin/com/shinsaikou/reader/ReaderProcessDeathTest.kt
        app/src/androidTest/kotlin/com/shinsaikou/backup/BackupExportRoundTripTest.kt
        tools/ci/reader_process_death.sh
        tools/ci/reader_process_death.py
        tools/test_reader_process_death.py
        # The download force-stop row (unit I of phase2-start.md): the outcome file its two runs
        # share, what reads that file between them, the fixture server that holds its pages open,
        # and their tests. A missing server or reader would end step 5f; a missing test file would be
        # discovered as nothing at all in step 1.
        app/src/androidTest/kotlin/com/shinsaikou/util/ForceStopOutcome.kt
        tools/ci/force_stop_outcome.py
        tools/test_force_stop_outcome.py
        tools/bench/download_hold_server.py
        tools/test_download_hold_server.py
        # Phase 5 unit TG4: the browse fixture store step 5i serves, and its tests. A missing server
        # would end step 5i; a missing test file would be discovered as nothing at all in step 1.
        tools/bench/browse_fixture_server.py
        tools/test_browse_fixture_server.py
        # The download device tests of units G, H and J: the download notifications' channel, a
        # downloaded chapter opened from disk with its source not installed, and the adoption of an
        # upstream download folder. Steps 5, 5b and 5c run them inside the :app suite and require each
        # by name (DEVICE_CLASSES_APP), which fails the step when one ran nothing; listed here too,
        # so a deleted one is found in a second rather than after the builds.
        app/src/androidTest/kotlin/com/shinsaikou/downloads/DownloadNotifierForegroundChannelTest.kt
        app/src/androidTest/kotlin/com/shinsaikou/reader/DownloadedChapterReaderTest.kt
        app/src/androidTest/kotlin/com/shinsaikou/downloads/DownloadAdoptionDeviceTest.kt
        # Unit L of phase2-start.md: the phase 2 entry point, the check step 8d ends the run with and
        # its tests, and the resume and Next chapter check step 5e runs, with its parser and tests.
        tools/ci/phase2.sh
        tools/gate/bound_rows.py
        tools/test_bound_rows.py
        tools/ci/reader_resume_next.sh
        tools/ci/reader_resume_next.py
        tools/test_reader_resume_next.py
        # Unit K4 of phase2-start.md: unit K3's in-session resume check, which step 5e runs after
        # the one above and which reads the device's output through that one's parser, and its tests.
        tools/ci/reader_resume_in_session.sh
        tools/test_reader_resume_in_session.py
        # Unit F of phase2-start.md: the script with which step 8c runs unit A's frozen harness
        # against the build under test, what publishes each run's row, and its tests. A deleted script would
        # fail its own input check, and step 8c would skip rather than fail.
        tools/ci/library_update_rows.sh
        tools/ci/library_update_rows.py
        tools/test_library_update_rows.py
    )
fi
if phase3; then
    REQUIRED_DELIVERABLE_FILES+=(
        # Unit R2 of phase3-start.md: the two :backup tests step 3b's row stands on, the file and the
        # script that record upstream's backup fields, and what runs and reads the tests, with its tests.
        # A deleted test class would make step 3b refuse the row; here it is named before any build.
        backup/src/test/kotlin/com/shinsaikou/backup/UpstreamBackupFieldsTest.kt
        backup/src/test/kotlin/com/shinsaikou/backup/UpstreamLosslessExportTest.kt
        tools/gate/upstream_backup_fields.json
        tools/gate/upstream_backup_fields.py
        tools/test_upstream_backup_fields.py
        tools/ci/upstream_lossless_row.sh
        tools/ci/upstream_lossless_row.py
        tools/test_upstream_lossless_row.py
        # Unit R1 of phase3-start.md: the owner signing flag's code and tests, what step 2b runs and
        # reads for release_build_owner_signed and the versionCode check, the recorded code, the two
        # scripts the owner runs at M's switch, and their tests; and the in-place upgrade check's
        # device classes, which step 5 requires by name (DEVICE_CLASSES_APP) and which skip without
        # their stage argument.
        build-logic/src/main/kotlin/saikou/gradle/signing/OwnerSigning.kt
        build-logic/src/main/kotlin/saikou/gradle/signing/ReleaseSigners.kt
        build-logic/src/main/kotlin/saikou/gradle/signing/VerifyReleaseSigning.kt
        build-logic/src/test/kotlin/saikou/gradle/signing/OwnerSigningTest.kt
        build-logic/src/test/kotlin/saikou/gradle/signing/ReleaseSignersTest.kt
        build-logic/src/test/kotlin/saikou/gradle/signing/VerifyReleaseSigningTest.kt
        tools/ci/release_signing_row.sh
        tools/ci/release_signing_row.py
        tools/test_release_signing_row.py
        tools/gate/last-release.json
        tools/release/apk.py
        tools/release/release_version.py
        tools/release/install_release.py
        tools/release/pre_switch.py
        tools/test_release_tools.py
        app/src/androidTest/kotlin/com/shinsaikou/bench/InPlaceUpgrade.kt
        app/src/androidTest/kotlin/com/shinsaikou/bench/InPlaceUpgradeSeedTest.kt
        app/src/androidTest/kotlin/com/shinsaikou/bench/InPlaceUpgradeCheckTest.kt
        # Unit I of phase3-start.md: the two host scripts step 5g runs, unit D's sync fixture they
        # start, and their tests. A missing script would fail step 5g only when it ran, and a missing
        # test file would be discovered as nothing at all in step 1. The device classes they drive
        # are required by name in step 5 (DEVICE_CLASSES_APP), so they are not listed here.
        tools/bench/sync_pair.py
        tools/bench/sync_3000.py
        tools/bench/sync_fixture_server.py
        tools/test_sync_pair.py
        tools/test_sync_3000.py
        tools/test_sync_fixture_server.py
        # Unit H of phase3-start.md: the host script step 5h runs, and its test. Its device class is
        # required by name in step 5 (DEVICE_CLASSES_APP).
        tools/bench/sync_14_day.py
        tools/test_sync_14_day.py
        # Unit L of phase3-start.md: the phase 3 entry point and the tests of the phase check and
        # the four wrappers.
        tools/ci/phase3.sh
        tools/test_gate_phase.py
    )
fi

# Where every bench fragment lands, and one of the two places collect.py reads them from.
BENCH_OUT=tools/out/bench
# Where step 6's compat job writes its report, one timestamped directory per run, and where step 8d's
# collect reads it: the latest name under it, whichever run wrote it.
COMPAT_OUT=tools/out/compat
PREVIOUS_OUT=tools/out/previous
# collect.py scores every fragment under tools/out/bench, with no way to tell this run's from an
# earlier one's, so a device whose serial was absent this time, or a step that was skipped, would be
# scored from whatever an earlier run left there: a measurement not taken becoming a number (R36).
# The compat report is the same case: step 6 skips without a repository URL or a 4 GB serial, and
# collect would then publish an earlier run's report as this commit's two compat rows. The earlier
# run's fragments, its compat reports and its measured.json therefore move to tools/out/previous/,
# replacing the copy before them, before any step can write one.
move_previous_run() {
    [ -e "$BENCH_OUT" ] || [ -e "$COMPAT_OUT" ] || [ -e tools/out/measured.json ] || return 0
    rm -rf "$PREVIOUS_OUT" && mkdir -p "$PREVIOUS_OUT" || return 1
    if [ -e "$BENCH_OUT" ]; then mv "$BENCH_OUT" "$PREVIOUS_OUT/" || return 1; fi
    if [ -e "$COMPAT_OUT" ]; then mv "$COMPAT_OUT" "$PREVIOUS_OUT/" || return 1; fi
    if [ -e tools/out/measured.json ]; then mv tools/out/measured.json "$PREVIOUS_OUT/" || return 1; fi
}

# The reader/probe images ImageProbeDeviceTest reads, and tools/fixtures/make_probe_images.py draws,
# that the directory lacks, one name per line.
probe_fixtures_missing() { # probe_fixtures_missing <dir>
    local name
    for name in animated.gif static.webp animated.webp still.avif still.heic still.jxl; do
        [ -f "$1/$name" ] || echo "$name"
    done
}

step "0. deliverables"
run "the previous run's fragments moved to $PREVIOUS_OUT" move_previous_run
missing_deliverables=()
for deliverable in "${REQUIRED_DELIVERABLE_FILES[@]}"; do
    [ -f "$deliverable" ] || missing_deliverables+=("$deliverable")
done
for deliverable in "${REQUIRED_DELIVERABLE_DIRS[@]}"; do
    [ -d "$deliverable" ] || missing_deliverables+=("$deliverable/")
done
# Every missing path, not the first one. A checklist that stops at the first is a checklist somebody
# has to run five times to learn five things.
if [ "${#missing_deliverables[@]}" -gt 0 ]; then
    for deliverable in "${missing_deliverables[@]}"; do echo "  missing: $deliverable" >&2; done
    fail "deliverables (${#missing_deliverables[@]} missing, listed above)"
fi
ok "deliverables ($((${#REQUIRED_DELIVERABLE_FILES[@]} + ${#REQUIRED_DELIVERABLE_DIRS[@]})) paths present)"

if phase1; then
    step "0b. phase 1 preflight"
    # The cold start pass with extensions reads every trace through the `perfetto` package, and
    # without it perfetto_metrics.py exits 4 and the pass ends at its first run (R36). That is an
    # hour into the run, on the 2 GB AVD, after every other step has paid for its emulator time. The
    # import is the same one the pass makes, under the same interpreter, so it is checked here.
    run "the perfetto trace processor imports under ${SAIKOU_PYTHON:-python3}" \
        "${SAIKOU_PYTHON:-python3}" -c 'from perfetto.trace_processor import TraceProcessor'
    # Strict mode reaches :reader in phase 1 (R49), and ImageProbeDeviceTest then throws for each of
    # the six reader/probe images it cannot find. They are synthetic, so a machine that can draw
    # them does, rather than failing for a file it could have made. Only a missing directory is
    # filled: one that exists is the owner's, and a file missing from it fails here by name, before
    # the device time. A directory the four-image generator wrote lacks the HEIF and the JXL.
    if [ -n "$HOST_FIXTURES_DIR" ] && [ -d "$HOST_FIXTURES_DIR" ]; then
        if [ -d "$HOST_FIXTURES_DIR/reader/probe" ]; then
            missing_probe=$(probe_fixtures_missing "$HOST_FIXTURES_DIR/reader/probe")
            if [ -n "$missing_probe" ]; then
                fail "reader/probe under saikou.fixtures.dir lacks ${missing_probe//$'\n'/, }" \
                    "(tools/fixtures/make_probe_images.py draws all six)"
            fi
            ok "reader/probe fixtures present under saikou.fixtures.dir"
        else
            run "reader/probe fixtures generated under saikou.fixtures.dir" \
                python3 tools/fixtures/make_probe_images.py "$HOST_FIXTURES_DIR/reader/probe"
        fi
    else
        skip "reader/probe fixtures (saikou.fixtures.dir names no directory on this machine)"
    fi
fi

step "1. repository hygiene"
if [ -n "$(git status --porcelain)" ]; then fail "the working tree is not clean"; else ok "clean tree"; fi
if [ -z "${SAIKOU_FORBIDDEN_WORDS_FILE:-}" ]; then
    skip "scan-forbidden-words (SAIKOU_FORBIDDEN_WORDS_FILE is unset)"
else
    run "scan-forbidden-words" bash tools/scan-forbidden-words.sh
fi
run "gate row definitions and release gate tests" python3 -m unittest discover -s tools -p 'test_*.py'
gradle_step "checkModuleGraph" checkModuleGraph
gradle_step "checkVendoringMarkers" checkVendoringMarkers

step "2. build"
gradle_step "assembleDebug" assembleDebug
gradle_step "assembleRelease" assembleRelease ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"}
gradle_step ":bench:assembleBenchmark" :bench:assembleBenchmark
# Errors only; the warnings are mostly vendored upstream views and are left alone. Lint is the only
# check here that reads the merged manifest and the resource tree, which is where a missing
# permission or an API-level mismatch hides until a device finds it.
gradle_step "lint" lint

# The release's version, for the collect subject in step 8d. Phases 0 to 2 keep the literal they have
# always published; phase 3 reads the build's own in step 2b.
SUBJECT_VERSION=0.1.0
if phase3; then
    step "2b. the release's signer and versionCode (unit R1 of phase3-start.md)"
    # Q18: release_build_owner_signed is true when step 2 built the release with
    # -Psaikou.ownerSigning=true and verifyReleaseSigning finds the certificate of the alias keystore.properties names on every
    # signer apksigner reports for app-release.apk in API 26 to 27, 28 to 32 and 33 and up. The
    # comparison's own tests run first, in build-logic, forced to run and read back by class from
    # their XML, since the root build's testDebugUnitTest never reaches an included build.
    if [ -z "$GRADLE_TASKS" ]; then
        skip "release signing (no Gradle wrapper, or it could not list its tasks)"
    else
        rm -rf build-logic/build/test-results/test
        run "build-logic tests" ./gradlew :build-logic:test --rerun
        ran_tests build-logic/build/test-results/test --fail-on-failures \
            --require-class saikou.gradle.signing.OwnerSigningTest \
            --require-class saikou.gradle.signing.ReleaseSignersTest \
            --require-class saikou.gradle.signing.VerifyReleaseSigningTest ||
            fail "build-logic tests ran no test of a signing class, or failed one"
        run "release_build_owner_signed: the owner's key in every API range" \
            bash tools/ci/release_signing_row.sh "$BENCH_OUT/any"
    fi
    # Q19: a release whose versionCode is below tools/gate/last-release.json fails, since the owner's
    # devices would refuse it; an equal code passes. The collect subject takes the build's versionName.
    RELEASE_APK=app/build/outputs/apk/release/app-release.apk
    if [ ! -f "$RELEASE_APK" ]; then
        skip "versionCode against last-release.json (no $RELEASE_APK)"
    else
        run "versionCode against last-release.json" python3 tools/release/release_version.py --check "$RELEASE_APK"
        SUBJECT_VERSION=$(python3 tools/release/release_version.py --name "$RELEASE_APK") ||
            fail "the release's versionName"
        ok "the collect subject takes versionName $SUBJECT_VERSION from the build"
    fi
fi

step "3. unit tests"
gradle_step "testDebugUnitTest" testDebugUnitTest
# The two `:data` steps need files the data unit has not written yet; Gradle would fail on a task
# with no tests and no migration rather than report the truth, so the file is the prerequisite.
if [ -f data/src/test/kotlin/com/shinsaikou/data/IndexAuditTest.kt ]; then
    gradle_step "index audit" :data:testDebugUnitTest --tests 'com.shinsaikou.data.IndexAuditTest'
else
    skip "index audit (no data/src/test/kotlin/com/shinsaikou/data/IndexAuditTest.kt yet)"
fi
# Schema version 1 is the .sq files and there is never a migrations directory (ruling R15), so the
# prerequisite is the committed snapshot the verify task diffs against, not a migrations directory.
if [ -f data/src/main/sqldelight/schemas/1.db ]; then
    gradle_step "migration verification" :data:verifyDebugDatabaseMigration
else
    skip "migration verification (no data/src/main/sqldelight/schemas/1.db yet)"
fi

if phase3; then
    step "3b. the lossless export to upstream (unit R2 of phase3-start.md)"
    # Q16 and Q18: export_reimports_upstream_lossless is true when R2's two :backup tests ran in this run
    # and passed. Step 3 ran them already, but its result says nothing about which tests ran or whether
    # Gradle took the task from its cache, so upstream_lossless_row.sh runs both again with --rerun,
    # reads each class's JUnit XML back (present, stamped after it started Gradle, at least one test,
    # none failed, errored or skipped) and only then writes the host fragment into the bench output,
    # where step 8d's collect.py --bench reads it. A refused run writes nothing and fails the step.
    if [ -z "$GRADLE_TASKS" ]; then
        skip "export_reimports_upstream_lossless (no Gradle wrapper, or it could not list its tasks)"
    else
        run "export_reimports_upstream_lossless: R2's two tests ran fresh and passed" \
            bash tools/ci/upstream_lossless_row.sh "$BENCH_OUT/any"
    fi
    # The recorded fields against the reference tree they were generated from, when this machine has
    # it (SAIKOU_UPSTREAM_DIR, or saikou.upstream.dir in local.properties). Without it the file stands as
    # committed, and the test above still holds Saikou's models to it.
    UPSTREAM_DIR=$(localprop saikou.upstream.dir SAIKOU_UPSTREAM_DIR || true)
    if [ -n "$UPSTREAM_DIR" ] && [ -d "$UPSTREAM_DIR" ]; then
        run "upstream_backup_fields.json agrees with the reference tree" \
            python3 tools/gate/upstream_backup_fields.py --upstream "$UPSTREAM_DIR" --check
    else
        skip "upstream_backup_fields.json against the reference tree" \
            "(saikou.upstream.dir names no directory on this machine)"
    fi
fi

step "4. AVDs"
if [ -z "$ANDROID_SDK_ROOT" ]; then
    skip "create-avds (ANDROID_SDK_ROOT is unset)"
else
    run "create-avds" bash tools/avd/create-avds.sh
fi
# Booting three emulators takes minutes and evicts whatever else is on the machine, so the serials
# are taken from the environment unless SAIKOU_PHASE0_BOOT=1 asks for a boot.
SERIAL_2G=${SAIKOU_SERIAL_2G:-}
SERIAL_4G=${SAIKOU_SERIAL_4G:-}
SERIAL_TAB=${SAIKOU_SERIAL_TABLET:-}
SERIAL_API26=${SAIKOU_SERIAL_API26:-}
# The low RAM AVD serves phase 1's repro alone (step 7b), so phase 0 neither reads nor boots it.
SERIAL_LOWRAM=
phase1 && SERIAL_LOWRAM=${SAIKOU_SERIAL_LOWRAM:-}
# The gate's name for each device, as rows.json and baseline.json spell it. Declared once here
# because step 5 tells the instrumentation which device it is on and step 8 tells the bench
# scripts, and a run that named the same AVD two different ways would publish rows the comparator
# pairs with nothing.
DEVICE_ID_2G=pixel8a_2g
DEVICE_ID_4G=pixel9_4g
DEVICE_ID_TAB=tablet_4g
DEVICE_ID_LOWRAM=lowram_1_5g
AVD_LOWRAM=saikou_lowram_1_5g
if [ "${SAIKOU_PHASE0_BOOT:-0}" = "1" ]; then
    [ -n "$SERIAL_2G" ] || SERIAL_2G=$(bash tools/avd/boot.sh saikou_pixel8a_2g)
    [ -n "$SERIAL_4G" ] || SERIAL_4G=$(bash tools/avd/boot.sh saikou_pixel9_4g)
    [ -n "$SERIAL_TAB" ] || SERIAL_TAB=$(bash tools/avd/boot.sh saikou_tablet_4g)
    [ -n "$SERIAL_API26" ] || SERIAL_API26=$(bash tools/avd/boot.sh saikou_api26_2g)
    if phase1 && [ -z "$SERIAL_LOWRAM" ]; then SERIAL_LOWRAM=$(bash tools/avd/boot.sh "$AVD_LOWRAM"); fi
fi
for pair in "2 GB:$SERIAL_2G" "4 GB:$SERIAL_4G" "tablet:$SERIAL_TAB" "API 26:$SERIAL_API26" "low RAM:$SERIAL_LOWRAM"; do
    [ -n "${pair#*:}" ] && ok "${pair%%:*} AVD is up as ${pair#*:}"
done
# R122 carry-over 6: the Play Store is off on each of them until the run ends (play_store_off).
for serial in "$SERIAL_2G" "$SERIAL_4G" "$SERIAL_TAB" "$SERIAL_API26" "$SERIAL_LOWRAM"; do
    [ -z "$serial" ] || play_store_off "$serial"
done
if [ -z "$SERIAL_2G$SERIAL_4G$SERIAL_TAB$SERIAL_API26$SERIAL_LOWRAM" ]; then
    if phase1; then
        skip "AVD boot (set SAIKOU_SERIAL_2G, SAIKOU_SERIAL_4G, SAIKOU_SERIAL_TABLET, SAIKOU_SERIAL_API26, SAIKOU_SERIAL_LOWRAM, or SAIKOU_PHASE0_BOOT=1)"
    else
        skip "AVD boot (set SAIKOU_SERIAL_2G, SAIKOU_SERIAL_4G, SAIKOU_SERIAL_TABLET, SAIKOU_SERIAL_API26, or SAIKOU_PHASE0_BOOT=1)"
    fi
fi

emptied_4g=0
step "5. instrumentation on the 4 GB AVD"
# Every module that owns a device test, not only :app. ARCHITECTURE.md ruling at line 251 binds
# :core:archive here, and it is the one instrumentation suite that already exists; skipping the
# whole step because :app has no source set yet was how it went unrun.
if [ -z "$SERIAL_4G" ]; then
    skip "connectedDebugAndroidTest (no 4 GB serial)"
elif [ -z "$GRADLE_TASKS" ]; then
    skip "connectedDebugAndroidTest (no Gradle wrapper, or it could not list its tasks)"
else
    export ANDROID_SERIAL=$SERIAL_4G
    # Step 8 pulls every fragment in the device's gate directory with --instrumentation, so a row
    # whose test did not run this time would be scored from an earlier run's fragment. Emptied first,
    # in both phases, as step 0 empties the host's copy and step 7b the low RAM device's.
    run "no earlier fragment on $DEVICE_ID_4G" "$ADB" -s "$SERIAL_4G" shell rm -rf /data/local/tmp/saikou-gate
    # Step 8b keeps this device's fragments on the host only when they are this run's.
    emptied_4g=1
    push_owner_inputs "$SERIAL_4G"
    ran_instrumentation=0
    for module in core:archive reader app; do
        dir=$(echo "$module" | tr ':' '/')
        if [ ! -d "$dir/src/androidTest" ]; then
            skip ":$module:connectedDebugAndroidTest (no $dir/src/androidTest yet)"
            continue
        fi
        # GateRowsTest measures rows that rows.json binds to one device, so it refuses to guess
        # which one it is on and asks for saikou.device.id. That argument is deliberately not in
        # app/build.gradle.kts's list, because it describes the run rather than the owner, so the
        # harness is the only thing that can supply it. Without it the suite returned early and
        # measured nothing, and under saikou.strict it fails instead (R28). Step 5 is always the
        # 4 GB AVD, so the id is the same one the bench loop below pairs with that serial.
        fresh_results "$dir/build/outputs/androidTest-results/connected/debug"
        connected_run ":$module:connectedDebugAndroidTest" "$SERIAL_4G" \
            "$dir/build/outputs/androidTest-results/connected/debug" ":$module:connectedDebugAndroidTest" \
            "-Pandroid.testInstrumentationRunnerArguments.saikou.device.id=$DEVICE_ID_4G" \
            ${STRICT_ARGS[@]+"${STRICT_ARGS[@]}"} ${RUNNER_ARGS[@]+"${RUNNER_ARGS[@]}"}
        device_checks "$module" || fail ":$module:connectedDebugAndroidTest has no expected class list"
        ran_tests "$dir/build/outputs/androidTest-results/connected/debug" ${CHECK_ARGS[@]+"${CHECK_ARGS[@]}"} ||
            fail ":$module:connectedDebugAndroidTest $RAN_WHAT"
        ran_instrumentation=1
    done
    [ "$ran_instrumentation" = "1" ] || skip "instrumentation (no module has a device test source set)"
fi

step "5b. instrumentation on the API 26 AVD"
# The only step that proves minSdk 26 actually runs. Everything else in this checklist runs on
# API 36, where a call added after 26 resolves happily and a missing desugar is invisible, so a
# green gate said nothing about the oldest device the app claims to support.
#
# GateRowsTest is excluded rather than given a device id. rows.json binds every row to one of the
# three gate devices and API 26 is not one of them, so a fragment published from here would be
# orphaned and release_gate.py would report the real row missing (R29). This device answers "does
# the app run on minSdk", not "what does it measure".
#
# BenchContentTest is excluded for a different reason: it writes 26 MB of benchmark images and opens
# them, which is minutes on the smallest and oldest AVD in the set and proves nothing this device is
# here to prove. The tall strips it would exercise already have :reader's own device tests here.
#
# The two exclusions go in two different runner arguments, one class and one package, because a
# comma separated value silently keeps only the first entry. This step ran
# `notClass=com.shinsaikou.gate.GateRowsTest,com.shinsaikou.bench.BenchContentTest` and got 24 of the 29 tests
# rather than 21: GateRowsTest was excluded and BenchContentTest ran every time, on the slowest AVD
# in the set, for as long as the filter has existed. Escaping the comma is worse, because
# `notClass=A\,B` matches no class at all and the step runs 0 tests, which is the silent zero
# ran_tests exists to catch. `com.shinsaikou.gate` holds one test class, GateRowsTest (beside it,
# MigrateFixture.kt has no test). `com.shinsaikou.bench` has grown past BenchContentTest and its seeder:
# it holds BenchExtensionsTest (step 8's cold start with extensions), LowRamReproTest and
# LibraryCoverSeeder (step 7b's repro, on its own AVD), R1's InPlaceUpgradeSeedTest and
# InPlaceUpgradeCheckTest with InPlaceUpgrade, T1's DelayedTrackingColdStartTest, and SC4's
# SchemaUpgradeBench (the schema version 2 to 3 upgrade's seed and timing stages). Every one is a
# bench or a stage a host script drives through its own step. Without their inputs all but
# BenchContentTest return at once with a Skipped: line, which step 5c's --fail-on-skips fails on, or
# under saikou.strict fail (BenchExtensionsTest without the compat sample). None answers "does the app
# run on minSdk", so the package is still the right exclusion, and a class and a package say what this
# step means without a comma in either value. A third exclusion needs a third argument, not a list.
if [ -z "$SERIAL_API26" ]; then
    skip "API 26 instrumentation (set SAIKOU_SERIAL_API26, or SAIKOU_PHASE0_BOOT=1)"
elif [ -z "$GRADLE_TASKS" ]; then
    skip "API 26 instrumentation (no Gradle wrapper, or it could not list its tasks)"
else
    export ANDROID_SERIAL=$SERIAL_API26
    push_owner_inputs "$SERIAL_API26"
    for module in core:archive reader app; do
        dir=$(echo "$module" | tr ':' '/')
        [ -d "$dir/src/androidTest" ] || continue
        fresh_results "$dir/build/outputs/androidTest-results/connected/debug"
        connected_run "API 26 :$module:connectedDebugAndroidTest" "$SERIAL_API26" \
            "$dir/build/outputs/androidTest-results/connected/debug" ":$module:connectedDebugAndroidTest" \
            "-Pandroid.testInstrumentationRunnerArguments.notClass=com.shinsaikou.gate.GateRowsTest" \
            "-Pandroid.testInstrumentationRunnerArguments.notPackage=com.shinsaikou.bench" \
            ${STRICT_ARGS[@]+"${STRICT_ARGS[@]}"} ${RUNNER_ARGS[@]+"${RUNNER_ARGS[@]}"}
        device_checks "$module" com.shinsaikou.gate.GateRowsTest 'com.shinsaikou.bench.*' ||
            fail "API 26 :$module:connectedDebugAndroidTest has no expected class list"
        ran_tests "$dir/build/outputs/androidTest-results/connected/debug" ${CHECK_ARGS[@]+"${CHECK_ARGS[@]}"} ||
            fail "API 26 :$module:connectedDebugAndroidTest $RAN_WHAT"
    done
fi

if phase1; then
    step "5c. instrumentation on the tablet AVD"
    # Ruling R51. Every two pane assertion in the suite returns early on a device that composes one
    # pane, and until this step no instrumentation ran on the tablet at all, so all of them took
    # that return in every run. The whole :app suite runs here rather than a package, because
    # ReaderSmokeTest's two rotations were written for the landscape-native tablet too. API 36
    # ignores the reader's orientation request on a display this wide, so there they turn the
    # display with UiAutomation instead, and under saikou.expect.tablet that path is required (R62).
    # GateRowsTest and com.shinsaikou.bench stay out for step 5b's reasons, one runner argument each.
    #
    # saikou.expect.tablet=true turns each device-shape early return into a failure, because
    # otherwise a leftover tablet UI preference on the AVD would make this step a green run of skips.
    # The skips that remain are read back and must be the seventeen this device expects: the phone half
    # of the library pair, which is right to return here, extension detail, which returns when no
    # extension is installed and nothing in this suite can install one for it, the schema
    # upgrade check, which runs only on a device an older schema's build set up and names its figures,
    # the Google round trip, which runs only when saikou.google.base names a fixture, the two-device
    # sync suite, which runs only when step 5g's sync_pair.py names its step, unit G's sync
    # device check, whose six steps each log one line unless saikou.sync.check names it, and unit H's
    # Drive token steps, which run only when step 5h's sync_14_day.py names one, and MyAnimeList's
    # browser sign-in and push (phase 3 unit T2), which runs only when saikou.mal.base names its fake,
    # and the track sheet against that fake (phase 3 unit T3), and the comparison with MyAnimeList
    # against it (phase 4 unit TC), both under the same condition, the 40,000-entry scale rows
    # (phase 5 unit SC0), which run only when tools/ci/scale40k.sh passes saikou.scale.40k, and the
    # source filter screen's two (phase 5 unit BF): its toggles, which need the fixture extension
    # that only saikou.download.fixture.apk brings, and its empty screen, which returns when an
    # extension is installed, and the extension filter's (phase 5 unit BX), which returns before
    # the Extensions page opens when an extension store is configured, so that nothing is fetched,
    # the chapter row's downloading state (phase 5 unit ID3), which needs the same fixture
    # extension and download_hold_server.py on port 18794 (step 5f serves both and runs it in phase
    # 2, where it may not skip),
    # and phase 5 unit TG2's two: every method over the browse fixture store, which runs only when
    # saikou.browse.fixture says tools/bench/browse_fixture_server.py is up on the host (step 5i serves
    # it and runs them in phase 2, where none may skip; the one pattern covers wave 2 unit
    # browse-selection-device-test's BrowseSelectionDeviceTest too, whose lines say the same), and the
    # guard of each class that opens Browse or builds its Extensions view model, which returns
    # before either when a store other than that fixture's is configured (the owner's backup that
    # BackupImportTest restores can bring one), so that nothing is fetched.
    #
    # AGP uninstalls the application when the run ends, which takes the tablet's bench content with
    # it. Step 8 seeds it again on every run before any tablet bench, so nothing here depends on it.
    if [ -z "$SERIAL_TAB" ]; then
        skip "tablet instrumentation (set SAIKOU_SERIAL_TABLET, or SAIKOU_PHASE0_BOOT=1)"
    elif [ -z "$GRADLE_TASKS" ]; then
        skip "tablet instrumentation (no Gradle wrapper, or it could not list its tasks)"
    else
        export ANDROID_SERIAL=$SERIAL_TAB
        push_owner_inputs "$SERIAL_TAB"
        fresh_results app/build/outputs/androidTest-results/connected/debug
        connected_run "tablet :app:connectedDebugAndroidTest" "$SERIAL_TAB" \
            app/build/outputs/androidTest-results/connected/debug :app:connectedDebugAndroidTest \
            "-Pandroid.testInstrumentationRunnerArguments.notClass=com.shinsaikou.gate.GateRowsTest" \
            "-Pandroid.testInstrumentationRunnerArguments.notPackage=com.shinsaikou.bench" \
            "-Pandroid.testInstrumentationRunnerArguments.saikou.expect.tablet=true" \
            ${STRICT_ARGS[@]+"${STRICT_ARGS[@]}"} ${RUNNER_ARGS[@]+"${RUNNER_ARGS[@]}"}
        device_checks app com.shinsaikou.gate.GateRowsTest 'com.shinsaikou.bench.*' ||
            fail "tablet :app:connectedDebugAndroidTest has no expected class list"
        ran_tests app/build/outputs/androidTest-results/connected/debug --fail-on-skips \
            --except 'theLibraryPushesTheEntryOntoItsOwnScreenOnAPhone: device is composing tablet UI' \
            --except 'theExtensionDetailSplitsIntoTwoPanesOnATablet: no extension installed' \
            --except 'anUpgradedLibraryKeepsItsRowsAndLogsAsPhaseThreeDecides: no saikou.upgrade.counts' \
            --except 'theGraphsGoogleClientSignsInAgainstTheSyncFixtureTheRunnerNames: no saikou.google.base' \
            --except 'theBrowserSignInFromTheTrackingRowThenAPushReachTheFake: no saikou.mal.base' \
            --except 'theSheetBindsEditsAndRemovesAgainstTheFake: no saikou.mal.base' \
            --except 'theComparisonShowsTheWebsitesEditsAndAppliesTheOwnersChoices: no saikou.mal.base' \
            --except 'Skipped: no sync pair step named' \
            --except 'Skipped: no drive token step named' \
            --except 'Skipped: saikou\.sync\.check is not [$a-z-]+' \
            --except 'Skipped: the[A-Za-z]+IsTimed: no saikou\.scale\.40k argument' \
            --except 'aLanguageSwitchedOffAndASourceUncheckedLeaveTheSourcesPage: no saikou\.download\.fixture\.apk argument' \
            --except 'withNoExtensionInstalledTheFilterScreenSaysNoSourceIsInstalled: an extension is installed' \
            --except 'theExtensionsPagesFilterOpensTheExtensionFilterWithItsEmptyScreen: an extension store is configured' \
            --except 'aDownloadingChapterDrawsItsProgressAndItsTapsAreTheQueuedOnes: no saikou\.download\.fixture\.apk argument' \
            --except '[A-Za-z]+: no saikou\.browse\.fixture argument' \
            --except '[A-Za-z]+: an extension store other than the browse fixture store is configured' \
            ${CHECK_ARGS[@]+"${CHECK_ARGS[@]}"} ||
            fail "tablet :app:connectedDebugAndroidTest $RAN_WHAT, or skipped assertions it exists to run"
        if grep -rqs 'theExtensionDetailSplitsIntoTwoPanesOnATablet: no extension installed' \
            app/build/outputs/androidTest-results/connected/debug; then
            echo "    extension detail's two pane layout is not covered: no extension was installed when it ran"
        fi
    fi
fi

if phase2; then
    step "5d. the library update cancel row and the download queue add row on the 4 GB AVD"
    # Ruling R47. GateRowsTest#theLibraryUpdateCancelRowIsMeasured starts the library update pass
    # through the application's graph over one Local source entry whose chapter ComicInfo.xml is a
    # FIFO, so the fetch blocks a real thread in read(); it cancels, asserts the time from the cancel
    # to the pass returning against rows.json's 2000 ms, and only then publishes
    # library_update_cancel_ms, which step 8 collects from this device with the rest of its
    # fragments. Step 5 runs the same method in every phase, and without saikou.gate.phase it returns
    # early with a Skipped: line, so phases 0 and 1 keep the verdict they had. Here the phase is
    # passed and that line fails the step (R51): a green run of a skip is not the row.
    #
    # GateRowsTest#theDownloadQueueAddRowIsMeasured (unit I of phase2-start.md) seeds 1,000
    # downloaded chapters through the manifest repository, times the chapter action's
    # DownloadQueue.enqueue over the entry's whole chapter list against rows.json's 1000 ms, and
    # publishes download_queue_add_ms_at_1000 on the same terms. Each method is its own connected
    # run: one filter naming two methods is the comma separated list step 5b warns about. Each run
    # starts from an empty results directory and is read back for GateRowsTest by name, so the
    # second cannot pass on the first's XML.
    if [ -z "$SERIAL_4G" ]; then
        skip "library update cancel row and download queue add row (no 4 GB serial)"
    elif [ -z "$GRADLE_TASKS" ]; then
        skip "library update cancel row and download queue add row (no Gradle wrapper, or it could not list its tasks)"
    else
        export ANDROID_SERIAL=$SERIAL_4G
        for gate_row in "library update cancel row:theLibraryUpdateCancelRowIsMeasured" \
            "download queue add row:theDownloadQueueAddRowIsMeasured"; do
            fresh_results app/build/outputs/androidTest-results/connected/debug
            connected_run "${gate_row%%:*} on $DEVICE_ID_4G" "$SERIAL_4G" \
                app/build/outputs/androidTest-results/connected/debug :app:connectedDebugAndroidTest \
                "-Pandroid.testInstrumentationRunnerArguments.class=com.shinsaikou.gate.GateRowsTest#${gate_row#*:}" \
                "-Pandroid.testInstrumentationRunnerArguments.saikou.device.id=$DEVICE_ID_4G" \
                "-Pandroid.testInstrumentationRunnerArguments.saikou.gate.phase=$PHASE" \
                ${STRICT_ARGS[@]+"${STRICT_ARGS[@]}"}
            ran_tests app/build/outputs/androidTest-results/connected/debug --fail-on-skips \
                --require-class com.shinsaikou.gate.GateRowsTest --fail-on-failures ||
                fail "${gate_row%%:*} on $DEVICE_ID_4G ran no test, failed it, or skipped the measurement"
        done
    fi

    step "5e. the reader and backup device tests, and the reader's host checks, on the API 26 AVD"
    # Contract 07 part 2 section 9.2 and unit K of phase2-start.md. ReaderModeSwitchTest switches the
    # reading mode through the reader's own settings sheet and asserts one viewer rebuild on the same
    # page; BackupExportRoundTripTest exports through the application's graph and restores the file
    # into a database cleared of its seed. Steps 5, 5b and 5c run both inside the whole suite; here
    # each runs on its own, one class per connected run because a comma separated class list is not
    # one filter per class (step 5b), and --require-class reads the class's own count out of the XML,
    # so a filter that matched another class or none fails rather than passing on the total. Neither
    # test gates on saikou.gate.phase or has an early return, so the phase is not passed and any
    # Skipped: line fails the step.
    #
    # ReaderProcessDeathTest cannot run as an instrumentation: an attached instrumentation keeps the
    # process from being killed in the background, and the end of every run force-stops the package
    # and drops the task. So tools/ci/reader_process_death.sh drives it from the host against the
    # installed debug build: the test class only seeds the entry, and the script opens the reader
    # through the entry's screen, kills the backgrounded process with am kill, brings the task back
    # and reads the chapter and page off the screen. It exits 1 on a wrong restore and 3 when the
    # check could not be made, and both fail the step.
    #
    # tools/ci/reader_resume_next.sh (unit K2's device check, ruling R74, moved here by unit L) runs
    # next on the same install and the same kind of seeded entry: a partly read chapter opened again
    # with no page named resumes where it was left, and the menu's Next chapter reaches a chapter
    # already preloaded. Same exit codes, and both fail the step.
    #
    # tools/ci/reader_resume_in_session.sh (unit K3's device check, ruling R75, moved here by unit K4)
    # runs last, on the same install and a newly seeded entry: an unread chapter left on page 3 and
    # reached inside a session by the menu's Next chapter opens on page 3. Same exit codes, and both
    # fail the step.
    #
    # The API 26 AVD: the oldest platform the reader supports, and a device no gate row is bound to,
    # so nothing here publishes a fragment or competes with a measured run. The low RAM AVD passes
    # the same three (unit K's report) and is left to step 7b alone, by unit L's decision: a
    # connected run there uninstalls the application when it ends, which takes the library of a
    # thousand covers 7b's release build keeps seeded with it, and nothing these checks assert
    # depends on the memory class, which is what that AVD is for (R61).
    if [ -z "$SERIAL_API26" ]; then
        skip "reader and backup device tests (set SAIKOU_SERIAL_API26, or SAIKOU_PHASE0_BOOT=1)"
    elif [ -z "$GRADLE_TASKS" ]; then
        skip "reader and backup device tests (no Gradle wrapper, or it could not list its tasks)"
    else
        export ANDROID_SERIAL=$SERIAL_API26
        for test_class in com.shinsaikou.reader.ReaderModeSwitchTest com.shinsaikou.backup.BackupExportRoundTripTest; do
            fresh_results app/build/outputs/androidTest-results/connected/debug
            connected_run "API 26 $test_class" "$SERIAL_API26" \
                app/build/outputs/androidTest-results/connected/debug :app:connectedDebugAndroidTest \
                "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
                ${STRICT_ARGS[@]+"${STRICT_ARGS[@]}"}
            ran_tests app/build/outputs/androidTest-results/connected/debug --fail-on-skips \
                --require-class "$test_class" --fail-on-failures ||
                fail "API 26 $test_class ran no test of its own, failed one, or skipped an assertion"
        done
        # The connected runs uninstall the application when they end, and the script needs both
        # packages: the application it drives and the test package that seeds the entry.
        run "API 26 install for the reader restore check" ./gradlew :app:installDebug :app:installDebugAndroidTest
        run "API 26 reader process death restore" env SAIKOU_SERIAL="$SERIAL_API26" SAIKOU_DEVICE_ID=api26_2g \
            SAIKOU_PACKAGE="${SAIKOU_PACKAGE:-com.shinsaikou}" bash tools/ci/reader_process_death.sh
        run "API 26 reader resume and Next chapter" env SAIKOU_SERIAL="$SERIAL_API26" SAIKOU_DEVICE_ID=api26_2g \
            SAIKOU_PACKAGE="${SAIKOU_PACKAGE:-com.shinsaikou}" bash tools/ci/reader_resume_next.sh
        run "API 26 reader resume inside a session" env SAIKOU_SERIAL="$SERIAL_API26" SAIKOU_DEVICE_ID=api26_2g \
            SAIKOU_PACKAGE="${SAIKOU_PACKAGE:-com.shinsaikou}" bash tools/ci/reader_resume_in_session.sh
    fi

    step "5f. the download queue force-stop row on the 4 GB AVD"
    # Unit I of phase2-start.md, decision Q8 (a)(i). download_queue_survives_force_stop is two
    # GateRowsTest methods with force_stop_between running `am force-stop` between them. Part one
    # installs unit A's fixture extension through the application's private install, queues two
    # chapters of an entry from each of three of its sources through the chapter action's
    # DownloadQueue.enqueue, waits until the first chapter has its first page on disk and the rest
    # held open by the fixture server, and writes the queue to the outcome file. Part two, in the
    # process the next instrumentation starts, compares the queue it reads with that file, releases
    # the hold, and waits for WorkManager's own resume to download every chapter into the manifest,
    # enqueueing nothing itself; only then does it publish true, which step 8 collects. Then the
    # chapter row's download control is run while a chapter downloads, on a server of its own (phase 5
    # unit ID3; the reason is at that run).
    #
    # The installed debug build, not a connected task: AGP uninstalls the application after a
    # connected run, and the queue is in its database. Both packages are removed here afterwards,
    # as a connected task would, because step 7 installs the release build over whatever is left.
    # The fixture APK is unit A's, copied into tools/out/update-fixture/ and never rebuilt (a
    # rebuild gives other bytes), and the server takes the port it is built with, 18794.
    if [ -z "$SERIAL_4G" ]; then
        skip "download queue force-stop row (no 4 GB serial)"
    elif [ -z "$GRADLE_TASKS" ]; then
        skip "download queue force-stop row (no Gradle wrapper, or it could not list its tasks)"
    elif [ ! -s "$DOWNLOAD_FIXTURE_APK" ]; then
        skip "download queue force-stop row (no $DOWNLOAD_FIXTURE_APK, unit A's fixture extension)"
    else
        export ANDROID_SERIAL=$SERIAL_4G SAIKOU_PACKAGE=${SAIKOU_PACKAGE:-com.shinsaikou}
        force_stop_out=$BENCH_OUT/$DEVICE_ID_4G/download-force-stop
        mkdir -p "$force_stop_out"
        run "debug install for the force-stop row on $DEVICE_ID_4G" ./gradlew :app:installDebug :app:installDebugAndroidTest
        run "fixture extension pushed to $DEVICE_ID_4G" \
            "$ADB" -s "$SERIAL_4G" push "$DOWNLOAD_FIXTURE_APK" "$DEVICE_DOWNLOAD_FIXTURE_APK"
        # The fixture extension only ever calls port 18794, so a unit's fixture device session on
        # this machine serves the same port; the step fails naming it rather than start a second
        # server beside one (R117 carry-over 4). Silent when the port is free.
        port_free 18794 ||
            fail "download queue force-stop row: port 18794 is in use, and the fixture server needs it"
        # fail() exits, and the server must not outlive the run on a port other runs share: on_exit,
        # the run's EXIT trap, stops it.
        run "download fixture server on port 18794" start_fixture_server "$force_stop_out" download_hold_server.py
        force_stop_passed=0
        force_stop_between "$SERIAL_4G" "$force_stop_out" "$DOWNLOAD_FORCE_STOP_OUTCOME" \
            com.shinsaikou.gate.GateRowsTest#theDownloadQueueForceStopPartOneLeavesDownloadsMidFlight \
            com.shinsaikou.gate.GateRowsTest#theDownloadQueueForceStopPartTwoFindsTheQueueAndResumesIt \
            -e saikou.device.id "$DEVICE_ID_4G" -e saikou.gate.phase "$PHASE" -e saikou.strict "$SAIKOU_STRICT" \
            -e saikou.download.fixture.apk "$DEVICE_DOWNLOAD_FIXTURE_APK" && force_stop_passed=1
        stop_fixture_server
        "$ADB" -s "$SERIAL_4G" uninstall "$SAIKOU_PACKAGE.test" >/dev/null 2>&1 || true
        "$ADB" -s "$SERIAL_4G" uninstall "$SAIKOU_PACKAGE" >/dev/null 2>&1 || true
        [ "$force_stop_passed" = 1 ] || fail "download queue force-stop row on $DEVICE_ID_4G"
        ok "download queue force-stop row on $DEVICE_ID_4G"
        run "download_queue_survives_force_stop published true on $DEVICE_ID_4G" \
            published_true "$SERIAL_4G" download_queue_survives_force_stop

        # The chapter row's download control while a chapter downloads (phase 5 unit ID3):
        # ChapterRowDeviceTest's one method that needs the fixture extension and a server holding
        # pages. Step 5c runs it with neither and expects its Skipped: line; here it must run. It
        # gets a server of its own, started fresh on the same port once the force-stop row's has
        # stopped: part two above released that one's hold for good, and running before part one
        # would leave held requests that part one's held count would read as its own. One connected
        # run, read back for its class with --fail-on-skips and no expected skip, as step 5i reads
        # its runs; the server stops after it, or in the run's EXIT trap on a failure, and the port
        # is checked free again.
        download_control_out=$BENCH_OUT/$DEVICE_ID_4G/download-control
        download_control_filter=com.shinsaikou.ui.manga.ChapterRowDeviceTest#aDownloadingChapterDrawsItsProgressAndItsTapsAreTheQueuedOnes
        mkdir -p "$download_control_out"
        port_free 18794 ||
            fail "chapter row downloading state: port 18794 is in use, and the fixture server needs it"
        run "download fixture server on port 18794 for the chapter row's downloading state" \
            start_fixture_server "$download_control_out" download_hold_server.py
        fresh_results app/build/outputs/androidTest-results/connected/debug
        connected_run "$download_control_filter over the download fixture on $DEVICE_ID_4G" "$SERIAL_4G" \
            app/build/outputs/androidTest-results/connected/debug :app:connectedDebugAndroidTest \
            "-Pandroid.testInstrumentationRunnerArguments.class=$download_control_filter" \
            "-Pandroid.testInstrumentationRunnerArguments.saikou.download.fixture.apk=$DEVICE_DOWNLOAD_FIXTURE_APK" \
            ${STRICT_ARGS[@]+"${STRICT_ARGS[@]}"}
        ran_tests app/build/outputs/androidTest-results/connected/debug --fail-on-skips \
            --require-class "${download_control_filter%%#*}" --fail-on-failures ||
            fail "$download_control_filter over the download fixture on $DEVICE_ID_4G ran no test of its own, failed one, or skipped one"
        stop_fixture_server
        run "port 18794 free again after the download fixture servers" port_free 18794
    fi
fi

if phase3; then
    step "5g. the two-device sync rows and sync_3000_no_oom (unit I of phase3-start.md)"
    # Q11. tools/bench/sync_pair.py runs SyncPairTest's scenarios one step per am instrument on the
    # 4 GB AVD and the API 26 AVD against D's sync fixture on port 18795, and writes each scenario's
    # row as a host fragment in $BENCH_OUT/any, which step 8d's collect.py --bench reads:
    # sync_read_and_bookmark_survive, sync_rename_no_duplicate, sync_deleted_stays_deleted,
    # sync_fresh_device_categories, sync_restore_join_keeps_progress (phase 5 unit TG3: a device that
    # restores a backup and then joins) and phase 5 unit CT's sub-category trees, rows since unit TG3:
    # sync_subcategory_race_one_tree, sync_subcategory_orphan_top_level and
    # sync_subcategory_restore_keeps_tree, and phase 5 unit ED's sync_edit_details_race (a title and
    # description edited on one device against a source refresh on the other, then reset). Its last
    # scenario, killed_mid_apply, writes no row: it
    # stops the 4 GB AVD's process with run-as kill -9 inside a full export's pull and inside a
    # segment's transaction, and fails the run when the restarted process does not converge. A
    # failed scenario writes no row for itself or any after it, and fails this step.
    #
    # tools/bench/sync_3000.py runs SyncPairTest's sync-3000 step on the 2 GB AVD: a 3,000-entry
    # library pulled onto the same library with its own edits, with the OutOfMemoryError count and
    # the peak heap, and copies the fragment the step published into $BENCH_OUT/$DEVICE_ID_2G, so it
    # is never left on the device for step 8's pull. A run that fails keeps no fragment there, a
    # false one included, and fails this step.
    #
    # Both install the debug build and its test APK with adb (a connected task uninstalls the
    # application after each run, and a scenario is many runs) and remove both packages at the end,
    # whatever happened, because step 7 installs the release build. Exit codes 1, 2 and 3 all fail.
    if [ -z "$GRADLE_TASKS" ]; then
        skip "sync rows (no Gradle wrapper, or it could not list its tasks)"
    elif [ -z "$SERIAL_4G$SERIAL_API26$SERIAL_2G" ]; then
        skip "sync rows (set SAIKOU_SERIAL_4G and SAIKOU_SERIAL_API26, and SAIKOU_SERIAL_2G, or SAIKOU_PHASE0_BOOT=1)"
    else
        run "debug and test APKs for the sync rows" ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
        if [ -z "$SERIAL_4G" ] || [ -z "$SERIAL_API26" ]; then
            skip "the two-device sync rows (set SAIKOU_SERIAL_4G and SAIKOU_SERIAL_API26, or SAIKOU_PHASE0_BOOT=1)"
        else
            run "the two-device sync rows on $DEVICE_ID_4G and API 26, and killed_mid_apply" \
                python3 tools/bench/sync_pair.py --serial-4g "$SERIAL_4G" --serial-api26 "$SERIAL_API26" \
                --adb "$ADB" --out "$BENCH_OUT/any" --work tools/out/sync-pair
        fi
        if [ -z "$SERIAL_2G" ]; then
            skip "sync_3000_no_oom (set SAIKOU_SERIAL_2G, or SAIKOU_PHASE0_BOOT=1)"
        else
            run "sync_3000_no_oom published true on $DEVICE_ID_2G and copied" \
                python3 tools/bench/sync_3000.py --serial-2g "$SERIAL_2G" \
                --adb "$ADB" --out "$BENCH_OUT/$DEVICE_ID_2G" --work tools/out/sync-3000
        fi
    fi
fi

if phase3; then
    step "5h. sync_14_day_no_reconsent on the API 26 AVD (unit H of phase3-start.md)"
    # Q12. tools/bench/sync_14_day.py runs DriveTokenDeviceTest's steps on the API 26 AVD, which step
    # 5g already booted, against D's sync fixture on port 18795 standing in for Google's endpoints
    # (nothing contacts Google): a sign-in with a code from the fixture's authorization endpoint and
    # no browser, the process stopped with run-as kill -9, every access token expired, the next
    # refresh dropped and the one after it 503, one Drive call 401, then a sync in a cold process
    # with the app's clock 14 days ahead. It writes the row's host fragment in $BENCH_OUT/any, true
    # only when that sync succeeded with the refresh token in the secret store unchanged and the
    # fixture's log shows no authorization request after sign-in's, a refresh_token grant since the
    # modes, and the client secret on every refresh; any other run keeps no fragment and fails this
    # step. The API 26 AVD because the refresh token has to survive the process death in the
    # Keystore-backed secret store, whose oldest implementation the app runs on is API 26's; the
    # owner's real check (owner item 6) is on a current phone. Its --revoked run, which must write
    # false, is unit H's evidence and not a gate step. It installs and removes both packages itself.
    if [ -z "$GRADLE_TASKS" ]; then
        skip "sync_14_day_no_reconsent (no Gradle wrapper, or it could not list its tasks)"
    elif [ -z "$SERIAL_API26" ]; then
        skip "sync_14_day_no_reconsent (set SAIKOU_SERIAL_API26, or SAIKOU_PHASE0_BOOT=1)"
    else
        run "debug and test APKs for sync_14_day_no_reconsent" ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
        run "sync_14_day_no_reconsent written true on API 26" \
            python3 tools/bench/sync_14_day.py --serial-api26 "$SERIAL_API26" \
            --adb "$ADB" --out "$BENCH_OUT/any" --work tools/out/sync-14-day
    fi
fi

if phase2; then
    step "5i. the browse fixture methods on the 4 GB AVD (phase 5 unit TG4)"
    # Phase 5 unit TG2's device methods over the browse fixture store (FixtureStore in the androidTest
    # source set): Browse's source rows, the add-to-library prompt and a later page's failure
    # (FixtureSourcesDeviceTest), a listing's multi-select and a library update after a bulk add from
    # it (BrowseSelectionDeviceTest, wave 2 unit browse-selection-device-test, R148 and R147), the
    # extension stores screen (ExtensionStoresDeviceTest), the
    # extension filter over the store (ExtensionFilterDeviceTest's one such method), and the source
    # filter's toggles over the fixture extension (SourcesFilterDeviceTest's one such method, which
    # installs it from the path saikou.download.fixture.apk names, as step 5f pushes it). Steps 5, 5b
    # and 5c run them with no server, where each logs its Skipped: line and step 5c expects it. Here
    # tools/bench/browse_fixture_server.py serves unit A's repository from $BROWSE_FIXTURE_REPO on
    # the port the fixture extension is built with, 18794, checked free first as step 5f checks it,
    # and saikou.browse.fixture=true says so to the runner. One connected run per class, as step 5e
    # runs its classes (a comma separated filter keeps only its first entry), each read back for its
    # class by name with --fail-on-skips and no expected skip: a method that returned early is not the
    # method. The server serves the whole step, because FixtureStore reads its log from where each
    # method began, and stops when the step ends or, on a failure, in the run's EXIT trap (fail()
    # exits); the port is checked free again after it. Each connected run uninstalls the application
    # when it ends, so every class starts on a fresh install with no store configured.
    if [ -z "$SERIAL_4G" ]; then
        skip "browse fixture methods (no 4 GB serial)"
    elif [ -z "$GRADLE_TASKS" ]; then
        skip "browse fixture methods (no Gradle wrapper, or it could not list its tasks)"
    elif [ ! -s "$DOWNLOAD_FIXTURE_APK" ] || [ ! -s "$BROWSE_FIXTURE_REPO/signer.sha256" ]; then
        skip "browse fixture methods (no $DOWNLOAD_FIXTURE_APK or $BROWSE_FIXTURE_REPO/signer.sha256, unit A's fixture extension)"
    else
        export ANDROID_SERIAL=$SERIAL_4G
        browse_out=$BENCH_OUT/$DEVICE_ID_4G/browse-fixture
        mkdir -p "$browse_out"
        run "fixture extension pushed to $DEVICE_ID_4G for the browse fixture methods" \
            "$ADB" -s "$SERIAL_4G" push "$DOWNLOAD_FIXTURE_APK" "$DEVICE_DOWNLOAD_FIXTURE_APK"
        port_free 18794 ||
            fail "browse fixture methods: port 18794 is in use, and the fixture server needs it"
        run "browse fixture server on port 18794" \
            start_fixture_server "$browse_out" browse_fixture_server.py --repo-dir "$BROWSE_FIXTURE_REPO"
        for test_filter in com.shinsaikou.ui.browse.FixtureSourcesDeviceTest \
            com.shinsaikou.ui.browse.BrowseSelectionDeviceTest \
            com.shinsaikou.ui.browse.ExtensionStoresDeviceTest \
            com.shinsaikou.ui.browse.ExtensionFilterDeviceTest#overTheFixtureStoreTheFilterListsEnglishAndItsSwitchHidesAndShowsTheExtension \
            com.shinsaikou.ui.browse.SourcesFilterDeviceTest#aLanguageSwitchedOffAndASourceUncheckedLeaveTheSourcesPage; do
            fresh_results app/build/outputs/androidTest-results/connected/debug
            connected_run "$test_filter over the browse fixture on $DEVICE_ID_4G" "$SERIAL_4G" \
                app/build/outputs/androidTest-results/connected/debug :app:connectedDebugAndroidTest \
                "-Pandroid.testInstrumentationRunnerArguments.class=$test_filter" \
                "-Pandroid.testInstrumentationRunnerArguments.saikou.browse.fixture=true" \
                "-Pandroid.testInstrumentationRunnerArguments.saikou.download.fixture.apk=$DEVICE_DOWNLOAD_FIXTURE_APK" \
                ${STRICT_ARGS[@]+"${STRICT_ARGS[@]}"}
            ran_tests app/build/outputs/androidTest-results/connected/debug --fail-on-skips \
                --require-class "${test_filter%%#*}" --fail-on-failures ||
                fail "$test_filter over the browse fixture on $DEVICE_ID_4G ran no test of its own, failed one, or skipped one"
        done
        stop_fixture_server
        run "port 18794 free again after the browse fixture server" port_free 18794
    fi
fi

step "6. compatibility job"
if [ -z "$SAIKOU_REPO_URL" ]; then
    skip "compat job (SAIKOU_REPO_URL is unset and local.properties has no saikou.repo.url)"
elif [ -z "$SERIAL_4G" ]; then
    skip "compat job (no 4 GB serial for --replay)"
else
    export SAIKOU_SERIAL=$SERIAL_4G
    run "compat job" python3 tools/compat/compat_job.py --full --replay
fi

step "7. release install and the reference lib-1.6 run"
APK=app/build/outputs/apk/release/app-release.apk
TEST_APK=app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
if [ -z "$SERIAL_4G" ]; then
    skip "release install (no 4 GB serial)"
elif [ ! -f "$APK" ]; then
    skip "release install (no $APK)"
else
    run "release install" "$ADB" -s "$SERIAL_4G" install -r "$APK"
    export ANDROID_SERIAL=$SERIAL_4G
    # Built here rather than left to the connected-test task below, because the reference check
    # that follows reads this APK and a stale one would answer about a build that is not the one
    # about to run. `run` rather than `gradle_step`: AGP registers :app:assembleReleaseAndroidTest
    # only when testBuildType names the release variant, and GRADLE_TASKS was probed without that
    # property, so has_task would turn this into a SKIP.
    run "release androidTest apk" ./gradlew :app:assembleReleaseAndroidTest -Psaikou.testBuildType=release \
        ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"}
    # Every member ExtensionHostTest reaches, resolved against the release APK before a device is
    # asked to run anything. AGP minifies the androidTest APK with the application's own mapping,
    # so a member R8 removed because nothing shipped calls it is missing for the test as well, and
    # that failure lands at run time as NoSuchMethodError or NoSuchFieldError, one member per
    # rebuild, with no list of what else is gone. The checker exits non-zero with the whole list,
    # which is a keep block's worth of work in one run rather than twenty minutes of emulator time
    # per member. The rules it found are in app/proguard-rules.pro; regenerate them with --keeps.
    run "R8 keeps for the release instrumentation" python3 tools/r8/check_test_references.py \
        "$APK" "$TEST_APK" --test-class com.shinsaikou.extensions.ExtensionHostTest
    # AGP registers connected-test tasks only for the variant named by testBuildType, which
    # defaults to debug, so :app:connectedReleaseAndroidTest does not exist unless the build is
    # told to produce it. -Psaikou.testBuildType is that switch, and it stays off for step 5.
    # This run is the one that catches R8 stripping something the extension loader reaches by
    # name, so it is worth the second variant.
    # A class filter that matches nothing is a silent pass (tests="0", exit 0), so the class named
    # here is a hard deliverable in step 0. That check is what keeps this run honest, which is why
    # there is no conditional here: if the file were gone the gate would already have failed.
    fresh_results app/build/outputs/androidTest-results/connected/release
    connected_run "release instrumentation" "$SERIAL_4G" \
        app/build/outputs/androidTest-results/connected/release :app:connectedReleaseAndroidTest \
        -Psaikou.testBuildType=release ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"} \
        -Pandroid.testInstrumentationRunnerArguments.class=com.shinsaikou.extensions.ExtensionHostTest
    release_checks=()
    release_what="ran no tests; the class filter matched nothing"
    if phase2; then
        release_checks=(--require-class com.shinsaikou.extensions.ExtensionHostTest --fail-on-failures)
        release_what="ran no ExtensionHostTest test, or failed one"
    fi
    ran_tests "app/build/outputs/androidTest-results/connected/release" ${release_checks[@]+"${release_checks[@]}"} ||
        fail "release instrumentation $release_what"
fi

ran_lowram=0
if phase1; then
    step "7b. the low RAM repro on the low RAM AVD"
    # Ruling R61. LowRamReproTest scrolls a library of a thousand distinct covers end to end five
    # times, then reads a webtoon chapter to its end, and publishes low_ram_repro_pass whether its
    # checks pass or not, so a FAIL reaches the release gate with its numbers. It runs against the
    # release build, as the benches do, installed with `adb install -r` rather than through a
    # connected-test task: AGP uninstalls the application after a connected run, which would throw
    # the seeded library away and make every run pay for seeding it again. About 350 s on a seeded
    # AVD.
    #
    # Before anything is installed, the booted AVD's MemTotal and memory class are held against
    # devices.env (R36): the row is a statement about a 1.5 GB device with its own VM heap, and an
    # AVD that create-avds.sh has not brought up to date since devices.env changed is not one.
    #
    # The verdict is the fragment's, not the runner's: `am instrument` reports a failure for a FAIL
    # verdict too. What a missing fragment means is read from the device's buffers, cleared before
    # the run and saved after it, by lowram_outcome.py: a death fails naming the crash or the kill,
    # a measurement that could not be taken fails quoting the test (R36), only a test that never
    # ran is MISSING, and a session the host lost (the shared adb server restarting kills the
    # shell's `am`, and the test with it) says so and asks for a rerun. Each ends the run. The
    # device's own gate directory is emptied first, so a fragment an earlier run left there cannot
    # stand in for this run's when the test never starts.
    if [ -z "$SERIAL_LOWRAM" ]; then
        skip "low RAM repro (set SAIKOU_SERIAL_LOWRAM, or SAIKOU_PHASE0_BOOT=1)"
    elif [ ! -f "$APK" ]; then
        skip "low RAM repro (no $APK)"
    elif [ -z "$GRADLE_TASKS" ]; then
        skip "low RAM repro (no Gradle wrapper, or it could not list its tasks)"
    else
        export SAIKOU_PACKAGE=${SAIKOU_PACKAGE:-com.shinsaikou}
        lowram_out=$BENCH_OUT/$DEVICE_ID_LOWRAM
        mkdir -p "$lowram_out"
        run "$AVD_LOWRAM is the profile devices.env describes" \
            lowram_preflight "$SERIAL_LOWRAM" "$AVD_LOWRAM" "$lowram_out"
        # Step 7 builds this only when there is a 4 GB serial; Gradle finds it up to date otherwise.
        run "release androidTest apk" ./gradlew :app:assembleReleaseAndroidTest -Psaikou.testBuildType=release \
            ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"}
        run "release install on $DEVICE_ID_LOWRAM" "$ADB" -s "$SERIAL_LOWRAM" install -r "$APK"
        run "release test apk on $DEVICE_ID_LOWRAM" "$ADB" -s "$SERIAL_LOWRAM" install -r "$TEST_APK"
        run "no earlier fragment on $DEVICE_ID_LOWRAM" "$ADB" -s "$SERIAL_LOWRAM" shell rm -rf /data/local/tmp/saikou-gate
        run "main, crash and events buffers cleared on $DEVICE_ID_LOWRAM" \
            "$ADB" -s "$SERIAL_LOWRAM" logcat -b main -b crash -b events -c
        instrument_one "$SERIAL_LOWRAM" \
            com.shinsaikou.bench.LowRamReproTest#theLibraryAndAWebtoonChapterSurviveOnTheLowRamProfile \
            "$lowram_out/lowram-repro.txt" -e saikou.device.id "$DEVICE_ID_LOWRAM" -e saikou.strict "$SAIKOU_STRICT" ||
            echo "    am instrument reported no pass; the fragment, or what its absence means, decides the step"
        run "crash, events and main buffers saved on $DEVICE_ID_LOWRAM" save_buffers "$SERIAL_LOWRAM" "$lowram_out"
        # Pulled under a name collect.py does not read: its own --instrumentation pull is what scores it.
        rm -f "$lowram_out/fragment.pulled"
        "$ADB" -s "$SERIAL_LOWRAM" pull /data/local/tmp/saikou-gate/low_ram_repro_pass.json \
            "$lowram_out/fragment.pulled" >/dev/null 2>&1 || true
        run "low RAM repro on $DEVICE_ID_LOWRAM" python3 "$HERE/lowram_outcome.py" \
            --fragment "$lowram_out/fragment.pulled" --instrument-log "$lowram_out/lowram-repro.txt" \
            --crash-buffer "$lowram_out/crash-buffer.txt" --events-buffer "$lowram_out/events-buffer.txt" \
            --main-buffer "$lowram_out/main-buffer.txt" --package "$SAIKOU_PACKAGE" --device "$DEVICE_ID_LOWRAM"
        # Ruling R58 on this device too: a crash after the fragment was published still fails the run.
        run "no crash of $SAIKOU_PACKAGE on $DEVICE_ID_LOWRAM" crash_buffer_check "$SERIAL_LOWRAM" "$DEVICE_ID_LOWRAM"
        ran_lowram=1
    fi
fi

# Phase 2 moves the collect and the release gate into step 8d, after unit F's slot, so its step 8
# is the benchmarks alone.
if phase2; then step "8. benchmarks"; else step "8. benchmarks and the gate"; fi
# The benchmarks measure the build that ships, so the seeder and the reader holds have to run under
# it too. Only step 7 built this variant before, and only for the 4 GB device, which is also why
# nothing had ever installed Saikou on the 2 GB AVD or the tablet: they were benchmarked against
# whatever happened to be on them.
if [ -n "$SERIAL_2G$SERIAL_4G$SERIAL_TAB" ] && [ -n "$GRADLE_TASKS" ]; then
    run "release androidTest apk" ./gradlew :app:assembleReleaseAndroidTest -Psaikou.testBuildType=release \
        ${SIGNING_ARGS[@]+"${SIGNING_ARGS[@]}"}
fi
# The same reference check step 7 runs, for the one bench class that reaches into the extension
# subsystem. Its keeps are the second block in app/proguard-rules.pro.
if [ -n "$SERIAL_2G" ] && [ -f "$APK" ] && [ -f "$TEST_APK" ]; then
    run "R8 keeps for the bench extensions" python3 tools/r8/check_test_references.py "$APK" "$TEST_APK" \
        --test-class com.shinsaikou.bench.BenchExtensionsTest --test-class com.shinsaikou.util.Fixtures \
        --test-class com.shinsaikou.util.Args
fi
ran_bench=0
for pair in "$SERIAL_2G:$DEVICE_ID_2G" "$SERIAL_4G:$DEVICE_ID_4G" "$SERIAL_TAB:$DEVICE_ID_TAB"; do
    serial=${pair%%:*}
    device=${pair##*:}
    if [ -z "$serial" ]; then skip "bench on $device (no serial)"; continue; fi
    export SAIKOU_SERIAL=$serial SAIKOU_DEVICE_ID=$device SAIKOU_PACKAGE=${SAIKOU_PACKAGE:-com.shinsaikou}
    export SAIKOU_PERFETTO=1 SAIKOU_BENCH_OUT=$BENCH_OUT/$device
    # The bench scripts create this directory themselves, but the extension removal below writes
    # its log here before any of them has run, and on a checkout that never benched this device
    # the redirect failed and ended the run at "bench extensions removed".
    mkdir -p "$SAIKOU_BENCH_OUT"
    [ "$device" = tablet_4g ] && export SAIKOU_ORIENTATION=landscape || export SAIKOU_ORIENTATION=portrait
    if [ ! -f "$APK" ]; then
        skip "bench on $device (no $APK)"
        continue
    fi
    run "release install on $device" "$ADB" -s "$serial" install -r "$APK"
    # Ruling R58: the platform crash buffer is emptied before this device's benches and read after
    # them, so a crash anywhere in the device's window fails the run, not only one inside the 120
    # second meminfo hold that reader_crash_count watches.
    phase1 && run "crash buffer cleared on $device" "$ADB" -s "$serial" logcat -b crash -c
    # PLAN.md's cold start row has two halves, and they are measured apart on the 2 GB phone.
    # `cold_start_ms` is timed with no extension installed, which is how its upstream baseline was
    # taken; "no ANR with 76 extensions installed; zero PackageManager or OkHttpClient work on the
    # main thread" is measured by a second pass at the end of this device's iteration in phase 1,
    # with the first 76 packages of the anonymized sample installed through the application's own
    # private install. The removal runs here in every phase as well as after that pass, so an
    # earlier run that stopped between the two cannot leave extensions behind to be timed as a
    # plain cold start. Both steps
    # need the sample on the device and the bench test APK, so a machine without either skips the
    # pass by name rather than measuring without it.
    extension_args=()
    if [ "$device" = "$DEVICE_ID_2G" ]; then
        if [ -f "$TEST_APK" ] && [ -n "$HOST_FIXTURES_DIR" ] && [ -d "$HOST_FIXTURES_DIR/compat" ]; then
            push_owner_inputs "$serial"
            run "bench test apk on $device" "$ADB" -s "$serial" install -r "$TEST_APK"
            # Strict, so a fixture that did not arrive fails the step instead of returning early.
            extension_args=(-e saikou.fixtures.dir "$DEVICE_FIXTURES_DIR" -e saikou.strict true)
            run "bench extensions removed on $device" instrument_one "$serial" \
                com.shinsaikou.bench.BenchExtensionsTest#removeTheExtensions \
                "$BENCH_OUT/$device/extensions-remove.txt" "${extension_args[@]}"
        else
            skip "cold start with extensions on $device (needs $TEST_APK and compat/ under saikou.fixtures.dir)"
        fi
    fi
    # Cold start opens no chapter. It force-stops the package and times `am start -W` into the
    # launcher activity, so it reads no fixture at all. It used to sit behind a guard on an owner
    # supplied steps file and was skipped along with the reader benches, which left `cold_start_ms`
    # unmeasured on both phones for want of an input it never opens.
    run "coldstart on $device" bash tools/bench/coldstart.sh 10
    ran_bench=1
    if [ ! -f "$TEST_APK" ]; then
        skip "reader benches on $device (no $TEST_APK)"
        phase1 && run "no crash of $SAIKOU_PACKAGE on $device" crash_buffer_check "$serial" "$device"
        continue
    fi
    run "bench test apk on $device" "$ADB" -s "$serial" install -r "$TEST_APK"
    # Generated on the device rather than pushed: `adb push` leaves directories owned by the shell
    # user and the local source cannot write its cover into one (carry-over 6). Idempotent, so only
    # the first run on a device pays for it, and run here rather than inside a bench so that neither
    # its cost nor a failure in it lands inside a measured window.
    run "bench content on $device" instrument_one "$serial" \
        com.shinsaikou.bench.BenchContentTest#seedTheBenchmarkContent "$BENCH_OUT/$device/seed.txt"
    run "webtoon scroll on $device" bash tools/bench/webtoon_scroll.sh tools/bench/steps/webtoon.txt
    [ "$device" = pixel8a_2g ] && run "page turn on $device" bash tools/bench/page_turn.sh tools/bench/steps/paged.txt
    # meminfo drives nothing itself; it polls whatever the process is already doing. It has to stay
    # after the reader benches, because a window polled over an idle library screen would be a
    # smaller number that does not compare with the baseline, which was taken while reading. The
    # reader is still up because the hold that opened it outlasts this poll on purpose.
    run "meminfo on $device" bash tools/bench/meminfo_poll.sh 120 2
    # Ends the hold, which would otherwise keep an instrumentation run alive for the rest of its
    # timeout on a device nothing is measuring any more.
    "$ADB" -s "$serial" shell am force-stop "$SAIKOU_PACKAGE" >/dev/null 2>&1 || true
    # The seed, the second pass and the removal after it publish only phase 1 rows, so they are
    # phase 1 steps; the removal before the plain cold start above runs in every phase, since it is
    # what keeps cold_start_ms clean after an interrupted run.
    if phase1 && [ "${#extension_args[@]}" -gt 0 ]; then
        seed_log="$BENCH_OUT/$device/extensions-seed.txt"
        run "bench extensions seeded on $device" instrument_one "$serial" \
            com.shinsaikou.bench.BenchExtensionsTest#seedTheExtensions "$seed_log" \
            "${extension_args[@]}" -e saikou.bench.keepExtensions true
        # How many the extension manager loaded, as the seed reported it. Left blank when the line
        # is missing, which coldstart.sh refuses by name rather than measuring with an unknown set.
        loaded=$(sed -n 's/^INSTRUMENTATION_STATUS: saikou.bench.extensionsLoaded=\([0-9]*\)\r*$/\1/p' "$seed_log" | tail -1)
        run "coldstart with extensions on $device" bash tools/bench/coldstart.sh 10 --extensions "$loaded"
        run "bench extensions removed on $device" instrument_one "$serial" \
            com.shinsaikou.bench.BenchExtensionsTest#removeTheExtensions \
            "$BENCH_OUT/$device/extensions-remove.txt" "${extension_args[@]}"
    fi
    phase1 && run "no crash of $SAIKOU_PACKAGE on $device" crash_buffer_check "$serial" "$device"
done

if phase2; then
    step "8b. the 4 GB AVD's gate fragments, kept on the host before unit F's step"
    # Unit F's step loads an AVD snapshot on this device, and a snapshot brings back the whole disk
    # as it was when it was saved: /data/local/tmp/saikou-gate would hold that moment's fragments
    # rather than this run's, or none. So every fragment steps 5, 5d and 5f published here is pulled
    # into the bench output now, where collect.py's --bench reads it, and step 8d does not pull this
    # device again. Only fragments step 5 emptied the directory for are this run's; otherwise the
    # step skips, and step 8d names every row that leaves unmeasured.
    if [ -z "$SERIAL_4G" ]; then
        skip "gate fragments from $DEVICE_ID_4G (no 4 GB serial)"
    elif [ "$emptied_4g" != 1 ]; then
        skip "gate fragments from $DEVICE_ID_4G (step 5 did not empty them this run, so they may be an earlier run's)"
    else
        fragments_4g=$BENCH_OUT/$DEVICE_ID_4G/gate-fragments
        rm -rf "$fragments_4g"
        mkdir -p "$BENCH_OUT/$DEVICE_ID_4G"
        run "gate fragments from $DEVICE_ID_4G kept in $fragments_4g" \
            "$ADB" -s "$SERIAL_4G" pull /data/local/tmp/saikou-gate "$fragments_4g"
    fi

    step "8c. the two library update rows on the 4 GB AVD (unit F's slot)"
    # Unit F of phase2-start.md: library_update_2000x20_ms and library_update_throughput_ratio,
    # measured from the host with unit A's frozen harness (tools/bench/library_update.sh, ruling R66)
    # against the build under test on this device, and published as tools/bench/common.sh's
    # fragment() writes a bench row, into $BENCH_OUT/$DEVICE_ID_4G, where step 8d's collect reads it.
    # tools/ci/library_update_rows.sh installs $APK over each scenario's seeded snapshot with its data
    # kept, saves that as a gate snapshot, runs the harness from it, and publishes duration_ms of
    # 2000x20 and min_rate_ratio of 500x2; a run that ended (R36) publishes nothing and fails the step.
    # It sits here, after every device step and every benchmark, so the host is idle while the
    # harness times a window: nothing else in this script runs until it returns, step 5f has stopped
    # its fixture server on port 18794, and step 8b has already kept the 4 GB AVD's fragments, which
    # the harness's snapshot load would otherwise throw away. A missing input (the device, the APK,
    # or a scenario's seeded snapshot, which the script's --check names) skips the step by name, and
    # step 8d then ends the run on the two rows.
    update_rows_env=(SAIKOU_SERIAL="$SERIAL_4G" SAIKOU_DEVICE_ID="$DEVICE_ID_4G"
        SAIKOU_BENCH_OUT="$BENCH_OUT/$DEVICE_ID_4G" SAIKOU_APK="$APK")
    update_rows_skip="step 8d ends the run on library_update_2000x20_ms and library_update_throughput_ratio"
    if [ -z "$SERIAL_4G" ]; then
        skip "library update rows (no 4 GB serial; $update_rows_skip)"
    elif [ ! -f "$APK" ]; then
        skip "library update rows (no $APK; $update_rows_skip)"
    elif ! update_rows_missing=$(env "${update_rows_env[@]}" bash tools/ci/library_update_rows.sh --check 2>&1); then
        skip "library update rows (${update_rows_missing:-its input check failed}; $update_rows_skip)"
    else
        run "library update rows on $DEVICE_ID_4G" env "${update_rows_env[@]}" bash tools/ci/library_update_rows.sh
    fi

    step "8d. collect, every bound row, and the release gate"
fi
# Phase 2 always collects, whatever ran: its last check is that every row it binds has a value, and
# a run that measured nothing is the run with the most rows missing, not a run with nothing to say.
if [ "$ran_bench" = "1" ] || [ "$ran_lowram" = "1" ] || phase2; then
    collect_args=(--bench "$BENCH_OUT")
    # Both are optional to collect.py, but the gate binds rows that only they can fill, so pass
    # them whenever the earlier steps actually produced them. Step 0 moved every earlier run's
    # compat report aside, so a report here is step 6's in this run.
    [ -d "$COMPAT_OUT" ] && collect_args+=(--compat "$COMPAT_OUT")
    if phase2; then
        # Step 8b put the 4 GB AVD's fragments under $BENCH_OUT; a second pull here would read
        # whatever unit F's snapshot left on the device.
        mkdir -p "$BENCH_OUT"
    else
        [ -n "$SERIAL_4G" ] && collect_args+=(--instrumentation "$SERIAL_4G")
    fi
    [ "$ran_lowram" = "1" ] && collect_args+=(--instrumentation "$SERIAL_LOWRAM")
    run "collect" python3 tools/bench/collect.py "${collect_args[@]}" \
        --subject "com.shinsaikou:$SUBJECT_VERSION:$(git rev-parse --short HEAD)" --out tools/out/measured.json
    # Ruling R36: a row the phase binds with no value ends the run here, each one named with what
    # measures it, before the release gate scores the rest.
    phase2 && run "every row phase $PHASE binds has one usable value" \
        python3 tools/gate/bound_rows.py --measured tools/out/measured.json --phase "$PHASE"
    run "release gate" python3 tools/gate/release_gate.py --baseline tools/gate/baseline.json \
        --measured tools/out/measured.json --phase "$PHASE"
else
    skip "collect and the release gate (nothing was measured)"
fi

echo
echo "phase $PHASE: $passed step(s) passed, $skipped skipped, 0 failed"
