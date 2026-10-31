#!/usr/bin/env bash
# Reads a key from local.properties when the matching environment variable is unset.
# Environment wins over local.properties (contract 01, section 10). Source this file.

localprop() { # usage: localprop <local.properties key> <ENV_NAME>
    local key=$1 env=$2 value file
    value=${!env:-}
    if [ -n "$value" ]; then printf '%s\n' "$value"; return 0; fi
    file=${SAIKOU_LOCAL_PROPERTIES:-$(git rev-parse --show-toplevel 2>/dev/null)/local.properties}
    [ -r "$file" ] || return 1
    value=$(sed -n "s/^[[:space:]]*${key//./\\.}[[:space:]]*=[[:space:]]*//p" "$file" | tail -1)
    [ -n "$value" ] || return 1
    printf '%s\n' "$value"
}

# localprop for a value the caller cannot go on without: it is exported under ENV_NAME, and when
# neither is set the script stops as on any missing input (exit 2), naming both.
require_localprop() { # usage: require_localprop <local.properties key> <ENV_NAME>
    local value
    value=$(localprop "$1" "$2") || { echo "missing: $2 (or $1 in local.properties)" >&2; exit 2; }
    printf -v "$2" '%s' "$value"
    export "${2?}"
}

require_env() { # usage: require_env NAME [NAME...]
    local name missing=0
    for name in "$@"; do
        if [ -z "${!name:-}" ]; then echo "missing: $name" >&2; missing=1; fi
    done
    [ "$missing" -eq 0 ] || exit 2
}

# Environment first, then local.properties sdk.dir, which is the standard AGP key and the only
# place a fresh clone records the SDK. Returning empty here is worse than failing: callers export
# the result, and an empty ANDROID_SDK_ROOT stops AGP falling back to local.properties itself.
sdk_root() {
    local value=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
    [ -n "$value" ] || value=$(localprop sdk.dir __SAIKOU_SDK_DIR_UNSET || true)
    [ -n "$value" ] || return 1
    printf '%s\n' "$value"
}
