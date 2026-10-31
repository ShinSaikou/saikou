#!/usr/bin/env python3
"""Checks that the booted low RAM AVD is the profile tools/avd/devices.env describes (R36, R61).

`low_ram_repro_pass` is only a statement about a 1.5 GB device if the device it ran on is one, and
twice it was not: the emulator raised the API 36 image's 1536 MB to 2560 MB without a word to the
gate, and the API 28 image's memory class came out at 512 MB because it follows the VM heap. The
AVD changes only when create-avds.sh runs, so a machine that has not run it since devices.env
changed keeps the old profile and would measure it. tools/ci/gate.sh reads the booted device's
`/proc/meminfo` and `getprop` before the repro and hands them to this, with what devices.env gives
the AVD:

  - MemTotal must be within TOLERANCE of the entry's RAM. The guest's MemTotal sits a little under
    the configured size (1530644 kB for 1536 MB on API 28), and a raised floor lands far outside;
  - the memory class must equal the entry's VM heap. It is worked out from the two properties the
    way `ActivityManager.getMemoryClass()` works it out: `dalvik.vm.heapgrowthlimit` when it is set,
    `dalvik.vm.heapsize` otherwise, each with its unit letter dropped. API 28 sets no growth limit,
    so on the low RAM AVD it is the heap the emulator passed as `vm.heapSize`.

A mismatch ends the run naming the AVD, the serial and the values, and tells the owner to run
create-avds.sh, which recreates an AVD whose image or heap changed. A dump without the values is a
profile that cannot be checked, and ends the run too.

Exit codes: 0 the profile matches; 1 it does not; 2 an input is missing or holds no value.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

MATCHES, MISMATCH, BAD_INPUT = 0, 1, 2

# How far MemTotal may sit from the configured RAM, either way.
TOLERANCE = 0.10

MEM_TOTAL = re.compile(r"^MemTotal:\s+(\d+)\s*kB\s*$", re.MULTILINE)
# `getprop` prints every property as `[name]: [value]`.
PROPERTY = re.compile(r"^\[([^\]]+)\]: \[([^\]]*)\]\s*$", re.MULTILINE)


def mem_total_kb(meminfo: str) -> int | None:
    found = MEM_TOTAL.search(meminfo)
    return int(found.group(1)) if found else None


def properties(getprop: str) -> dict[str, str]:
    return {name: value for name, value in PROPERTY.findall(getprop)}


def memory_class_mb(props: dict[str, str]) -> tuple[int | None, str]:
    """The memory class `ActivityManager` reports, and which property it came from.

    The platform drops the last character of the value and parses the rest, so `384m` is 384. A
    value that does not parse is None, which the caller reports as a profile it cannot check.
    """
    for name in ("dalvik.vm.heapgrowthlimit", "dalvik.vm.heapsize"):
        value = props.get(name, "").strip()
        if value:
            digits = value[:-1] if value[-1].isalpha() else value
            return (int(digits) if digits.isdigit() else None), f"{name} {value}"
    return None, "neither dalvik.vm.heapgrowthlimit nor dalvik.vm.heapsize is set"


def check(meminfo: str, getprop: str, ram_mb: int, heap_mb: int) -> tuple[int, list[str], str]:
    """Returns the exit code, what does not match (empty when it all does), and the values read."""
    total = mem_total_kb(meminfo)
    memory_class, source = memory_class_mb(properties(getprop))
    configured_kb = ram_mb * 1024
    low, high = configured_kb * (1 - TOLERANCE), configured_kb * (1 + TOLERANCE)
    read = (f"MemTotal {total if total is not None else 'unread'} kB, memory class "
            f"{memory_class if memory_class is not None else 'unread'} MB ({source})")
    if total is None or memory_class is None:
        return BAD_INPUT, [], read
    problems = []
    if not low <= total <= high:
        problems.append(f"MemTotal is {total} kB, where devices.env's {ram_mb} MB within "
                        f"{TOLERANCE:.0%} is {low:.0f} to {high:.0f} kB")
    if memory_class != heap_mb:
        problems.append(f"the memory class is {memory_class} MB ({source}), where devices.env asks for {heap_mb}")
    return (MISMATCH if problems else MATCHES), problems, read


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--meminfo", type=Path, required=True, help="`cat /proc/meminfo` on the device")
    parser.add_argument("--getprop", type=Path, required=True, help="`getprop` on the device")
    parser.add_argument("--avd", required=True, help="the AVD the gate expects, as devices.env names it")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--ram-mb", type=int, required=True, help="devices.env's RAM for the AVD")
    parser.add_argument("--heap-mb", type=int, required=True, help="devices.env's VM heap for the AVD")
    args = parser.parse_args(argv)

    for dump in (args.meminfo, args.getprop):
        if not dump.is_file():
            print(f"missing: {dump}", file=sys.stderr)
            return BAD_INPUT

    code, problems, read = check(args.meminfo.read_text(errors="replace"), args.getprop.read_text(errors="replace"),
                                 args.ram_mb, args.heap_mb)
    where = f"{args.avd} on {args.serial}"
    if code == BAD_INPUT:
        print(f"the profile of {where} could not be read, so the low RAM repro would measure a device nobody "
              f"checked (R36): {read}", file=sys.stderr)
        return code
    if problems:
        print(f"{where} is not the profile tools/avd/devices.env describes, so the low RAM repro would measure "
              f"another device: {'; '.join(problems)}. Run tools/avd/create-avds.sh, which recreates an AVD "
              f"whose image or VM heap changed, then boot {args.avd} again.", file=sys.stderr)
        return code
    print(f"    {where}: {read}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
