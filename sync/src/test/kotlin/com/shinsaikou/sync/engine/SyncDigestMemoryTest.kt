package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.StoredChapter
import com.shinsaikou.domain.sync.model.StoredManga
import com.shinsaikou.domain.sync.model.StoredMergedGroup
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.domain.sync.model.SyncPushRequest
import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.source.model.UpdateStrategy
import com.shinsaikou.sync.SyncDigest
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * [SyncDigest]'s memory does not grow with the library (phase 5 unit SC1, R131 carry-over 1): 200,000
 * wire rows, each line a few hundred bytes, digested in a 64 MB heap. The digest that sorted and
 * joined every line held them all at once and ran out of that heap here, while still encoding them.
 *
 * It runs only in `:sync`'s `testDebugUnitTestLowHeap`, whose fork has `-Xmx64m`; every other test
 * task leaves this class out (sync/build.gradle.kts). The first assertion checks that heap, so the
 * test cannot pass in a larger one.
 */
class SyncDigestMemoryTest {

    @Test
    fun `two hundred thousand rows digest in a 64 MB heap`() {
        Runtime.getRuntime().maxMemory() shouldBeLessThanOrEqual HEAP_LIMIT_BYTES

        val store = SyntheticLibrary(entries = ENTRIES, chaptersPerEntry = CHAPTERS_PER_ENTRY)
        val first = runBlocking { SyncDigest.of(store) }
        first.rows shouldBe ENTRIES * (1 + CHAPTERS_PER_ENTRY)
        store.pagesRead shouldBe (ENTRIES + PAGE - 1) / PAGE + 1

        // Read again, the same library gives the same digest.
        runBlocking { SyncDigest.of(store) } shouldBe first
    }

    /**
     * A library of [entries] favourites of [chaptersPerEntry] chapters each, made a page at a time as
     * it is read and never held: the store holds no row itself.
     */
    private class SyntheticLibrary(private val entries: Int, private val chaptersPerEntry: Int) : SyncStore {
        var pagesRead = 0

        override suspend fun libraryMangaIds(afterId: Long, limit: Long): List<Long> =
            ((afterId + 1)..minOf(afterId + limit, entries.toLong())).toList()

        override suspend fun mergedGroups(): List<StoredMergedGroup> = emptyList()

        override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows {
            pagesRead++
            val chapters = mangaIds.flatMap { mangaId -> (1..chaptersPerEntry).map { chapter(mangaId, it) } }
            return SyncPushRows(
                mangas = mangaIds.associateWith(::manga),
                categoryKeys = emptyMap(),
                excludedScanlators = emptyMap(),
                scanlatorPriority = emptyMap(),
                tracks = emptyMap(),
                chapters = chapters.associateBy { it.id },
                notes = emptyMap(),
                history = emptyMap(),
                categories = emptyMap(),
            )
        }

        private fun manga(id: Long) = StoredManga(
            id = id,
            source = SOURCE,
            url = "/library/series-${"%06d".format(id)}",
            favorite = true,
            dateAdded = 1_750_000_000_000L + id,
            favoriteModifiedAt = 1_750_000_000_000L + id,
            deletedAt = null,
            restoredAt = null,
            version = 1,
            title = "Synthetic series ${"%06d".format(id)}",
            author = "Author $id",
            artist = "Artist $id",
            description = "A series made up for the digest's memory test, number $id.",
            genre = listOf("Action", "Drama"),
            status = 1,
            thumbnailUrl = "/covers/series-$id.jpg",
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            memo = JsonObject(mapOf("seed" to JsonPrimitive(id))),
            viewerFlags = 0,
            chapterFlags = 0,
            displayModifiedAt = null,
            notes = "",
            notesModifiedAt = null,
            manualPosition = id,
            positionModifiedAt = null,
            titleOverride = null,
            descriptionOverride = null,
            detailsModifiedAt = null,
            categoriesModifiedAt = null,
            exclusionsModifiedAt = null,
            priorityModifiedAt = null,
            trackersModifiedAt = null,
        )

        private fun chapter(mangaId: Long, number: Int) = StoredChapter(
            id = mangaId * 1_000 + number,
            mangaId = mangaId,
            url = "/library/series-${"%06d".format(mangaId)}/chapter-${"%04d".format(number)}",
            read = number % 2 == 0,
            bookmark = number % 7 == 0,
            lastPageRead = number.toLong(),
            dateFetch = 1_750_000_000_000L + number,
            version = 1,
            name = "Chapter $number: a synthetic chapter title of a realistic length",
            scanlator = "Synthetic scanlation group",
            chapterNumber = number.toDouble(),
            dateUpload = 1_740_000_000_000L + number,
            volume = null,
            sourceOrder = number.toLong(),
            deletedAt = null,
            restoredAt = null,
            bookmarkNotesModifiedAt = null,
            readModifiedAt = 0,
            bookmarkModifiedAt = 0,
        )

        override suspend fun readForPush(request: SyncPushRequest): SyncPushRows =
            error("the digest only reads the library")

        override suspend fun chapterMangaIds(chapterIds: Collection<Long>): Map<Long, Long> =
            error("the digest only reads the library")

        override suspend fun beginExport(pushedSeq: Long): Long = error("the digest only reads the library")

        override suspend fun libraryCounts(): SyncLibraryCounts = error("the digest only reads the library")

        override suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T =
            error("the digest only reads the library")
    }

    private companion object {
        /** `-Xmx64m`; the JVM reports a little less than the flag, never more. */
        const val HEAP_LIMIT_BYTES = 64L * 1024 * 1024

        /** 4,000 entries of a manga row and 49 chapter rows: 200,000 rows. */
        const val ENTRIES = 4_000
        const val CHAPTERS_PER_ENTRY = 49

        /** SyncDigest's page. */
        const val PAGE = 20

        const val SOURCE = 11L
    }
}
