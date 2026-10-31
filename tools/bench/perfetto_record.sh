#!/usr/bin/env bash
# Records a Perfetto trace with the on-device binary and pulls it (contract 01, section 7.5).
# usage: perfetto_record.sh <seconds> <out.pftrace>
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

DURATION=${1:-30}
OUT=${2:-$SAIKOU_BENCH_OUT/trace.pftrace}
REMOTE=/data/misc/perfetto-traces/saikou-$SAIKOU_DEVICE_ID.pftrace

# Application and binder slices, for the cold start pass with extensions
# (`perfetto_metrics.py --metric main_thread_pm_okhttp_slices`). atrace_apps turns on the
# application's own trace sections, which is how the product's `OkHttpClient.build` and
# `PackageManager.getPackageArchiveInfo` reach the trace; `aidl` and `binder_driver` record every
# binder transaction and the AIDL method the server ran for it. The two task events keep the
# process table right for a process that starts inside the trace: without them a slow cold start
# was recorded under the zygote's name, and nothing in the trace belonged to the package.
#
# Off unless SAIKOU_PERFETTO_ATRACE=1. Enabling application tracing makes every section the
# application and its libraries emit (Compose's among them) do real work, and the rows measured
# from these traces elsewhere, cold_start_ms and the jank rows, keep the recording their baselines
# were taken under.
ATRACE=""
if [ "${SAIKOU_PERFETTO_ATRACE:-0}" = "1" ]; then
    ATRACE="
      ftrace_events: \"task/task_newtask\"
      ftrace_events: \"task/task_rename\"
      atrace_categories: \"aidl\"
      atrace_categories: \"binder_driver\"
      atrace_apps: \"$SAIKOU_PACKAGE\""
fi

$ADB shell setprop persist.traced.enable 1 >/dev/null 2>&1 || true
$ADB shell rm -f "$REMOTE" >/dev/null 2>&1 || true
printf '%s\n' "buffers { size_kb: 131072 fill_policy: RING_BUFFER }
data_sources { config { name: \"android.surfaceflinger.frametimeline\" } }
data_sources { config { name: \"android.log\" android_log_config { log_ids: LID_DEFAULT } } }
data_sources {
  config {
    name: \"linux.process_stats\"
    process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }
  }
}
data_sources {
  config {
    name: \"linux.ftrace\"
    ftrace_config { ftrace_events: \"sched/sched_switch\" ftrace_events: \"sched/sched_process_exit\"$ATRACE }
  }
}
duration_ms: $((DURATION * 1000))" | $ADB shell "perfetto --txt -c - -o $REMOTE" >/dev/null 2>&1 || exit 3
mkdir -p "$(dirname "$OUT")"
$ADB pull "$REMOTE" "$OUT" >/dev/null 2>&1 || exit 3
echo "$OUT"
