#!/usr/bin/env python3
"""Perfetto metrics for the gate rows (contract 01, section 7.5).

Computes the janky frame percent from the frame timeline, the count of main thread
PackageManager and OkHttpClient work, and the number of extension archives a launch parsed. Uses
the `perfetto` trace processor Python package when it is importable, otherwise shells out to
`trace_processor_shell` on the PATH.

The trace processor is a development dependency of the bench, like adb. Installed on the bench
machine with `pip install --user perfetto` (0.58.2 on 2026-09-23, which fetched
`trace_processor_shell` v57.2 on first use into ~/.local/share/perfetto/prebuilts). A distribution
Python that refuses user installs (PEP 668) needs `--break-system-packages` beside `--user`, which
still writes only under ~/.local; point SAIKOU_PYTHON at another interpreter instead if that is not
wanted.

PLAN.md section 6 binds the gate to these numbers rather than to gfxinfo, because gfxinfo reported
zero frames for some content on the 2 GB AVD.

Exit codes: 0 ok; 2 missing input, or a trace that cannot answer the metric asked of it; 4 no trace
processor available; 5 the trace carries no jank signal (every frame presented early, which is what
an emulator with no display pacing produces).
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

# A frame counts as janky when the app missed its own deadline or stuffed the buffer. Frames the
# compositor merely mispredicted (`Prediction Error`) are not app jank, and a frame presented early
# was not late for the viewer. An emulator with no real display pacing presents every frame early
# and labels it `Prediction Error`; when that happens the whole trace carries no jank signal, which
# `--metric frame_timeline_summary` shows and the bench scripts fall back to gfxinfo for.
JANK_QUERY = """
select
  sum(case when (a.on_time_finish = 0 or a.jank_type glob '*App Deadline Missed*'
                 or a.jank_type glob '*Buffer Stuffing*')
                and a.present_type != 'Early Present' then 1 else 0 end) as janky,
  count(*) as total
from actual_frame_timeline_slice a
join process p using (upid)
where p.name = '{package}'{window}
"""

PACING_QUERY = """
select sum(case when a.present_type != 'Early Present' then 1 else 0 end) as paced,
       count(*) as total
from actual_frame_timeline_slice a
join process p using (upid)
where p.name = '{package}'{window}
"""

SUMMARY_QUERY = """
select a.jank_type as jank_type, a.present_type as present_type, count(*) as frames
from actual_frame_timeline_slice a
join process p using (upid)
where p.name = '{package}'{window}
group by 1, 2
order by frames desc
"""

# PLAN.md's cold start target: no PackageManager or OkHttpClient work on the main thread (upstream's
# #3703). Two kinds of evidence, summed.
#
# The application's own trace sections, `PackageManager*` and `OkHttpClient*`. The product emits
# `OkHttpClient.build` around the one place it builds its client,
# `PackageManager.getPackageArchiveInfo` around the extension loader's archive parse, which happens
# inside the process and so has no binder transaction for the second half to see, and
# `PackageManager.<method>` around each of its own calls to a method on the list below.
#
# Binder transactions from the main thread to IPackageManager, read out of the stdlib's
# android_binder_txns, which names each one from the server's `AIDL::java::IPackageManager::<method>`
# slice. The client side was meant to be read from `AIDL::java::...::client` slices, but the API 36
# image emits no Java client slices at all, only the server half, so the transaction's server name
# is what attributes it.
#
# Some of those transactions are the platform's and AndroidX's, and they happen on every Android
# 16 application's main thread whatever the application does. A method trace of a Saikou cold start
# on the 2 GB AVD (2026-09-23, `am start --start-profiler --sampling 0 --streaming`) put every
# main-thread IPackageManager call below on a caller outside the application: ActivityThread's
# bindApplication and GraphicsEnvironment.setup (notifyDexLoad, getPackagesForUid,
# getApplicationInfo, hasSystemFeature), LoadedApk.initializeJavaContextClassLoader (getPackageInfo),
# ActivityThread.performLaunchActivity (getSdkSandboxPackageName), androidx.startup's
# InitializationProvider (getProviderInfo), WorkManager's scheduler set-up
# (getComponentEnabledSetting), AppCompat's locale service lookup and configuration handling
# (getServiceInfo, getActivityInfo), core's NavUtils (getActivityInfo) and activity-compose's
# setContent (hasSystemFeature). Counting them would fail the row on a platform fact no application
# can change, so these methods are not counted (ruling R60). An application call to one of them on
# the main thread is invisible to the binder half, so every such call in Saikou's main source sets
# runs inside a `PackageManager.<method>` section, which the first half counts on the main thread;
# tools/test_pm_call_sites.py reads this tuple and fails on a call that is not wrapped. The
# application's own PackageManager work that #3703 is about, the installed package scan and the
# archive parse, is not on this list and is counted either way.
PLATFORM_MAIN_THREAD_PM_METHODS = (
    "notifyDexLoad",
    "getPackagesForUid",
    "getApplicationInfo",
    "hasSystemFeature",
    "getPackageInfo",
    "getSdkSandboxPackageName",
    "getProviderInfo",
    "getComponentEnabledSetting",
    "getServiceInfo",
    "getActivityInfo",
)

# The extension loader's archive parse, one section per extension it loads. The loader runs it on
# its IO dispatcher, so the count is taken on every thread of the process, not the main one.
ARCHIVE_PARSE_SECTION = "PackageManager.getPackageArchiveInfo"

ARCHIVE_PARSE_QUERY = """
select count(*) as sections
from slice s
join thread_track tt on s.track_id = tt.id
join thread t using (utid)
join process p using (upid)
where p.name = '{package}' and s.name = '{section}'{window}
"""

# `thread.is_main_thread` is null when the trace never said which thread leads the process, and
# `tid = pid` is the same fact read directly.
MAIN_THREAD = "coalesce(t.is_main_thread, t.tid = p.pid)"

MAIN_THREAD_SLICE_QUERY = """
include perfetto module android.binder;
select
  (select count(*)
   from slice s
   join thread_track tt on s.track_id = tt.id
   join thread t using (utid)
   join process p using (upid)
   where p.name = '{package}' and {main}
     and (s.name glob 'PackageManager*' or s.name glob 'OkHttpClient*'){window}) as sections,
  (select count(*)
   from android_binder_txns b
   join thread t on t.utid = b.client_utid
   join process p on p.upid = b.client_upid
   where p.name = '{package}' and {main}
     and b.interface = 'IPackageManager'
     and b.method_name not in ({platform}){binder_window}) as binder_calls,
  (select count(*)
   from slice s
   join thread_track tt on s.track_id = tt.id
   join thread t using (utid)
   join process p using (upid)
   where p.name = '{package}'
     and (s.name glob 'PackageManager*' or s.name glob 'OkHttpClient*')) as sections_anywhere,
  (select count(*)
   from android_binder_txns b
   join process p on p.upid = b.client_upid
   where p.name = '{package}' and b.interface is not null) as named_binder_calls,
  (select count(*)
   from thread t
   join process p using (upid)
   where p.name = '{package}' and {main}) as main_threads
"""


def die(code: int, message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def query(trace: Path, sql: str) -> list[dict]:
    try:
        from perfetto.trace_processor import TraceProcessor  # type: ignore
    except ImportError:
        shell = shutil.which("trace_processor_shell")
        if shell is None:
            die(4, "missing: perfetto python package or trace_processor_shell")
        result = subprocess.run(
            [shell, "-q", "/dev/stdin", "--json", str(trace)],
            input=sql, capture_output=True, text=True, check=True,
        )
        return json.loads(result.stdout or "[]")
    processor = TraceProcessor(trace=str(trace))
    try:
        return [row.__dict__ for row in processor.query(sql)]
    finally:
        processor.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="Perfetto metrics.")
    parser.add_argument("trace", type=Path)
    parser.add_argument("--window", default="all",
                        help="start,end in trace nanoseconds, or `all` for the whole trace")
    parser.add_argument("--package", required=True)
    parser.add_argument("--metric", default="jank_pct",
                        choices=["jank_pct", "main_thread_pm_okhttp_slices",
                                 "extension_archive_parses", "frame_timeline_summary"])
    args = parser.parse_args()

    if not args.trace.is_file():
        die(2, f"missing: {args.trace}")
    if args.window == "all":
        window = ""
    else:
        try:
            start, end = (int(float(value)) for value in args.window.split(","))
        except ValueError:
            die(2, "missing: --window start,end")
        window = f" and s.ts >= {start} and s.ts <= {end}"

    if args.metric == "frame_timeline_summary":
        sql = SUMMARY_QUERY.format(package=args.package, window=window.replace("s.ts", "a.ts"))
        for row in query(args.trace, sql):
            print(f"{row.get('frames'):>8}  {row.get('jank_type')}  {row.get('present_type')}")
    elif args.metric == "jank_pct":
        pacing = query(args.trace, PACING_QUERY.format(package=args.package,
                                                       window=window.replace("s.ts", "a.ts")))
        if pacing and (pacing[0].get("total") or 0) and not (pacing[0].get("paced") or 0):
            die(5, "the frame timeline presents every frame early: this display is not paced, "
                   "so the trace cannot tell a janky frame from a smooth one")
        sql = JANK_QUERY.format(package=args.package, window=window.replace("s.ts", "a.ts"))
        rows = query(args.trace, sql)
        janky = (rows[0].get("janky") or 0) if rows else 0
        total = (rows[0].get("total") or 0) if rows else 0
        if not total:
            die(2, f"the trace holds no frame timeline slices for {args.package}")
        print(round(100.0 * janky / total, 2))
    elif args.metric == "extension_archive_parses":
        print(extension_archive_parses(args.trace, args.package, window))
    else:
        print(main_thread_pm_okhttp(args.trace, args.package, window))
    return 0


def extension_archive_parses(trace: Path, package: str, window: str) -> int:
    """How many extension archives the launch in this trace parsed, on any thread.

    Zero is an answer here, not a refusal: the caller compares it with the number of extensions
    installed, and a trace that recorded no application sections falls short of that the same way.
    """
    rows = query(trace, ARCHIVE_PARSE_QUERY.format(package=package, section=ARCHIVE_PARSE_SECTION,
                                                   window=window))
    return (rows[0].get("sections") or 0) if rows else 0


def main_thread_pm_okhttp(trace: Path, package: str, window: str) -> int:
    """The count, after proving the trace could have shown a nonzero one.

    Every guard below is a way this metric used to answer 0 for a trace that recorded nothing it
    looks for: a trace with no application slices at all, with no binder names, or of another
    process. Each is refused by name instead (R29, R36).
    """
    sql = MAIN_THREAD_SLICE_QUERY.format(
        package=package,
        main=MAIN_THREAD,
        window=window,
        binder_window=window.replace("s.ts", "b.client_ts"),
        platform=", ".join(f"'{method}'" for method in PLATFORM_MAIN_THREAD_PM_METHODS),
    )
    rows = query(trace, sql)
    row = rows[0] if rows else {}
    if not (row.get("main_threads") or 0):
        die(2, f"the trace holds no main thread for {package}, so it never saw the launch")
    if not (row.get("sections_anywhere") or 0):
        die(2, f"the trace holds no PackageManager or OkHttpClient section from {package} on any "
               "thread; application tracing (atrace_apps) was not recorded, so a main thread "
               "section could not have been seen either")
    if not (row.get("named_binder_calls") or 0):
        die(2, f"the trace names no binder transaction from {package}; the aidl and "
               "binder_driver atrace categories were not recorded")
    return (row.get("sections") or 0) + (row.get("binder_calls") or 0)


if __name__ == "__main__":
    raise SystemExit(main())
