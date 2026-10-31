#!/usr/bin/env bash
# One device session of unit upstream-compare-20k: each app in turn on one AVD, the same way.
# usage: compare20k.sh --out <dir> --env <targets.env> [--apps saikou,upstream-0.21.0,upstream-0.20.4]
#                      [--scenarios S2,S3,...] [--skip-restore] <backup file>...
#
# For each app, in the order given: every Saikou and upstream package left on the device is uninstalled
# and its external folders removed (an uninstall right after boot can leave them owned by the old
# app id, R136), the app is installed fresh from its APK (the upstream APKs are checked against their
# published SHA-256), compiled with `cmd package compile -m speed -f` as coldstart.sh compiles both
# subjects, granted notifications, set up through its first-run screens (the profile's [setup]),
# given the backup files (S1, restored one after another, in the order given), then measured
# (S2 to S9), and uninstalled before the next app, so no two apps share the device's memory.
# /data's use is read before the install and after the scenarios: the difference is the app's data,
# chiefly its database. Everything compare20k.py writes goes to <out>/<app>/.
#
# The device: SAIKOU_AVD (default saikou_pixel9_4g), booted by SAIKOU_AVD_BOOT (a command that prints
# the serial last; boot.sh's -no-snapshot boot, never -wipe-data) after MemAvailable reaches 8 GB and
# under the lock SAIKOU_AVD_LOCKS/<avd> (both required unless C20K_SERIAL names an emulator this
# caller already holds). Airplane mode is on for the whole session (no extension is installed and
# nothing may wait on a site), and is turned off again, the emulator stopped and the lock released
# on any exit.
#
# Inputs: SAIKOU_APK (default the worktree's release APK), SAIKOU_UPSTREAM021_APK and SAIKOU_UPSTREAM_APK
# (v0.21.0's and v0.20.4's; default under ~/.cache/saikou-baseline/apk), C20K_OWNER (the lock's owner
# line), C20K_KEEP (with C20K_SERIAL: leave the last app installed and the network off, to look at
# its screens after). The upstream app's package and its folder in shared storage are this
# machine's settings, SAIKOU_UPSTREAM_PACKAGE and SAIKOU_UPSTREAM_STORAGE_DIR, required when an upstream
# app is in --apps; every SAIKOU_UPSTREAM_* falls back to its saikou.upstream.* key in local.properties
# (tools/common/localprops.sh). The adb server is the isolated one (ANDROID_ADB_SERVER_PORT=5038,
# ADB_LOCAL_TRANSPORT_MAX_PORT=5731).
# Exit codes: 0 every app ran; 2 usage or a missing input; 3 a device step failed (the session
# goes on to the next app; the failures are listed at the end).
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
export ANDROID_ADB_SERVER_PORT=5038 ADB_LOCAL_TRANSPORT_MAX_PORT=5731
ADB_BIN="$ANDROID_SDK_ROOT/platform-tools/adb"
AVD=${SAIKOU_AVD:-saikou_pixel9_4g}
SAIKOU_APK=${SAIKOU_APK:-$REPO/app/build/outputs/apk/release/app-release.apk}
UPSTREAM_021_APK=$(localprop saikou.upstream021.apk SAIKOU_UPSTREAM021_APK ||
    echo "$HOME/.cache/saikou-baseline/apk/upstream-v0.21.0/upstream-x86_64-v0.21.0.apk")
UPSTREAM_0204_APK=$(localprop saikou.upstream.apk SAIKOU_UPSTREAM_APK ||
    echo "$HOME/.cache/saikou-baseline/apk/upstream-x86_64-v0.20.4.apk")
UPSTREAM_021_SHA=09d75d3144cd66d55f1ed57dc3e1a7b71ddfe787a940bb1575204bbec99cb371
UPSTREAM_0204_SHA=7ae8515adda66dded864d87bec8f129f9982b65d18022d2792cb754b230ab339

OUT= ENV_FILE= APPS=saikou,upstream-0.21.0,upstream-0.20.4 SCENARIOS= SKIP_RESTORE=
FILES=()
while [ $# -gt 0 ]; do
    case "$1" in
        --out) OUT=$2; shift 2 ;;
        --env) ENV_FILE=$2; shift 2 ;;
        --apps) APPS=$2; shift 2 ;;
        --scenarios) SCENARIOS=$2; shift 2 ;;
        --skip-restore) SKIP_RESTORE=1; shift ;;
        -*) echo "unknown option: $1" >&2; exit 2 ;;
        *) FILES+=("$1"); shift ;;
    esac
done
[ -n "$OUT" ] && [ -n "$ENV_FILE" ] || { echo "usage: compare20k.sh --out <dir> --env <targets.env> [--apps ...] <backup file>..." >&2; exit 2; }
[ -r "$ENV_FILE" ] || { echo "missing: $ENV_FILE" >&2; exit 2; }
[ ${#FILES[@]} -gt 0 ] || [ -n "$SKIP_RESTORE" ] || { echo "no backup file given" >&2; exit 2; }
for f in "${FILES[@]}"; do [ -r "$f" ] || { echo "missing: $f" >&2; exit 2; }; done
mkdir -p "$OUT"
STATUS="$OUT/session.log"
note() { echo "$(date -Iseconds) $*" | tee -a "$STATUS"; }

apk_of() {
    case "$1" in
        saikou) echo "$SAIKOU_APK" ;;
        upstream-0.21.0) echo "$UPSTREAM_021_APK" ;;
        upstream-0.20.4) echo "$UPSTREAM_0204_APK" ;;
        *) return 1 ;;
    esac
}
sha_of() {
    case "$1" in
        upstream-0.21.0) echo "$UPSTREAM_021_SHA" ;;
        upstream-0.20.4) echo "$UPSTREAM_0204_SHA" ;;
        *) echo "" ;;
    esac
}
IFS=, read -r -a APP_LIST <<< "$APPS"
# The upstream app's package and storage folder: required when it runs, and otherwise still cleaned
# away when they are set, so a session of Saikou alone starts from a device without it too. The
# upstream profiles name them as ${UPSTREAM_PACKAGE} and ${UPSTREAM_STORAGE_DIR} (ui_steps.py).
case ",$APPS," in
    *,upstream-*)
        require_localprop saikou.upstream.package SAIKOU_UPSTREAM_PACKAGE
        require_localprop saikou.upstream.storage.dir SAIKOU_UPSTREAM_STORAGE_DIR ;;
esac
UPSTREAM_PACKAGE=$(localprop saikou.upstream.package SAIKOU_UPSTREAM_PACKAGE || true)
UPSTREAM_STORAGE_DIR=$(localprop saikou.upstream.storage.dir SAIKOU_UPSTREAM_STORAGE_DIR || true)
# One folder directly under /sdcard, since the cleanup removes it whole.
case "$UPSTREAM_STORAGE_DIR" in
    */*|.|..|*[[:space:]]*)
        echo "SAIKOU_UPSTREAM_STORAGE_DIR is not one folder name: $UPSTREAM_STORAGE_DIR" >&2; exit 2 ;;
esac
PACKAGES=(com.shinsaikou ${UPSTREAM_PACKAGE:+"$UPSTREAM_PACKAGE"})
for app in "${APP_LIST[@]}"; do
    apk=$(apk_of "$app") || { echo "unknown app: $app" >&2; exit 2; }
    [ -r "$apk" ] || { echo "missing APK for $app: $apk" >&2; exit 2; }
    [ -r "$HERE/steps/compare20k-$app.txt" ] || { echo "missing profile: steps/compare20k-$app.txt" >&2; exit 2; }
    want=$(sha_of "$app")
    if [ -n "$want" ] && [ "$(sha256sum "$apk" | awk '{print $1}')" != "$want" ]; then
        echo "$apk is not the published $app APK" >&2; exit 2
    fi
done

SERIAL=${C20K_SERIAL:-}
OWN_EMULATOR=
LOCK=
adb_() { "$ADB_BIN" -s "$SERIAL" "$@" < /dev/null; }
finish() {
    if [ -n "$SERIAL" ] && [ -z "${C20K_KEEP:-}" ]; then
        for p in "${PACKAGES[@]}"; do adb_ uninstall "$p" >> "$STATUS" 2>&1; done
        adb_ shell 'rm -f /sdcard/Download/*.tachibk' >> "$STATUS" 2>&1
        adb_ shell cmd connectivity airplane-mode disable >> "$STATUS" 2>&1 && note "airplane mode off"
        if [ -n "$OWN_EMULATOR" ]; then
            adb_ emu kill >> "$STATUS" 2>&1
            while "$ADB_BIN" devices < /dev/null | grep -q "^$SERIAL[[:space:]]"; do sleep 2; done
            note "emulator $SERIAL stopped"
        fi
    fi
    if [ -n "$LOCK" ]; then rm -rf "$LOCK" && note "lock $LOCK released"; fi
}
trap finish EXIT
trap 'exit 130' INT TERM

if [ -z "$SERIAL" ]; then
    [ -n "${SAIKOU_AVD_LOCKS:-}" ] && [ -n "${SAIKOU_AVD_BOOT:-}" ] || { echo "missing: SAIKOU_AVD_LOCKS and SAIKOU_AVD_BOOT" >&2; exit 2; }
    waited=0
    until [ "$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)" -ge 8388608 ]; do sleep 60; waited=$((waited + 60)); done
    note "MemAvailable $(awk '/^MemAvailable:/{print $2}' /proc/meminfo) kB after waiting $waited s"
    t=$(date +%s)
    until mkdir "$SAIKOU_AVD_LOCKS/$AVD" 2>/dev/null; do sleep 30; done
    LOCK="$SAIKOU_AVD_LOCKS/$AVD"
    echo "${C20K_OWNER:-upstream-compare-20k compare20k.sh} $(date -Iseconds)" > "$LOCK/owner"
    note "lock $LOCK taken after $(($(date +%s) - t)) s"
    SERIAL=$($SAIKOU_AVD_BOOT "$AVD" 2>> "$STATUS" | tail -1)
    [ -n "$SERIAL" ] || { note "the boot of $AVD printed no serial"; exit 3; }
    OWN_EMULATOR=1
    note "$AVD up as $SERIAL"
fi
[ "$(adb_ emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$AVD" ] || { note "$SERIAL is not $AVD"; SERIAL=; exit 3; }
adb_ shell cmd connectivity airplane-mode enable >> "$STATUS" 2>&1
adb_ shell svc wifi disable >> "$STATUS" 2>&1
adb_ shell svc data disable >> "$STATUS" 2>&1
note "airplane mode $(adb_ shell settings get global airplane_mode_on | tr -d '\r'); host load $(cut -d' ' -f1-3 /proc/loadavg); Docker Desktop VM running: $(pgrep -f 'qemu-system.*docker-desktop' > /dev/null && echo yes || echo no)"
note "data partition: $(adb_ shell df -k /data | tail -1 | tr -s ' ')"

clean_device() {
    for p in "${PACKAGES[@]}"; do adb_ uninstall "$p" >> "$STATUS" 2>&1; done
    for p in "${PACKAGES[@]}"; do adb_ shell rm -rf "/sdcard/Android/data/$p" "/sdcard/Android/media/$p" "/sdcard/Android/obb/$p" >> "$STATUS" 2>&1; done
    [ -z "$UPSTREAM_STORAGE_DIR" ] || adb_ shell rm -rf "/sdcard/$UPSTREAM_STORAGE_DIR" >> "$STATUS" 2>&1
    adb_ shell 'rm -f /sdcard/Download/*.tachibk' >> "$STATUS" 2>&1
}
used_kb() { adb_ shell df -k /data | tail -1 | awk '{print $3}'; }

FAILED=()
for app in "${APP_LIST[@]}"; do
    apk=$(apk_of "$app")
    profile="$HERE/steps/compare20k-$app.txt"
    package=$(awk '$1 == "package" {print $2; exit}' "$profile")
    # An upstream profile names its package as ${UPSTREAM_PACKAGE}, which is matched as written.
    # shellcheck disable=SC2016
    package=${package//'${UPSTREAM_PACKAGE}'/$UPSTREAM_PACKAGE}
    dir="$OUT/$app"
    mkdir -p "$dir"
    drive=(python3 "$HERE/compare20k.py" --serial "$SERIAL" --profile "$profile" --env "$ENV_FILE" --out "$dir")
    note "== $app ($package) from $apk"
    clean_device
    before=$(used_kb)
    if [ -n "$UPSTREAM_PACKAGE" ] && [ "$package" = "$UPSTREAM_PACKAGE" ]; then
        adb_ shell mkdir -p "/sdcard/$UPSTREAM_STORAGE_DIR"
    fi
    if ! adb_ install "$apk" >> "$STATUS" 2>&1; then note "$app: install failed"; FAILED+=("$app install"); continue; fi
    adb_ shell pm grant "$package" android.permission.POST_NOTIFICATIONS >> "$STATUS" 2>&1
    adb_ shell cmd package compile -m speed -f "$package" >> "$STATUS" 2>&1
    adb_ shell dumpsys package "$package" | tr -d '\r' | grep -m2 -E 'versionName|flags=\[' | sed 's/^ */  /' >> "$dir/package.txt"
    names=()
    for f in "${FILES[@]}"; do adb_ push "$f" /sdcard/Download/ >> "$STATUS" 2>&1 && names+=("$f"); done
    activity=$(adb_ shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$package" | tr -d '\r' | tail -1)
    adb_ shell am start -n "$activity" >> "$STATUS" 2>&1
    if ! "${drive[@]}" setup >> "$dir/driver.log" 2>&1; then note "$app: setup failed (see $dir/driver.log)"; FAILED+=("$app setup"); continue; fi
    note "$app: set up"
    if [ -z "$SKIP_RESTORE" ]; then
        if ! "${drive[@]}" restore "${names[@]}" >> "$dir/driver.log" 2>&1; then
            note "$app: restore failed (see $dir/driver.log)"; FAILED+=("$app restore")
            note "$app: data used $(( ($(used_kb) - before) / 1024 )) MB after the failed restore"
            continue
        fi
        note "$app: restored; data used $(( ($(used_kb) - before) / 1024 )) MB"
        adb_ shell 'rm -f /sdcard/Download/*.tachibk' >> "$STATUS" 2>&1
        note "$app: data used without the files $(( ($(used_kb) - before) / 1024 )) MB"
        sleep 10
    fi
    if ! "${drive[@]}" scenarios ${SCENARIOS:+--only "$SCENARIOS"} >> "$dir/driver.log" 2>&1; then
        note "$app: scenarios stopped (see $dir/driver.log)"; FAILED+=("$app scenarios")
    else
        note "$app: scenarios done"
    fi
    note "$app: data used $(( ($(used_kb) - before) / 1024 )) MB at the end"
    "${drive[@]}" summary > "$dir/summary.json" 2>> "$dir/driver.log"
    [ -n "${C20K_KEEP:-}" ] || clean_device
done
[ ${#FAILED[@]} -eq 0 ] || { note "failed: ${FAILED[*]}"; exit 3; }
note "every app ran"
