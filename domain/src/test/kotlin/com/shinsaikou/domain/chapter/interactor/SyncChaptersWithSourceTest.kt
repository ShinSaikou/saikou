package com.shinsaikou.domain.chapter.interactor

import com.shinsaikou.domain.chapter.ChapterReplacements
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.database.TransactionRunner
import com.shinsaikou.domain.download.model.DownloadManifestEntry
import com.shinsaikou.domain.download.repository.DownloadManifestRepository
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.model.ChapterInfo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import java.util.Date

class SyncChaptersWithSourceTest {

    private lateinit var repository: FakeChapterRepository
    private lateinit var updateManga: UpdateManga
    private lateinit var manifest: DownloadManifestRepository
    private lateinit var libraryPreferences: LibraryPreferences
    private lateinit var replacements: ChapterReplacements
    private var failCommit = false
    private lateinit var sync: SyncChaptersWithSource

    /** Run as the refresh's transaction begins, after its reads: what the reader writes in between. */
    private var beforeTransaction: suspend () -> Unit = {}

    /** The scanlators the owner excluded for [manga]. */
    private var excludedScanlators = emptySet<String>()

    /** The reading history of [manga]'s chapters, as `getHistoryByMangaId` answers it. */
    private var histories = emptyList<History>()
    private val historyRepository = mockk<HistoryRepository>(relaxed = true) {
        coEvery { getHistoryByMangaId(any()) } answers { histories }
    }

    @BeforeEach
    fun setUp() {
        repository = FakeChapterRepository()
        updateManga = mockk(relaxed = true)
        manifest = mockk { coEvery { getByMangaId(any()) } returns emptyList() }
        libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        replacements = ChapterReplacements(repository, ImmediateTransactionRunner)
        sync = SyncChaptersWithSource(
            chapterRepository = repository,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = updateManga,
            getChaptersByMangaId = GetChaptersByMangaId(repository),
            transactionRunner = object : TransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T {
                    beforeTransaction()
                    return block().also { if (failCommit) throw IllegalStateException("fixture: the commit failed") }
                }
            },
            downloadManifestRepository = manifest,
            libraryPreferences = libraryPreferences,
            getExcludedScanlators = mockk { coEvery { await(manga.id) } answers { excludedScanlators } },
            historyRepository = historyRepository,
            chapterReplacements = replacements,
        )
    }

    @Test
    fun `inserts every chapter the database does not have`() = runTest {
        val inserted = sync.await(listOf(info("/1", "Chapter 1"), info("/2", "Chapter 2")), manga)

        inserted.map { it.url } shouldBe listOf("/1", "/2")
        inserted.map { it.sourceOrder } shouldBe listOf(0L, 1L)
        inserted.map { it.chapterNumber } shouldBe listOf(1.0, 2.0)
        repository.stored shouldHaveSize 2
    }

    @Test
    fun `updates a chapter whose name or order changed and keeps its progress`() = runTest {
        repository.seed(stored("/1", "Chapter 1", read = true, lastPageRead = 12L))

        val inserted = sync.await(listOf(info("/1", "Chapter 1: Renamed")), manga)

        inserted shouldHaveSize 0
        repository.updates shouldHaveSize 1
        repository.updates.single().name shouldBe "Chapter 1: Renamed"
        repository.stored.single().read shouldBe true
        repository.stored.single().lastPageRead shouldBe 12L
    }

    /**
     * The chapters are read before the transaction, so an update that carried the row's own fields
     * wrote back a progress commit the reader made in between (phase 4 unit BF, R120 carry-over 1).
     * The update names the source's fields and leaves the rest null, which the statement keeps.
     */
    @Test
    fun `an update sends the source's fields and leaves the row's own to the row`() = runTest {
        repository.seed(
            stored("/1", "Chapter 1", read = true, bookmark = true, lastPageRead = 12L).copy(dateFetch = 111L),
        )

        sync.await(listOf(info("/1", "Chapter 1: Renamed", dateUpload = 5L)), manga)

        repository.updates shouldBe listOf(
            ChapterUpdate(
                id = 1L,
                sourceOrder = 0L,
                url = "/1",
                name = "Chapter 1: Renamed",
                dateUpload = 5L,
                chapterNumber = 1.0,
                scanlator = null,
                volume = null,
                memo = JsonObject(emptyMap()),
            ),
        )
    }

    /**
     * The memo is a source field (phase 5 unit FX, R128 carry-over 3): `ShouldUpdateDbChapter`
     * compares it, as upstream's does, and the update did not write it, so a chapter whose source memo
     * differed from the stored one was updated on every refresh with nothing changing. The first
     * refresh writes it, in the update its other fields would ride in, and the second writes nothing.
     */
    @Test
    fun `a source memo that differs from the stored one is written once, and the next refresh writes nothing`() =
        runTest {
            repository.seed(stored("/1", "Chapter 1", read = true, lastPageRead = 4L))
            val listed = listOf(info("/1", "Chapter 1", dateUpload = 1L, memo = memo("v2")))

            sync.await(listed, manga)

            repository.updates shouldBe listOf(
                ChapterUpdate(
                    id = 1L,
                    sourceOrder = 0L,
                    url = "/1",
                    name = "Chapter 1",
                    dateUpload = 1L,
                    chapterNumber = 1.0,
                    scanlator = null,
                    volume = null,
                    memo = memo("v2"),
                ),
            )
            repository.stored.single().memo shouldBe memo("v2")
            repository.stored.single().read shouldBe true

            sync.await(listed, manga)

            repository.updates shouldHaveSize 1
        }

    @Test
    fun `removes a chapter the source no longer lists`() = runTest {
        repository.seed(stored("/1", "Chapter 1"), stored("/2", "Chapter 2"))

        sync.await(listOf(info("/1", "Chapter 1")), manga)

        repository.removed shouldBe listOf(2L)
        repository.softDeleted shouldHaveSize 0
    }

    /**
     * The manifest has no foreign key, so a hard delete would leave the download matched to
     * nothing, one confirmed tap in Settings away from being deleted.
     */
    @Test
    fun `a removed chapter that has a download is soft deleted and keeps its id`() = runTest {
        repository.seed(stored("/1", "Chapter 1"), stored("/2", "Chapter 2"))
        coEvery { manifest.getByMangaId(1L) } returns listOf(downloaded(chapterId = 2L))

        sync.await(listOf(info("/1", "Chapter 1")), manga)

        repository.removed shouldHaveSize 0
        repository.softDeleted shouldBe listOf(2L)
        repository.stored.single { it.id == 2L }.deletedAt.shouldNotBeNull()
    }

    /**
     * The update coalesces deleted_at, so without clearDeletedAt the tombstone would stay. The clear
     * rides in the relisting's one update, so the chapter's version moves once (phase 4 unit CR).
     */
    @Test
    fun `a soft-deleted chapter the source lists again is restored under its old id, not inserted`() = runTest {
        repository.seed(stored("/1", "Chapter 1"), stored("/2", "Chapter 2"))
        coEvery { manifest.getByMangaId(1L) } returns listOf(downloaded(chapterId = 2L))
        sync.await(listOf(info("/1", "Chapter 1")), manga)

        val inserted = sync.await(listOf(info("/1", "Chapter 1"), info("/2", "Chapter 2: Relisted")), manga)

        inserted shouldHaveSize 0
        // Chapter 1 is updated too: the source dates it at zero, which the update keeps as stored.
        repository.updates.filter { it.id == 2L }.map { it.name to it.clearDeletedAt } shouldBe
            listOf("Chapter 2: Relisted" to true)
        repository.stored.map { it.id } shouldBe listOf(1L, 2L)
        val back = repository.stored.single { it.id == 2L }
        back.deletedAt.shouldBeNull()
        back.name shouldBe "Chapter 2: Relisted"
        back.sourceOrder shouldBe 1L
    }

    @Test
    fun `a re-uploaded chapter inherits the progress and fetch date of the row it replaces`() = runTest {
        repository.seed(
            stored("/old", "Chapter 1", read = true, lastPageRead = 5L, bookmark = true).copy(dateFetch = 111L),
        )

        val inserted = sync.await(listOf(info("/new", "Chapter 1")), manga)

        // It was marked read, so it is not reported as a new unread chapter.
        inserted shouldHaveSize 0
        val carried = repository.stored.single { it.url == "/new" }
        carried.read shouldBe true
        carried.bookmark shouldBe true
        carried.lastPageRead shouldBe 5L
        carried.dateFetch shouldBe 111L
    }

    /**
     * The old row is deleted, and its history with it by the cascade, so a source that changed its
     * chapter urls took the entry's reading history away (History lists only live chapters).
     */
    @Test
    fun `a re-uploaded chapter takes the reading history of the row it replaces`() = runTest {
        repository.seed(stored("/old", "Chapter 1", read = true))
        histories = listOf(History(id = 9L, chapterId = 1L, readAt = Date(500L), readDuration = 60_000L))

        sync.await(listOf(info("/new", "Chapter 1")), manga)

        val replacement = repository.stored.single { it.url == "/new" }.id
        coVerify(exactly = 1) { historyRepository.upsertHistory(HistoryUpdate(replacement, Date(500L), 60_000L)) }
        // The old row's history goes with it by the delete's cascade.
        coVerify(exactly = 0) { historyRepository.resetHistory(any()) }
    }

    /** A soft-deleted row keeps its own history, so it is removed there or the time would count twice. */
    @Test
    fun `a downloaded chapter's history moves to its replacement and leaves the soft-deleted row`() = runTest {
        repository.seed(stored("/old", "Chapter 1"))
        coEvery { manifest.getByMangaId(1L) } returns listOf(downloaded(chapterId = 1L))
        histories = listOf(History(id = 9L, chapterId = 1L, readAt = Date(500L), readDuration = 60_000L))

        sync.await(listOf(info("/new", "Chapter 1")), manga)

        repository.softDeleted shouldBe listOf(1L)
        val replacement = repository.stored.single { it.url == "/new" }.id
        coVerify(exactly = 1) { historyRepository.upsertHistory(HistoryUpdate(replacement, Date(500L), 60_000L)) }
        coVerify(exactly = 1) { historyRepository.resetHistory(9L) }
    }

    @Test
    fun `two new copies of a number take the replaced row's history once`() = runTest {
        repository.seed(stored("/old", "Chapter 1"))
        histories = listOf(History(id = 9L, chapterId = 1L, readAt = Date(500L), readDuration = 60_000L))

        sync.await(listOf(info("/a", "Chapter 1"), info("/b", "Chapter 1")), manga)

        coVerify(exactly = 1) { historyRepository.upsertHistory(any()) }
    }

    /**
     * The progress and the history to carry are read in the transaction that removes the old row.
     * Read with the chapters, the page and the history row the reader wrote in between were lost.
     */
    @Test
    fun `a page and a history row written to the replaced row after the read are carried`() = runTest {
        repository.seed(stored("/old", "Chapter 1", lastPageRead = 2L))
        histories = listOf(History(id = 9L, chapterId = 1L, readAt = Date(500L), readDuration = 60_000L))
        beforeTransaction = {
            repository.stored.replaceAll { if (it.id == 1L) it.copy(read = true, lastPageRead = 7L) else it }
            histories = listOf(History(id = 9L, chapterId = 1L, readAt = Date(900L), readDuration = 90_000L))
        }

        sync.await(listOf(info("/new", "Chapter 1")), manga)

        val replacement = repository.stored.single { it.url == "/new" }
        replacement.read shouldBe true
        replacement.lastPageRead shouldBe 7L
        coVerify(exactly = 1) { historyRepository.upsertHistory(HistoryUpdate(replacement.id, Date(900L), 90_000L)) }
    }

    @Test
    fun `a reader open on a replaced chapter writes to the chapter that replaced it`() = runTest {
        val old = stored("/old", "Chapter 1")
        repository.seed(old)
        val follower = replacements.follow()

        sync.await(listOf(info("/new", "Chapter 1")), manga)

        follower.write(old) { it } shouldBe repository.stored.single { it.url == "/new" }.id
    }

    /** A soft-deleted row is still there, but its history moved: the replacement takes the writes. */
    @Test
    fun `a reader open on a replaced chapter with a download writes to the chapter that replaced it`() = runTest {
        val old = stored("/1", "Chapter 1")
        repository.seed(old)
        coEvery { manifest.getByMangaId(1L) } returns listOf(downloaded(chapterId = 1L))
        val follower = replacements.follow()

        sync.await(listOf(info("/new", "Chapter 1")), manga)

        repository.softDeleted shouldBe listOf(1L)
        follower.write(old) { it } shouldBe repository.stored.single { it.url == "/new" }.id
    }

    /** As before: the soft-deleted row keeps the progress for a relisting. */
    @Test
    fun `a reader open on a soft-deleted chapter nothing replaced keeps writing to it`() = runTest {
        val gone = stored("/2", "Chapter 2", chapterNumber = 2.0)
        repository.seed(stored("/1", "Chapter 1"), gone)
        coEvery { manifest.getByMangaId(1L) } returns listOf(downloaded(chapterId = 2L))
        val follower = replacements.follow()

        sync.await(listOf(info("/1", "Chapter 1")), manga)

        follower.write(gone) { it } shouldBe 2L
    }

    /**
     * With two rows of a number gone, the carry-over takes one of them and the reader of the other
     * would write over the wrong chapter's progress; with two new chapters of it, the reader's write
     * would land on one of them by chance. Neither is a replacement the reader is sent to.
     */
    @Test
    fun `a reader is not sent anywhere when two rows of its number left`() = runTest {
        val first = stored("/11", "Chapter 1")
        val second = stored("/12", "Chapter 1")
        repository.seed(first, second)
        val follower = replacements.follow()

        sync.await(listOf(info("/new", "Chapter 1")), manga)

        follower.write(first) { it }.shouldBeNull()
        follower.write(second) { it }.shouldBeNull()
    }

    @Test
    fun `a reader is not sent anywhere when two chapters of its number came`() = runTest {
        val old = stored("/1", "Chapter 1")
        repository.seed(old)
        val follower = replacements.follow()

        sync.await(listOf(info("/21", "Chapter 1"), info("/22", "Chapter 1")), manga)

        follower.write(old) { it }.shouldBeNull()
    }

    /**
     * A refresh whose transaction did not commit left its replacements recorded; a later refresh that
     * replaced the same row ambiguously then sent the reader to the first one's guess.
     */
    @Test
    fun `a replacement from a refresh that did not commit is forgotten`() = runTest {
        val old = stored("/old", "Chapter 1")
        repository.seed(old)
        val follower = replacements.follow()

        failCommit = true
        shouldThrow<IllegalStateException> { sync.await(listOf(info("/new", "Chapter 1")), manga) }
        failCommit = false
        // What the rollback leaves: the old row alone, as before the refresh.
        repository.stored.clear()
        repository.seed(old)
        sync.await(listOf(info("/new", "Chapter 1"), info("/other", "Chapter 1")), manga)

        follower.write(old) { it }.shouldBeNull()
    }

    @Test
    fun `a follower closed before the refresh is not told of it`() = runTest {
        val old = stored("/old", "Chapter 1")
        repository.seed(old)
        val follower = replacements.follow()
        follower.close()

        sync.await(listOf(info("/new", "Chapter 1")), manga)

        follower.write(old) { it }.shouldBeNull()
    }

    @Test
    fun `a refresh that replaces no row reads no history`() = runTest {
        repository.seed(stored("/1", "Chapter 1"))

        sync.await(listOf(info("/1", "Chapter 1"), info("/2", "Chapter 2")), manga)

        coVerify(exactly = 0) { historyRepository.getHistoryByMangaId(any()) }
    }

    @Test
    fun `with After fetching new chapter a fetched duplicate of a read chapter is inserted read and not reported`() =
        runTest {
            libraryPreferences.markDuplicateReadChapterAsRead.set(
                setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW),
            )
            repository.seed(stored("/1", "Chapter 1", read = true, lastPageRead = 5L))

            val fetched = listOf(info("/1", "Chapter 1"), info("/dup", "Chapter 1"), info("/2", "Chapter 2"))
            val inserted = sync.await(fetched, manga)

            inserted.map { it.url } shouldBe listOf("/2")
            val duplicate = repository.stored.single { it.url == "/dup" }
            duplicate.read shouldBe true
            duplicate.lastPageRead shouldBe 0L
            repository.stored.single { it.url == "/2" }.read shouldBe false
        }

    @Test
    fun `with After fetching new chapter a fetched duplicate of an unread chapter stays unread`() = runTest {
        libraryPreferences.markDuplicateReadChapterAsRead.set(
            setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW),
        )
        repository.seed(stored("/1", "Chapter 1"))

        val inserted = sync.await(listOf(info("/1", "Chapter 1"), info("/dup", "Chapter 1")), manga)

        inserted.map { it.url } shouldBe listOf("/dup")
        repository.stored.single { it.url == "/dup" }.read shouldBe false
    }

    @Test
    fun `with only After reading a chapter a fetched duplicate of a read chapter stays unread`() = runTest {
        libraryPreferences.markDuplicateReadChapterAsRead.set(
            setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING),
        )
        repository.seed(stored("/1", "Chapter 1", read = true))

        val inserted = sync.await(listOf(info("/1", "Chapter 1"), info("/dup", "Chapter 1")), manga)

        inserted.map { it.url } shouldBe listOf("/dup")
        repository.stored.single { it.url == "/dup" }.read shouldBe false
    }

    /**
     * Upstream v0.20.4 leaves an excluded scanlator's new chapter out of what its sync returns; Saikou's
     * returned it, so the library update counted it, notified it and handed it to auto-download, and
     * the notification opened a chapter the entry's list hides.
     */
    @Test
    fun `a new chapter from an excluded scanlator is stored but not returned`() = runTest {
        excludedScanlators = setOf("Group B")

        val inserted = sync.await(
            listOf(
                info("/2a", "Chapter 2").copy(scanlator = "Group A"),
                info("/2b", "Chapter 2").copy(scanlator = "Group B"),
                info("/3", "Chapter 3"),
            ),
            manga,
        )

        inserted.map { it.url } shouldBe listOf("/2a", "/3")
        repository.stored.map { it.url }.toSet() shouldBe setOf("/2a", "/2b", "/3")
    }

    @Test
    fun `strips the manga title off the front of an inserted chapter's name`() = runTest {
        val inserted = sync.await(listOf(info("/1", "A Title - Chapter 5")), manga)

        inserted.single().name shouldBe "Chapter 5"
    }

    @Test
    fun `an empty source list throws for a network source`() = runTest {
        shouldThrow<NoChaptersException> { sync.await(emptyList(), manga) }
    }

    @Test
    fun `an empty source list is accepted for the local source`() = runTest {
        val local = manga.copy(source = SourceIds.LOCAL)

        sync.await(emptyList(), local) shouldHaveSize 0
        repository.stored shouldHaveSize 0
    }

    @Test
    fun `nothing to do leaves the database untouched`() = runTest {
        repository.seed(stored("/1", "Chapter 1"))

        sync.await(listOf(info("/1", "Chapter 1", dateUpload = 1L)), manga) shouldHaveSize 0

        repository.updates shouldHaveSize 0
        repository.removed shouldHaveSize 0
        repository.stored shouldHaveSize 1
    }

    /**
     * Upstream v0.20.4's no-change path also recomputes a prediction that has fallen behind the
     * window. Without it, an entry whose next update had passed and that had no new chapter kept that
     * past date, so the release period rule never skipped it and every automatic run fetched it.
     */
    @Test
    fun `an automatic refresh with nothing new moves on a next update that fell behind the window`() = runTest {
        repository.seed(stored("/1", "Chapter 1"))
        val stale = manga.copy(fetchInterval = 7, nextUpdate = WINDOW.first - 1L)

        sync.await(listOf(info("/1", "Chapter 1", dateUpload = 1L)), stale, manualFetch = false, fetchWindow = WINDOW)

        coVerify(exactly = 1) { updateManga.awaitUpdateFetchInterval(stale, any(), any(), WINDOW) }
    }

    @Test
    fun `an automatic refresh with nothing new keeps a next update inside the window`() = runTest {
        repository.seed(stored("/1", "Chapter 1"))
        val due = manga.copy(fetchInterval = 7, nextUpdate = WINDOW.first)

        sync.await(listOf(info("/1", "Chapter 1", dateUpload = 1L)), due, manualFetch = false, fetchWindow = WINDOW)

        coVerify(exactly = 0) { updateManga.awaitUpdateFetchInterval(any(), any(), any(), any()) }
    }

    @Test
    fun `an inserted chapter carries the volume its name gives, and a name with none gets null`() = runTest {
        val inserted = sync.await(listOf(info("/1", "Vol.2 Ch.5"), info("/2", "Chapter 6")), manga)

        inserted.map { it.volume } shouldBe listOf(2.0, null)
        repository.stored.map { it.volume } shouldBe listOf(2.0, null)
    }

    /** The one-time push after the parser lands: a stored chapter gains its volume, once. */
    @Test
    fun `a stored chapter whose name carries a marker is updated with its volume once`() = runTest {
        repository.seed(stored("/1", "Vol.1 Ch.1"), stored("/2", "Chapter 2", chapterNumber = 2.0, sourceOrder = 1L))

        sync.await(listOf(info("/1", "Vol.1 Ch.1", dateUpload = 1L), info("/2", "Chapter 2", dateUpload = 1L)), manga)

        repository.updates.map { it.id to it.volume } shouldBe listOf(1L to 1.0)
        repository.stored.map { it.volume } shouldBe listOf(1.0, null)

        sync.await(listOf(info("/1", "Vol.1 Ch.1", dateUpload = 1L), info("/2", "Chapter 2", dateUpload = 1L)), manga)
        repository.updates shouldHaveSize 1
    }

    /**
     * The update coalesces volume, so without clearVolume the old one would stay (challenge c2 g3 D
     * item 5). The clear rides in the rename's one update, so the chapter's version moves once.
     */
    @Test
    fun `a name that loses its marker clears the stored volume, and a later refresh writes nothing`() = runTest {
        repository.seed(
            stored("/1", "Vol.1 Ch.1", volume = 1.0),
            stored("/2", "Vol.1 Ch.2", volume = 1.0, chapterNumber = 2.0, sourceOrder = 1L),
        )

        sync.await(listOf(info("/1", "Ch.1", dateUpload = 1L), info("/2", "Vol.1 Ch.2", dateUpload = 1L)), manga)

        repository.volumeCleared shouldBe listOf(1L)
        repository.updates.map { Triple(it.id, it.name, it.clearVolume) } shouldBe listOf(Triple(1L, "Ch.1", true))
        repository.stored.map { it.name to it.volume } shouldBe listOf("Ch.1" to null, "Vol.1 Ch.2" to 1.0)

        sync.await(listOf(info("/1", "Ch.1", dateUpload = 1L), info("/2", "Vol.1 Ch.2", dateUpload = 1L)), manga)
        repository.updates shouldHaveSize 1
        repository.volumeCleared shouldBe listOf(1L)
    }

    @Test
    fun `a soft-deleted chapter listed again without its marker is restored with no volume`() = runTest {
        repository.seed(stored("/1", "Chapter 1"), stored("/2", "Vol.1 Ch.2", volume = 1.0))
        coEvery { manifest.getByMangaId(1L) } returns listOf(downloaded(chapterId = 2L))
        sync.await(listOf(info("/1", "Chapter 1", dateUpload = 1L)), manga)

        sync.await(listOf(info("/1", "Chapter 1", dateUpload = 1L), info("/2", "Ch.2", dateUpload = 1L)), manga)

        repository.updates.map { Triple(it.id, it.clearVolume, it.clearDeletedAt) } shouldBe
            listOf(Triple(2L, true, true))
        repository.volumeCleared shouldBe listOf(2L)
        repository.stored.single { it.id == 2L }.volume.shouldBeNull()
    }

    /**
     * An entry that joined the library with no chapters (a bulk add from a listing) reads its whole
     * back catalogue on its first list. Chapters dated up to the add are filed under the date added,
     * which keeps them out of the Updates view (`date_fetch > date_added`) and out of the returned
     * list that feeds the count, the notification and auto-download.
     */
    @Test
    fun `a favourite's first list files the chapters dated before the add under the date added and returns only the later one`() =
        runTest {
            val before = System.currentTimeMillis()
            val listed = listOf(
                info("/3", "Chapter 3", dateUpload = ADDED + 500L),
                info("/2", "Chapter 2", dateUpload = ADDED - 500L),
                info("/1", "Chapter 1", dateUpload = 0L),
            )

            val inserted = sync.await(listed, addedManga)

            inserted.map { it.url } shouldBe listOf("/3")
            repository.stored shouldHaveSize 3
            repository.stored.single { it.url == "/3" }.dateFetch shouldBeGreaterThanOrEqualTo before
            repository.stored.single { it.url == "/2" }.dateFetch shouldBe ADDED
            repository.stored.single { it.url == "/1" }.dateFetch shouldBe ADDED
            inserted.single().dateFetch shouldBe repository.stored.single { it.url == "/3" }.dateFetch
        }

    @Test
    fun `a chapter uploaded exactly at the date added is backlog on a first list`() = runTest {
        val inserted = sync.await(listOf(info("/1", "Chapter 1", dateUpload = ADDED)), addedManga)

        inserted shouldHaveSize 0
        repository.stored.single().dateFetch shouldBe ADDED
    }

    @Test
    fun `a chapter uploaded one millisecond after the date added is new on a first list`() = runTest {
        val inserted = sync.await(listOf(info("/1", "Chapter 1", dateUpload = ADDED + 1L)), addedManga)

        inserted.map { it.url } shouldBe listOf("/1")
        repository.stored.single().dateFetch shouldBeGreaterThan ADDED
    }

    @Test
    fun `a backlog chapter with no upload date still has its upload date stamped`() = runTest {
        val before = System.currentTimeMillis()

        sync.await(listOf(info("/1", "Chapter 1", dateUpload = 0L)), addedManga)

        val row = repository.stored.single()
        row.dateFetch shouldBe ADDED
        row.dateUpload shouldBeGreaterThanOrEqualTo before
    }

    @Test
    fun `an entry that is not a favourite returns every new chapter dated now`() = runTest {
        val before = System.currentTimeMillis()
        val browsing = manga.copy(favorite = false, dateAdded = 0L)

        val inserted = sync.await(listOf(info("/1", "Chapter 1", dateUpload = 5L), info("/2", "Chapter 2")), browsing)

        inserted.map { it.url } shouldBe listOf("/1", "/2")
        repository.stored.forEach { it.dateFetch shouldBeGreaterThanOrEqualTo before }
    }

    @Test
    fun `a favourite with no date added returns every new chapter dated now`() = runTest {
        val before = System.currentTimeMillis()
        val undated = manga.copy(favorite = true, dateAdded = 0L)

        val inserted = sync.await(listOf(info("/1", "Chapter 1", dateUpload = 5L), info("/2", "Chapter 2")), undated)

        inserted.map { it.url } shouldBe listOf("/1", "/2")
        repository.stored.forEach { it.dateFetch shouldBeGreaterThanOrEqualTo before }
    }

    @Test
    fun `a favourite that already has chapters returns a new chapter dated before the add as usual`() = runTest {
        val before = System.currentTimeMillis()
        repository.seed(stored("/1", "Chapter 1"))

        val inserted = sync.await(
            listOf(info("/2", "Chapter 2", dateUpload = ADDED - 500L), info("/1", "Chapter 1", dateUpload = 1L)),
            addedManga,
        )

        inserted.map { it.url } shouldBe listOf("/2")
        repository.stored.single { it.url == "/2" }.dateFetch shouldBeGreaterThanOrEqualTo before
    }

    @Test
    fun `a favourite whose only chapter is soft deleted is not on a first list`() = runTest {
        val before = System.currentTimeMillis()
        repository.seed(stored("/1", "Chapter 1").copy(deletedAt = 5L))

        val inserted = sync.await(listOf(info("/2", "Chapter 2", dateUpload = ADDED - 500L)), addedManga)

        inserted.map { it.url } shouldBe listOf("/2")
        repository.stored.single { it.url == "/2" }.dateFetch shouldBeGreaterThanOrEqualTo before
    }

    @Test
    fun `a chapter listed after the first list is returned even when it is dated before the add`() = runTest {
        val first = listOf(info("/1", "Chapter 1", dateUpload = ADDED - 500L))
        sync.await(first, addedManga) shouldHaveSize 0

        val second = sync.await(listOf(info("/2", "Chapter 2", dateUpload = ADDED - 400L)) + first, addedManga)

        second.map { it.url } shouldBe listOf("/2")
        repository.stored.single { it.url == "/1" }.dateFetch shouldBe ADDED
    }

    private val manga = Manga.create().copy(id = 1L, source = 99L, title = "A Title")

    /** A library favourite added at [ADDED], with no chapter rows yet. */
    private val addedManga = manga.copy(favorite = true, dateAdded = ADDED)

    private fun info(url: String, name: String, dateUpload: Long = 0L, memo: JsonObject = JsonObject(emptyMap())) =
        ChapterInfo(url = url, name = name, dateUpload = dateUpload, memo = memo)

    /** A source's private metadata for a chapter, as an extension sets `SChapter.memo`. */
    private fun memo(revision: String) = JsonObject(mapOf("revision" to JsonPrimitive(revision)))

    private fun stored(
        url: String,
        name: String,
        read: Boolean = false,
        bookmark: Boolean = false,
        lastPageRead: Long = 0L,
        volume: Double? = null,
        chapterNumber: Double = 1.0,
        sourceOrder: Long = 0L,
    ) = Chapter.create().copy(
        id = url.filter { it.isDigit() }.toLongOrNull() ?: 1L,
        mangaId = 1L,
        url = url,
        name = name,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        chapterNumber = chapterNumber,
        sourceOrder = sourceOrder,
        dateUpload = 1L,
        volume = volume,
    )

    private fun downloaded(chapterId: Long) = DownloadManifestEntry(
        id = chapterId,
        chapterId = chapterId,
        mangaId = 1L,
        sourceId = 99L,
        mangaTitle = "A Title",
        mangaUrl = "/manga",
        chapterUrl = "/$chapterId",
        chapterName = "Chapter $chapterId",
        scanlator = null,
        chapterDirName = "Chapter $chapterId",
        pageCount = 1,
        sizeBytes = 0,
        dateDownloaded = 0,
    )

    private data object ImmediateTransactionRunner : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    /** Just enough of the repository to observe what the interactor decided. */
    private class FakeChapterRepository : ChapterRepository {
        val stored = mutableListOf<Chapter>()
        val updates = mutableListOf<ChapterUpdate>()
        val removed = mutableListOf<Long>()
        val softDeleted = mutableListOf<Long>()
        val volumeCleared = mutableListOf<Long>()
        private var nextId = 100L

        fun seed(vararg chapters: Chapter) {
            stored += chapters
        }

        override suspend fun addAll(chapters: List<Chapter>): List<Chapter> {
            val withIds = chapters.map { it.copy(id = nextId++) }
            stored += withIds
            return withIds
        }

        override suspend fun update(chapterUpdate: ChapterUpdate) = updateAll(listOf(chapterUpdate))

        override suspend fun updateAll(chapterUpdates: List<ChapterUpdate>) {
            updates += chapterUpdates
            chapterUpdates.forEach { update ->
                if (update.clearVolume) volumeCleared += update.id
                val index = stored.indexOfFirst { it.id == update.id }
                if (index < 0) return@forEach
                val current = stored[index]
                stored[index] = current.copy(
                    name = update.name ?: current.name,
                    url = update.url ?: current.url,
                    sourceOrder = update.sourceOrder ?: current.sourceOrder,
                    chapterNumber = update.chapterNumber ?: current.chapterNumber,
                    // The real update coalesces, so a null here leaves the stored value.
                    volume = if (update.clearVolume) null else update.volume ?: current.volume,
                    memo = update.memo ?: current.memo,
                    deletedAt = if (update.clearDeletedAt) null else update.deletedAt ?: current.deletedAt,
                )
            }
        }

        override suspend fun removeChaptersWithIds(chapterIds: List<Long>) {
            removed += chapterIds
            stored.removeAll { it.id in chapterIds }
        }

        override suspend fun getChapterByMangaId(mangaId: Long, applyScanlatorFilter: Boolean): List<Chapter> =
            stored.filter { it.mangaId == mangaId && it.deletedAt == null }

        override suspend fun getScanlatorsByMangaId(mangaId: Long): List<String> = emptyList()

        override fun getScanlatorsByMangaIdAsFlow(mangaId: Long): Flow<List<String>> = notNeeded()

        override suspend fun getBookmarkedChaptersByMangaId(mangaId: Long): List<Chapter> = notNeeded()

        override suspend fun getChapterById(id: Long): Chapter? = stored.find { it.id == id }

        override suspend fun getChapterByMangaIdAsFlow(
            mangaId: Long,
            applyScanlatorFilter: Boolean,
        ): Flow<List<Chapter>> = notNeeded()

        override suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long): Chapter? =
            stored.find { it.url == url && it.mangaId == mangaId }

        override suspend fun getChaptersByIds(ids: List<Long>): List<Chapter> = stored.filter { it.id in ids }

        override suspend fun getChaptersByNumbers(
            mangaIds: List<Long>,
            numbers: List<Double>,
            applyScanlatorFilter: Boolean,
        ): List<Chapter> = stored.filter { it.mangaId in mangaIds && it.chapterNumber in numbers }

        override suspend fun softDelete(chapterIds: List<Long>, now: Long) {
            softDeleted += chapterIds
            stored.replaceAll { if (it.id in chapterIds) it.copy(deletedAt = now) else it }
        }

        override suspend fun getDeletedChaptersByMangaId(mangaId: Long): List<Chapter> =
            stored.filter { it.mangaId == mangaId && it.deletedAt != null }

        private fun notNeeded(): Nothing = throw UnsupportedOperationException("not used by this test")
    }

    private companion object {
        /** A date added well before any clock reading, so a chapter fetched now is later than it. */
        const val ADDED = 1_000_000L

        /** A library update's fetch window, today give or take its grace day. */
        val WINDOW = 5_000_000L to 6_000_000L
    }
}
