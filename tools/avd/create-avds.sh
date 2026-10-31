#!/usr/bin/env bash
# Creates the gate AVDs (contract 01, section 7.4).
#
# `avdmanager` ships with cmdline-tools, which is not installed on every machine. When it is
# missing this script writes the AVD config directly into $ANDROID_AVD_HOME (the same thing
# avdmanager would have written), which needs the system image to be present already. When the
# image is missing too, the AVD is reported as not creatable with the exact package to install, and
# the alias in devices.env is printed so the owner knows which existing AVD stands in for it.
#
# Inputs: ANDROID_SDK_ROOT; optional SAIKOU_AVD_FORCE=1 to recreate existing AVDs.
# Exit codes: 0 every AVD is either ready or has a printed fallback; 2 SDK root missing, or a
# devices.env entry whose VM heap is not a number; 3 an image failed to install.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=../common/localprops.sh
source "$HERE/../common/localprops.sh"
# shellcheck source=devices.env
source "$HERE/devices.env"

SDK=$(sdk_root)
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "missing: ANDROID_SDK_ROOT"; exit 2; }
AVD_HOME=${ANDROID_AVD_HOME:-$HOME/.android/avd}
FORCE=${SAIKOU_AVD_FORCE:-0}
mkdir -p "$AVD_HOME"

find_tool() { # find_tool <name>; prints the path or nothing
    local name=$1 candidate
    for candidate in "$SDK/cmdline-tools/latest/bin/$name" "$SDK"/cmdline-tools/*/bin/"$name" \
                     "$SDK/tools/bin/$name"; do
        [ -x "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
    done
    command -v "$name" 2>/dev/null || true
}

AVDMANAGER=$(find_tool avdmanager)
SDKMANAGER=$(find_tool sdkmanager)
[ -n "$AVDMANAGER" ] || echo "note: avdmanager is not installed; writing AVD configs directly"

installed_profiles=""
[ -n "$AVDMANAGER" ] && installed_profiles=$("$AVDMANAGER" list device 2>/dev/null |
    sed -n 's/.*id: [0-9]* or "\([^"]*\)".*/\1/p' || true)

write_config() { # write_config <name> <profile> <image> <ram> <w> <h> <density>
    local name=$1 profile=$2 image=$3 ram=$4 width=$5 height=$6 density=$7
    local dir="$AVD_HOME/$name.avd" skin="$SDK/skins/$profile" tag="${image##*;}"
    tag=$(printf '%s\n' "$image" | cut -d';' -f3)
    local api; api=$(printf '%s\n' "$image" | cut -d';' -f2)
    mkdir -p "$dir"
    cat > "$AVD_HOME/$name.ini" <<INI
avd.ini.encoding=UTF-8
path=$dir
path.rel=avd/$name.avd
target=$api
INI
    cat > "$dir/config.ini" <<INI
AvdId=$name
avd.ini.displayname=$name
avd.ini.encoding=UTF-8
abi.type=$(printf '%s\n' "$image" | cut -d';' -f4)
fastboot.forceColdBoot=no
hw.accelerometer=yes
hw.audioInput=yes
hw.battery=yes
hw.camera.back=none
hw.camera.front=none
hw.cpu.arch=$(printf '%s\n' "$image" | cut -d';' -f4 | sed 's/^x86_64$/x86_64/')
hw.cpu.ncore=4
hw.dPad=no
hw.device.manufacturer=Google
hw.device.name=$profile
hw.gps=yes
hw.gyroscope=yes
hw.initialOrientation=portrait
hw.lcd.density=$density
hw.lcd.height=$height
hw.lcd.width=$width
hw.mainKeys=no
hw.sdCard=yes
hw.sensors.orientation=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=${image//;//}/
runtime.network.latency=none
runtime.network.speed=full
sdcard.size=512M
showDeviceFrame=no
skin.dynamic=yes
tag.id=$tag
tag.ids=$tag
target=$api
INI
    [ -d "$skin" ] && printf 'skin.name=%s\nskin.path=%s\n' "$profile" "$skin" >> "$dir/config.ini"
}

apply_settings() { # apply_settings <name> <ram> <heap>
    local config="$AVD_HOME/$1.avd/config.ini" key
    # vm.heapSize is devices.env's: SAIKOU_AVD_HEAP_MB for every entry that names none, pinned so
    # runs stay comparable across devices (contract 01, section 7.4).
    for setting in "hw.ramSize=$2" "hw.gpu.enabled=yes" "hw.gpu.mode=auto" \
                   "disk.dataPartition.size=8G" "hw.keyboard=yes" "vm.heapSize=$3"; do
        key=${setting%%=*}
        sed -i "/^${key//./\\.}=/d" "$config"
        echo "$setting" >> "$config"
    done
}

missing=()
for spec in "${SAIKOU_AVDS[@]}"; do
    IFS='|' read -r name profile image ram width height density heap <<<"$spec"
    heap=${heap:-$SAIKOU_AVD_HEAP_MB}
    case "$heap" in
        ''|*[!0-9]*) echo "devices.env: $name names a VM heap of '$heap', which is not a number of MB"; exit 2 ;;
    esac

    # An AVD built from another image, or with another VM heap, than devices.env names is
    # recreated, so a change of either reaches every machine without SAIKOU_AVD_FORCE recreating the
    # AVDs that did not change, and the changed one starts from a fresh data partition under its
    # new profile, as a new AVD would.
    if [ -d "$AVD_HOME/$name.avd" ] && [ "$FORCE" != "1" ]; then
        current=$(sed -n 's/^image\.sysdir\.1=//p' "$AVD_HOME/$name.avd/config.ini" 2>/dev/null || true)
        current_heap=$(sed -n 's/^vm\.heapSize=//p' "$AVD_HOME/$name.avd/config.ini" 2>/dev/null | tail -1 || true)
        if [ "${current%/}" != "${image//;//}" ]; then
            echo "image changed for $name: ${current:-none} to ${image//;//}"
        elif [ "$current_heap" != "$heap" ]; then
            echo "heap changed for $name: ${current_heap:-none} to ${heap}MB"
        else
            apply_settings "$name" "$ram" "$heap"
            echo "ready: $name ($profile, ${ram}MB)"
            continue
        fi
    fi

    image_dir="$SDK/${image//;//}"
    if [ ! -d "$image_dir" ]; then
        if [ -n "$SDKMANAGER" ]; then
            yes | "$SDKMANAGER" --install "$image" >/dev/null || { echo "failed to install $image"; exit 3; }
        else
            alias_var="SAIKOU_AVD_ALIAS_$name"
            echo "cannot create: $name"
            echo "  install the system image first: sdkmanager --install '$image'"
            echo "  (sdkmanager itself comes from cmdline-tools, which is not installed)"
            if [ -n "${!alias_var:-}" ]; then
                echo "  standing in for it until then: ${!alias_var}"
            else
                echo "  no existing AVD stands in for it; this gate row cannot be measured"
            fi
            missing+=("$name")
            continue
        fi
    fi

    if [ -n "$AVDMANAGER" ]; then
        if [ -n "$installed_profiles" ] && ! printf '%s\n' "$installed_profiles" | grep -qx "$profile"; then
            fallback_var="SAIKOU_PROFILE_FALLBACK_${profile}"
            profile=${!fallback_var:-$profile}
            echo "device profile fallback for $name: $profile"
        fi
        echo no | "$AVDMANAGER" create avd -n "$name" -k "$image" -d "$profile" --force >/dev/null
    else
        write_config "$name" "$profile" "$image" "$ram" "$width" "$height" "$density"
    fi
    apply_settings "$name" "$ram" "$heap"
    echo "created: $name ($profile, ${ram}MB, ${width}x${height}, heap ${heap}MB)"
done

if [ "${#missing[@]}" -gt 0 ]; then
    echo
    echo "not created: ${missing[*]}"
fi
