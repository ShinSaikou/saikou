#!/usr/bin/env python3
"""Reads the fixture server's request log of one library update run and prints its result, R66.

Section 4 Q2 publishes the server-side window: from the first fixture request of the run to the
last response, on one clock with one piece of code for both apps. The device-side window, from the
tap on the app's refresh to the removal of its update notification, is recorded beside it, and a
run whose two windows differ by more than 10 percent ends (R36), because one app would then be doing
substantial work the server cannot see.

What a run's JSON holds:
    duration_ms          the server-side window, the value units C and F read
    device_window_ms     the tap to the notification's removal, from the driver
    window_difference_pct  |device - server| / server, in percent
    requests             request counts by kind (chapters, details, pages, ...)
    per_source           for each source that was asked for anything: its id, its request count
                         and its rate, requests divided by that source's own window (its first
                         request's receipt to its last response)
    min_rate_ratio       the lowest per-source rate of the scenario's sources over the configured
                         limit (5 per second): the figure library_update_throughput_ratio compares
                         with 0.9 ("measured requests per second within 10 percent of the
                         RateLimitInterceptor setting")

The guard ends the run (R36), with every reason listed, when:
    - any fixture request failed: a status of 400 or more, or a client that went away before its
      response was written;
    - the chapter-list requests do not cover the seeded entries exactly once each: a count that
      differs from the scenario's entry count, an entry fetched twice, an entry never fetched, or an
      entry or source the scenario does not seed;
    - any source request was not a chapter list. Both apps are configured so a library update
      fetches chapter lists only (R66 lists the settings); a details, page or browse request inside
      the window means a setting drifted or something else ran, and the window no longer measures
      the same work in both apps;
    - a source request arrived before the tap or after the notification's removal;
    - the two windows differ by more than 10 percent;
    - the driver could not take the device-side window (it says why).
This is what catches both apps' skip rules and fetch-interval logic, which would otherwise make a
run look fast by fetching less.

Usage: update_log.py <request log> --scenario <2000x20|500x2> --drive <driver JSON> [--field k=v ...]
Prints one JSON object. Exit codes: 0 a measured run; 2 usage or an unreadable log; 3 the run ended
(R36), and the JSON says why and carries no duration.
Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_fixture as fx  # noqa: E402

WINDOW_TOLERANCE_PCT = 10.0


class LogError(Exception):
    pass


@dataclass(frozen=True)
class Request:
    t0: float
    t1: float
    source: int | None
    kind: str
    entry: int | None
    status: int
    aborted: bool
    path: str


def read_log(text: str) -> tuple[dict, list[Request]]:
    """The start header and every request, in log order. A line that is not whole JSON, a log with no
    start header, or a request line missing a field is an error: a log the server did not finish
    writing cannot vouch for the run."""
    header: dict | None = None
    requests: list[Request] = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as e:
            raise LogError(f"line {number} is not JSON: {e}") from e
        if record.get("event") == "start":
            header = record
            continue
        if "event" in record:
            continue
        try:
            requests.append(Request(float(record["t0"]), float(record["t1"]), record["source"], record["kind"],
                                    record["entry"], int(record["status"]), bool(record["aborted"]), record["path"]))
        except (KeyError, TypeError, ValueError) as e:
            raise LogError(f"line {number} is not a request record: {e}") from e
    if header is None:
        raise LogError("the log has no start header, so it is not the fixture server's")
    return header, requests


def source_requests(requests: list[Request]) -> list[Request]:
    """Requests to a fixture source. Repository requests are setup and belong to no run."""
    return [r for r in requests if r.source is not None]


def window_s(requests: list[Request]) -> float:
    """From the first request's receipt to the last response, in seconds."""
    return max(r.t1 for r in requests) - min(r.t0 for r in requests)


def per_source(requests: list[Request]) -> dict[int, dict]:
    """Each source's request count and its rate over its own window."""
    out: dict[int, dict] = {}
    for source in sorted({r.source for r in requests}):
        mine = [r for r in requests if r.source == source]
        span = window_s(mine)
        out[source] = {"id": str(fx.source_id(source)), "requests": len(mine),
                       "rps": round(len(mine) / span, 4) if span > 0 else None}
    return out


def min_rate_ratio(sources: dict[int, dict], scenario: fx.Scenario) -> float | None:
    """The lowest rate among the scenario's sources over the configured limit. None when any of them
    has no rate, which the guard already ends the run for."""
    limit = fx.RATE_LIMIT_PERMITS / fx.RATE_LIMIT_PERIOD_SECONDS
    rates = [sources.get(s, {}).get("rps") for s in range(1, scenario.sources + 1)]
    if any(r is None for r in rates):
        return None
    return round(min(rates) / limit, 4)


def guard(requests: list[Request], scenario: fx.Scenario, tap: float | None, gone: float | None) -> list[str]:
    """Every reason the run cannot stand as a measurement, or nothing."""
    problems: list[str] = []
    fixture = source_requests(requests)
    if not fixture:
        return ["the fixture server received no source request, so no update reached it"]

    failed = [r for r in fixture if r.status >= 400 or r.aborted]
    if failed:
        sample = ", ".join(f"{r.path} on s{r.source:02d} ({'aborted' if r.aborted else r.status})" for r in failed[:5])
        problems.append(f"{len(failed)} fixture request(s) failed: {sample}")

    chapters = [r for r in fixture if r.kind == "chapters"]
    fetched = Counter((r.source, r.entry) for r in chapters)
    expected = scenario.expected()
    if len(chapters) != scenario.entries:
        problems.append(f"{len(chapters)} chapter-list request(s) for the {scenario.entries} seeded entries")
    missing = sorted(expected - fetched.keys())
    if missing:
        problems.append(f"{len(missing)} seeded entr{'y was' if len(missing) == 1 else 'ies were'} never fetched, "
                        f"first {', '.join(f's{s:02d}/{e}' for s, e in missing[:5])}")
    twice = sorted(k for k, n in fetched.items() if n > 1)
    if twice:
        problems.append(f"{len(twice)} entr{'y was' if len(twice) == 1 else 'ies were'} fetched more than once, "
                        f"first {', '.join(f's{s:02d}/{e}' for s, e in twice[:5])}")
    foreign = sorted(k for k in fetched if k not in expected)
    if foreign:
        problems.append(f"{len(foreign)} chapter list(s) fetched for entries the scenario does not seed, "
                        f"first {', '.join(f's{s:02d}/{e}' for s, e in foreign[:5])}")

    other = Counter(r.kind for r in fixture if r.kind != "chapters")
    if other:
        problems.append("source requests other than chapter lists: "
                        + ", ".join(f"{n} {kind}" for kind, n in sorted(other.items()))
                        + "; the settings R66 fixes should leave a chapter-list-only update")

    if tap is not None:
        early = [r for r in fixture if r.t0 < tap]
        if early:
            problems.append(f"{len(early)} source request(s) arrived before the tap")
    if gone is not None:
        late = [r for r in fixture if r.t0 > gone]
        if late:
            problems.append(f"{len(late)} source request(s) arrived after the update notification was removed")
    return problems


def compare_windows(server_s: float, device_s: float) -> tuple[float, str | None]:
    """The difference in percent of the server window, and the reason to end the run if it is over."""
    difference = abs(device_s - server_s) / server_s * 100 if server_s > 0 else float("inf")
    if difference > WINDOW_TOLERANCE_PCT:
        return difference, (f"the device-side window ({device_s:.1f} s) and the server-side window ({server_s:.1f} s) "
                             f"differ by {difference:.1f} percent, over the {WINDOW_TOLERANCE_PCT:g} percent R36 allows")
    return difference, None


def result(requests: list[Request], scenario: fx.Scenario, drive: dict) -> dict:
    """The run's JSON object. A run that ended carries its reasons and no duration."""
    tap, gone = drive.get("tap"), drive.get("gone")
    reasons = list(drive.get("problems", []))
    if tap is None or gone is None:
        reasons.append("the driver recorded no device-side window")
    reasons += guard(requests, scenario, tap, gone)

    fixture = source_requests(requests)
    out: dict = {
        "scenario": scenario.name,
        "entries": scenario.entries,
        "rate_limit_rps": fx.RATE_LIMIT_PERMITS / fx.RATE_LIMIT_PERIOD_SECONDS,
        "requests": dict(sorted(Counter(r.kind for r in requests).items())),
    }
    server_ms = device_ms = None
    if fixture:
        server_ms = round(window_s(fixture) * 1000)
        sources = per_source(fixture)
        out["per_source"] = {f"{s:02d}": v for s, v in sources.items()}
        out["min_rate_ratio"] = min_rate_ratio(sources, scenario)
    if tap is not None and gone is not None:
        device_ms = round((gone - tap) * 1000)
        out["device_window_ms"] = device_ms
    if server_ms and device_ms is not None:
        difference, problem = compare_windows(server_ms / 1000, device_ms / 1000)
        out["window_difference_pct"] = round(difference, 2)
        if problem:
            reasons.append(problem)
    out["server_window_ms"] = server_ms
    out["status"] = "ended" if reasons else "ok"
    out["duration_ms"] = None if reasons else server_ms
    out["reasons"] = reasons
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("log", type=Path)
    parser.add_argument("--scenario", required=True, choices=sorted(fx.SCENARIOS))
    parser.add_argument("--drive", required=True, type=Path, help="the driver's JSON")
    parser.add_argument("--field", action="append", default=[], help="k=v copied into the output as a string")
    args = parser.parse_args(argv)
    try:
        _, requests = read_log(args.log.read_text())
        drive = json.loads(args.drive.read_text())
    except (OSError, LogError, json.JSONDecodeError) as e:
        print(f"update_log: {e}", file=sys.stderr)
        return 2
    out = {}
    for field in args.field:
        name, _, value = field.partition("=")
        out[name] = value
    out.update(result(requests, fx.SCENARIOS[args.scenario], drive))
    out["drive"] = {k: v for k, v in drive.items() if k not in ("problems",)}
    out["log"] = str(args.log)
    print(json.dumps(out, sort_keys=False))
    return 0 if out["status"] == "ok" else 3


if __name__ == "__main__":
    sys.exit(main())
