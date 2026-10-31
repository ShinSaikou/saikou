#!/usr/bin/env bash
# Kills the emulator with the given serial.
set -euo pipefail
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"
SERIAL=${1:-}
[ -n "$SERIAL" ] || { echo "usage: stop.sh <serial>"; exit 2; }
SDK=$(sdk_root)
[ -n "$SDK" ] || { echo "missing: ANDROID_SDK_ROOT"; exit 2; }
"$SDK/platform-tools/adb" -s "$SERIAL" emu kill || exit 3
