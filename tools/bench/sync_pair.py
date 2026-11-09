#!/usr/bin/env python3
"""The two-device sync suite (phase 3 unit I of design/plans/phase3-start.md, Q11).

Drives com.shinsaikou.sync.SyncPairTest, one step per `am instrument`, on two AVDs at once: A is
saikou_pixel9_4g (API 36) and B is saikou_api26_2g (API 26). A scenario is a list of steps, each on one
device, run in the order written, so the two devices' steps interleave as two real devices' would.
This is the only writer of the scenarios' rows.

What one run does, in order:

  1. removes from --out every row fragment the chosen scenarios write, before anything else, so a
     fragment an earlier run left never survives this one; then checks each serial is the AVD it has
     to be (`adb emu avd name`), and refuses any other device: a phone, a tablet or another AVD never
     receives an install from here;
  2. starts D's fixture, tools/bench/sync_fixture_server.py, on --port (18795, the gate's port) with a
     WebDAV user and password generated for this run, which reach the devices only as instrumentation
     arguments and are never written to a file; it stops the fixture on exit, as gate.sh's
     start_fixture_server does (R83 carry-over 2), and a taken port ends the run before any install;
  3. installs the debug app and its test APK once on each device (`adb install -r`, never a connected
     Gradle task, which uninstalls the app after one run);
  4. for each scenario: resets the fixture through its control path (only this script and the
     fixture's own tests call it, R83 item 2), clears the app's data on both devices (`pm clear`),
     and runs the scenario's steps. Each step goes through the same check as gate.sh's
     instrument_one: `am instrument -w -r` exits 0 whatever happened, so the run passes only when
     its output ends in `OK (1 test)`; zero tests is a failure. A step's result line
     (`INSTRUMENTATION_STATUS: saikou.sync.pair.result=...`, key=value pairs) must be there, name the
     step, show no row still latched by an apply (`latched=0`), and meet the step's expectations.
     When the last step has passed on both devices, the two devices' last digests must be equal, and
     only then is the row written: one host fragment, `<row>.any.json` in --out, in
     tools/bench/common.sh's fragment() shape, the way library_update_rows.sh writes its rows;
  5. a scenario that fails ends the run: nothing is written for it or for any scenario after it, so
     no fragment of a failed scenario is in --out;
  6. uninstalls both packages from both devices, whatever happened (as gate.sh step 5f does).

killed_mid_apply writes no row: it passes or it ends the run. It stops A's process twice while A
pulls, once part way through a full export of several parts and once inside a segment's
transaction, and each time checks that the next process finds the same library, raises no latch,
and that a third sync changes nothing. A kill step (KILL_STEPS) is started without waiting and
never ends on its own: the test prints one `saikou.sync.pair.kill=pid=<pid> ...` status line when
the process is where it should be stopped, and this script then stops it with
`run-as com.shinsaikou kill -9 <pid>` (R89 carry-over 3: `am kill` reads as a force stop on API 36). The
step writes R73's outcome file first, /data/local/tmp/saikou-force-stop/killed_mid_apply.json, which
this script removes before the step and pulls after it, and which the next process's `after-kill`
step reads. After the export's kill, the fixture's log must show no part at or below the mark the
kill left read again, and every part above it read.

Every step's raw instrumentation output is kept in --work, one file per step, with the fixture's
request log. Nothing written there carries the password.

--backend drive (phase 3 unit H) runs the same scenarios over the fixture's Google Drive instead of
its WebDAV, as evidence and never as a row: every step takes the fixture's base address as
`saikou.google.base` and `saikou.sync.pair.backend drive`, so the sign-in steps take a code from the
fixture's authorization endpoint and sign in with DriveAccount, and nothing in --out is removed or
written; each scenario's method note goes to its folder in --work instead. It runs every
scenario with a row: killed_mid_apply's check reads WebDAV part paths from the fixture's log, so
it is refused over Drive.

new_entry_backlog (phase 4 unit NB) is in NAMED_ONLY: it runs only when --scenario names it, so the
gate's run (step 5g names none) is unchanged, and it writes no row. A adds an entry of 100 chapters
from a fixture source the test APK serves, both devices sync, and B's digest after its pull must
equal A's; then B's manual library update, against the source listing 101, must queue exactly the
new chapter. It runs over WebDAV only.

manual_sort_pair (phase 4 device coverage unit DC2, item 2) is also NAMED_ONLY and writes no row. A
drops the pair library's "Pair reading" category into the reverse of the seed's own order (the
write `SetManualLibraryOrder.await` itself makes, not a UI drag), both devices sync, and B reads the
category back in that same dropped order. The order is reported by each entry's seed index rather
than its database id, so the expectation below is one fixed string on every run. WebDAV only.

subcategory_race, subcategory_parent_deleted and subcategory_restore_join (phase 5 unit CT, item 8)
write the rows sync_subcategory_race_one_tree, sync_subcategory_orphan_top_level and
sync_subcategory_restore_keeps_tree (phase 5 unit TG3, item 3; CT kept them named only); each
ends on tree-check on both devices, which reports the
tree the screens show (`tree`, `parent>name` for a child, names joined by `|`, spaces as `_`) and
how many categories store a parent (`nested`). In the race, A nests "Pair reading" under "Pair old
name" and B nests "Pair old name" under "Pair reading" before either syncs; both store the merged
cycle and show one tree, the member with the lower category sync key at the top (TREE_AFTER_RACE).
In the second, A deletes "Pair old name" and B then nests "Pair reading" under it; "Pair reading"
ends top-level on both. In the third, A nests "Pair reading" under "Pair old name" and exports; B,
cleared, restores a backup of the three categories, flat, then signs in; the restore's flat
categories must not flatten A's tree on either device (item 7).

restore_then_join (phase 5 unit TG3, item 1) writes the row sync_restore_join_keeps_progress. A seeds
the pair library, reads further on six of its entries (another chapter read, another bookmark, a
higher page) and signs in; B, cleared, restores a backup built from fixture rows of those six entries,
with B's own reads, bookmarks and pages, and of two entries A does not hold, then signs in. After A
syncs both hold every chapter of both, read wherever either read it, on the higher page, with both
bookmarks, and a further sync on each writes nothing. A restore's chapters have no change-log row
(phase 5 unit SC2), so only B's full export can carry them to A.

edit_details_race (phase 5 unit ED, R138 item 2; R165 item 4) writes the row sync_edit_details_race.
A edits the pair library's scenario entry's title, description, author, artist, status and genres
as the Edit details dialog saves them; B, offline, refreshes the same entry from a fixture source
whose title has changed (twice, the title first and then a new description, so B's version passes
A's one edit). After A, B and A sync, both show A's edit with B's new source title and description
under it; then A resets the edit, and after A and B sync both show the source's details and no
edit. Each details step reports the shown and
stored texts with spaces as `_` (EDITED_* and the other texts below are SyncPairEditDetails').

Adding a scenario: write its steps in SyncPairTest (a `when` branch each), add a Scenario to
SCENARIOS below with its row, and add the row to rows.json. Each scenario starts from JOIN: both
devices cleared, A seeds the library and signs in (its first run exports it), B signs in (its first
run pulls it), and A syncs once more, so both hold the same library and every file the other wrote.

Usage: sync_pair.py [--serial-4g SERIAL] [--serial-api26 SERIAL] [--scenario NAME ...] [--out DIR]
           [--work DIR] [--apk APK] [--test-apk APK] [--port N] [--adb ADB] [--backend webdav|drive]
The serials default to SAIKOU_SERIAL_4G and SAIKOU_SERIAL_API26, adb to the SDK's
(ANDROID_SDK_ROOT or ANDROID_HOME).
Exit codes: 0 every scenario passed and its row is written (over Drive: its note); 1 a scenario failed (named on stderr);
2 usage or a missing input (an APK, a serial, adb) or a device that is not the AVD it must be;
3 the fixture could not start, or an install failed. Python 3.11+, standard library only.
"""

from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
import queue
import re
import secrets
import signal
import subprocess
import sys
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

PASSED, FAILED, BAD_INPUT, NOT_STARTED = 0, 1, 2, 3

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.shinsaikou"
TEST_PACKAGE = f"{PACKAGE}.test"
PAIR_TEST = "com.shinsaikou.sync.SyncPairTest#step"
STEP_ARGUMENT = "saikou.sync.pair.step"
RESULT_KEY = "saikou.sync.pair.result"
BACKEND_ARGUMENT = "saikou.sync.pair.backend"
GOOGLE_BASE_ARGUMENT = "saikou.google.base"
WEBDAV, DRIVE = "webdav", "drive"
FIXTURE = ROOT / "tools" / "bench" / "sync_fixture_server.py"
PORT = 18795
# The emulators reach the host's loopback as 10.0.2.2; the fixture's WebDAV lives under /dav/.
DEVICE_HOST = "10.0.2.2"
FIXTURE_READY_S = 10
STEP_TIMEOUT_S = 600
ROW_DEVICE = "any"
# The steps that sign in to the fixture, and so take its address, user and password as arguments.
SIGN_IN_STEPS = ("sign-in", "kill-in-export", "sync-3000")
# The steps that stop A's process part way, and what they print when it is time.
KILL_STEPS = ("kill-in-export", "kill-in-segment")
KILL_KEY = "saikou.sync.pair.kill"
KILL_OUTCOME = "/data/local/tmp/saikou-force-stop/killed_mid_apply.json"
# How long a kill step may take to say it is ready, and to end once its process is stopped.
KILL_READY_S = 420
KILL_END_S = 60

# `am instrument -r` ends a passing run with this line; a count of zero is a filter that matched
# nothing, which is a failure (gate.sh's instrument_one).
OK_LINE = re.compile(r"^OK \(([1-9][0-9]*) tests?\)\s*$", re.M)
RESULT_LINE = re.compile(rf"^INSTRUMENTATION_STATUS: {re.escape(RESULT_KEY)}=(.*)$", re.M)
KILL_LINE = re.compile(rf"^INSTRUMENTATION_STATUS: {re.escape(KILL_KEY)}=(.*)$")
# An export part's path in the fixture's log: its device, export id and number.
PART_PATH = re.compile(r"/devices/([^/]+)/full-([^/]+)/part-(\d+)\.jsonl\.gz$")

# How long the export's check waits for the fixture to write the lines of reads it already answered.
LOG_WAIT_S = 5


@dataclass(frozen=True)
class Device:
    role: str  # "a" or "b", as the scenarios name them
    avd: str
    device_id: str  # the gate's name for it (rows.json, baseline.json)


DEVICES = {
    "a": Device("a", "saikou_pixel9_4g", "pixel9_4g"),
    "b": Device("b", "saikou_api26_2g", "api26_2g"),
}

CLEAR = "pm-clear"


@dataclass(frozen=True)
class Step:
    """One thing on one device: a SyncPairTest step, or CLEAR, the app's data cleared from the host.

    expect: values the step's result line must carry, compared as text; at_least: values it must
    carry of at least that number. save and same_as keep a digest under a label and require a later
    step's digest to equal it.
    """
    device: str
    name: str
    expect: dict[str, str] = field(default_factory=dict)
    save: str | None = None
    same_as: str | None = None
    at_least: dict[str, int] = field(default_factory=dict)


@dataclass(frozen=True)
class Scenario:
    name: str
    row: str | None  # None: the scenario writes no row, and passes or ends the run
    steps: tuple[Step, ...]
    rule: str


# SyncPairLibrary's sizes, which killed_mid_apply's expectations name (SourceAgreementTest holds them).
PAIR_ENTRIES = 30
PAIR_CHAPTERS = 12
PAIR_READ = 3
BULK_CHAPTERS = 488
BULK_READ = 4_500

# SyncPairRestore's backup (SourceAgreementTest holds these too): six of the pair library's entries
# with all their chapters, one more read, one bookmark and two pages on each, and two entries only the
# backup holds; A reads one other chapter, bookmarks another and reads one page further on the six.
RESTORE_OVERLAP = 6
RESTORE_ONLY = 2
RESTORE_ONLY_CHAPTERS = 6
RESTORE_CHAPTERS = RESTORE_OVERLAP * PAIR_CHAPTERS + RESTORE_ONLY * RESTORE_ONLY_CHAPTERS
# What restore-check counts over those chapters after the join: the seed's reads, B's and A's on each
# of the six and two on each backup-only entry; B's and A's bookmark on the six and one on the others;
# B's two pages (one raised by A) on the six and one on the others.
RESTORE_CHECK = {
    "checked": str(RESTORE_CHAPTERS),
    "read": str(RESTORE_OVERLAP * (PAIR_READ + 2) + RESTORE_ONLY * 2),
    "bookmarked": str(RESTORE_OVERLAP * 2 + RESTORE_ONLY),
    "paged": str(RESTORE_OVERLAP * 2 + RESTORE_ONLY),
}


# SyncPairLibrary's categories, and the tree SyncPairTree's tree-check reports for them (phase 5 unit
# CT): names with spaces as "_", a child as "parent>name", joined by "|" in the screens' order.
PAIR_READING, PAIR_OLD_NAME, PAIR_GONE = "Pair reading", "Pair old name", "Pair gone"


def category_sync_key(name: str) -> str:
    """CategorySyncKey.of: SHA-256 of the trimmed, lower-cased name, as lower-case hex."""
    return hashlib.sha256(name.strip().lower().encode()).hexdigest()


def tree_line(*entries: str) -> str:
    return "|".join(entry.replace(" ", "_") for entry in entries)


# The race stores each of the two under the other; CategoryTree breaks that cycle at the member with
# the lower sync key, which stays at the top with the other under it, in its own place in the order.
_TOP, _UNDER = sorted((PAIR_READING, PAIR_OLD_NAME), key=category_sync_key)
TREE_AFTER_RACE = tree_line(_TOP, f"{_TOP}>{_UNDER}", PAIR_GONE)
TREE_PARENT_DELETED = tree_line(PAIR_READING, PAIR_GONE)
TREE_NESTED = tree_line(PAIR_OLD_NAME, f"{PAIR_OLD_NAME}>{PAIR_READING}", PAIR_GONE)
NESTED_READING = f"{PAIR_OLD_NAME}>{PAIR_READING}".replace(" ", "_")
NESTED_OLD_NAME = f"{PAIR_READING}>{PAIR_OLD_NAME}".replace(" ", "_")


# SyncPairEditDetails' texts, as its steps report them (spaces as "_", a missing text as "none").
def details_word(text: str | None) -> str:
    return "none" if text is None else text.replace(" ", "_")


EDITED_TITLE, EDITED_DESCRIPTION = "Pair title edited on A", "Pair description edited on A"
RENAMED_TITLE, NEW_DESCRIPTION = "Pair title renamed by the source", "Pair description from the source"
EDITED_AUTHOR, EDITED_ARTIST = "Pair author edited on A", "Pair artist edited on A"
# MangaStatus.COMPLETED's code, and the genres, which a step reports joined by commas.
EDITED_STATUS, EDITED_GENRES = 2, ("Pair genre one", "Pair genre two")
# The seed's title for the scenario entry (SyncPairLibrary.seed, entry 0) and its description, none.
# It has no author, artist or genres and status 0, and the fixture source keeps them so.
SEED_TITLE = "Saikou sync pair entry 0"


def details(shown_title: str, shown_description: str | None, source_title: str, source_description: str | None,
            edited: bool, author: str | None = None, artist: str | None = None, status: int = 0,
            genres: tuple[str, ...] | None = None) -> dict[str, str]:
    return {"shownTitle": details_word(shown_title), "shownDescription": details_word(shown_description),
            "sourceTitle": details_word(source_title), "sourceDescription": details_word(source_description),
            "shownAuthor": details_word(author), "shownArtist": details_word(artist), "shownStatus": str(status),
            "shownGenres": details_word(None if genres is None else ",".join(genres)),
            "edited": "true" if edited else "false"}


# A's edit of the four details the title and description share a clock with (R165 item 4).
EDITED_REST = {"author": EDITED_AUTHOR, "artist": EDITED_ARTIST, "status": EDITED_STATUS, "genres": EDITED_GENRES}
DETAILS_EDITED_ON_A = details(EDITED_TITLE, EDITED_DESCRIPTION, SEED_TITLE, None, edited=True, **EDITED_REST)
DETAILS_REFRESHED_ON_B = details(RENAMED_TITLE, NEW_DESCRIPTION, RENAMED_TITLE, NEW_DESCRIPTION, edited=False)
DETAILS_AFTER_RACE = details(EDITED_TITLE, EDITED_DESCRIPTION, RENAMED_TITLE, NEW_DESCRIPTION, edited=True,
                             **EDITED_REST)
DETAILS_AFTER_RESET = DETAILS_REFRESHED_ON_B


def a(name: str, **kwargs) -> Step:
    return Step("a", name, **kwargs)


def b(name: str, **kwargs) -> Step:
    return Step("b", name, **kwargs)


# Both devices hold the generated library and have read every file the other wrote. A's first run
# exports the library; B's first run pulls it and exports its own; A's next run pulls B's export and
# writes nothing, since it is the same library.
JOIN = (
    a("seed"),
    a("sign-in", expect={"exported": "true", "entriesAfter": "30"}),
    b("sign-in", expect={"exported": "true", "entriesBefore": "0", "entriesAfter": "30"}),
    a("sync", expect={"written": "0"}),
)

SCENARIOS = (
    Scenario(
        "race", "sync_read_and_bookmark_survive",
        JOIN + (
            a("race-read"),
            b("race-bookmark"),
            a("sync"),
            b("sync"),
            a("sync"),
            a("race-check"),
            b("race-check"),
        ),
        "a chapter read to its last page on A and bookmarked on B, offline, before either syncs, is read and "
        "bookmarked on both after A, B and A sync",
    ),
    Scenario(
        "rename", "sync_rename_no_duplicate",
        JOIN + (
            a("rename-chapter"),
            a("sync"),
            b("sync"),
            a("rename-check"),
            b("rename-check"),
        ),
        "a chapter renamed on A as a library update renames it is one row with the new name on both, and its "
        "entry keeps its chapter count",
    ),
    Scenario(
        "delete", "sync_deleted_stays_deleted",
        JOIN + (
            a("delete-chapter"),
            b("delete-read"),
            b("sync"),
            a("sync"),
            b("sync"),
            a("delete-check"),
            b("delete-check"),
            a("sync", expect={"written": "0"}),
            b("sync", expect={"written": "0"}),
            a("delete-check"),
            b("delete-check"),
        ),
        "a chapter A's library update dropped while B read it stays deleted on both, and a further round trip "
        "writes nothing and brings it back nowhere",
    ),
    Scenario(
        "fresh_device_joins", "sync_fresh_device_categories",
        JOIN + (
            a("fresh-categories"),
            a("sync", save="a-before-join"),
            b(CLEAR),
            b("sign-in", expect={"exported": "true", "entriesBefore": "0", "entriesAfter": "30"},
              same_as="a-before-join"),
            b("fresh-check"),
            a("sync", expect={"written": "0"}, same_as="a-before-join"),
            a("fresh-check"),
        ),
        "after A renames one category and deletes another, B's data cleared and signed in again joins with both "
        "renamed and deleted, one system category, and its first push changes nothing on A",
    ),
    Scenario(
        "restore_then_join", "sync_restore_join_keeps_progress",
        (
            a("seed"),
            a("restore-progress", expect={"progressed": str(RESTORE_OVERLAP * 3)}),
            a("sign-in", expect={"exported": "true", "entriesAfter": str(PAIR_ENTRIES)}),
            b("restore-backup", expect={"mangaInserted": str(RESTORE_OVERLAP + RESTORE_ONLY),
                                        "chaptersWritten": str(RESTORE_CHAPTERS), "categoriesCreated": "3",
                                        "restoreFlag": "true"}),
            b("sign-in", expect={"exported": "true", "entriesBefore": str(RESTORE_OVERLAP + RESTORE_ONLY),
                                 "entriesAfter": str(PAIR_ENTRIES + RESTORE_ONLY)}),
            a("sync", expect={"entriesAfter": str(PAIR_ENTRIES + RESTORE_ONLY)}, save="a-after-join"),
            b("sync", expect={"written": "0"}, same_as="a-after-join"),
            a("restore-check", expect=RESTORE_CHECK),
            b("restore-check", expect=RESTORE_CHECK),
            a("sync", expect={"written": "0"}),
            b("sync", expect={"written": "0"}),
        ),
        "B, cleared, restores a backup of fixture rows (six of A's entries with B's own reads, bookmarks and "
        "pages, and two entries A does not hold) and joins A, which read further on the same six; both then "
        "hold every chapter of both, read wherever either read it, on the higher page, with both bookmarks, "
        "and a further sync writes nothing",
    ),
    Scenario(
        "subcategory_race", "sync_subcategory_race_one_tree",
        JOIN + (
            a("tree-nest-reading", expect={"nested": NESTED_READING}),
            b("tree-nest-old-name", expect={"nested": NESTED_OLD_NAME}),
            a("sync"),
            b("sync"),
            a("sync", save="a-after-race"),
            b("sync", expect={"written": "0"}, same_as="a-after-race"),
            a("tree-check", expect={"tree": TREE_AFTER_RACE, "nested": "2"}),
            b("tree-check", expect={"tree": TREE_AFTER_RACE, "nested": "2"}),
        ),
        "Pair reading nested under Pair old name on A and Pair old name under Pair reading on B, before either "
        "syncs, are stored as the merged cycle on both after A, B and A sync, and both show one tree",
    ),
    Scenario(
        "subcategory_parent_deleted", "sync_subcategory_orphan_top_level",
        JOIN + (
            a("tree-delete-old-name"),
            b("tree-nest-reading", expect={"nested": NESTED_READING}),
            b("sync"),
            a("sync"),
            b("sync", save="b-after-delete"),
            a("sync", expect={"written": "0"}, same_as="b-after-delete"),
            a("tree-check", expect={"tree": TREE_PARENT_DELETED, "nested": "0"}),
            b("tree-check", expect={"tree": TREE_PARENT_DELETED, "nested": "0"}),
        ),
        "Pair old name deleted on A and Pair reading then nested under it on B leaves Pair reading at the top "
        "level on both after B, A and B sync",
    ),
    Scenario(
        "subcategory_restore_join", "sync_subcategory_restore_keeps_tree",
        (
            a("seed"),
            a("tree-nest-reading", expect={"nested": NESTED_READING}),
            a("sign-in", expect={"exported": "true", "entriesAfter": str(PAIR_ENTRIES)}),
            b("tree-restore", expect={"created": "3"}),
            b("tree-check", expect={"tree": tree_line(PAIR_READING, PAIR_OLD_NAME, PAIR_GONE), "nested": "0"}),
            b("sign-in", expect={"exported": "true", "entriesBefore": "0", "entriesAfter": str(PAIR_ENTRIES)}),
            a("sync", save="a-after-join"),
            b("sync", expect={"written": "0"}, same_as="a-after-join"),
            a("tree-check", expect={"tree": TREE_NESTED, "nested": "1"}),
            b("tree-check", expect={"tree": TREE_NESTED, "nested": "1"}),
        ),
        "Pair reading nested under Pair old name on A, exported, keeps its parent on both after B restores a "
        "backup of the same three categories, flat, and then joins",
    ),
    Scenario(
        "edit_details_race", "sync_edit_details_race",
        JOIN + (
            a("details-edit", expect=DETAILS_EDITED_ON_A),
            b("details-refresh", expect=DETAILS_REFRESHED_ON_B),
            a("sync"),
            b("sync"),
            a("sync", save="a-after-race"),
            b("sync", expect={"written": "0"}, same_as="a-after-race"),
            a("details-check", expect=DETAILS_AFTER_RACE),
            b("details-check", expect=DETAILS_AFTER_RACE),
            a("details-reset", expect=DETAILS_AFTER_RESET),
            a("sync"),
            b("sync", save="b-after-reset"),
            a("sync", expect={"written": "0"}, same_as="b-after-reset"),
            a("details-check", expect=DETAILS_AFTER_RESET),
            b("details-check", expect=DETAILS_AFTER_RESET),
        ),
        "a title and description edited on A while B, offline, refreshes the entry from a source that renamed it "
        "show as A's edit on both after A, B and A sync, with B's new source text under it; A's reset then shows "
        "the source's new title and description on both",
    ),
    Scenario(
        "killed_mid_apply", None,
        (
            # A full export of several parts, which A pulls as a new device and is stopped inside.
            b("seed"),
            b("bulk-chapters", expect={"added": str(PAIR_ENTRIES * BULK_CHAPTERS)}),
            b("sign-in", expect={"exported": "true", "entriesBefore": str(PAIR_ENTRIES), "entriesAfter": str(PAIR_ENTRIES)}),
            a("kill-in-export"),
            a("after-kill", expect={"case": "export", "exitReason": "signaled", "exitStatus": "9", "runsBeforeKill": "0",
                                    "marks": "0", "entriesAfter": str(PAIR_ENTRIES)}),
            b("sync"),
            a("sync", expect={"written": "0"}),
            # A segment of several thousand rows, which A is stopped inside the transaction of. No
            # run of the stopped process finished before the kill (runsBeforeKill), and the segment's
            # rows written again after it show the kill landed before the commit.
            b("bulk-read", expect={"marked": str(BULK_READ)}),
            b("sync", expect={"written": "0"}),
            a("kill-in-segment"),
            a("after-kill", expect={"case": "segment", "exitReason": "signaled", "exitStatus": "9", "runsBeforeKill": "0"},
              at_least={"writtenSinceKill": BULK_READ}),
            b("sync", expect={"written": "0"}),
            a("sync", expect={"written": "0"}),
        ),
        "A's process stopped with kill -9 part way through pulling B's full export of several parts, and again "
        "inside the transaction of B's segment of several thousand rows, starts cold, syncs again and converges with "
        "B, with no latch left and a third sync writing nothing; no part at or below the export's mark is read again",
    ),
)

# The backlog entry of SyncPairBacklog, which the backlog scenario's expectations name.
BACKLOG_CHAPTERS = 100
BACKLOG_NEW_CHAPTER = f"/saikou-sync-pair/backlog/c{BACKLOG_CHAPTERS + 1}"

# SyncPairLibrary's three categories hold PAIR_ENTRIES by turn, so "Pair reading" (turn 0) holds a
# third of them; SyncPairReorder drops that category into the reverse of the seed's own index order
# (0, 3, 6, ... in ascending seed order becomes descending here), which these two constants name for
# both the drag step's and the check step's expectation, so the test APK's fixed reversal and this
# script's fixed string can never drift apart silently.
READING_COUNT = PAIR_ENTRIES // 3
READING_DROPPED_ORDER = ",".join(str(index) for index in range((READING_COUNT - 1) * 3, -1, -3))

# Scenarios run only when --scenario names them: evidence for one unit, never a row and never part
# of a run that names none, as gate.sh's step 5g does.
NAMED_ONLY = (
    Scenario(
        "new_entry_backlog", None,
        JOIN + (
            a("backlog-add", expect={"entryChapters": str(BACKLOG_CHAPTERS), "logged": str(BACKLOG_CHAPTERS),
                                     "loggedChapters": str(BACKLOG_CHAPTERS)}),
            a("sync", save="a-after-add"),
            b("sync", same_as="a-after-add"),
            b("backlog-update", expect={"chaptersBefore": str(BACKLOG_CHAPTERS), "updatesBefore": "0", "queuedBefore": "0",
                                        "inserted": "1", "queued": "1", "queuedChapters": BACKLOG_NEW_CHAPTER,
                                        "chaptersAfter": str(BACKLOG_CHAPTERS + 1), "updatesAfter": "1",
                                        "failuresOutsidePair": "0"}),
            b("sync"),
            a("sync"),
        ),
        "an entry of 100 chapters added on A as the manga screen adds it reaches B with every chapter, B's digest "
        "after its pull equal to A's, and B's manual library update against the source listing 101 queues the new "
        "chapter alone",
    ),
    Scenario(
        "manual_sort_pair", None,
        JOIN + (
            a("reorder-drag", expect={"moved": str(READING_COUNT), "order": READING_DROPPED_ORDER}),
            a("sync", save="a-after-reorder"),
            b("sync", same_as="a-after-reorder"),
            b("reorder-check", expect={"ranked": str(READING_COUNT), "order": READING_DROPPED_ORDER}),
        ),
        "a reorder of the pair library's Pair reading category on A, synced to B, reads back on B in the "
        "dropped order, as R118 says",
    ),
)
SCENARIO_NAMES = tuple(scenario.name for scenario in SCENARIOS + NAMED_ONLY)


class ScenarioFailed(Exception):
    pass


class Refused(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


def parse_result(output: str) -> dict[str, str] | None:
    """The step's result line as key=value pairs, or None when the step printed none. The last wins."""
    lines = RESULT_LINE.findall(output)
    if not lines:
        return None
    pairs = {}
    for token in lines[-1].strip().split(" "):
        key, sep, value = token.partition("=")
        if sep:
            pairs[key] = value
    return pairs


def verdict(output: str) -> str | None:
    """None when the run passed exactly as instrument_one requires, else why not."""
    match = OK_LINE.search(output)
    if match is None:
        return "the runner did not end in OK (1 test)"
    if match.group(1) != "1":
        return f"the runner ran {match.group(1)} tests where one step is one test"
    return None


def fragment(row: str, method: str) -> dict:
    """One row in tools/bench/common.sh's fragment() shape, for the device rows.json binds it to."""
    return {
        "id": row,
        "device": ROW_DEVICE,
        "value": True,
        "unit": "bool",
        "samples": None,
        "method": method,
        "recordedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def fragment_path(out: Path, row: str) -> Path:
    return out / f"{row}.{ROW_DEVICE}.json"


class Adb:
    """adb, always with -s: this script never sends a command to a device it was not given."""

    def __init__(self, adb: str, serial: str):
        self.adb = adb
        self.serial = serial

    def run(self, *args: str, timeout: float = 120) -> subprocess.CompletedProcess:
        return subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, text=True,
                              timeout=timeout)

    def shell(self, *args: str, timeout: float = 120) -> str:
        return self.run("shell", *args, timeout=timeout).stdout.replace("\r", "")


def _die_with_parent() -> None:
    """In the fixture's child before exec: SIGTERM when this script ends by any path, a SIGKILL
    included, so no fixture outlives the run and holds the port (Linux prctl PR_SET_PDEATHSIG)."""
    try:
        ctypes.CDLL(None, use_errno=True).prctl(1, signal.SIGTERM)
    except (OSError, AttributeError):
        pass


class Fixture:
    """D's fixture on its own port, started and stopped as gate.sh's start_fixture_server does. stop()
    is the clean end; the parent-death signal is the backstop when this script is killed first."""

    def __init__(self, port: int, work: Path, user: str, password: str):
        self.work = work
        self.log = work / "fixture.log"
        self.out = work / "fixture.txt"
        self.log.unlink(missing_ok=True)
        with self.out.open("w") as out:
            self.process = subprocess.Popen(
                [sys.executable, str(FIXTURE), "--log", str(self.log), "--port", str(port),
                 "--dav-user", user, "--dav-password", password],
                stdout=out, stderr=subprocess.STDOUT, preexec_fn=_die_with_parent)
        deadline = time.monotonic() + FIXTURE_READY_S
        while True:
            ready = re.search(r"^ready (\d+)$", self.out.read_text(), re.M)
            if ready:
                self.port = int(ready.group(1))
                return
            if self.process.poll() is not None:
                raise Refused(NOT_STARTED, f"the sync fixture exited {self.process.returncode}: "
                              f"{self.out.read_text().strip()}")
            if time.monotonic() > deadline:
                self.stop()
                raise Refused(NOT_STARTED, f"the sync fixture was not ready after {FIXTURE_READY_S} s")
            time.sleep(0.1)

    def reset(self) -> None:
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}/_fixture/reset", data=b"", method="POST")
        with urllib.request.urlopen(request, timeout=10) as answer:
            if answer.status != 200:
                raise ScenarioFailed(f"the fixture's reset answered {answer.status}")

    def stop(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()
            self.process.wait()


class Run:
    def __init__(self, args: argparse.Namespace, adb: str, serials: dict[str, str], roles: dict[str, Device] = DEVICES,
                 backend: str = WEBDAV):
        self.args = args
        self.backend = backend
        self.roles = roles
        self.devices = {role: Adb(adb, serial) for role, serial in serials.items()}
        self.user = "pair-" + secrets.token_hex(4)
        # Hex, so the password never starts with "-": the fixture's argparse and am instrument read
        # it as the value after its flag, and a urlsafe token begins with "-" one run in 64.
        self.password = secrets.token_hex(16)
        self.api = {}
        self.installed: list[str] = []
        self.fixture: Fixture | None = None
        # The last kill: its step's fields (pid, case, and the export's device, id and mark), and
        # when this script's kill -9 returned, on the fixture log's clock (both are CLOCK_MONOTONIC).
        self.killed: dict[str, str] | None = None
        self.apk_sha256 = hashlib.sha256(args.apk.read_bytes()).hexdigest()

    name = "sync_pair"

    def say(self, message: str) -> None:
        print(f"{self.name}: {message}", flush=True)

    def check_devices(self) -> None:
        for role, adb in self.devices.items():
            expected = self.roles[role].avd
            answer = adb.run("emu", "avd", "name", timeout=30)
            name = answer.stdout.replace("\r", "").splitlines()[0].strip() if answer.stdout.strip() else ""
            if answer.returncode != 0 or name != expected:
                raise Refused(BAD_INPUT, f"{adb.serial} is {name or 'not an emulator this can ask'}, not {expected}; "
                              "nothing was installed")
            self.api[role] = adb.shell("getprop", "ro.build.version.sdk").strip()

    def install(self) -> None:
        for role, adb in self.devices.items():
            self.installed.append(role)
            for apk in (self.args.apk, self.args.test_apk):
                answer = adb.run("install", "-r", "-t", str(apk), timeout=300)
                if answer.returncode != 0 or "Success" not in answer.stdout:
                    raise Refused(NOT_STARTED, f"installing {apk.name} on {adb.serial} failed: "
                                  f"{(answer.stdout + answer.stderr).strip()[-400:]}")
            self.say(f"installed {self.args.apk.name} and {self.args.test_apk.name} on {adb.serial} "
                     f"({self.roles[role].avd}, API {self.api[role]})")

    def uninstall(self) -> None:
        for role in self.installed:
            adb = self.devices[role]
            for package in (TEST_PACKAGE, PACKAGE):
                adb.run("uninstall", package, timeout=120)
            self.say(f"uninstalled {TEST_PACKAGE} and {PACKAGE} from {adb.serial}")

    def runner(self, adb: Adb) -> str:
        found = ""
        for line in adb.shell("pm", "list", "instrumentation").splitlines():
            match = re.match(rf"^instrumentation:(\S+) \(target={re.escape(PACKAGE)}\)$", line.strip())
            if match:
                found = match.group(1)
        if not found:
            raise ScenarioFailed(f"no instrumentation targeting {PACKAGE} is installed on {adb.serial}")
        return found

    def instrument_args(self, adb: Adb, step: Step, *extra: str) -> list[str]:
        """`am instrument` for one SyncPairTest step, with the fixture's sign-in for the steps that sign in;
        over Drive, every step with the fixture's Google address, which each process needs to build its backend."""
        if self.backend == DRIVE:
            extra = ("-e", BACKEND_ARGUMENT, DRIVE, "-e", GOOGLE_BASE_ARGUMENT,
                     f"http://{DEVICE_HOST}:{self.fixture.port}", *extra)
        elif step.name in SIGN_IN_STEPS:
            extra = ("-e", "saikou.sync.pair.url", f"http://{DEVICE_HOST}:{self.fixture.port}/dav/",
                     "-e", "saikou.sync.pair.user", self.user, "-e", "saikou.sync.pair.password", self.password, *extra)
        return ["shell", "am", "instrument", "-w", "-r", "-e", "class", PAIR_TEST, "-e", STEP_ARGUMENT, step.name,
                *extra, self.runner(adb)]

    def step_log(self, scenario: Scenario, index: int, step: Step, suffix: str = ".txt") -> Path:
        log = self.args.work / scenario.name / f"{index:02d}-{step.device}-{step.name}{suffix}"
        log.parent.mkdir(parents=True, exist_ok=True)
        return log

    def step(self, scenario: Scenario, index: int, step: Step) -> dict[str, str] | None:
        adb = self.devices[step.device]
        label = f"{scenario.name} step {index} ({step.name} on {self.roles[step.device].avd})"
        if step.name == CLEAR:
            answer = adb.run("shell", "pm", "clear", PACKAGE)
            if "Success" not in answer.stdout:
                raise ScenarioFailed(f"{label}: pm clear answered {(answer.stdout + answer.stderr).strip()}")
            self.say(f"{label}: cleared")
            return None
        if step.name in KILL_STEPS:
            self.kill_step(scenario, index, step, label)
            return None
        try:
            answer = adb.run(*self.instrument_args(adb, step), timeout=STEP_TIMEOUT_S)
        except subprocess.TimeoutExpired:
            raise ScenarioFailed(f"{label}: no answer from am instrument in {STEP_TIMEOUT_S} s") from None
        output = answer.stdout.replace("\r", "") + answer.stderr.replace("\r", "")
        self.step_log(scenario, index, step).write_text(output)
        why = verdict(output)
        if why:
            raise ScenarioFailed(f"{label}: {why}; the runner said:\n" + "\n".join(output.splitlines()[-20:]))
        result = parse_result(output)
        if result is None:
            raise ScenarioFailed(f"{label}: passed with no {RESULT_KEY} line")
        if result.get("step") != step.name:
            raise ScenarioFailed(f"{label}: the result line names step {result.get('step')!r}")
        if result.get("latched") != "0":
            raise ScenarioFailed(f"{label}: {result.get('latched')} rows still have is_syncing raised")
        for key, want in step.expect.items():
            if result.get(key) != want:
                raise ScenarioFailed(f"{label}: {key} is {result.get(key)!r}, where the scenario expects {want!r}")
        for key, least in step.at_least.items():
            if not (result.get(key) or "").isdigit() or int(result[key]) < least:
                raise ScenarioFailed(f"{label}: {key} is {result.get(key)!r}, where the scenario expects at least {least}")
        if step.name == "after-kill":
            self.check_after_kill(label, result)
        self.say(f"{label}: " + " ".join(f"{k}={v}" for k, v in result.items() if k != "step"))
        return result

    def kill_step(self, scenario: Scenario, index: int, step: Step, label: str) -> None:
        """Starts a kill step, stops its process when it says so, and keeps its outcome file.

        The step is `am instrument` in the background, read line by line; when its
        saikou.sync.pair.kill line arrives, the pid it names is stopped with run-as kill -9, and the
        step must then end without passing. Raises ScenarioFailed.
        """
        adb = self.devices[step.device]
        adb.shell("rm", "-f", KILL_OUTCOME)
        if adb.shell("ls", KILL_OUTCOME).strip() == KILL_OUTCOME:
            raise ScenarioFailed(f"{label}: an earlier outcome file is still at {KILL_OUTCOME}")
        command = [adb.adb, "-s", adb.serial, *self.instrument_args(adb, step)]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        lines: list[str] = []
        fields: dict[str, str] | None = None
        killed_at = None
        deadline = time.monotonic() + KILL_READY_S
        try:
            reader = _LineReader(process.stdout)
            while fields is None:
                line = reader.next_line(deadline - time.monotonic())
                if line is None:
                    break
                lines.append(line.rstrip("\r\n"))
                match = KILL_LINE.match(lines[-1])
                if match:
                    fields = dict(token.partition("=")[::2] for token in match.group(1).split(" ") if "=" in token)
            if fields is not None:
                pid = fields.get("pid", "")
                if not pid.isdigit():
                    raise ScenarioFailed(f"{label}: the kill line names no pid: {lines[-1]}")
                stopped = adb.run("shell", "run-as", PACKAGE, "kill", "-9", pid, timeout=30)
                killed_at = time.monotonic()
                if stopped.returncode != 0:
                    raise ScenarioFailed(f"{label}: run-as kill -9 {pid} answered {(stopped.stdout + stopped.stderr).strip()}")
                end = time.monotonic() + KILL_END_S
                while (line := reader.next_line(end - time.monotonic())) is not None:
                    lines.append(line.rstrip("\r\n"))
        finally:
            if process.poll() is None:
                process.kill()
            process.wait()
            self.step_log(scenario, index, step).write_text("\n".join(lines) + "\n")
        output = "\n".join(lines)
        if fields is None:
            why = verdict(output)
            raise ScenarioFailed(f"{label}: the step never said it was ready to be stopped"
                                 f"{'' if why else ', and passed'}; the runner said:\n" + "\n".join(lines[-20:]))
        if verdict(output) is None:
            raise ScenarioFailed(f"{label}: the step passed after its process was stopped")
        if fields["pid"] in adb.shell("pidof", PACKAGE).split():
            raise ScenarioFailed(f"{label}: process {fields['pid']} is still alive after kill -9")
        pulled = self.step_log(scenario, index, step, ".outcome.pulled")
        pulled.unlink(missing_ok=True)
        adb.run("pull", KILL_OUTCOME, str(pulled), timeout=60)
        try:
            outcome = json.loads(pulled.read_text())
        except (OSError, json.JSONDecodeError):
            raise ScenarioFailed(f"{label}: no readable outcome file at {KILL_OUTCOME}") from None
        if str(outcome.get("pid")) != fields["pid"] or outcome.get("row") != scenario.name:
            raise ScenarioFailed(f"{label}: the outcome file names pid {outcome.get('pid')} of {outcome.get('row')}, "
                                 f"where the step said pid {fields['pid']}")
        self.killed = dict(fields, killedAt=str(killed_at))
        self.say(f"{label}: stopped process {fields['pid']} with kill -9: "
                 + " ".join(f"{k}={v}" for k, v in fields.items() if k != "pid"))

    def check_after_kill(self, label: str, result: dict[str, str]) -> None:
        """The step after a kill: the process it asks the platform about is the one stopped, and after
        an export's kill, the fixture's log shows the pull resumed after the mark."""
        killed, self.killed = self.killed, None
        if killed is None:
            raise ScenarioFailed(f"{label}: no kill step came before it")
        if result.get("pid") != killed["pid"] or result.get("case") != killed.get("case"):
            raise ScenarioFailed(f"{label}: it read pid {result.get('pid')} ({result.get('case')}), where the kill "
                                 f"stopped {killed['pid']} ({killed.get('case')})")
        if killed.get("case") == "export":
            self.say(f"{label}: " + self.check_resumed_parts(killed))

    def check_resumed_parts(self, killed: dict[str, str]) -> str:
        """Reads the fixture's log for the killed export's parts: none at or below the mark read after
        the kill, every one above it read, and the mark itself above 0 and below the last part. The
        fixture writes a request's line after it answers, so the step can return before the line of
        its last read exists: the log is read again until every part above the mark is there or
        [LOG_WAIT_S] pass. For the same reason the lines of two reads can land in either order, so
        the reads are listed in the order the fixture received them, by their `t0`."""
        device, export, mark = killed.get("device", ""), killed.get("export", ""), killed.get("mark", "")
        if not mark.isdigit():
            raise ScenarioFailed(f"the export's kill named no mark: {killed}")
        mark_n, since = int(mark), float(killed["killedAt"])

        def read_log() -> tuple[set[int], list[int]]:
            stored: set[int] = set()
            received: list[tuple[float, int]] = []
            for line in self.fixture.log.read_text().splitlines():
                entry = json.loads(line)
                found = PART_PATH.search(entry.get("path") or "")
                if not found or found.group(1) != device or found.group(2) != export:
                    continue
                part = int(found.group(3))
                if entry.get("method") == "PUT" and entry.get("status") in (201, 204):
                    stored.add(part)
                elif entry.get("method") == "GET" and entry.get("status") == 200 and entry.get("t0", 0) > since:
                    received.append((entry["t0"], part))
            return stored, [part for _, part in sorted(received)]

        deadline = time.monotonic() + LOG_WAIT_S
        stored, after = read_log()
        while (not stored or set(range(mark_n + 1, max(stored) + 1)) - set(after)) and time.monotonic() < deadline:
            time.sleep(0.05)
            stored, after = read_log()
        if not stored:
            raise ScenarioFailed(f"the fixture's log holds no part of export {export} from {device}")
        total = max(stored)
        again = sorted(part for part in after if part <= mark_n)
        missing = sorted(set(range(mark_n + 1, total + 1)) - set(after))
        if not 1 <= mark_n < total:
            raise ScenarioFailed(f"the kill left mark {mark_n} of {total} parts, which tests no resume")
        if again:
            raise ScenarioFailed(f"parts {again} at or below the mark {mark_n} were read again after the kill")
        if missing:
            raise ScenarioFailed(f"parts {missing} above the mark {mark_n} were not read after the kill")
        return (f"export {export[:8]} of {total} parts, killed at mark {mark_n}; read after the kill: parts "
                f"{', '.join(map(str, after))}; none at or below the mark")

    def scenario(self, scenario: Scenario) -> str:
        """Runs one scenario and returns the method note of its row. Raises ScenarioFailed."""
        self.fixture.reset()
        for role in self.devices:
            self.step(scenario, 0, Step(role, CLEAR))
        last: dict[str, dict[str, str]] = {}
        saved: dict[str, str] = {}
        for index, step in enumerate(scenario.steps, start=1):
            result = self.step(scenario, index, step)
            if result is None:
                last.pop(step.device, None)
                continue
            last[step.device] = result
            if step.save:
                saved[step.save] = result["digest"]
            if step.same_as and result.get("digest") != saved.get(step.same_as):
                raise ScenarioFailed(f"{scenario.name} step {index} ({step.name}): digest {result.get('digest')} is not "
                                     f"{step.same_as}'s {saved.get(step.same_as)}")
        if set(last) != set(self.devices):
            raise ScenarioFailed(f"{scenario.name}: a device ran no step after its last clear")
        digests = {role: result.get("digest") for role, result in last.items()}
        if len(set(digests.values())) != 1:
            raise ScenarioFailed(f"{scenario.name}: the devices did not converge: last digests {digests}")
        devices = " and ".join(f"{self.roles[role].avd} (API {self.api[role]}, {self.devices[role].serial})"
                               for role in self.devices)
        steps = sum(1 for step in scenario.steps if step.name != CLEAR)
        return (f"tools/bench/sync_pair.py scenario {scenario.name}: {scenario.rule}. {steps} SyncPairTest steps "
                f"passed on {devices} against the sync fixture's {'Google Drive' if self.backend == DRIVE else 'WebDAV'} on "
                f"port {self.fixture.port}, debug build "
                f"SHA-256 {self.apk_sha256[:12]}; both devices' last library digest {digests['a'][:12]}")

    def run(self, scenarios: list[Scenario]) -> int:
        out = self.args.out
        out.mkdir(parents=True, exist_ok=True)
        self.args.work.mkdir(parents=True, exist_ok=True)
        for scenario in scenarios:
            if scenario.row and self.backend == WEBDAV:
                fragment_path(out, scenario.row).unlink(missing_ok=True)
        self.check_devices()
        self.fixture = Fixture(self.args.port, self.args.work, self.user, self.password)
        try:
            self.say(f"sync fixture ready on port {self.fixture.port}, log {self.fixture.log}")
            self.install()
            for scenario in scenarios:
                self.say(f"scenario {scenario.name} ({scenario.row or 'no row'})")
                try:
                    method = self.scenario(scenario)
                except ScenarioFailed as failed:
                    written = f"{scenario.row} is not written and " if scenario.row else ""
                    print(f"sync_pair: scenario {scenario.name} failed, so {written}the run ends: {failed}",
                          file=sys.stderr)
                    return FAILED
                if scenario.row is None:
                    self.say(f"scenario {scenario.name} passed; it writes no row")
                    continue
                if self.backend == DRIVE:
                    note = self.args.work / scenario.name / "passed-over-drive.txt"
                    note.write_text(method + "\n")
                    self.say(f"scenario {scenario.name} passed over Drive, evidence only: {scenario.row} is not "
                             f"written; the note is {note}")
                    continue
                target = fragment_path(out, scenario.row)
                target.write_text(json.dumps(fragment(scenario.row, method), indent=2) + "\n")
                self.say(f"{scenario.row} = true, written to {target}")
            return PASSED
        finally:
            try:
                self.uninstall()
            finally:
                self.fixture.stop()
                self.say("sync fixture stopped")


class _LineReader:
    """Lines from a pipe with a timeout, read on a thread so a silent process cannot hold the caller."""

    def __init__(self, stream):
        self.queue: queue.Queue[str | None] = queue.Queue()
        threading.Thread(target=self._pump, args=(stream,), daemon=True).start()

    def _pump(self, stream) -> None:
        for line in stream:
            self.queue.put(line)
        self.queue.put(None)

    def next_line(self, timeout: float) -> str | None:
        """The next line, or None at the end of the stream or when timeout seconds pass first."""
        try:
            return self.queue.get(timeout=max(timeout, 0))
        except queue.Empty:
            return None


def sdk_adb() -> str | None:
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        root = os.environ.get(variable)
        if root and (Path(root) / "platform-tools" / "adb").exists():
            return str(Path(root) / "platform-tools" / "adb")
    return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--serial-4g", default=os.environ.get("SAIKOU_SERIAL_4G", ""),
                        help="saikou_pixel9_4g's serial (device A)")
    parser.add_argument("--serial-api26", default=os.environ.get("SAIKOU_SERIAL_API26", ""),
                        help="saikou_api26_2g's serial (device B)")
    parser.add_argument("--scenario", action="append", choices=SCENARIO_NAMES,
                        help="run only this scenario; repeat for several (default: all but the named-only ones, in order)")
    parser.add_argument("--out", type=Path, default=ROOT / "tools" / "out" / "bench" / ROW_DEVICE,
                        help="where the row fragments go")
    parser.add_argument("--work", type=Path, default=ROOT / "tools" / "out" / "sync-pair",
                        help="where each step's output and the fixture's log go")
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path,
                        default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--port", type=int, default=PORT)
    parser.add_argument("--adb", default=None, help="adb to use (default: the SDK's)")
    parser.add_argument("--backend", choices=(WEBDAV, DRIVE), default=WEBDAV,
                        help="the fixture's backend the devices sign in to; drive writes no row")
    args = parser.parse_args(argv)

    adb = args.adb or sdk_adb()
    missing = [what for what, present in (
        ("--serial-4g or SAIKOU_SERIAL_4G", args.serial_4g),
        ("--serial-api26 or SAIKOU_SERIAL_API26", args.serial_api26),
        (f"the debug APK at {args.apk}", args.apk.is_file()),
        (f"the test APK at {args.test_apk}", args.test_apk.is_file()),
        ("adb (--adb, ANDROID_SDK_ROOT or ANDROID_HOME)", adb),
    ) if not present]
    if missing:
        for what in missing:
            print(f"missing: {what}", file=sys.stderr)
        return BAD_INPUT
    if args.serial_4g == args.serial_api26:
        print("the two serials are one device", file=sys.stderr)
        return BAD_INPUT
    chosen = [scenario for scenario in SCENARIOS if not args.scenario or scenario.name in args.scenario]
    chosen += [scenario for scenario in NAMED_ONLY if args.scenario and scenario.name in args.scenario]
    if args.backend == DRIVE:
        named_only = [scenario.name for scenario in chosen if scenario in NAMED_ONLY]
        if named_only:
            print(f"{', '.join(named_only)} runs over WebDAV only; it does not run over Drive", file=sys.stderr)
            return BAD_INPUT
        if args.scenario and any(scenario.row is None for scenario in chosen):
            print("killed_mid_apply reads WebDAV part paths from the fixture's log; it does not run over Drive",
                  file=sys.stderr)
            return BAD_INPUT
        left_out = [scenario.name for scenario in chosen if scenario.row is None]
        if left_out:
            print(f"sync_pair: {', '.join(left_out)} is left out over Drive: its check reads WebDAV part paths "
                  "from the fixture's log", flush=True)
        chosen = [scenario for scenario in chosen if scenario.row]

    # A stop from outside runs the same clean-up as a failure: the fixture stopped, both packages removed.
    def stopped(signum, _frame):
        raise KeyboardInterrupt(f"signal {signum}")

    signal.signal(signal.SIGTERM, stopped)
    run = Run(args, adb, {"a": args.serial_4g, "b": args.serial_api26}, backend=args.backend)
    try:
        return run.run(chosen)
    except Refused as refused:
        print(f"sync_pair: {refused}", file=sys.stderr)
        return refused.code


if __name__ == "__main__":
    raise SystemExit(main())
