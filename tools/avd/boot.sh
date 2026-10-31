#!/usr/bin/env bash
# Boots an AVD headless and prints its serial (contract 01, section 7.4).
# usage: boot.sh <avd name>
# Exit codes: 0 ok; 2 missing input; 3 adb or emulator error.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"
# shellcheck source=devices.env
source "$HERE/devices.env"

NAME=${1:-}
[ -n "$NAME" ] || { echo "usage: boot.sh <avd name>"; exit 2; }
SDK=$(sdk_root)
[ -n "$SDK" ] || { echo "missing: ANDROID_SDK_ROOT"; exit 2; }
ADB="$SDK/platform-tools/adb"
AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}

# A name that was never created falls back to the alias devices.env records for it.
if [ ! -d "$AVD_HOME/$NAME.avd" ]; then
    alias_var="SAIKOU_AVD_ALIAS_$NAME"
    [ -n "${!alias_var:-}" ] || { echo "no such AVD and no alias: $NAME" >&2; exit 2; }
    echo "using ${!alias_var} in place of $NAME" >&2
    NAME=${!alias_var}
fi

RAM=2048
for spec in "${SAIKOU_AVDS[@]}"; do
    IFS='|' read -r name _profile _image ram _w _h _d _heap <<<"$spec"
    [ "$name" = "$1" ] && RAM=$ram
done

port=5554
while "$ADB" devices | grep -q "emulator-$port"; do port=$((port + 2)); done
serial="emulator-$port"

log=${SAIKOU_EMULATOR_LOG:-${TMPDIR:-/tmp}/saikou-emulator-$NAME.log}
setsid nohup "$SDK/emulator/emulator" -avd "$NAME" -port "$port" -no-window -no-snapshot \
    -no-audio -gpu swiftshader_indirect -memory "$RAM" > "$log" 2>&1 < /dev/null &
disown || true

"$ADB" -s "$serial" wait-for-device || exit 3
until [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    sleep 2
done

for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" -s "$serial" shell settings put global "$scale" 0
done
# Play Protect disables a sideloaded APK a few minutes after it is installed on a Play Store system
# image, which ends a benchmark run mid-way. The verifier is off for the life of the AVD.
"$ADB" -s "$serial" shell settings put global package_verifier_enable 0
"$ADB" -s "$serial" shell settings put global verifier_verify_adb_installs 0
"$ADB" -s "$serial" shell settings put global package_verifier_user_consent -1
# SystemUI's "Viewing full screen" cling covers a reader the first time it goes immersive and eats
# every tap a bench script sends.
"$ADB" -s "$serial" shell settings put secure immersive_mode_confirmations confirmed
echo "$serial"
