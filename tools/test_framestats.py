#!/usr/bin/env python3
"""Unit tests for tools/bench/framestats.py, the R40 page turn value.

Run with: python3 -m unittest discover -s tools -p 'test_*.py'

Every input here is hand written in the shape `dumpsys gfxinfo <package> framestats` prints, which
is the only input the parser ever sees, so nothing needs a device or a real dump. The frames are
built out of a work time and a GPU wait rather than out of raw timestamps, because that is what the
ruling is about: a frame whose FrameCompleted lands after its deadline only because the GPU was
still busy is not a frame this application was late for, and the arithmetic that separates the two
is the thing worth testing.

The cases that matter, and why each one is here:
  - the R40 subtraction itself, on a dump holding one frame of each kind;
  - a GpuCompleted of 0, which is a frame with no GPU timestamp at all rather than a frame whose
    wait was zero length, and which must not be turned into a negative or a subtraction;
  - the union across two overlapping dumps, because the platform only ever returns the last 120
    frames and the bench therefore polls and hands over several dumps that share frames;
  - a missing column refused by name, because the column set differs between platform versions and
    reading the wrong two columns would still print a plausible percentage.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
FRAMESTATS_PATH = TOOLS / "bench" / "framestats.py"

_spec = importlib.util.spec_from_file_location("framestats", FRAMESTATS_PATH)
framestats = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = framestats
_spec.loader.exec_module(framestats)

MS = 1_000_000
MARKER = "---PROFILEDATA---"

# The ten columns the row needs: REQUIRED_COLUMNS, in an order convenient for the eye rather than
# the platform's. The real API 36 header carries 24, and one test below uses that full header to
# prove the lookup is by name, but a dump with only these is a legitimate input and keeps the other
# cases readable. HandleInputStart, PerformTraversalsStart and DrawStart are part of the identity
# tuple a frame is unioned on, not part of what the value is measured from, but the parser refuses a
# dump missing any REQUIRED_COLUMNS name so they have to be here too.
COLUMNS = [
    "Flags",
    "FrameTimelineVsyncId",
    "IntendedVsync",
    "HandleInputStart",
    "PerformTraversalsStart",
    "DrawStart",
    "FrameDeadline",
    "SwapBuffers",
    "GpuCompleted",
    "FrameCompleted",
]

# The header as API 36 writes it, in its own order, for the positional independence test.
API36_COLUMNS = [
    "Flags", "FrameTimelineVsyncId", "IntendedVsync", "Vsync", "InputEventId", "HandleInputStart",
    "AnimationStart", "PerformTraversalsStart", "DrawStart", "FrameDeadline", "FrameStartTime",
    "FrameInterval", "WorkloadTarget", "SyncQueued", "SyncStart", "IssueDrawCommandsStart",
    "SwapBuffers", "FrameCompleted", "DequeueBufferDuration", "QueueBufferDuration", "GpuCompleted",
    "SwapBuffersCompleted", "DisplayPresentTime", "CommandSubmissionCompleted",
]


def frame(vsync_id: int, work_ms: float, gpu_wait_ms: float, budget_ms: float = 16.67,
          flags: int = 0, gpu_completed: int | None = None,
          completed_ns: int | None = None) -> dict[str, int]:
    """One frame, described the way the ruling describes one.

    `work_ms` is everything from the intended vsync to SwapBuffers, which is the part this
    application is answerable for, and `gpu_wait_ms` is the SwapBuffers to GpuCompleted wait that
    R40 subtracts. FrameCompleted lands with GpuCompleted, which is what real dumps show for frames
    that wait on the GPU.
    """
    intended = 24_000_000_000_000 + vsync_id * 16 * MS
    swap = intended + round(work_ms * MS)
    gpu = swap + round(gpu_wait_ms * MS)
    return {
        "Flags": flags,
        "FrameTimelineVsyncId": vsync_id,
        "IntendedVsync": intended,
        "FrameDeadline": intended + round(budget_ms * MS),
        "SwapBuffers": swap,
        "GpuCompleted": gpu if gpu_completed is None else gpu_completed,
        "FrameCompleted": gpu if completed_ns is None else completed_ns,
        # Only the seven columns above are read, but the wider header needs values for the rest.
        "Vsync": intended,
        "InputEventId": 0,
        "HandleInputStart": intended,
        "AnimationStart": intended,
        "PerformTraversalsStart": intended,
        "DrawStart": intended,
        "FrameStartTime": intended,
        "FrameInterval": 16_666_666,
        "WorkloadTarget": 16_666_666,
        "SyncQueued": intended,
        "SyncStart": intended,
        "IssueDrawCommandsStart": intended,
        "DequeueBufferDuration": 2000,
        "QueueBufferDuration": 60000,
        "SwapBuffersCompleted": gpu,
        "DisplayPresentTime": 0,
        "CommandSubmissionCompleted": swap,
    }


def dump(frames: list[dict[str, int]], columns: list[str] | None = None,
         rendered: int | None = None, janky: int | None = None,
         extra_rows: list[str] = ()) -> str:
    """A framestats dump: the summary block when asked for one, then the PROFILEDATA block."""
    columns = columns or COLUMNS
    lines = []
    if rendered is not None:
        percent = 0.0 if not rendered else round(100.0 * (janky or 0) / rendered, 2)
        lines += [
            "** Graphics info for pid 11515 [com.shinsaikou] **",
            "",
            "Stats since: 23992727181816ns",
            f"Total frames rendered: {rendered}",
            f"Janky frames: {janky} ({percent}%)",
            "Janky frames (legacy): 0 (0.00%)",
            "Number Slow issue draw commands: 37",
        ]
    lines.append(MARKER)
    lines.append(",".join(columns) + ",")
    for row in frames:
        lines.append(",".join(str(row[name]) for name in columns) + ",")
    lines += list(extra_rows)
    lines.append(MARKER)
    lines.append("")
    lines.append("View hierarchy:")
    return "\n".join(lines) + "\n"


def other_window_frame(vsync_id: int, work_ms: float, gpu_wait_ms: float, budget_ms: float = 16.67,
                        flags: int = 0) -> dict[str, int]:
    """A frame from a second window drawing against the same display vsync as another window's.

    gfxinfo prints one PROFILEDATA block per window, and two windows can draw against one display
    vsync, so this shares FrameTimelineVsyncId with a frame built by `frame()` at the same vsync_id
    while carrying its own HandleInputStart, PerformTraversalsStart and DrawStart, the columns that
    tell the two frames apart under IDENTITY_COLUMNS.
    """
    built = frame(vsync_id, work_ms, gpu_wait_ms, budget_ms, flags)
    for name in ("HandleInputStart", "PerformTraversalsStart", "DrawStart"):
        built[name] += 1
    return built


def dump_windows(windows: list[list[dict[str, int]]], columns: list[str] | None = None,
                  rendered: int | None = None, janky: int | None = None) -> str:
    """A framestats dump carrying one PROFILEDATA block per window, the way gfxinfo prints one when
    a process has more than one window drawing.
    """
    columns = columns or COLUMNS
    lines = []
    if rendered is not None:
        percent = 0.0 if not rendered else round(100.0 * (janky or 0) / rendered, 2)
        lines += [
            "** Graphics info for pid 11515 [com.shinsaikou] **",
            "",
            "Stats since: 23992727181816ns",
            f"Total frames rendered: {rendered}",
            f"Janky frames: {janky} ({percent}%)",
            "Janky frames (legacy): 0 (0.00%)",
            "Number Slow issue draw commands: 37",
        ]
    for frames in windows:
        lines.append(MARKER)
        lines.append(",".join(columns) + ",")
        for row in frames:
            lines.append(",".join(str(row[name]) for name in columns) + ",")
        lines.append(MARKER)
    lines.append("")
    lines.append("View hierarchy:")
    return "\n".join(lines) + "\n"


def report(*texts: str) -> dict:
    """Runs the parser over dumps written to a temporary directory, as the bench hands them over."""
    with tempfile.TemporaryDirectory() as directory:
        paths = []
        for index, text in enumerate(texts):
            path = Path(directory) / f"poll-{index:04d}.txt"
            path.write_text(text)
            paths.append(path)
        return framestats.report(paths)


class R40ArithmeticTest(unittest.TestCase):

    def test_a_frame_late_only_because_of_the_gpu_wait_is_not_app_jank(self):
        # Four frames: on time; late by 17.0 ms but with only 2 ms of app work behind it; late with
        # 20 ms of app work behind it; on time.
        result = report(dump([
            frame(1, work_ms=2.0, gpu_wait_ms=4.0),
            frame(2, work_ms=2.0, gpu_wait_ms=15.0),
            frame(3, work_ms=20.0, gpu_wait_ms=1.0),
            frame(4, work_ms=1.0, gpu_wait_ms=1.0),
        ]))
        self.assertEqual(result["frames_counted"], 4)
        self.assertEqual(result["app_jank_pct"], 25.0)
        self.assertEqual(result["frames_app_janky"], 1)
        # The old rule called two of the four janky, and that number stays visible.
        self.assertEqual(result["profile_jank_pct"], 50.0)

    def test_the_median_gpu_wait_reported_is_the_one_on_the_frames_the_old_rule_called_janky(self):
        result = report(dump([
            frame(1, work_ms=2.0, gpu_wait_ms=4.0),
            frame(2, work_ms=2.0, gpu_wait_ms=15.0),
            frame(3, work_ms=20.0, gpu_wait_ms=1.0),
            frame(4, work_ms=1.0, gpu_wait_ms=1.0),
        ]))
        # Frames 2 and 3 are the janky ones, so their waits are the ones the subtraction removed.
        self.assertEqual(result["median_gpu_wait_janky_ms"], 8.0)
        self.assertEqual(result["median_gpu_wait_ms"], 2.5)

    def test_a_frame_inside_its_budget_on_both_rules_is_janky_on_neither(self):
        result = report(dump([frame(1, work_ms=5.0, gpu_wait_ms=5.0)]))
        self.assertEqual(result["app_jank_pct"], 0.0)
        self.assertEqual(result["profile_jank_pct"], 0.0)

    def test_the_budget_comes_from_the_frame_deadline_rather_than_from_a_fixed_16_ms(self):
        # A 120 Hz frame has half the budget, and the same 9 ms of work misses it.
        result = report(dump([frame(1, work_ms=9.0, gpu_wait_ms=0.5, budget_ms=8.33)]))
        self.assertEqual(result["app_jank_pct"], 100.0)

    def test_columns_are_found_by_name_and_not_by_position(self):
        result = report(dump([
            frame(1, work_ms=20.0, gpu_wait_ms=1.0),
            frame(2, work_ms=2.0, gpu_wait_ms=15.0),
        ], columns=API36_COLUMNS))
        self.assertEqual(result["frames_counted"], 2)
        self.assertEqual(result["app_jank_pct"], 50.0)

    def test_window_layout_changed_and_rt_animation_are_counted_not_exempted(self):
        # Flags 1 (WindowLayoutChanged, bit 0) and 2 (RTAnimation, bit 1) are two of the bits the
        # platform counts in its own `Janky frames` total, so this row has to count them too or the
        # numerator and the denominator answer for two different populations.
        result = report(dump([
            frame(1, work_ms=2.0, gpu_wait_ms=2.0, flags=1),
            frame(2, work_ms=30.0, gpu_wait_ms=1.0, flags=2),
        ]))
        self.assertEqual(result["frames_counted"], 2)
        self.assertEqual(result["frames_exempt"], 0)
        self.assertEqual(result["app_jank_pct"], 50.0)

    def test_surface_canvas_and_skipped_frame_are_exempted(self):
        # Flags 4 (SurfaceCanvas, bit 2) and 8 (SkippedFrame, bit 3) are the two bits the platform
        # itself exempts from its own accounting, so this row leaves them out too.
        result = report(dump([
            frame(1, work_ms=2.0, gpu_wait_ms=2.0, flags=4),
            frame(2, work_ms=30.0, gpu_wait_ms=1.0, flags=8),
        ]))
        self.assertEqual(result["frames_counted"], 0)
        self.assertEqual(result["frames_exempt"], 2)

    def test_the_exempt_check_is_a_bitwise_mask_and_not_an_equality_test(self):
        # Flags 5 is bit 0 (counted) and bit 2 (exempt) both set. A test that compared Flags against
        # the exempt constants by equality would count this frame, since 5 equals neither 4 nor 8.
        result = report(dump([frame(1, work_ms=30.0, gpu_wait_ms=1.0, flags=5)]))
        self.assertEqual(result["frames_counted"], 0)
        self.assertEqual(result["frames_exempt"], 1)


class GpuCompletedTest(unittest.TestCase):

    def test_a_gpu_completed_of_zero_is_no_wait_rather_than_a_subtraction(self):
        # The frame took 18 ms of application time and carries no GPU timestamp. Subtracting
        # 0 - SwapBuffers would hand the row a wait of about minus twenty four thousand seconds.
        result = report(dump([frame(1, work_ms=18.0, gpu_wait_ms=0.0, gpu_completed=0)]))
        self.assertEqual(result["app_jank_pct"], 100.0)
        self.assertEqual(result["median_gpu_wait_ms"], 0.0)

    def test_a_gpu_completed_older_than_swap_buffers_is_a_stale_slot_and_not_a_negative_wait(self):
        # A ring buffer slot the platform had not written yet: the timestamp belongs to an earlier
        # frame. Clamping to zero keeps it from inventing application time this frame never spent.
        stale = frame(1, work_ms=2.0, gpu_wait_ms=1.0)
        stale["GpuCompleted"] = stale["IntendedVsync"] - 5_000 * MS
        result = report(dump([stale]))
        self.assertEqual(result["median_gpu_wait_ms"], 0.0)
        self.assertEqual(result["app_jank_pct"], 0.0)

    def test_a_frame_still_in_flight_is_not_counted(self):
        # The last row of a live dump holds the previous frame's completion timestamps, so its total
        # comes out negative. Counting it would publish a percentage over a frame nobody drew.
        in_flight = frame(9, work_ms=2.0, gpu_wait_ms=1.0)
        in_flight["FrameCompleted"] = in_flight["IntendedVsync"] - 5_000 * MS
        result = report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0), in_flight]))
        self.assertEqual(result["frames_counted"], 1)
        self.assertEqual(result["frames_incomplete"], 1)


class UnionTest(unittest.TestCase):

    def test_two_windows_drawing_against_the_same_display_vsync_are_both_counted(self):
        # Two windows of the same process, each with its own PROFILEDATA block, drawing a frame
        # against the display vsync numbered 1 and another against the one numbered 2. Keying the
        # union on FrameTimelineVsyncId alone would fold window A's frame 2 and window B's frame 2
        # into one entry and silently drop the other, which is the defect IDENTITY_COLUMNS fixes.
        result = report(dump_windows([
            [
                frame(1, work_ms=2.0, gpu_wait_ms=1.0),
                frame(2, work_ms=20.0, gpu_wait_ms=1.0),
            ],
            [
                other_window_frame(1, work_ms=2.0, gpu_wait_ms=1.0),
                other_window_frame(2, work_ms=2.0, gpu_wait_ms=1.0),
            ],
        ]))
        self.assertEqual(result["rows_parsed"], 4)
        self.assertEqual(result["frames_unique"], 4)
        self.assertEqual(result["frames_counted"], 4)
        # Only window A's frame at vsync 2 is late.
        self.assertEqual(result["app_jank_pct"], 25.0)

    def test_a_frame_in_two_overlapping_dumps_is_counted_once(self):
        first = dump([
            frame(1, work_ms=2.0, gpu_wait_ms=1.0),
            frame(2, work_ms=2.0, gpu_wait_ms=1.0),
            frame(3, work_ms=20.0, gpu_wait_ms=1.0),
        ])
        second = dump([
            frame(2, work_ms=2.0, gpu_wait_ms=1.0),
            frame(3, work_ms=20.0, gpu_wait_ms=1.0),
            frame(4, work_ms=2.0, gpu_wait_ms=1.0),
        ])
        result = report(first, second)
        self.assertEqual(result["rows_parsed"], 6)
        self.assertEqual(result["frames_counted"], 4)
        # One app janky frame out of four, not two out of six.
        self.assertEqual(result["app_jank_pct"], 25.0)

    def test_the_finished_copy_of_a_frame_wins_over_the_copy_caught_in_flight(self):
        in_flight = frame(7, work_ms=20.0, gpu_wait_ms=1.0)
        in_flight["FrameCompleted"] = in_flight["IntendedVsync"] - 5_000 * MS
        result = report(dump([in_flight]), dump([frame(7, work_ms=20.0, gpu_wait_ms=1.0)]))
        self.assertEqual(result["frames_counted"], 1)
        self.assertEqual(result["frames_incomplete"], 0)
        self.assertEqual(result["app_jank_pct"], 100.0)


class SummaryTest(unittest.TestCase):

    def test_the_total_janky_percent_comes_from_the_summary_block(self):
        result = report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)], rendered=434, janky=37))
        self.assertEqual(result["total_jank_pct"], 8.53)
        self.assertEqual(result["frames_rendered"], 434)

    def test_the_summary_of_the_last_poll_answers_for_the_window(self):
        # The counters are cumulative since the reset, so the dump with the most frames behind it is
        # the one that covers the whole gesture window.
        early = dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)], rendered=100, janky=4)
        late = dump([frame(2, work_ms=2.0, gpu_wait_ms=1.0)], rendered=400, janky=40)
        for order in ((early, late), (late, early)):
            with self.subTest(order="early first" if order[0] is early else "late first"):
                result = report(*order)
                self.assertEqual(result["frames_rendered"], 400)
                self.assertEqual(result["total_jank_pct"], 10.0)

    def test_frames_the_polling_missed_are_reported_rather_than_left_out_silently(self):
        result = report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)], rendered=434, janky=37))
        self.assertEqual(result["frames_counted"], 1)
        self.assertEqual(result["frames_missed"], 433)

    def test_a_window_the_polling_kept_up_with_reports_no_gap(self):
        frames = [frame(index, work_ms=2.0, gpu_wait_ms=1.0) for index in range(1, 5)]
        result = report(dump(frames, rendered=4, janky=0))
        self.assertEqual(result["frames_missed"], 0)

    def test_a_dump_with_no_summary_block_reports_no_total(self):
        result = report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)]))
        self.assertIsNone(result["total_jank_pct"])
        self.assertIsNone(result["frames_missed"])

    def test_more_frames_counted_than_the_summary_says_were_rendered_goes_negative(self):
        # Two or more PROFILEDATA rows beyond `Total frames rendered` cannot happen inside one
        # window, so it means the dumps span a `gfxinfo reset` or two runs. Clamping this to zero
        # would publish that as full coverage instead of the contradiction it is. (One frame beyond
        # it can, and has its own tests below.)
        result = report(dump([
            frame(1, work_ms=2.0, gpu_wait_ms=1.0),
            frame(2, work_ms=2.0, gpu_wait_ms=1.0),
            frame(3, work_ms=2.0, gpu_wait_ms=1.0),
            frame(4, work_ms=2.0, gpu_wait_ms=1.0),
        ], rendered=2, janky=0))
        self.assertEqual(result["frames_counted"], 4)
        self.assertEqual(result["frames_missed"], -2)


class UnreportedFrameTest(unittest.TestCase):
    """The platform stamps FrameCompleted before it counts the frame, and a dump read in between
    lists the newest frame as finished while the summary leaves it out. Seen on the gate AVDs in
    about one dump in twenty, always by exactly one frame, always the newest.
    """

    def test_the_closing_dumps_newest_frame_is_set_aside_when_the_summary_lags_it(self):
        early = dump(
            [frame(1, work_ms=2.0, gpu_wait_ms=1.0), frame(2, work_ms=2.0, gpu_wait_ms=1.0)],
            rendered=2, janky=0,
        )
        closing = dump([
            frame(2, work_ms=2.0, gpu_wait_ms=1.0),
            frame(3, work_ms=2.0, gpu_wait_ms=1.0),
            frame(4, work_ms=30.0, gpu_wait_ms=1.0),
        ], rendered=3, janky=0)
        result = report(early, closing)
        self.assertEqual(result["frames_counted"], 3)
        self.assertEqual(result["frames_missed"], 0)
        self.assertEqual(result["frames_incomplete"], 1)
        # The frame set aside is the newest one, and nothing it did reaches the value: frame 4 was
        # late on the app's own work and would otherwise have been the one janky frame.
        self.assertEqual(result["frames_app_janky"], 0)

    def test_an_older_extra_frame_from_before_the_reset_is_left_for_the_guard(self):
        # One frame too many, but it began before `Stats since`, so the dumps span a reset and the
        # newest frame is not the one to blame.
        result = report(dump([
            frame(-600, work_ms=2.0, gpu_wait_ms=1.0),
            frame(1, work_ms=2.0, gpu_wait_ms=1.0),
            frame(2, work_ms=2.0, gpu_wait_ms=1.0),
        ], rendered=2, janky=0))
        self.assertEqual(result["frames_counted"], 3)
        self.assertEqual(result["frames_missed"], -1)
        self.assertEqual(result["frames_incomplete"], 0)

    def test_a_summary_without_its_reset_time_makes_no_exception(self):
        text = dump(
            [frame(1, work_ms=2.0, gpu_wait_ms=1.0), frame(2, work_ms=2.0, gpu_wait_ms=1.0)],
            rendered=1, janky=0,
        ).replace("Stats since: 23992727181816ns\n", "")
        self.assertNotIn("Stats since", text)
        result = report(text)
        self.assertEqual(result["frames_missed"], -1)

    def test_a_newest_frame_still_in_flight_in_the_closing_dump_makes_no_exception(self):
        # The closing dump's newest slot is unfinished, so it is not the frame the summary lags on
        # and the extra counted frame has to have come from somewhere else.
        stale = frame(3, work_ms=2.0, gpu_wait_ms=1.0)
        stale["FrameCompleted"] = stale["IntendedVsync"] - MS
        result = report(dump([
            frame(1, work_ms=2.0, gpu_wait_ms=1.0),
            frame(2, work_ms=2.0, gpu_wait_ms=1.0),
            stale,
        ], rendered=1, janky=0))
        self.assertEqual(result["frames_missed"], -1)

    def test_the_summary_parser_reads_the_reset_time(self):
        parsed = framestats.parse_summary(dump([], rendered=4, janky=1))
        self.assertEqual(parsed["since"], 23992727181816)


class DumpsEmptyTest(unittest.TestCase):

    def test_a_dump_with_no_profiledata_block_at_all_counts_as_empty(self):
        no_block = "** Graphics info for pid 11515 [com.shinsaikou] **\n\nView hierarchy:\n"
        result = report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)]), no_block)
        self.assertEqual(result["dumps"], 2)
        self.assertEqual(result["dumps_empty"], 1)
        self.assertEqual(result["frames_counted"], 1)

    def test_a_dump_whose_profiledata_block_holds_no_row_counts_as_empty(self):
        empty_block = dump([])
        result = report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)]), empty_block)
        self.assertEqual(result["dumps"], 2)
        self.assertEqual(result["dumps_empty"], 1)
        self.assertEqual(result["frames_counted"], 1)

    def test_dumps_empty_reaches_the_sh_output(self):
        with tempfile.TemporaryDirectory() as directory:
            has_block = Path(directory) / "has-block.txt"
            has_block.write_text(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)]))
            no_block = Path(directory) / "no-block.txt"
            no_block.write_text("** Graphics info for pid 11515 [com.shinsaikou] **\n\nView hierarchy:\n")
            outcome = subprocess.run(
                [sys.executable, str(FRAMESTATS_PATH), "--format", "sh", str(has_block),
                 str(no_block)],
                capture_output=True, text=True,
            )
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        values = dict(line.split("=", 1) for line in outcome.stdout.splitlines())
        self.assertEqual(values["FRAMESTATS_DUMPS_EMPTY"], "1")


class RefusalTest(unittest.TestCase):

    def test_a_missing_column_is_refused_by_name(self):
        columns = [name for name in COLUMNS if name != "FrameDeadline"]
        with self.assertRaises(framestats.FramestatsError) as raised:
            report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)], columns=columns))
        self.assertIn("FrameDeadline", str(raised.exception))

    def test_an_older_layout_without_the_frame_timeline_id_is_refused_by_name(self):
        columns = [name for name in COLUMNS if name != "FrameTimelineVsyncId"]
        with self.assertRaises(framestats.FramestatsError) as raised:
            report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)], columns=columns))
        self.assertIn("FrameTimelineVsyncId", str(raised.exception))

    def test_a_truncated_row_is_counted_rather_than_thrown_away_quietly(self):
        result = report(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)],
                             extra_rows=["0,4,240000000,2400"]))
        self.assertEqual(result["frames_counted"], 1)
        self.assertEqual(result["rows_malformed"], 1)


class CommandLineTest(unittest.TestCase):

    def run_cli(self, text: str, *arguments: str) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "framestats.txt"
            path.write_text(text)
            return subprocess.run(
                [sys.executable, str(FRAMESTATS_PATH), *arguments, str(path)],
                capture_output=True, text=True,
            )

    def test_the_sh_format_is_numbers_a_shell_can_read_without_quoting(self):
        outcome = self.run_cli(
            dump([frame(1, work_ms=20.0, gpu_wait_ms=1.0)], rendered=10, janky=1), "--format", "sh")
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        values = dict(line.split("=", 1) for line in outcome.stdout.splitlines())
        self.assertEqual(values["FRAMESTATS_APP_JANK_PCT"], "100.0")
        self.assertEqual(values["FRAMESTATS_TOTAL_JANK_PCT"], "10.0")
        self.assertEqual(values["FRAMESTATS_FRAMES_COUNTED"], "1")
        self.assertEqual(values["FRAMESTATS_FRAMES_MISSED"], "9")
        for name, value in values.items():
            self.assertTrue(name.startswith("FRAMESTATS_"), name)
            self.assertNotIn(" ", value, f"{name} needs quoting")

    def test_a_dump_with_no_finished_frame_fails_rather_than_publishing_a_percentage(self):
        in_flight = frame(1, work_ms=2.0, gpu_wait_ms=1.0)
        in_flight["FrameCompleted"] = in_flight["IntendedVsync"] - 5_000 * MS
        outcome = self.run_cli(dump([in_flight]))
        self.assertEqual(outcome.returncode, 5)
        self.assertIn("no finished frame", outcome.stderr)

    def test_a_missing_column_leaves_the_command_line_with_nothing_to_publish(self):
        columns = [name for name in COLUMNS if name != "GpuCompleted"]
        outcome = self.run_cli(dump([frame(1, work_ms=2.0, gpu_wait_ms=1.0)], columns=columns))
        self.assertEqual(outcome.returncode, 2)
        self.assertIn("GpuCompleted", outcome.stderr)


def extract_functions(source: str, names: tuple[str, ...]) -> str:
    """Pulls whole shell functions out of common.sh by text, from a `name() {` line through the next
    line that is exactly `}` at column zero.

    common.sh cannot be sourced without a device: it calls require_env and makes adb calls while it
    is being sourced. The two functions below depend only on their arguments, python3 and a handful
    of variables this test sets, so they run correctly on their own. This mirrors the extraction
    idiom in tools/test_bench_launch.py. A scan that stops matching must not pass vacuously, so a
    function it cannot find is an error rather than an empty string.
    """
    lines = source.splitlines()
    extracted: list[str] = []
    for name in names:
        start = next((index for index, line in enumerate(lines)
                      if line.startswith(f"{name}() {{")), None)
        if start is None:
            raise AssertionError(f"common.sh has no {name} function")
        end = next((index for index in range(start + 1, len(lines)) if lines[index] == "}"), None)
        if end is None:
            raise AssertionError(f"{name} in common.sh has no closing brace at column zero")
        extracted.extend(lines[start:end + 1])
    return "\n".join(extracted) + "\n"


class ShellSeamTest(unittest.TestCase):
    """The seam between this parser and the bench scripts.

    framestats_read reads the `--format sh` lines into shell variables and framestats_coverage turns
    the counts into the sentence that goes in the fragment's method note. A rename on either side of
    that seam leaves both halves working and the fragment carrying an empty number, so the two are
    exercised here against dumps that overlap the way polled ones do.
    """

    def test_the_bench_helpers_read_the_value_and_the_coverage_out_of_the_polled_dumps(self):
        common = (TOOLS / "bench" / "common.sh").read_text()
        functions = extract_functions(
            common, ("framestats_read", "framestats_coverage", "framestats_janky_wait"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "polls").mkdir()
            (root / "out").mkdir()
            (root / "functions.sh").write_text(functions)
            # Two polls and the closing dump, the middle frames shared by all three.
            (root / "polls" / "poll-0001.txt").write_text(dump([
                frame(1, work_ms=2.0, gpu_wait_ms=1.0),
                frame(2, work_ms=2.0, gpu_wait_ms=15.0),
            ], rendered=2, janky=1))
            (root / "polls" / "poll-0002.txt").write_text(dump([
                frame(2, work_ms=2.0, gpu_wait_ms=15.0),
                frame(3, work_ms=20.0, gpu_wait_ms=1.0),
            ], rendered=3, janky=2))
            (root / "final.txt").write_text(dump([
                frame(3, work_ms=20.0, gpu_wait_ms=1.0),
                frame(4, work_ms=2.0, gpu_wait_ms=1.0),
            ], rendered=4, janky=2))
            script = (
                "set -euo pipefail\n"
                f"BENCH_HERE={TOOLS / 'bench'}\n"
                f"SAIKOU_BENCH_OUT={root / 'out'}\n"
                "SAIKOU_DEVICE_ID=test_device\n"
                f"FRAMESTATS_DIR={root / 'polls'}\n"
                f"source {root / 'functions.sh'}\n"
                f"framestats_read page_turn {root / 'final.txt'}\n"
                'echo "value=$FRAMESTATS_APP_JANK_PCT"\n'
                'echo "total=$FRAMESTATS_TOTAL_JANK_PCT"\n'
                'echo "wait=$FRAMESTATS_MEDIAN_GPU_WAIT_JANKY_MS"\n'
                'echo "clause=$(framestats_janky_wait)"\n'
                'echo "coverage=$(framestats_coverage)"\n'
                # The window with nothing janky in it, which is the shape the note has to survive
                # once the reader is under the threshold on both rules.
                "FRAMESTATS_MEDIAN_GPU_WAIT_JANKY_MS=\n"
                'echo "empty=$(framestats_janky_wait)"\n'
            )
            outcome = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        printed = dict(line.split("=", 1) for line in outcome.stdout.splitlines())
        # Four frames after the union, one of them late for reasons of its own.
        self.assertEqual(printed["value"], "25.0")
        self.assertEqual(printed["total"], "50.0")
        self.assertEqual(printed["wait"], "8.0")
        self.assertEqual(printed["clause"], "8.0 ms on the frames the total rule called janky")
        self.assertEqual(printed["empty"], "no frame janky by the total rule to measure one on")
        self.assertIn("4 frames counted over 3 polled dumps", printed["coverage"])
        self.assertNotIn("never seen by a poll", printed["coverage"])

    def test_the_coverage_sentence_names_an_unfinished_frame_with_its_count(self):
        common = (TOOLS / "bench" / "common.sh").read_text()
        functions = extract_functions(
            common, ("framestats_read", "framestats_coverage", "framestats_janky_wait"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "polls").mkdir()
            (root / "out").mkdir()
            (root / "functions.sh").write_text(functions)
            # frame 2's FrameCompleted has not advanced past its IntendedVsync, the shape a dump
            # taken mid frame leaves behind, so it is unfinished on both the poll and the final dump.
            unfinished = frame(2, work_ms=2.0, gpu_wait_ms=1.0)
            unfinished["FrameCompleted"] = unfinished["IntendedVsync"]
            (root / "polls" / "poll-0001.txt").write_text(dump([
                frame(1, work_ms=2.0, gpu_wait_ms=1.0),
                unfinished,
            ]))
            (root / "final.txt").write_text(dump([
                unfinished,
                frame(3, work_ms=2.0, gpu_wait_ms=1.0),
            ], rendered=2, janky=0))
            script = (
                "set -euo pipefail\n"
                f"BENCH_HERE={TOOLS / 'bench'}\n"
                f"SAIKOU_BENCH_OUT={root / 'out'}\n"
                "SAIKOU_DEVICE_ID=test_device\n"
                f"FRAMESTATS_DIR={root / 'polls'}\n"
                f"source {root / 'functions.sh'}\n"
                f"framestats_read page_turn {root / 'final.txt'}\n"
                'echo "coverage=$(framestats_coverage)"\n'
            )
            outcome = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        printed = dict(line.split("=", 1) for line in outcome.stdout.splitlines())
        self.assertIn("2 frames counted over 2 polled dumps", printed["coverage"])
        self.assertIn("1 frame(s) unfinished when the dump was taken", printed["coverage"])

    def test_an_unfinished_frame_is_named_even_when_it_is_the_only_exclusion(self):
        # frames_missed at 0, no empty dump, no malformed row and no exempt frame: every other
        # clause in framestats_coverage is silent here, so only the unfinished frame clause can be
        # the reason this sentence says anything beyond the base count. Deleting that clause would
        # leave this test the only one able to catch it, since the other two tests here also have a
        # frames_missed value of 0 for the same reason gfxinfo's own summary never counts an
        # unfinished frame either.
        common = (TOOLS / "bench" / "common.sh").read_text()
        functions = extract_functions(
            common, ("framestats_read", "framestats_coverage", "framestats_janky_wait"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "polls").mkdir()
            (root / "out").mkdir()
            (root / "functions.sh").write_text(functions)
            unfinished = frame(2, work_ms=2.0, gpu_wait_ms=1.0)
            unfinished["FrameCompleted"] = unfinished["IntendedVsync"]
            (root / "final.txt").write_text(dump([
                frame(1, work_ms=2.0, gpu_wait_ms=1.0),
                unfinished,
            ], rendered=1, janky=0))
            script = (
                "set -euo pipefail\n"
                f"BENCH_HERE={TOOLS / 'bench'}\n"
                f"SAIKOU_BENCH_OUT={root / 'out'}\n"
                "SAIKOU_DEVICE_ID=test_device\n"
                f"FRAMESTATS_DIR={root / 'polls'}\n"
                f"source {root / 'functions.sh'}\n"
                f"framestats_read page_turn {root / 'final.txt'}\n"
                'echo "missed=$FRAMESTATS_FRAMES_MISSED"\n'
                'echo "dumps_empty=$FRAMESTATS_DUMPS_EMPTY"\n'
                'echo "rows_malformed=$FRAMESTATS_ROWS_MALFORMED"\n'
                'echo "frames_exempt=$FRAMESTATS_FRAMES_EXEMPT"\n'
                'echo "coverage=$(framestats_coverage)"\n'
            )
            outcome = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        printed = dict(line.split("=", 1) for line in outcome.stdout.splitlines())
        self.assertEqual(printed["missed"], "0")
        self.assertEqual(printed["dumps_empty"], "0")
        self.assertEqual(printed["rows_malformed"], "0")
        self.assertEqual(printed["frames_exempt"], "0")
        self.assertIn("1 frame(s) unfinished when the dump was taken", printed["coverage"])

    def test_the_coverage_sentence_omits_the_unfinished_phrase_when_every_frame_finished(self):
        common = (TOOLS / "bench" / "common.sh").read_text()
        functions = extract_functions(
            common, ("framestats_read", "framestats_coverage", "framestats_janky_wait"))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "polls").mkdir()
            (root / "out").mkdir()
            (root / "functions.sh").write_text(functions)
            (root / "final.txt").write_text(dump([
                frame(1, work_ms=2.0, gpu_wait_ms=1.0),
                frame(2, work_ms=20.0, gpu_wait_ms=1.0),
            ], rendered=2, janky=1))
            script = (
                "set -euo pipefail\n"
                f"BENCH_HERE={TOOLS / 'bench'}\n"
                f"SAIKOU_BENCH_OUT={root / 'out'}\n"
                "SAIKOU_DEVICE_ID=test_device\n"
                f"FRAMESTATS_DIR={root / 'polls'}\n"
                f"source {root / 'functions.sh'}\n"
                f"framestats_read page_turn {root / 'final.txt'}\n"
                'echo "coverage=$(framestats_coverage)"\n'
            )
            outcome = subprocess.run(["bash", "-c", script], capture_output=True, text=True)
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        printed = dict(line.split("=", 1) for line in outcome.stdout.splitlines())
        self.assertNotIn("unfinished when the dump was taken", printed["coverage"])


class CoverageGuardTest(unittest.TestCase):
    """Ruling R50, under R36: a window the polls did not fully see ends the run.

    The R40 figure is janky frames over frames counted, so a poll that fell behind shrinks the
    denominator and reads as a better number. assert_frames_covered turns any gap into exit 3, and
    the two bench scripts that publish the figure call it straight after framestats_read.
    """

    def run_guard(self, missed: str | None) -> subprocess.CompletedProcess:
        common = (TOOLS / "bench" / "common.sh").read_text()
        functions = extract_functions(common, ("assert_frames_covered",))
        script = "set -euo pipefail\n" + functions
        if missed is not None:
            script += f"FRAMESTATS_FRAMES_MISSED={missed}\n"
        script += "FRAMESTATS_FRAMES_COUNTED=440\nFRAMESTATS_FRAMES_RENDERED=449\n"
        script += "assert_frames_covered\necho covered\n"
        return subprocess.run(["bash", "-c", script], capture_output=True, text=True)

    def test_a_window_every_poll_kept_up_with_carries_on(self):
        outcome = self.run_guard("0")
        self.assertEqual(outcome.returncode, 0, outcome.stderr)
        self.assertEqual(outcome.stdout.strip(), "covered")

    def test_frames_no_poll_saw_end_the_run(self):
        outcome = self.run_guard("9")
        self.assertEqual(outcome.returncode, 3)
        self.assertNotIn("covered", outcome.stdout)
        self.assertIn("440 frame(s) counted against 449 rendered", outcome.stderr)

    def test_more_frames_counted_than_rendered_ends_the_run(self):
        outcome = self.run_guard("-1")
        self.assertEqual(outcome.returncode, 3)

    def test_a_window_with_no_rendered_count_to_compare_against_ends_the_run(self):
        self.assertEqual(self.run_guard("").returncode, 3)
        self.assertEqual(self.run_guard(None).returncode, 3)

    def test_both_scripts_that_publish_the_r40_figure_guard_it(self):
        # The scripts need a device to run, so the honest limit here is structural, the way
        # test_bench_launch.py pins coldstart.sh's guard: the call is present and comes after the
        # parse whose counts it reads, and the published value is the R40 figure.
        for name in ("page_turn.sh", "webtoon_scroll.sh"):
            source = (TOOLS / "bench" / name).read_text()
            with self.subTest(script=name):
                self.assertIn("framestats_read ", source)
                self.assertIn("\nassert_frames_covered\n", source)
                self.assertLess(source.index("framestats_read "),
                                source.index("\nassert_frames_covered\n"))
                self.assertIn("value=${FRAMESTATS_APP_JANK_PCT:-}", source)

    def test_the_webtoon_rows_never_publish_the_perfetto_figure(self):
        # Ruling R50: the Perfetto frame timeline counts the GPU wait, so once a trace processor is
        # installed a substitution would put the webtoon rows back on the definition R40 retired.
        source = (TOOLS / "bench" / "webtoon_scroll.sh").read_text()
        self.assertNotIn("value=$PERFETTO_VALUE", source)
        self.assertNotIn("value=$perfetto_value", source)
        self.assertNotIn("SAIKOU_JANK_SOURCE", source)


if __name__ == "__main__":
    unittest.main()
