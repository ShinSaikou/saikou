#!/usr/bin/env bash
# Contract 01, section 7.1. Scans tracked files, the staged diff and unpushed commit messages for
# the regexes in SAIKOU_FORBIDDEN_WORDS_FILE, and for the em dash character.
# Exit codes: 0 no match; 1 at least one match; 2 the list file is missing or unreadable;
# 3 not inside a git work tree.
set -uo pipefail

LIST=${SAIKOU_FORBIDDEN_WORDS_FILE:-}
if [ -z "$LIST" ]; then
    echo "missing: SAIKOU_FORBIDDEN_WORDS_FILE" >&2
    exit 2
fi
if [ ! -r "$LIST" ]; then
    echo "unreadable: SAIKOU_FORBIDDEN_WORDS_FILE" >&2
    exit 2
fi
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "not a git work tree" >&2
    exit 3
fi

STAGED_ONLY=0
[ "${1:-}" = "--staged" ] && STAGED_ONLY=1

EM_DASH=$(printf '\xe2\x80\x94')
hits=0

# Every scan helper returns 1 when it printed a hit, so callers can run them in a pipeline.
scan_files() {
    local found=0
    [ "$#" -gt 0 ] || return 0
    grep -E -i -n -o -I -f "$LIST" -- "$@" && found=1
    grep -F -n -o -I -- "$EM_DASH" "$@" && found=1
    return $(( found == 0 ? 0 : 1 ))
}

scan_stream() { # usage: ... | scan_stream <label>
    local label=$1 input found=0
    input=$(cat)
    [ -n "$input" ] || return 0
    printf '%s\n' "$input" | grep -E -i -n -o -f "$LIST" | sed "s|^|$label:|" | grep . && found=1
    printf '%s\n' "$input" | grep -F -n -o -- "$EM_DASH" | sed "s|^|$label:|" | grep . && found=1
    return $(( found == 0 ? 0 : 1 ))
}

if [ "$STAGED_ONLY" -eq 0 ]; then
    if [ -n "${SAIKOU_SCAN_PATHS:-}" ]; then
        # shellcheck disable=SC2086
        mapfile -t files < <(git ls-files -- $SAIKOU_SCAN_PATHS)
    else
        mapfile -t files < <(git ls-files)
    fi
    if [ "${#files[@]}" -gt 0 ]; then
        scan_files "${files[@]}" || hits=1
    fi
fi

git diff --cached -U0 | scan_stream "<staged diff>" || hits=1

if git rev-parse --verify --quiet origin/main >/dev/null 2>&1; then
    git log --format='%H %s%n%b' origin/main..HEAD | scan_stream "<commit message>" || hits=1
fi

if [ "$hits" -ne 0 ]; then
    echo "forbidden words found" >&2
    exit 1
fi
echo "scan clean"
exit 0
