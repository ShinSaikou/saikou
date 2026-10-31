#!/usr/bin/env bash
# Saves the AVD snapshots library_update.sh loads (ruling R66), through each app's own screens.
# usage: library_update_seed.sh <upstream|upstream021|saikou> <base|2000x20|500x2>
#
#   base      uninstalls both apps and the fixture extension, installs the app, walks its first run,
#             adds the fixture repository, installs and trusts the extension and applies the settings
#             R66 fixes (steps/update-<app>-setup.txt), compiles the app, and saves
#             update-bench-<app>-base. One app per snapshot: the other app is never installed in it.
#   <scenario> loads update-bench-<app>-base, pushes the scenario's backup, restores it through the
#             app's own restore screen (steps/update-<app>-restore.txt), waits for the restore to
#             finish, removes the pushed file and saves update-bench-<app>-<scenario>.
#
# Both apps and their snapshots stay on the AVD they were seeded on; a snapshot is loaded before
# every run, so a run never changes what the next one starts from.
#
# Environment: SAIKOU_SERIAL and SAIKOU_DEVICE_ID as for every bench script. Each SAIKOU_UPSTREAM_*
# below falls back to its saikou.upstream.* key in local.properties (tools/common/localprops.sh).
#   SAIKOU_UPSTREAM_APK  the owner's published upstream v0.20.4 release APK (saikou.upstream.apk, default
#                     ~/.cache/saikou-baseline/apk/upstream-x86_64-v0.20.4.apk); refused unless its SHA-256
#                     is the one below, so no build of the fork is ever seeded. SAIKOU_UPSTREAM021_APK
#                     (saikou.upstream021.apk, default ~/.cache/saikou-baseline/apk/upstream-v0.21.0/
#                     upstream-x86_64-v0.21.0.apk) is v0.21.0's, for the app `upstream021`.
#   SAIKOU_UPSTREAM_PACKAGE  the upstream app's package, required for either upstream app. Its deep
#                     link scheme (SAIKOU_UPSTREAM_SCHEME) and its folder in shared storage
#                     (SAIKOU_UPSTREAM_STORAGE_DIR) are required for its base snapshot. A Saikou seed
#                     needs none of them, and uninstalls the upstream app first only when its package
#                     is set.
#   SAIKOU_APK         the Saikou APK (default app/build/outputs/apk/release/app-release.apk).
#   SAIKOU_UPDATE_BACKUP and SAIKOU_UPDATE_SNAPSHOT  restore another backup file and save it under
#                     another name, both or neither, and never under a scenario's name: a library
#                     that is not the scenario's must not be loadable as the scenario.
# Exit codes: 0 saved; 2 missing input; 3 adb, device or step error.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
FIXTURE_OUT=$ROOT/tools/out/update-fixture
UPSTREAM_SHA256=7ae8515adda66dded864d87bec8f129f9982b65d18022d2792cb754b230ab339
# Upstream v0.21.0's published x86_64 release (upstream-compare-20k), seeded as the app `upstream021`.
UPSTREAM021_SHA256=09d75d3144cd66d55f1ed57dc3e1a7b71ddfe787a940bb1575204bbec99cb371
USAGE="usage: library_update_seed.sh <upstream|upstream021|saikou> <base|2000x20|500x2>"
APK_CACHE=$HOME/.cache/saikou-baseline/apk
# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"

APP=${1:-}
WHAT=${2:-}
case "$APP" in
    upstream) APK=$(localprop saikou.upstream.apk SAIKOU_UPSTREAM_APK || echo "$APK_CACHE/upstream-x86_64-v0.20.4.apk") ;;
    upstream021) APK=$(localprop saikou.upstream021.apk SAIKOU_UPSTREAM021_APK ||
        echo "$APK_CACHE/upstream-v0.21.0/upstream-x86_64-v0.21.0.apk") ;;
    saikou) PACKAGE=com.shinsaikou; APK=${SAIKOU_APK:-$ROOT/app/build/outputs/apk/release/app-release.apk} ;;
    *) echo "$USAGE" >&2; exit 2 ;;
esac
case "$WHAT" in
    base|2000x20|500x2) ;;
    *) echo "$USAGE" >&2; exit 2 ;;
esac
BASE_SNAPSHOT=update-bench-$APP-base
if [ -n "${SAIKOU_UPDATE_BACKUP:-}${SAIKOU_UPDATE_SNAPSHOT:-}" ]; then
    [ "$WHAT" != base ] && [ -n "${SAIKOU_UPDATE_BACKUP:-}" ] && [ -n "${SAIKOU_UPDATE_SNAPSHOT:-}" ] || {
        echo "SAIKOU_UPDATE_BACKUP and SAIKOU_UPDATE_SNAPSHOT go together, and only with a scenario" >&2; exit 2; }
    case "$SAIKOU_UPDATE_SNAPSHOT" in
        update-bench-*) echo "$SAIKOU_UPDATE_SNAPSHOT is a bench snapshot name; save another library under another" >&2; exit 2 ;;
    esac
fi
SNAPSHOT=${SAIKOU_UPDATE_SNAPSHOT:-update-bench-$APP-$WHAT}

# The upstream app's own names are this machine's settings, and the steps files read the same ones
# (ui_steps.py, ${UPSTREAM_*}). A Saikou seed uses the package only to uninstall the app, if it is set.
if [ "$APP" = saikou ]; then
    UPSTREAM_PACKAGE=$(localprop saikou.upstream.package SAIKOU_UPSTREAM_PACKAGE || true)
else
    require_localprop saikou.upstream.package SAIKOU_UPSTREAM_PACKAGE
    UPSTREAM_PACKAGE=$SAIKOU_UPSTREAM_PACKAGE PACKAGE=$SAIKOU_UPSTREAM_PACKAGE
    if [ "$WHAT" = base ]; then
        require_localprop saikou.upstream.scheme SAIKOU_UPSTREAM_SCHEME
        require_localprop saikou.upstream.storage.dir SAIKOU_UPSTREAM_STORAGE_DIR
    fi
fi
require_env SAIKOU_SERIAL SAIKOU_DEVICE_ID
SDK=$(sdk_root) || { echo "missing: ANDROID_SDK_ROOT" >&2; exit 2; }
export ANDROID_SDK_ROOT=$SDK
BASE_ADB="$SDK/platform-tools/adb -s $SAIKOU_SERIAL"

snapshot_exists() {
    $BASE_ADB emu avd snapshot list 2>/dev/null | tr -d '\r' | awk '{print $2}' | grep -qx "$1"
}
snapshot() { # snapshot <save|load> <name>
    local answer
    answer=$($BASE_ADB emu avd snapshot "$1" "$2" 2>&1 | tr -d '\r')
    case "$answer" in
        *OK*) ;;
        *) echo "adb: snapshot $1 $2 on $SAIKOU_SERIAL answered: $answer" >&2; exit 3 ;;
    esac
}
wait_booted() {
    local waited=0
    $BASE_ADB wait-for-device || exit 3
    until [ "$($BASE_ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
        [ "$waited" -lt 120 ] || { echo "adb: $SAIKOU_SERIAL did not finish booting" >&2; exit 3; }
        sleep 1
        waited=$((waited + 1))
    done
}
run_ui_steps() { # run_ui_steps <steps file>
    python3 "$HERE/ui_steps.py" --serial "$SAIKOU_SERIAL" run "$1" || {
        echo "seed: $(basename "$1") stopped on $SAIKOU_SERIAL; nothing was saved" >&2; exit 3; }
}
# The app goes to the background and stops, so every run from the snapshot is a cold start.
park_app() {
    $BASE_ADB shell am force-stop "$PACKAGE" || exit 3
    $BASE_ADB shell input keyevent KEYCODE_HOME || exit 3
    sleep 2
}

# The fixture server, with the repository the setup installs from.
if [ ! -s "$FIXTURE_OUT/update-fixture.apk" ] || [ ! -s "$FIXTURE_OUT/signer.sha256" ]; then
    bash "$ROOT/tools/fixtures/update-extension/build.sh" "$FIXTURE_OUT" || exit 3
fi
mkdir -p "$FIXTURE_OUT"
SERVER_OUT=$FIXTURE_OUT/seed-$APP-$WHAT.$SAIKOU_DEVICE_ID
python3 "$HERE/update_fixture_server.py" --log "$SERVER_OUT.requests.jsonl" --repo-dir "$FIXTURE_OUT" \
    > "$SERVER_OUT.server.txt" 2>&1 &
SERVER_PID=$!
stop_server() {
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        kill -TERM "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}
trap stop_server EXIT
for _ in $(seq 1 50); do
    grep -q '^ready ' "$SERVER_OUT.server.txt" 2>/dev/null && break
    kill -0 "$SERVER_PID" 2>/dev/null || { echo "fixture server: $(cat "$SERVER_OUT.server.txt")" >&2; exit 3; }
    sleep 0.2
done
grep -q '^ready ' "$SERVER_OUT.server.txt" || { echo "fixture server never became ready" >&2; exit 3; }

if [ "$WHAT" = base ]; then
    [ -s "$APK" ] || { echo "missing: $APK" >&2; exit 2; }
    if [ "$APP" != saikou ]; then
        sum=$(sha256sum "$APK" | awk '{print $1}')
        want=$UPSTREAM_SHA256; [ "$APP" = upstream021 ] && want=$UPSTREAM021_SHA256
        [ "$sum" = "$want" ] || {
            echo "$APK has SHA-256 $sum, which is not the published upstream release for $APP" >&2; exit 2; }
    fi
    for pkg in ${UPSTREAM_PACKAGE:+"$UPSTREAM_PACKAGE"} com.shinsaikou app.fixture.libraryupdate; do
        $BASE_ADB uninstall "$pkg" >/dev/null 2>&1 || true
    done
    # Upstream's first run opens the folder picker here; the folder outlives an uninstall.
    if [ "$APP" != saikou ]; then
        $BASE_ADB shell mkdir -p "/sdcard/$SAIKOU_UPSTREAM_STORAGE_DIR" || exit 3
    fi
    $BASE_ADB install "$APK" >/dev/null || { echo "adb: installing $APK on $SAIKOU_SERIAL failed" >&2; exit 3; }
    SAIKOU_PACKAGE=$PACKAGE
    SAIKOU_BENCH_OUT=$FIXTURE_OUT
    # shellcheck source=common.sh
    source "$HERE/common.sh"
    grant_runtime_permissions
    launch
    run_ui_steps "$HERE/steps/update-$APP-setup.txt"
    park_app
    # The same compilation coldstart.sh gives both apps, so no run pays for JIT warm-up the other skips.
    $BASE_ADB shell cmd package compile -m speed -f "$PACKAGE" >/dev/null || exit 3
    snapshot save "$SNAPSHOT"
    echo "saved $SNAPSHOT on $SAIKOU_SERIAL"
    exit 0
fi

snapshot_exists "$BASE_SNAPSHOT" || {
    echo "missing: AVD snapshot $BASE_SNAPSHOT on $SAIKOU_SERIAL (run library_update_seed.sh $APP base)" >&2; exit 2; }
BACKUP=${SAIKOU_UPDATE_BACKUP:-$FIXTURE_OUT/update-$WHAT.tachibk}
if [ -z "${SAIKOU_UPDATE_BACKUP:-}" ]; then
    python3 "$HERE/update_backup.py" "$WHAT" "$BACKUP" >/dev/null || exit 3
fi
[ -s "$BACKUP" ] || { echo "missing: $BACKUP" >&2; exit 2; }
BACKUP_NAME=$(basename "$BACKUP")
export BACKUP_NAME

snapshot load "$BASE_SNAPSHOT"
wait_booted
SAIKOU_PACKAGE=$PACKAGE
SAIKOU_BENCH_OUT=$FIXTURE_OUT
# shellcheck source=common.sh
source "$HERE/common.sh"
$BASE_ADB push "$BACKUP" "/sdcard/Download/$BACKUP_NAME" >/dev/null || exit 3
launch
run_ui_steps "$HERE/steps/update-$APP-restore.txt"
$BASE_ADB shell rm -f "/sdcard/Download/$BACKUP_NAME" || exit 3
park_app
snapshot save "$SNAPSHOT"
echo "saved $SNAPSHOT on $SAIKOU_SERIAL"
