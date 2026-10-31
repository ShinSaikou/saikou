#!/usr/bin/env bash
# One library update run against the fixture sources, on upstream v0.20.4 or on Saikou (ruling R66).
# usage: library_update.sh <upstream|upstream021|saikou> <2000x20|500x2>
#
# Prints one JSON object on stdout: the server-side window (`duration_ms`, section 4 Q2's published
# value), the device-side window beside it, per-source requests per second, and request counts by
# kind. The same code measures both apps; only the package, the notification id and the path to the
# refresh differ, and those are below.
#
# The run, in order:
#   1. load the AVD snapshot `update-bench-<app>-<scenario>` (SAIKOU_UPDATE_SNAPSHOT overrides the
#      name), which library_update_seed.sh saved after the app restored the scenario's backup, so
#      every run starts from the same library and finds the same 2,000 or 500 new chapters;
#   2. check the locale the refresh locator reads is pinned to English;
#   3. start tools/bench/update_fixture_server.py, which logs every request on the host clock;
#   4. launch the app, walk to its Library screen and tap its own refresh (update_drive.py), then
#      wait for the update's progress notification to go;
#   5. stop the server and hand its log to update_log.py, whose guard ends the run (R36) when the
#      chapter-list requests do not cover the seeded entries exactly once, when any request failed,
#      when anything other than a chapter list was fetched, or when the two windows differ by more
#      than 10 percent.
#
# One run at a time per host: the fixture's port is a frozen parameter, compiled into the
# extension, and a second server cannot bind it (the run then ends with exit 3 before it starts).
# Environment: SAIKOU_SERIAL and SAIKOU_DEVICE_ID as for every bench script; SAIKOU_UPDATE_TIMEOUT_S,
# the longest a run may take before it ends (default 1800). The request log, the driver's JSON and
# the run's JSON are kept under SAIKOU_BENCH_OUT as update-<app>-<scenario>.<device>.*. An upstream
# run also needs the upstream app's package: SAIKOU_UPSTREAM_PACKAGE, else saikou.upstream.package in
# local.properties.
# Exit codes: 0 measured; 2 missing input; 3 adb or device error, or the run ended (R36): the JSON
# is still printed when there is one, with `status: ended`, its reasons and no duration.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"
APP=${1:-}
SCENARIO=${2:-}
case "$APP" in
    # Upstream v0.20.4's release: its library update notification is Notifications.ID_LIBRARY_PROGRESS.
    upstream) NOTIFICATION_ID=-101 ;;
    # Upstream v0.21.0's release (upstream-compare-20k): the same id.
    upstream021) NOTIFICATION_ID=-101 ;;
    # LibraryUpdateNotifier.ID_LIBRARY_PROGRESS.
    saikou) PACKAGE=com.shinsaikou; NOTIFICATION_ID=101 ;;
    *) echo "usage: library_update.sh <upstream|upstream021|saikou> <2000x20|500x2>" >&2; exit 2 ;;
esac
case "$SCENARIO" in
    2000x20|500x2) ;;
    *) echo "usage: library_update.sh <upstream|upstream021|saikou> <2000x20|500x2>" >&2; exit 2 ;;
esac
# Both upstream releases install under one package, which is this machine's setting, not the repository's.
if [ "$APP" != saikou ]; then
    require_localprop saikou.upstream.package SAIKOU_UPSTREAM_PACKAGE
    PACKAGE=$SAIKOU_UPSTREAM_PACKAGE
fi
SNAPSHOT=${SAIKOU_UPDATE_SNAPSHOT:-update-bench-$APP-$SCENARIO}
RUN_TIMEOUT=${SAIKOU_UPDATE_TIMEOUT_S:-1800}
STEPS="$HERE/steps/update-$APP-refresh.txt"

require_env SAIKOU_SERIAL SAIKOU_DEVICE_ID
SDK=$(sdk_root) || { echo "missing: ANDROID_SDK_ROOT" >&2; exit 2; }
export ANDROID_SDK_ROOT=$SDK
BASE_ADB="$SDK/platform-tools/adb -s $SAIKOU_SERIAL"

# 1. The snapshot, loaded before common.sh is sourced, because common.sh reads the package off the
# device and the package is whatever the snapshot holds.
if ! $BASE_ADB emu avd snapshot list 2>/dev/null | tr -d '\r' | awk '{print $2}' | grep -qx "$SNAPSHOT"; then
    echo "missing: AVD snapshot $SNAPSHOT on $SAIKOU_SERIAL (tools/bench/library_update_seed.sh saves it)" >&2
    exit 2
fi
loaded=$($BASE_ADB emu avd snapshot load "$SNAPSHOT" 2>&1 | tr -d '\r')
case "$loaded" in
    *OK*) ;;
    *) echo "adb: loading snapshot $SNAPSHOT on $SAIKOU_SERIAL answered: $loaded" >&2; exit 3 ;;
esac
$BASE_ADB wait-for-device || exit 3
waited=0
until [ "$($BASE_ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    [ "$waited" -lt 120 ] || { echo "adb: $SAIKOU_SERIAL did not come back from $SNAPSHOT" >&2; exit 3; }
    sleep 1
    waited=$((waited + 1))
done

# 2. The refresh is found by its English label, so a device in another locale would fail to find it
# or, worse, find something else.
locale=$($BASE_ADB shell getprop persist.sys.locale | tr -d '\r')
[ -n "$locale" ] || locale=$($BASE_ADB shell getprop ro.product.locale | tr -d '\r')
case "$locale" in
    en-US|en_US) ;;
    *) echo "adb: $SAIKOU_SERIAL runs in locale '${locale:-unknown}', and the refresh locators read en-US labels" >&2; exit 3 ;;
esac

SAIKOU_PACKAGE=$PACKAGE
# shellcheck source=common.sh
source "$HERE/common.sh"
version=$($ADB shell dumpsys package "$PACKAGE" | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1)

PREFIX="$SAIKOU_BENCH_OUT/update-$APP-$SCENARIO.$SAIKOU_DEVICE_ID"
LOG="$PREFIX.requests.jsonl"
DRIVE="$PREFIX.drive.json"
rm -f "$LOG" "$DRIVE"

# 3. The fixture server, with no repository: nothing in a run installs anything.
python3 "$HERE/update_fixture_server.py" --log "$LOG" > "$PREFIX.server.txt" 2>&1 &
SERVER_PID=$!
stop_server() {
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -TERM "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}
trap stop_server EXIT
for _ in $(seq 1 50); do
    grep -q '^ready ' "$PREFIX.server.txt" 2>/dev/null && break
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "fixture server: $(cat "$PREFIX.server.txt")" >&2; exit 3; }
    sleep 0.2
done
grep -q '^ready ' "$PREFIX.server.txt" || { echo "fixture server never became ready" >&2; exit 3; }

# The emulator's network can take a few seconds to answer after a snapshot load, and a source
# request lost then is an entry the guard would end the run for. So the device's own shell reaches
# the server first, five times running, before the app starts. The probes name no source.
reached=0
for _ in $(seq 1 30); do
    if $ADB shell "printf 'GET /probe HTTP/1.0\r\nHost: 10.0.2.2\r\n\r\n' | nc -w 2 10.0.2.2 18794" 2>/dev/null |
        grep -q '^ok'; then
        reached=$((reached + 1))
        [ "$reached" -lt 5 ] || break
    else
        reached=0
        sleep 1
    fi
done
[ "$reached" -ge 5 ] || { echo "adb: $SAIKOU_SERIAL could not reach the fixture server at 10.0.2.2:18794" >&2; exit 3; }

# 4. The app's own launch, then its own refresh.
launch
drive_status=0
python3 "$HERE/update_drive.py" --serial "$SAIKOU_SERIAL" --package "$PACKAGE" \
    --notification-id "$NOTIFICATION_ID" --steps "$STEPS" --out "$DRIVE" \
    --run-timeout "$RUN_TIMEOUT" || drive_status=$?
[ "$drive_status" -le 3 ] && [ -s "$DRIVE" ] || { echo "update_drive exited $drive_status and wrote nothing" >&2; exit 3; }

# 5. The app stops before the server does, so the device closes its own idle connections. Left
# open, the server's close would leave the emulator's network holding them half closed, on the same
# ports the next load of this snapshot connects from: on the dry runs that cost the next run a
# 30 second connect timeout on a source's first request, and the entry it was for.
$ADB shell am force-stop "$PACKAGE" || exit 3
sleep 2
# The server's log is complete only once it has stopped.
stop_server
trap - EXIT
status=0
python3 "$HERE/update_log.py" "$LOG" --scenario "$SCENARIO" --drive "$DRIVE" \
    --field "app=$APP" --field "package=$PACKAGE" --field "versionName=$version" \
    --field "device=$SAIKOU_DEVICE_ID" --field "serial=$SAIKOU_SERIAL" --field "snapshot=$SNAPSHOT" \
    --field "recordedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$PREFIX.json" || status=$?
cat "$PREFIX.json"
# update_log.py's 2 is a log it could not read, which is this run's failure, not a missing input.
if [ "$status" -eq 2 ]; then
    status=3
fi
exit "$status"
