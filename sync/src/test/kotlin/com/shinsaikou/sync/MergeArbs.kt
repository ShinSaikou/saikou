package com.shinsaikou.sync

import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.merge.BookmarkNoteRecord
import com.shinsaikou.sync.merge.CategoryRecord
import com.shinsaikou.sync.merge.ChapterRecord
import com.shinsaikou.sync.merge.HistoryRecord
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.merge.MangaRecord
import com.shinsaikou.sync.merge.MergedGroupRecord
import com.shinsaikou.sync.merge.MergedMemberRecord
import com.shinsaikou.sync.merge.TrackRecord
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generators for every merge record. Each value is drawn from a handful of choices, so two or three
 * draws often hold equal versions, equal clocks and equal values with differing neighbours: the
 * ties a rule has to break the same way on both devices come up in most runs rather than by luck.
 * Every record is built through its constructor, so a field added to a record fails to compile here
 * until it has a generator.
 *
 * A tombstone is drawn only in a shape a store can hold: a manga, chapter or category row is never
 * both deleted and restored at or after its deletion, because the rule itself clears such a
 * tombstone. Stored rows keep that shape only if every tombstone and restore is stamped after the
 * clock it has to beat.
 */
internal object MergeArbs {
    val clock: Arb<Long?> = Arb.element(null, 1L, 2L, 3L)
    val version: Arb<Long> = Arb.element(0L, 1L, 2L)
    val date: Arb<Long> = Arb.element(0L, 1L, 2L)
    val text: Arb<String> = Arb.element("", "a", "b", "ab")
    val optionalText: Arb<String?> = Arb.element(null, "", "a", "b")
    val small: Arb<Long> = Arb.element(0L, 1L, 2L)
    val number: Arb<Double> = Arb.element(-0.0, 0.0, 1.0, 1.5, 2.0)
    val optionalNumber: Arb<Double?> = Arb.element(null, 0.0, 1.0)

    /**
     * Every subset of three keys, listed. `Arb.set` drew these once, and asked for all three keys
     * it takes at most thirty draws and throws if one never came up: about once in 64,000 such
     * sets, which failed roughly one run in eleven of the laws that draw this generator.
     */
    val keys: Arb<Set<String>> = Arb.element(
        emptySet(),
        setOf("a"),
        setOf("b"),
        setOf("c"),
        setOf("a", "b"),
        setOf("a", "c"),
        setOf("b", "c"),
        setOf("a", "b", "c"),
    )

    val ranking: Arb<Map<String, Long>> = Arb.map(Arb.element("a", "b", "c"), small, minSize = 0, maxSize = 3)

    val memo: Arb<JsonObject> = Arb.element(
        JsonObject(emptyMap()),
        JsonObject(mapOf("a" to JsonPrimitive(1))),
        JsonObject(mapOf("a" to JsonPrimitive("1"))),
        JsonObject(mapOf("b" to JsonArray(listOf(JsonPrimitive(true), JsonNull)), "a" to JsonPrimitive(1))),
    )
    val genre: Arb<List<String>?> = Arb.element(null, emptyList(), listOf("a"), listOf("a", "b"), listOf("b", "a"))

    val track: Arb<TrackRecord> = arbitrary {
        TrackRecord(
            remoteId = small.bind(),
            libraryId = Arb.element(null, 1L).bind(),
            title = text.bind(),
            lastChapterRead = number.bind(),
            totalChapters = small.bind(),
            status = small.bind(),
            score = number.bind(),
            remoteUrl = text.bind(),
            startDate = date.bind(),
            finishDate = date.bind(),
            private = Arb.boolean().bind(),
        )
    }
    val tracks: Arb<Map<Long, TrackRecord>> = Arb.map(Arb.element(1L, 2L), track, minSize = 0, maxSize = 2)

    val note: Arb<BookmarkNoteRecord> = arbitrary {
        BookmarkNoteRecord(note = Arb.element("", "x", "y", "xy").bind(), dateAdded = Arb.element(1L, 2L).bind())
    }
    val notes: Arb<Map<Long, BookmarkNoteRecord>> = Arb.map(Arb.element(0L, 1L, 2L), note, minSize = 0, maxSize = 3)

    val manga: Arb<MangaRecord> = arbitrary {
        MangaRecord(
            key = MergeFixtures.MANGA_KEY,
            favorite = Arb.boolean().bind(),
            dateAdded = date.bind(),
            favoriteModifiedAt = clock.bind(),
            deletedAt = clock.bind(),
            restoredAt = clock.bind(),
            version = version.bind(),
            title = text.bind(),
            author = optionalText.bind(),
            artist = optionalText.bind(),
            description = optionalText.bind(),
            genre = genre.bind(),
            status = small.bind(),
            thumbnailUrl = optionalText.bind(),
            updateStrategy = Arb.enum<UpdateStrategy>().bind(),
            memo = memo.bind(),
            viewerFlags = small.bind(),
            chapterFlags = small.bind(),
            displayModifiedAt = clock.bind(),
            notes = text.bind(),
            notesModifiedAt = clock.bind(),
            manualPosition = small.bind(),
            positionModifiedAt = clock.bind(),
            titleOverride = optionalText.bind(),
            descriptionOverride = optionalText.bind(),
            detailsModifiedAt = clock.bind(),
            categories = keys.bind(),
            categoriesModifiedAt = clock.bind(),
            excludedScanlators = keys.bind(),
            exclusionsModifiedAt = clock.bind(),
            scanlatorPriority = ranking.bind(),
            priorityModifiedAt = clock.bind(),
            tracks = tracks.bind(),
            trackersModifiedAt = clock.bind(),
        ).let { if (restoredAfterDeletion(it.deletedAt, it.restoredAt)) it.copy(deletedAt = null) else it }
    }

    val chapter: Arb<ChapterRecord> = arbitrary {
        ChapterRecord(
            key = MergeFixtures.CHAPTER_KEY,
            read = Arb.boolean().bind(),
            bookmark = Arb.boolean().bind(),
            lastPageRead = small.bind(),
            dateFetch = date.bind(),
            version = version.bind(),
            name = text.bind(),
            scanlator = optionalText.bind(),
            chapterNumber = number.bind(),
            dateUpload = date.bind(),
            volume = optionalNumber.bind(),
            deletedAt = clock.bind(),
            restoredAt = clock.bind(),
            bookmarkNotes = notes.bind(),
            bookmarkNotesModifiedAt = clock.bind(),
        ).let { if (restoredAfterDeletion(it.deletedAt, it.restoredAt)) it.copy(deletedAt = null) else it }
    }

    val category: Arb<CategoryRecord> = arbitrary {
        CategoryRecord(
            syncKey = MergeFixtures.CATEGORY_KEY,
            name = text.bind(),
            order = small.bind(),
            flags = small.bind(),
            parentKey = optionalText.bind(),
            modifiedAt = clock.bind(),
            deletedAt = clock.bind(),
            restoredAt = clock.bind(),
        ).let { if (restoredAfterDeletion(it.deletedAt, it.restoredAt)) it.copy(deletedAt = null) else it }
    }

    val history: Arb<HistoryRecord> = arbitrary {
        HistoryRecord(chapter = MergeFixtures.CHAPTER_KEY, readAt = clock.bind(), readDuration = small.bind())
    }

    /** The three entries a generated group is made of: its main entry and the members it may name. */
    val groupKeys: List<MangaKey> = listOf(MangaKey(1L, "/m/a"), MangaKey(1L, "/m/b"), MangaKey(2L, "/m/a"))

    /**
     * A group's clock is never null, and three values make equal clocks common. Members are drawn
     * from [groupKeys], listed by position then key as a record holds them, so equal sets are equal
     * values.
     */
    private val groupClock: Arb<Long> = Arb.element(1L, 2L, 3L)

    private fun groupOf(main: Arb<MangaKey>): Arb<MergedGroupRecord> = arbitrary {
        val members = groupKeys.filter { Arb.boolean().bind() }.map {
            MergedMemberRecord(key = it, position = small.bind(), checkUpdates = Arb.boolean().bind())
        }
        MergedGroupRecord(
            main = main.bind(),
            showAll = Arb.boolean().bind(),
            modifiedAt = groupClock.bind(),
            members = members.sortedWith(compareBy({ it.position }, { it.key.source }, { it.key.url })),
        )
    }

    /** Groups of one main entry, the only kind [MergeEngine.mergeMergedGroup] is asked to join. */
    val mergedGroup: Arb<MergedGroupRecord> = groupOf(Arb.element(MergeFixtures.GROUP_MAIN))

    /** Groups of any of three main entries, for the rule that settles a member two groups claim. */
    val anyMergedGroup: Arb<MergedGroupRecord> = groupOf(Arb.element(groupKeys))

    private fun restoredAfterDeletion(deletedAt: Long?, restoredAt: Long?) =
        deletedAt != null && restoredAt != null && restoredAt >= deletedAt
}
