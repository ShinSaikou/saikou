#!/usr/bin/env python3
"""Frame timing out of `dumpsys gfxinfo <package> framestats` (contract 01 section 7.5, ruling R40).

The page turn row counts the frames this application was late for. It used to publish gfxinfo's own
`Janky frames` percent, which counts every frame that missed its deadline for any reason at all. On
the 2 GB AVD that number read 8.53 percent for the reader, 8.61 percent for the system launcher's
app drawer, and 8.31 percent for the Phase 0 baseline subject recorded a week earlier: the same
figure for three unrelated subjects, because what it measures there is the emulator's display and
not anything an application did. Every janky frame in that run missed its deadline by a median of
0.073 ms while 15.93 ms of the 16.67 ms budget sat in the wait between SwapBuffers and GpuCompleted.

Ruling R40 takes that wait back out, per frame, from the PROFILEDATA rows:

    total    = FrameCompleted - IntendedVsync
    gpu_wait = GpuCompleted - SwapBuffers        (0 when GpuCompleted is 0)
    budget   = FrameDeadline - IntendedVsync
    the frame is janky for the row when (total - gpu_wait) > budget

The total janky percent and the median GPU wait are reported beside the new value, because a row
that suddenly publishes a much smaller number has to keep showing the number it replaced and the
size of the term it removed. Both belong in the fragment's method note.

THE 120 FRAME WINDOW. `dumpsys gfxinfo <package> framestats` returns only the last 120 frames, and a
40 gesture bench window draws about 438 of them. One dump taken at the end of a run would compute
its percentage over the final quarter of the window while appearing to answer for all of it, so the
bench polls during the gesture loop and hands every dump it took to this file at once. The overlap
between consecutive dumps costs nothing, because a frame seen twice is recognised and counted once.

Coverage is reported rather than assumed: the summary block counts every frame the process rendered
since the last `gfxinfo reset`, so comparing it against the number of frames actually parsed says
whether the polling kept up. One frame can be listed as finished before the summary counts it;
unreported_frame says when that frame is set aside. FrameTimelineVsyncId cannot stand in for that
check. It is a token minted per display vsync rather than per application frame, so it advances by
more than one between two frames this process drew, and a run with no gap at all shows gaps in the
ids. The same fact is why it is not the key frames are unioned on either; see IDENTITY_COLUMNS.

Exit codes: 0 ok; 2 missing input, or a dump whose layout lacks a column the row needs; 5 the dumps
hold no usable frame.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

# gfxinfo brackets the per frame rows with this marker, once to open and once to close.
PROFILEDATA_MARKER = "---PROFILEDATA---"

# The columns R40 needs, looked up by name. The column set differs between platform versions: API 36
# writes 24 columns including FrameTimelineVsyncId, FrameDeadline, FrameStartTime, FrameInterval,
# WorkloadTarget and CommandSubmissionCompleted, none of which older layouts carry. Reading by
# position against the wrong layout would subtract the wrong two timestamps and still print a
# plausible percentage, which is the failure this file exists to make impossible.
# What makes two rows the same frame. gfxinfo prints one PROFILEDATA block per window of the
# process and two windows can draw against a single display vsync, so FrameTimelineVsyncId is not an
# identity on its own: unioning on it alone discards one of two real frames and moves the published
# percentage. These five columns are written as a frame begins and not rewritten afterwards, so one
# frame polled twice carries identical values for all of them while two frames differ in at least
# one. The completion columns are deliberately absent, since they are exactly what changes between
# one poll and the next as a frame finishes.
IDENTITY_COLUMNS = (
    "FrameTimelineVsyncId",
    "IntendedVsync",
    "HandleInputStart",
    "PerformTraversalsStart",
    "DrawStart",
)

# What the value is computed from.
MEASURED_COLUMNS = (
    "Flags",
    "FrameDeadline",
    "SwapBuffers",
    "FrameCompleted",
    "GpuCompleted",
)

REQUIRED_COLUMNS = IDENTITY_COLUMNS + MEASURED_COLUMNS

# The platform exempts two of the four flag bits from its own jank accounting, SurfaceCanvas and
# SkippedFrame. WindowLayoutChanged (1 << 0) and RTAnimation (1 << 1) it counts, and they land in
# the `Janky frames` total this value is published beside, so exempting them here would take the
# numerator from one population and the denominator from another, in the direction that flatters
# the row. The frames dropped by this mask are counted and published for the same reason.
EXEMPT_FLAGS = (1 << 2) | (1 << 3)

NANOS_PER_MS = 1_000_000


class FramestatsError(Exception):
    """A dump this file cannot honestly compute the row from."""


def die(code: int, message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(code)


def parse_frames(text: str, source: str) -> tuple[list[dict[str, int]], int]:
    """Returns the frames of every PROFILEDATA block in one dump, plus the count of rows that did
    not parse.

    Raises FramestatsError naming the column when a block's header is missing one the row needs,
    rather than computing something wrong out of the columns that are there.

    A malformed row is counted and skipped instead of aborting the run: a dump read while the
    platform was writing it can end mid line, and one truncated row is not a reason to throw away
    the other few hundred. The count is reported so a dump that is mostly rubbish cannot pass for a
    clean one.
    """
    frames: list[dict[str, int]] = []
    malformed = 0
    in_block = False
    columns: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == PROFILEDATA_MARKER:
            # The marker both opens and closes a block, and gfxinfo prints one block per window, so
            # the sense simply alternates.
            in_block = not in_block
            columns = []
            continue
        if not in_block or not stripped:
            continue
        if not columns:
            columns = [name.strip() for name in stripped.split(",") if name.strip()]
            missing = [name for name in REQUIRED_COLUMNS if name not in columns]
            if missing:
                raise FramestatsError(
                    f"{source}: this framestats layout has no "
                    f"{', '.join(missing)} column, so the page turn row cannot be computed from it"
                )
            continue
        # Every row ends with a trailing comma, which split leaves as one empty field.
        fields = [field.strip() for field in stripped.split(",")]
        while fields and fields[-1] == "":
            fields.pop()
        if len(fields) != len(columns):
            malformed += 1
            continue
        row = dict(zip(columns, fields))
        try:
            frames.append({name: int(row[name]) for name in REQUIRED_COLUMNS})
        except ValueError:
            malformed += 1
    return frames, malformed


def parse_summary(text: str) -> dict[str, int] | None:
    """Returns the summary block's frame counts, or None when the dump carries no summary.

    These counters are cumulative since the last `gfxinfo reset`, which is what makes them useful
    here: they answer for the whole gesture window even though the PROFILEDATA rows beside them only
    reach back 120 frames.
    """
    rendered = None
    janky = None
    since = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("Stats since:"):
            # `Stats since: 2738568710872ns`, the reset, on the clock IntendedVsync is read from.
            # Optional: only unreported_frame reads it, and without it that exception is not made.
            try:
                since = int(stripped.split(":", 1)[1].strip().removesuffix("ns"))
            except ValueError:
                since = None
        elif stripped.startswith("Total frames rendered:"):
            try:
                rendered = int(stripped.split(":", 1)[1].strip())
            except ValueError:
                return None
        elif stripped.startswith("Janky frames:"):
            # `Janky frames (legacy):` does not match, which is the point of the colon in the
            # prefix. Both lines are present in every dump and only this one is wanted.
            # `Janky frames: 37 (8.53%)`. The count is parsed rather than the percent, so the value
            # published here is computed the same way the rest of this file computes one.
            try:
                janky = int(stripped.split(":", 1)[1].split("(", 1)[0].strip())
            except ValueError:
                return None
    if rendered is None or janky is None:
        return None
    return {"rendered": rendered, "janky": janky, "since": since}


def union_frames(frames: list[dict[str, int]]) -> list[dict[str, int]]:
    """Folds the frames of several overlapping dumps into one frame per IDENTITY_COLUMNS tuple.

    When the same frame turns up twice the copy with the larger FrameCompleted wins. That is not an
    arbitrary tie break. A frame still in flight when a dump was taken has its completion fields
    holding whatever the ring buffer's previous occupant left there, which is an older and therefore
    smaller timestamp, so the larger value is always the one belonging to this frame.
    """
    best: dict[tuple[int, ...], dict[str, int]] = {}
    for frame in frames:
        key = tuple(frame[name] for name in IDENTITY_COLUMNS)
        previous = best.get(key)
        if previous is None or frame["FrameCompleted"] > previous["FrameCompleted"]:
            best[key] = frame
    return [best[key] for key in sorted(best)]


def classify(frame: dict[str, int]) -> str:
    """Whether a frame is "exempt", "incomplete" or "counted" by the R40 row."""
    if frame["Flags"] & EXEMPT_FLAGS:
        # Only the bits the platform itself exempts, which is why EXEMPT_FLAGS is a mask rather
        # than a test for any flag at all. A frame the summary block counts is a frame this row
        # counts.
        return "exempt"
    total = frame["FrameCompleted"] - frame["IntendedVsync"]
    budget = frame["FrameDeadline"] - frame["IntendedVsync"]
    if total <= 0 or budget <= 0:
        # The frame had not finished when the dump was taken, so its completion timestamps are
        # still the previous frame's. A later poll carries the same frame filled in.
        return "incomplete"
    return "counted"


def unreported_frame(united: list[dict[str, int]], summary: dict[str, int] | None,
                     summary_frames: list[dict[str, int]]) -> dict[str, int] | None:
    """The one frame the summary could not have counted yet, when there is exactly one.

    The platform stamps a frame's FrameCompleted and only then adds it to the counters the summary
    block prints, and a dump read between the two lists the frame as finished while `Total frames
    rendered` leaves it out. On the gate AVDs that happened to the newest frame of about one dump in
    twenty, always by exactly one frame and always to the frame with nothing drawn after it in that
    dump, and when the closing dump was one of them the window read as one frame over-counted and
    R50's coverage guard ended the run on a window the polling had covered completely.

    So the newest frame of the dump the summary came from is set aside, as not yet finished when
    that dump was taken, under all of these conditions and no others: the union counts exactly one
    frame more than the summary says were rendered, the summary names the reset it counts from, no
    counted frame began before that reset, and the newest frame in the summary's own dump is
    finished there. An excess of more than one, or any frame from before the reset, is still the
    contradiction R50 exists to stop (dumps spanning a reset or two runs) and is left for the guard.
    """
    if summary is None or not summary_frames or summary.get("since") is None:
        return None
    counted = [frame for frame in united if classify(frame) == "counted"]
    if len(counted) != summary["rendered"] + 1:
        return None
    if any(frame["IntendedVsync"] < summary["since"] for frame in counted):
        return None
    newest = max(summary_frames, key=lambda frame: frame["IntendedVsync"])
    if classify(newest) != "counted":
        return None
    key = tuple(newest[name] for name in IDENTITY_COLUMNS)
    return next(frame for frame in united if tuple(frame[name] for name in IDENTITY_COLUMNS) == key)


def measure(frames: list[dict[str, int]], summary: dict[str, int] | None) -> dict:
    """Computes the R40 value and everything the method note has to carry beside it."""
    counted: list[tuple[int, int, int]] = []
    exempt = 0
    incomplete = 0
    for frame in frames:
        kind = classify(frame)
        if kind == "exempt":
            exempt += 1
            continue
        if kind == "incomplete":
            incomplete += 1
            continue
        total = frame["FrameCompleted"] - frame["IntendedVsync"]
        budget = frame["FrameDeadline"] - frame["IntendedVsync"]
        gpu_wait = frame["GpuCompleted"] - frame["SwapBuffers"] if frame["GpuCompleted"] else 0
        if gpu_wait < 0:
            # A GpuCompleted older than SwapBuffers is a stale slot rather than a negative wait, and
            # subtracting a negative would invent app time the frame never spent.
            gpu_wait = 0
        counted.append((total, gpu_wait, budget))

    app_janky = sum(1 for total, gpu_wait, budget in counted if total - gpu_wait > budget)
    profile_janky = sum(1 for total, _, budget in counted if total > budget)
    waits = [gpu_wait for _, gpu_wait, _ in counted]
    janky_waits = [gpu_wait for total, gpu_wait, budget in counted if total > budget]

    rendered = summary["rendered"] if summary else None
    result: dict = {
        "app_jank_pct": percent(app_janky, len(counted)),
        "profile_jank_pct": percent(profile_janky, len(counted)),
        "total_jank_pct": percent(summary["janky"], rendered) if summary else None,
        "median_gpu_wait_ms": median_ms(waits),
        "median_gpu_wait_janky_ms": median_ms(janky_waits),
        "frames_counted": len(counted),
        "frames_app_janky": app_janky,
        "frames_rendered": rendered,
        "frames_exempt": exempt,
        "frames_incomplete": incomplete,
    }
    # What the polling missed, which is the only honest way to say whether this percentage answers
    # for the whole gesture window or for the tail of it. The summary counts neither exempt nor
    # unfinished frames, so it is compared against the frames that were actually counted. The
    # difference is signed on purpose. More frames counted than the subject reports rendering cannot
    # happen inside one window once unreported_frame has set aside the one frame the summary may
    # not have counted yet, so it means the dumps span a `gfxinfo reset` or two runs, and clamping
    # it to zero would publish that as full coverage.
    result["frames_missed"] = rendered - len(counted) if rendered is not None else None
    return result


def percent(part: int, whole: int | None) -> float | None:
    if not whole:
        return None
    return round(100.0 * part / whole, 2)


def median_ms(values: list[int]) -> float | None:
    if not values:
        return None
    return round(statistics.median(values) / NANOS_PER_MS, 2)


def report(dumps: list[Path]) -> dict:
    """Parses every dump and returns the measurement, with the parse bookkeeping folded in."""
    frames: list[dict[str, int]] = []
    malformed = 0
    empty = 0
    summary: dict[str, int] | None = None
    summary_frames: list[dict[str, int]] = []
    for dump in dumps:
        text = dump.read_text(errors="replace")
        parsed, bad = parse_frames(text, dump.name)
        # A dump with no PROFILEDATA block at all, or one holding no row, is the layout failure the
        # by-name column check cannot see, because that check only runs once a header has been read.
        # A window destroyed, a backgrounded process or a truncated dump all land here, and twenty of
        # twenty one polls can arrive like this while the run still publishes a percentage over the
        # one that did not.
        if not parsed:
            empty += 1
        frames.extend(parsed)
        malformed += bad
        found = parse_summary(text)
        # The counters are cumulative since the reset, so the dump with the most frames behind it is
        # the one that answers for the whole window whatever order the files arrived in.
        if found is not None and (summary is None or found["rendered"] > summary["rendered"]):
            summary = found
            summary_frames = parsed
    united = union_frames(frames)
    unreported = unreported_frame(united, summary, summary_frames)
    result = measure([frame for frame in united if frame is not unreported], summary)
    if unreported is not None:
        result["frames_incomplete"] += 1
    result["dumps"] = len(dumps)
    result["rows_parsed"] = len(frames)
    result["rows_malformed"] = malformed
    result["dumps_empty"] = empty
    result["frames_unique"] = len(united)
    return result


SH_KEYS = (
    "app_jank_pct",
    "profile_jank_pct",
    "total_jank_pct",
    "median_gpu_wait_ms",
    "median_gpu_wait_janky_ms",
    "frames_counted",
    "frames_app_janky",
    "frames_rendered",
    "frames_missed",
    "frames_exempt",
    "frames_incomplete",
    "dumps",
    "dumps_empty",
    "rows_malformed",
)


def format_sh(result: dict) -> str:
    """`FRAMESTATS_NAME=value` lines for the bench scripts to read.

    Every value is a number or empty, so a caller never has to quote one, and the file this is
    written to is kept beside the fragment as the evidence for the number published.
    """
    lines = []
    for key in SH_KEYS:
        value = result.get(key)
        lines.append(f"FRAMESTATS_{key.upper()}={'' if value is None else value}")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="R40 page turn jank from dumpsys gfxinfo framestats dumps.")
    parser.add_argument("dumps", nargs="+", type=Path,
                        help="every framestats dump polled over one gesture window")
    parser.add_argument("--format", default="json", choices=["json", "sh"])
    args = parser.parse_args()

    for dump in args.dumps:
        if not dump.is_file():
            die(2, f"missing: {dump}")
    try:
        result = report(args.dumps)
    except FramestatsError as exc:
        die(2, str(exc))
    if not result["frames_counted"]:
        die(5, "the framestats dumps hold no finished frame, so there is nothing to compute the "
               "page turn row from")
    print(format_sh(result) if args.format == "sh" else json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
