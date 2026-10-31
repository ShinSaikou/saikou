package com.shinsaikou.sync

import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.merge.BookmarkNoteRecord
import com.shinsaikou.sync.merge.CanonicalWriter
import com.shinsaikou.sync.merge.CategoryRecord
import com.shinsaikou.sync.merge.ChapterRecord
import com.shinsaikou.sync.merge.HistoryRecord
import com.shinsaikou.sync.merge.MangaRecord
import com.shinsaikou.sync.merge.TrackRecord
import com.shinsaikou.sync.merge.higherEncoding
import dev.zacsweers.metro.Inject
import kotlinx.serialization.json.JsonObject
import kotlin.math.max

/**
 * Per-field merge of a local row and its remote twin, to the table of Q6 in plans/phase3-start.md
 * (contract 06 section 5.1). Every function is pure.
 *
 * A value a source owns is decided by the row `version`, which counts writes and so says nothing
 * about which of the owner's edits is newer. A value the owner sets is decided by its own
 * modification clock, the greater winning. Progress is decided by no clock: read state, page and
 * reading time take the side that got further. Every tie, of a version or of a clock, is broken by
 * the value's canonical encoding and never by which side is local, so each rule gives both devices
 * the same row whichever of them runs it, and gives the same row however a run of rows is grouped.
 *
 * Each merged clock is the greater of the two. A child collection's function returns the value
 * alone, and its caller stores that greater clock beside it.
 */
@Inject
class MergeEngine {

    /**
     * [value] is the row to store and [changed] says whether it differs from the local row in any
     * stored column, clocks and version included. [fieldsFromRemote] names, for the conflict report,
     * each value field the merge changed where the two sides disagreed; clocks and the version are
     * never named.
     */
    data class Merged<T>(val value: T, val changed: Boolean, val fieldsFromRemote: Set<String>)

    fun mergeManga(local: MangaRecord, remote: MangaRecord): Merged<MangaRecord> {
        require(local.key == remote.key) { "Cannot merge two manga: ${local.key} and ${remote.key}" }

        // The newer favourite clock takes the favourite and its add date. On a tie the entry stays
        // in the library and keeps the first date it was added on.
        val favourite = byClock(
            local.favoriteModifiedAt, remote.favoriteModifiedAt,
            local.favorite to local.dateAdded, remote.favorite to remote.dateAdded,
        ) { a, b -> (a.first || b.first) to earliestNonZero(a.second, b.second) }
        val favoriteModifiedAt = maxClock(local.favoriteModifiedAt, remote.favoriteModifiedAt)

        // As a chapter's tombstone. The favourite does not read it: a favourite flip from 0 to 1
        // stamps restoredAt, so a re-add beats an older delete through that clock.
        val (deletedAt, restoredAt) = tombstone(local.deletedAt, remote.deletedAt, local.restoredAt, remote.restoredAt)

        // The memo is one of the source values: a source writes it, and the next update rewrites it.
        val source = higherVersion(local.version, remote.version, local.sourceValues(), remote.sourceValues()) {
            it.encode(this)
        }

        val display = byClock(
            local.displayModifiedAt, remote.displayModifiedAt,
            local.viewerFlags to local.chapterFlags, remote.viewerFlags to remote.chapterFlags,
        ) { a, b -> higherEncoding(a, b) { long(it.first).long(it.second) } }
        val notes = byClock(local.notesModifiedAt, remote.notesModifiedAt, local.notes, remote.notes) { a, b ->
            higherEncoding(a, b) { string(it) }
        }
        val manualPosition = byClock(
            local.positionModifiedAt, remote.positionModifiedAt, local.manualPosition, remote.manualPosition,
        ) { a, b -> higherEncoding(a, b) { long(it) } }
        // The owner's title and description edit, as the notes are (R138 item 2): one clock for the
        // pair, so a reset of both travels as one value. A source refresh never moves this clock, so
        // the edit beats any refresh, and the source values under it still follow the version.
        val details = byClock(
            local.detailsModifiedAt, remote.detailsModifiedAt,
            local.titleOverride to local.descriptionOverride, remote.titleOverride to remote.descriptionOverride,
        ) { a, b -> higherEncoding(a, b) { nullableString(it.first).nullableString(it.second) } }

        val merged = MangaRecord(
            key = local.key,
            favorite = favourite.first,
            dateAdded = favourite.second,
            favoriteModifiedAt = favoriteModifiedAt,
            deletedAt = deletedAt,
            restoredAt = restoredAt,
            version = max(local.version, remote.version),
            title = source.title,
            author = source.author,
            artist = source.artist,
            description = source.description,
            genre = source.genre,
            status = source.status,
            thumbnailUrl = source.thumbnailUrl,
            updateStrategy = source.updateStrategy,
            memo = source.memo,
            viewerFlags = display.first,
            chapterFlags = display.second,
            displayModifiedAt = maxClock(local.displayModifiedAt, remote.displayModifiedAt),
            notes = notes,
            notesModifiedAt = maxClock(local.notesModifiedAt, remote.notesModifiedAt),
            manualPosition = manualPosition,
            positionModifiedAt = maxClock(local.positionModifiedAt, remote.positionModifiedAt),
            titleOverride = details.first,
            descriptionOverride = details.second,
            detailsModifiedAt = maxClock(local.detailsModifiedAt, remote.detailsModifiedAt),
            categories = mergeMangaCategories(
                local.categories, remote.categories, local.categoriesModifiedAt, remote.categoriesModifiedAt,
            ).value,
            categoriesModifiedAt = maxClock(local.categoriesModifiedAt, remote.categoriesModifiedAt),
            excludedScanlators = mergeExcludedScanlators(
                local.excludedScanlators, remote.excludedScanlators,
                local.exclusionsModifiedAt, remote.exclusionsModifiedAt,
            ).value,
            exclusionsModifiedAt = maxClock(local.exclusionsModifiedAt, remote.exclusionsModifiedAt),
            scanlatorPriority = mergeScanlatorPriority(
                local.scanlatorPriority, remote.scanlatorPriority,
                local.priorityModifiedAt, remote.priorityModifiedAt,
            ).value,
            priorityModifiedAt = maxClock(local.priorityModifiedAt, remote.priorityModifiedAt),
            tracks = mergeTracks(
                local.tracks, remote.tracks, local.trackersModifiedAt, remote.trackersModifiedAt,
            ).value,
            trackersModifiedAt = maxClock(local.trackersModifiedAt, remote.trackersModifiedAt),
        )
        return merged(local, remote, merged, MANGA_VALUES)
    }

    fun mergeChapter(local: ChapterRecord, remote: ChapterRecord): Merged<ChapterRecord> {
        require(local.key == remote.key) { "Cannot merge two chapters: ${local.key} and ${remote.key}" }

        val source = higherVersion(local.version, remote.version, local.sourceValues(), remote.sourceValues()) {
            it.encode(this)
        }
        val (deletedAt, restoredAt) = tombstone(local.deletedAt, remote.deletedAt, local.restoredAt, remote.restoredAt)

        val merged = ChapterRecord(
            key = local.key,
            // Known limit: an unread or an unbookmark never travels; the other side's true returns.
            read = local.read || remote.read,
            bookmark = local.bookmark || remote.bookmark,
            // Plain max: the reader ignores the page of a read chapter, so no reset is needed.
            lastPageRead = max(local.lastPageRead, remote.lastPageRead),
            // The first fetch, so the chapter holds one place in Updates on every device.
            dateFetch = earliestNonZero(local.dateFetch, remote.dateFetch),
            version = max(local.version, remote.version),
            name = source.name,
            scanlator = source.scanlator,
            chapterNumber = source.chapterNumber,
            dateUpload = source.dateUpload,
            volume = source.volume,
            deletedAt = deletedAt,
            restoredAt = restoredAt,
            bookmarkNotes = mergeBookmarkNotes(
                local.bookmarkNotes, remote.bookmarkNotes,
                local.bookmarkNotesModifiedAt, remote.bookmarkNotesModifiedAt,
            ).value,
            bookmarkNotesModifiedAt = maxClock(local.bookmarkNotesModifiedAt, remote.bookmarkNotesModifiedAt),
        )
        return merged(local, remote, merged, CHAPTER_VALUES)
    }

    /**
     * A category row. The apply drops every membership of a category that merges to deleted, as
     * deleting a category does locally.
     */
    fun mergeCategory(local: CategoryRecord, remote: CategoryRecord): Merged<CategoryRecord> {
        require(local.syncKey == remote.syncKey) {
            "Cannot merge two categories: ${local.syncKey} and ${remote.syncKey}"
        }

        val values = byClock(local.modifiedAt, remote.modifiedAt, local.values(), remote.values()) { a, b ->
            higherEncoding(a, b) { it.encode(this) }
        }
        val (deletedAt, restoredAt) = tombstone(local.deletedAt, remote.deletedAt, local.restoredAt, remote.restoredAt)

        val merged = CategoryRecord(
            syncKey = local.syncKey,
            name = values.name,
            order = values.order,
            flags = values.flags,
            parentKey = values.parentKey,
            modifiedAt = maxClock(local.modifiedAt, remote.modifiedAt),
            deletedAt = deletedAt,
            restoredAt = restoredAt,
        )
        return merged(local, remote, merged, CATEGORY_VALUES)
    }

    /**
     * A history row the local side has never seen is taken whole. Known limit: clearing history
     * never travels, because the other side's later time wins it back.
     */
    fun mergeHistory(local: HistoryRecord?, remote: HistoryRecord): Merged<HistoryRecord> {
        if (local == null) {
            return Merged(remote, changed = true, fieldsFromRemote = HISTORY_VALUES.map { it.name }.toSet())
        }
        require(local.chapter == remote.chapter) {
            "Cannot merge the history of two chapters: ${local.chapter} and ${remote.chapter}"
        }
        val merged = HistoryRecord(
            chapter = local.chapter,
            readAt = maxClock(local.readAt, remote.readAt),
            // Two views of the same reading, never two readings to add up.
            readDuration = max(local.readDuration, remote.readDuration),
        )
        return merged(local, remote, merged, HISTORY_VALUES)
    }

    /**
     * One tracker bound on both sides: the side that read further knows the tracker's current state,
     * so every field comes from it and `lastChapterRead` is the max; equal progress, the higher
     * encoding.
     */
    fun mergeTrack(local: TrackRecord, remote: TrackRecord): Merged<TrackRecord> {
        val merged = when {
            remote.lastChapterRead > local.lastChapterRead -> remote
            local.lastChapterRead > remote.lastChapterRead -> local
            else -> higherEncoding(local, remote) { it.encode(this) }
        }
        return merged(local, remote, merged, TRACK_VALUES)
    }

    /**
     * The trackers a manga is bound to, keyed by tracker id. The newer clock takes its whole set,
     * each binding as it is on that side. A tie keeps every binding, and one bound on both sides
     * merges under [mergeTrack]. A progress write never moves the clock, so after the first sync of
     * a binding the two sides tie and each binding's fields merge.
     */
    fun mergeTracks(
        local: Map<Long, TrackRecord>,
        remote: Map<Long, TrackRecord>,
        localModifiedAt: Long?,
        remoteModifiedAt: Long?,
    ): Merged<Map<Long, TrackRecord>> {
        val merged = byClock(localModifiedAt, remoteModifiedAt, local, remote) { a, b ->
            unionBy(a, b) { x, y -> mergeTrack(x, y).value }
        }
        return mergedCollection(local, remote, merged, "tracks")
    }

    /** The categories a manga is in, as Q7 keys: the newer clock's whole set; a tie, the union. */
    fun mergeMangaCategories(
        local: Set<String>,
        remote: Set<String>,
        localModifiedAt: Long?,
        remoteModifiedAt: Long?,
    ): Merged<Set<String>> {
        val merged = byClock(localModifiedAt, remoteModifiedAt, local, remote) { a, b -> a + b }
        return mergedCollection(local, remote, merged, "categories")
    }

    /** The newer clock's whole set, so an un-exclusion travels; a tie, the union. */
    fun mergeExcludedScanlators(
        local: Set<String>,
        remote: Set<String>,
        localModifiedAt: Long?,
        remoteModifiedAt: Long?,
    ): Merged<Set<String>> {
        val merged = byClock(localModifiedAt, remoteModifiedAt, local, remote) { a, b -> a + b }
        return mergedCollection(local, remote, merged, "excludedScanlators")
    }

    /** The newer clock's whole ranking; a tie, the ranking with the higher encoding. */
    fun mergeScanlatorPriority(
        local: Map<String, Long>,
        remote: Map<String, Long>,
        localModifiedAt: Long?,
        remoteModifiedAt: Long?,
    ): Merged<Map<String, Long>> {
        val merged = byClock(localModifiedAt, remoteModifiedAt, local, remote) { a, b ->
            higherEncoding(a, b) { ranking ->
                long(ranking.size.toLong())
                ranking.entries.sortedBy { it.key }.forEach { (scanlator, priority) ->
                    string(scanlator).long(priority)
                }
            }
        }
        return mergedCollection(local, remote, merged, "scanlatorPriority")
    }

    /**
     * A chapter's notes, keyed by page index: the newer clock's whole set. A tie keeps every page,
     * and a page noted on both sides keeps the longer text, then the higher encoding.
     */
    fun mergeBookmarkNotes(
        local: Map<Long, BookmarkNoteRecord>,
        remote: Map<Long, BookmarkNoteRecord>,
        localModifiedAt: Long?,
        remoteModifiedAt: Long?,
    ): Merged<Map<Long, BookmarkNoteRecord>> {
        val merged = byClock(localModifiedAt, remoteModifiedAt, local, remote) { a, b ->
            unionBy(a, b) { x, y ->
                when {
                    x.note.length != y.note.length -> if (x.note.length > y.note.length) x else y
                    else -> higherEncoding(x, y) { string(it.note).long(it.dateAdded) }
                }
            }
        }
        return mergedCollection(local, remote, merged, "bookmarkNotes")
    }

    internal class Field<T>(val name: String, val get: (T) -> Any?)

    internal companion object {
        /** The value fields a conflict report can name; every other field is a key or a clock. */
        val MANGA_VALUES: List<Field<MangaRecord>> = listOf(
            Field("favorite") { it.favorite },
            Field("dateAdded") { it.dateAdded },
            Field("deletedAt") { it.deletedAt },
            Field("title") { it.title },
            Field("author") { it.author },
            Field("artist") { it.artist },
            Field("description") { it.description },
            Field("genre") { it.genre },
            Field("status") { it.status },
            Field("thumbnailUrl") { it.thumbnailUrl },
            Field("updateStrategy") { it.updateStrategy },
            Field("memo") { it.memo },
            Field("viewerFlags") { it.viewerFlags },
            Field("chapterFlags") { it.chapterFlags },
            Field("notes") { it.notes },
            Field("manualPosition") { it.manualPosition },
            Field("titleOverride") { it.titleOverride },
            Field("descriptionOverride") { it.descriptionOverride },
            Field("categories") { it.categories },
            Field("excludedScanlators") { it.excludedScanlators },
            Field("scanlatorPriority") { it.scanlatorPriority },
            Field("tracks") { it.tracks },
        )
        val CHAPTER_VALUES: List<Field<ChapterRecord>> = listOf(
            Field("read") { it.read },
            Field("bookmark") { it.bookmark },
            Field("lastPageRead") { it.lastPageRead },
            Field("dateFetch") { it.dateFetch },
            Field("name") { it.name },
            Field("scanlator") { it.scanlator },
            Field("chapterNumber") { it.chapterNumber },
            Field("dateUpload") { it.dateUpload },
            Field("volume") { it.volume },
            Field("deletedAt") { it.deletedAt },
            Field("bookmarkNotes") { it.bookmarkNotes },
        )
        val CATEGORY_VALUES: List<Field<CategoryRecord>> = listOf(
            Field("name") { it.name },
            Field("order") { it.order },
            Field("flags") { it.flags },
            Field("parentKey") { it.parentKey },
            Field("deletedAt") { it.deletedAt },
        )
        val HISTORY_VALUES: List<Field<HistoryRecord>> = listOf(
            Field("readAt") { it.readAt },
            Field("readDuration") { it.readDuration },
        )
        val TRACK_VALUES: List<Field<TrackRecord>> = listOf(
            Field("remoteId") { it.remoteId },
            Field("libraryId") { it.libraryId },
            Field("title") { it.title },
            Field("lastChapterRead") { it.lastChapterRead },
            Field("totalChapters") { it.totalChapters },
            Field("status") { it.status },
            Field("score") { it.score },
            Field("remoteUrl") { it.remoteUrl },
            Field("startDate") { it.startDate },
            Field("finishDate") { it.finishDate },
            Field("private") { it.private },
        )
    }
}

private fun <T> merged(local: T, remote: T, merged: T, fields: List<MergeEngine.Field<T>>): MergeEngine.Merged<T> =
    MergeEngine.Merged(
        value = merged,
        changed = merged != local,
        fieldsFromRemote = fields
            .filter { it.get(merged) != it.get(local) && it.get(remote) != it.get(local) }
            .map { it.name }
            .toSet(),
    )

private fun <T> mergedCollection(local: T, remote: T, merged: T, name: String): MergeEngine.Merged<T> =
    MergeEngine.Merged(
        value = merged,
        changed = merged != local,
        fieldsFromRemote = if (merged != local && remote != local) setOf(name) else emptySet(),
    )

/** Two clocks in order; null, never stamped, is older than any stamp. */
private fun compareClocks(a: Long?, b: Long?): Int = when {
    a == b -> 0
    a == null -> -1
    b == null -> 1
    else -> a.compareTo(b)
}

/** The newer of two clocks, which the apply stores beside a merged collection. */
internal fun maxClock(a: Long?, b: Long?): Long? = if (compareClocks(a, b) >= 0) a else b

/** The value under the newer clock; on equal clocks, [tie] of the two. */
private inline fun <T> byClock(localClock: Long?, remoteClock: Long?, local: T, remote: T, tie: (T, T) -> T): T {
    val order = compareClocks(localClock, remoteClock)
    return when {
        order > 0 -> local
        order < 0 -> remote
        else -> tie(local, remote)
    }
}

/** The value of the higher version; on equal versions, the higher encoding. */
private inline fun <T> higherVersion(
    localVersion: Long,
    remoteVersion: Long,
    local: T,
    remote: T,
    crossinline encode: CanonicalWriter.(T) -> Unit,
): T = when {
    localVersion > remoteVersion -> local
    remoteVersion > localVersion -> remote
    else -> higherEncoding(local, remote) { encode(it) }
}

private fun earliestNonZero(a: Long, b: Long): Long = when {
    a == 0L -> b
    b == 0L -> a
    else -> minOf(a, b)
}

/** Every key of either map; a key in both takes [both] of its two values. */
private inline fun <K, V> unionBy(a: Map<K, V>, b: Map<K, V>, both: (V, V) -> V): Map<K, V> {
    val union = a.toMutableMap()
    for ((key, value) in b) union[key] = union[key]?.let { both(it, value) } ?: value
    return union
}

private data class MangaSourceValues(
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: List<String>?,
    val status: Long,
    val thumbnailUrl: String?,
    val updateStrategy: UpdateStrategy,
    val memo: JsonObject,
) {
    fun encode(writer: CanonicalWriter) {
        writer.string(title).nullableString(author).nullableString(artist).nullableString(description)
            .stringList(genre).long(status).nullableString(thumbnailUrl).updateStrategy(updateStrategy).json(memo)
    }
}

private fun MangaRecord.sourceValues() = MangaSourceValues(
    title, author, artist, description, genre, status, thumbnailUrl, updateStrategy, memo,
)

private data class ChapterSourceValues(
    val name: String,
    val scanlator: String?,
    val chapterNumber: Double,
    val dateUpload: Long,
    val volume: Double?,
) {
    fun encode(writer: CanonicalWriter) {
        writer.string(name).nullableString(scanlator).double(chapterNumber).long(dateUpload).nullableDouble(volume)
    }
}

private fun ChapterRecord.sourceValues() = ChapterSourceValues(name, scanlator, chapterNumber, dateUpload, volume)

private data class CategoryValues(val name: String, val order: Long, val flags: Long, val parentKey: String?) {
    fun encode(writer: CanonicalWriter) {
        writer.string(name).long(order).long(flags).nullableString(parentKey)
    }
}

private fun CategoryRecord.values() = CategoryValues(name, order, flags, parentKey)

private fun TrackRecord.encode(writer: CanonicalWriter) {
    writer.long(remoteId).nullableLong(libraryId).string(title).double(lastChapterRead).long(totalChapters)
        .long(status).double(score).string(remoteUrl).long(startDate).long(finishDate).boolean(private)
}

/**
 * A manga, chapter or category tombstone against its revival (Q6): deleted while the newest
 * deletion is newer than the newest restore. A tombstone a restore beat is dropped, and can never
 * count again, because the restore clock it lost to only grows.
 */
private fun tombstone(
    localDeleted: Long?,
    remoteDeleted: Long?,
    localRestored: Long?,
    remoteRestored: Long?,
): Pair<Long?, Long?> {
    val deleted = maxClock(localDeleted, remoteDeleted)
    val restored = maxClock(localRestored, remoteRestored)
    val stays = deleted != null && compareClocks(deleted, restored) > 0
    return (if (stays) deleted else null) to restored
}
