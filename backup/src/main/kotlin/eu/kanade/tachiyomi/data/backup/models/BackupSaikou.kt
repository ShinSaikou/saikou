// Fresh: Saikou's own messages of the .tachibk, fields of the vendored models beside them, as BackupMergedEntry.kt is.
package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * Saikou's own data in the `.tachibk` (R165 item 3, amending contract 06 decision F10): what the owner
 * made in Saikou that upstream's format has no field for travels in extra fields of the same file, so
 * a backup saved through the single-file picker keeps it. Readers skip a field they do not know, so
 * upstream and its forks still restore the rest of the file, and no field they know changes meaning.
 *
 * The block is 5000 to 5099, in every message. It was checked on 2026-11-08 against the backup models
 * of upstream (main), TachiyomiSY, TachiyomiJ2K, Komikku, Yokai and Neko (`Backup`, `BackupManga`,
 * `BackupCategory`, `BackupChapter`, `BackupHistory`, `BackupTracking` and the messages they hold):
 * the highest number any of them uses is 907 (Neko's `BackupChapter`), and protobuf reserves 19000 to
 * 19999. Numbers taken so far:
 *
 * - `Backup`: 5000 [BackupMergedGroup] list, 5001 [BackupCustomCover] list.
 * - `BackupManga`: 5000 manual position, 5001 [BackupScanlatorPriority] list, 5002 the status edit
 *   (null when there is none, so an edit to Unknown, code 0, travels too).
 * - `BackupChapter`: 5000 volume, 5001 [BackupPageNote] list.
 * - `BackupCategory`: 5000 the parent's order.
 *
 * The other overrides are not here: they go in TachiyomiSY's own fields of `BackupManga`, 800 the
 * title, 801 the artist, 802 the author, 804 the description, 805 the genres and 602 the status, so
 * that SY and Komikku restore them too. 602 cannot tell an edit to Unknown from no edit (both are 0),
 * which is why the status is in 5002 as well. The messages below are Saikou's alone, so their own
 * fields start at 1.
 *
 * Nothing here is sync bookkeeping (versions, latches, tombstones, clocks, the change log) or a
 * device's downloads.
 */

/** One rank of an entry's scanlator priority, `scanlator_priority` as stored: the lowest first. */
@Serializable
class BackupScanlatorPriority(
    @ProtoNumber(1) var scanlator: String,
    @ProtoNumber(2) var priority: Long = 0,
)

/** One page's note in a chapter, `bookmark_notes` as stored. */
@Serializable
class BackupPageNote(
    @ProtoNumber(1) var pageIndex: Long,
    @ProtoNumber(2) var note: String = "",
    @ProtoNumber(3) var dateAdded: Long = 0,
)

/**
 * A merged entry (R163): its main entry and its members, each named by source and url as the file
 * names its entries, in chapter order, the main entry among them. [title] is the main entry's title as
 * the library showed it, for the restore report. [showAll] is Show every copy.
 */
@Serializable
class BackupMergedGroup(
    @ProtoNumber(1) var title: String = "",
    @ProtoNumber(2) var mainSource: Long = 0,
    @ProtoNumber(3) var mainUrl: String = "",
    @ProtoNumber(4) var showAll: Boolean = false,
    @ProtoNumber(5) var members: List<BackupMergedMember> = emptyList(),
)

/** A member of a [BackupMergedGroup]. [checkUpdates] is off when the library update skips it. */
@Serializable
class BackupMergedMember(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var checkUpdates: Boolean = true,
)

/** The bytes of an entry's custom cover file as the device stores it, the entry named by source and url. */
@Serializable
class BackupCustomCover(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var image: ByteArray = ByteArray(0),
)
