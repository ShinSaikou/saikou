package com.shinsaikou.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.shinsaikou.domain.category.CategorySyncKey
import com.shinsaikou.source.StubSourceNames
import com.shinsaikou.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.source.DatabaseStubSourceNames
import tachiyomi.data.source.StubSourceRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.track.model.Track
import java.util.Date
import java.util.Properties

/**
 * The seven `tachiyomi.data` repositories against the real schema on the JVM driver. The mappers
 * take positional column tuples, so a column added in the wrong place is a silent field swap that
 * no signature catches: every round trip below gives each field a distinct value on purpose.
 */
class RepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var handler: DatabaseHandler

    private lateinit var mangaRepository: MangaRepositoryImpl
    private lateinit var chapterRepository: ChapterRepositoryImpl
    private lateinit var categoryRepository: CategoryRepositoryImpl
    private lateinit var historyRepository: HistoryRepositoryImpl
    private lateinit var trackRepository: TrackRepositoryImpl
    private lateinit var updatesRepository: UpdatesRepositoryImpl
    private lateinit var stubSourceRepository: StubSourceRepositoryImpl
    private lateinit var stubSourceNames: DatabaseStubSourceNames

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), Database.Schema.synchronous())
        driver.execute(null, "PRAGMA foreign_keys = ON", 0)
        val database = Database(
            driver = driver,
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
                genre_overrideAdapter = StringListColumnAdapter,
            ),
        )
        handler = AndroidDatabaseHandler(database, UnconfinedTestDispatcher())

        mangaRepository = MangaRepositoryImpl(handler)
        chapterRepository = ChapterRepositoryImpl(handler)
        categoryRepository = CategoryRepositoryImpl(handler)
        historyRepository = HistoryRepositoryImpl(handler)
        trackRepository = TrackRepositoryImpl(handler)
        updatesRepository = UpdatesRepositoryImpl(handler)
        stubSourceRepository = StubSourceRepositoryImpl(handler)
        stubSourceNames = DatabaseStubSourceNames(handler)
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    // Manga ------------------------------------------------------------------------------------

    @Test
    fun `insertNetworkManga stores every mapped column and reads the row back`() = runTest {
        val stored = mangaRepository.insertNetworkManga(listOf(networkManga())).single()

        stored.id shouldBe 1L
        stored.source shouldBe 7L
        stored.url shouldBe "/manga/1"
        stored.title shouldBe "A title"
        stored.artist shouldBe "An artist"
        stored.author shouldBe "An author"
        stored.description shouldBe "A description"
        stored.genre shouldContainExactly listOf("action", "drama")
        stored.status shouldBe 2L
        stored.thumbnailUrl shouldBe "/cover.png"
        stored.favorite shouldBe true
        stored.lastUpdate shouldBe 111L
        stored.nextUpdate shouldBe 222L
        stored.initialized shouldBe true
        stored.viewerFlags shouldBe 3L
        stored.chapterFlags shouldBe 4L
        stored.coverLastModified shouldBe 333L
        stored.dateAdded shouldBe 444L
        stored.updateStrategy shouldBe UpdateStrategy.ONLY_FETCH_ONCE
        stored.fetchInterval shouldBe 5
        stored.memo shouldBe JsonObject(mapOf("k" to JsonPrimitive("v")))
        // insertNetworkManga pins the sync and ordering columns itself.
        stored.notes shouldBe ""
        stored.manualPosition shouldBe 0L
        stored.deletedAt.shouldBeNull()
    }

    @Test
    fun `insertNetworkManga leaves an existing source and url pair alone`() = runTest {
        mangaRepository.insertNetworkManga(listOf(networkManga()))
        val second = mangaRepository.insertNetworkManga(
            listOf(networkManga().copy(title = "A different title")),
        ).single()

        second.id shouldBe 1L
        second.title shouldBe "A title"
        mangaRepository.getFavorites() shouldContainExactly listOf(second)
    }

    @Test
    fun `insertNetworkManga refreshes an existing uninitialized row`() = runTest {
        mangaRepository.insertNetworkManga(
            listOf(networkManga().copy(title = "A placeholder", thumbnailUrl = null, initialized = false)),
        )

        val refreshed = mangaRepository.insertNetworkManga(
            listOf(networkManga().copy(title = "A complete title", thumbnailUrl = "/new-cover.png", initialized = true)),
        ).single()

        refreshed.title shouldBe "A complete title"
        refreshed.thumbnailUrl shouldBe "/new-cover.png"
        refreshed.initialized shouldBe true
    }

    @Test
    fun `update coalesces and re-encodes the adapted columns`() = runTest {
        val stored = mangaRepository.insertNetworkManga(listOf(networkManga())).single()

        mangaRepository.update(
            MangaUpdate(
                id = stored.id,
                genre = listOf("comedy"),
                updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
                memo = JsonObject(mapOf("k2" to JsonPrimitive("v2"))),
                notes = "A note",
                manualPosition = 9L,
            ),
        ) shouldBe true

        val updated = mangaRepository.getMangaById(stored.id)
        updated.genre shouldContainExactly listOf("comedy")
        updated.updateStrategy shouldBe UpdateStrategy.ALWAYS_UPDATE
        updated.memo shouldBe JsonObject(mapOf("k2" to JsonPrimitive("v2")))
        updated.notes shouldBe "A note"
        updated.manualPosition shouldBe 9L
        // Everything left null in the update is untouched.
        updated.title shouldBe "A title"
        updated.artist shouldBe "An artist"
    }

    @Test
    fun `setMangaCategories replaces the whole set`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        categoryRepository.insert(Category(id = 0, name = "One", order = 0, flags = 0))
        categoryRepository.insert(Category(id = 0, name = "Two", order = 1, flags = 0))
        val ids = categoryRepository.getAll().filterNot { it.isSystemCategory }.map { it.id }

        mangaRepository.setMangaCategories(manga.id, ids)
        categoryRepository.getCategoriesByMangaId(manga.id).map { it.name } shouldContainExactly listOf("One", "Two")

        mangaRepository.setMangaCategories(manga.id, listOf(ids.last()))
        categoryRepository.getCategoriesByMangaId(manga.id).map { it.name } shouldContainExactly listOf("Two")
    }

    @Test
    fun `setExcludedScanlators replaces the whole set`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()

        mangaRepository.setExcludedScanlators(manga.id, setOf("a", "b"))
        mangaRepository.getExcludedScanlators(manga.id) shouldBe setOf("a", "b")

        mangaRepository.setExcludedScanlators(manga.id, setOf("c"))
        mangaRepository.getExcludedScanlators(manga.id) shouldBe setOf("c")
        mangaRepository.getExcludedScanlatorsAsFlow(manga.id).first() shouldBe setOf("c")
    }

    @Test
    fun `setManualPositions numbers from one so zero stays unranked`() = runTest {
        val first = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val second = mangaRepository.insertNetworkManga(
            listOf(networkManga().copy(url = "/manga/2")),
        ).single()

        mangaRepository.setManualPositions(listOf(second.id, first.id))

        mangaRepository.getMangaById(second.id).manualPosition shouldBe 1L
        mangaRepository.getMangaById(first.id).manualPosition shouldBe 2L
    }

    @Test
    fun `softDelete drops the manga out of the library and undelete puts it back`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()

        mangaRepository.softDelete(manga.id, now = 999L)
        mangaRepository.getFavorites().shouldBeEmpty()
        mangaRepository.getMangaById(manga.id).deletedAt shouldBe 999L

        mangaRepository.undelete(manga.id)
        mangaRepository.getMangaById(manga.id).deletedAt.shouldBeNull()
    }

    @Test
    fun `rewriteSourceId moves every row of the old source and reports the count`() = runTest {
        mangaRepository.insertNetworkManga(listOf(networkManga()))
        mangaRepository.insertNetworkManga(listOf(networkManga().copy(url = "/manga/2")))

        mangaRepository.rewriteSourceId(oldSourceId = 7L, newSourceId = 8L) shouldBe 2L
        mangaRepository.getFavorites().map { it.source } shouldContainExactly listOf(8L, 8L)
    }

    @Test
    fun `getSourceIdsWithFavoriteCountAsFlow counts the live favourites per source`() = runTest {
        mangaRepository.insertNetworkManga(listOf(networkManga()))
        mangaRepository.insertNetworkManga(listOf(networkManga().copy(url = "/manga/2")))
        mangaRepository.insertNetworkManga(listOf(networkManga().copy(source = 8L)))

        mangaRepository.getSourceIdsWithFavoriteCountAsFlow().first() shouldBe mapOf(7L to 2L, 8L to 1L)
    }

    @Test
    fun `clear database's count is what its delete removes with keep read off, a soft-deleted row in neither`() = runTest {
        val fixture = insertClearDatabaseFixture()

        mangaRepository.getSourceIdsWithNonLibraryMangaAsFlow().first() shouldBe mapOf(7L to 2L, 8L to 1L)
        val confirmed = mangaRepository.getLiveNonLibraryMangaIds(listOf(7L, 8L))
        confirmed.size shouldBe 3
        mangaRepository.deleteNonLibraryMangaByIds(confirmed, keepReadManga = false)

        remainingMangaIds() shouldContainExactly listOf(fixture.library, fixture.softDeleted)
        mangaRepository.getSourceIdsWithNonLibraryMangaAsFlow().first() shouldBe emptyMap()
    }

    @Test
    fun `with keep read on, clear database removes the count less the entries with progress`() = runTest {
        val fixture = insertClearDatabaseFixture()

        mangaRepository.getSourceIdsWithNonLibraryMangaAsFlow().first() shouldBe mapOf(7L to 2L, 8L to 1L)
        val confirmed = mangaRepository.getLiveNonLibraryMangaIds(listOf(7L, 8L))
        mangaRepository.deleteNonLibraryMangaByIds(confirmed, keepReadManga = true)

        remainingMangaIds() shouldContainExactly listOf(fixture.library, fixture.read, fixture.softDeleted)
        mangaRepository.getSourceIdsWithNonLibraryMangaAsFlow().first() shouldBe mapOf(7L to 1L)
    }

    @Test
    fun `clear database deletes only the rows its confirmation froze, never one a sync moved out of the library since`() = runTest {
        val fixture = insertClearDatabaseFixture()
        val confirmed = mangaRepository.getLiveNonLibraryMangaIds(listOf(7L))
        val unread = confirmed.single { it != fixture.read }
        val otherSource = scalarLong("SELECT _id FROM mangas WHERE source = 8")!!

        // While the dialog is open: the library entry leaves the library, and a frozen row joins it.
        mangaRepository.update(MangaUpdate(id = fixture.library, favorite = false)) shouldBe true
        mangaRepository.update(MangaUpdate(id = unread, favorite = true)) shouldBe true
        mangaRepository.deleteNonLibraryMangaByIds(confirmed + fixture.softDeleted, keepReadManga = false)

        // Only the read entry went: the new non-library row was never confirmed, the rejoined one
        // is a favourite again, the tombstone is not live, and source 8 was not ticked.
        remainingMangaIds() shouldContainExactly listOf(fixture.library, unread, fixture.softDeleted, otherSource)
    }

    @Test
    fun `resetting viewer flags logs one manga change per favourite and none per non-favourite`() = runTest {
        val first = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val second = mangaRepository.insertNetworkManga(listOf(networkManga().copy(url = "/manga/2"))).single()
        mangaRepository.insertNetworkManga(listOf(networkManga().copy(url = "/manga/3", favorite = false)))
        val seqBefore = scalarLong("SELECT coalesce(max(seq), 0) FROM sync_change_log")!!

        mangaRepository.resetViewerFlags() shouldBe true

        scalarString(
            "SELECT group_concat(entity_type || ':' || entity_id || ':' || change_type) FROM " +
                "(SELECT * FROM sync_change_log WHERE seq > $seqBefore ORDER BY entity_id)",
        ) shouldBe "manga:${first.id}:upsert,manga:${second.id}:upsert"
        scalarLong("SELECT count(*) FROM mangas WHERE viewer != 0") shouldBe 0L
    }

    @Test
    fun `the library view maps its aggregates onto the manga`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        categoryRepository.insert(Category(id = 0, name = "One", order = 0, flags = 0))
        val categoryId = categoryRepository.getAll().last().id
        mangaRepository.setMangaCategories(manga.id, listOf(categoryId))
        val chapters = chapterRepository.addAll(
            listOf(
                chapter(manga.id, sourceOrder = 0).copy(read = true, dateUpload = 500L, dateFetch = 10L),
                chapter(manga.id, sourceOrder = 1).copy(bookmark = true, dateUpload = 900L, dateFetch = 20L),
            ),
        )
        historyRepository.upsertHistory(HistoryUpdate(chapters.first().id, Date(700L), 42L))

        val libraryManga = mangaRepository.getLibraryManga().single()
        libraryManga.manga.id shouldBe manga.id
        libraryManga.categories shouldContainExactly listOf(categoryId)
        libraryManga.totalChapters shouldBe 2L
        libraryManga.readCount shouldBe 1L
        libraryManga.bookmarkCount shouldBe 1L
        libraryManga.latestUpload shouldBe 900L
        libraryManga.chapterFetchedAt shouldBe 20L
        libraryManga.lastRead shouldBe 700L
    }

    @Test
    fun `getDuplicateLibraryManga matches on title and counts the live chapters`() = runTest {
        val first = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val second = mangaRepository.insertNetworkManga(
            listOf(networkManga().copy(url = "/manga/2", title = "A title, reprint")),
        ).single()
        chapterRepository.addAll(listOf(chapter(second.id, sourceOrder = 0)))

        val duplicates = mangaRepository.getDuplicateLibraryManga(first.id, "a title")
        duplicates.single().manga.id shouldBe second.id
        duplicates.single().chapterCount shouldBe 1L
    }

    /** Triage E105: `LIKE '%%'` matched every library entry as a duplicate of a blank title. */
    @Test
    fun `getDuplicateLibraryManga matches nothing for a blank title`() = runTest {
        val blank = mangaRepository.insertNetworkManga(
            listOf(networkManga().copy(url = "/manga/blank", title = "")),
        ).single()
        mangaRepository.insertNetworkManga(listOf(networkManga()))

        mangaRepository.getDuplicateLibraryManga(blank.id, "") shouldBe emptyList()
        mangaRepository.getDuplicateLibraryManga(blank.id, "   ") shouldBe emptyList()
    }

    // Chapter ----------------------------------------------------------------------------------

    @Test
    fun `addAll assigns the row ids and the mapper reads every column back`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()

        val added = chapterRepository.addAll(
            listOf(chapter(manga.id, sourceOrder = 0), chapter(manga.id, sourceOrder = 1)),
        )
        added.map { it.id } shouldContainExactly listOf(1L, 2L)

        val stored = chapterRepository.getChapterById(added.first().id)!!
        stored.mangaId shouldBe manga.id
        stored.url shouldBe "/chapter/0"
        stored.name shouldBe "Chapter 0"
        stored.scanlator shouldBe "A scanlator"
        stored.read shouldBe false
        stored.bookmark shouldBe false
        stored.lastPageRead shouldBe 3L
        stored.chapterNumber shouldBe 1.5
        stored.sourceOrder shouldBe 0L
        stored.dateFetch shouldBe 10L
        stored.dateUpload shouldBe 20L
        stored.memo shouldBe JsonObject(mapOf("c" to JsonPrimitive(1)))
        stored.volume shouldBe 2.0
        stored.deletedAt.shouldBeNull()
    }

    @Test
    fun `update coalesces and softDelete hides the chapter from the manga`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val added = chapterRepository.addAll(listOf(chapter(manga.id, sourceOrder = 0))).single()

        chapterRepository.update(ChapterUpdate(id = added.id, read = true, volume = 4.0))
        val updated = chapterRepository.getChapterById(added.id)!!
        updated.read shouldBe true
        updated.volume shouldBe 4.0
        updated.name shouldBe "Chapter 0"

        chapterRepository.softDelete(listOf(added.id), now = 555L)
        chapterRepository.getChapterByMangaId(manga.id).shouldBeEmpty()
        chapterRepository.getChaptersByIds(listOf(added.id)).single().deletedAt shouldBe 555L
    }

    @Test
    fun `the scanlator filter drops excluded scanlators only when it is asked to`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        chapterRepository.addAll(
            listOf(
                chapter(manga.id, sourceOrder = 0),
                chapter(manga.id, sourceOrder = 1).copy(scanlator = "Another scanlator"),
            ),
        )
        mangaRepository.setExcludedScanlators(manga.id, setOf("A scanlator"))

        chapterRepository.getChapterByMangaId(manga.id).size shouldBe 2
        chapterRepository.getChapterByMangaId(manga.id, applyScanlatorFilter = true)
            .map { it.scanlator } shouldContainExactly listOf("Another scanlator")
        chapterRepository.getScanlatorsByMangaId(manga.id) shouldContainExactly
            listOf("A scanlator", "Another scanlator")
    }

    @Test
    fun `getDeletedChaptersByMangaId returns the soft-deleted rows only, and clearDeletedAt brings one back`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val added = chapterRepository.addAll((0L..2L).map { chapter(manga.id, sourceOrder = it) })
        chapterRepository.softDelete(listOf(added[1].id, added[2].id), now = 555L)

        chapterRepository.getDeletedChaptersByMangaId(manga.id).map { it.id } shouldContainExactly
            listOf(added[1].id, added[2].id)

        // A deletedAt beside the flag is ignored, as volume is beside clearVolume.
        chapterRepository.update(ChapterUpdate(id = added[1].id, deletedAt = 777L, clearDeletedAt = true))

        chapterRepository.getChapterByMangaId(manga.id).map { it.id } shouldContainExactly
            listOf(added[0].id, added[1].id)
        chapterRepository.getChapterById(added[1].id)!!.deletedAt.shouldBeNull()
        chapterRepository.getDeletedChaptersByMangaId(manga.id).map { it.id } shouldContainExactly
            listOf(added[2].id)
    }

    @Test
    fun `the scanlator list leaves out a soft-deleted chapter`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val added = chapterRepository.addAll(
            listOf(
                chapter(manga.id, sourceOrder = 0),
                chapter(manga.id, sourceOrder = 1).copy(scanlator = "Another scanlator"),
            ),
        )

        chapterRepository.softDelete(listOf(added[1].id), now = 555L)

        chapterRepository.getScanlatorsByMangaId(manga.id) shouldContainExactly listOf("A scanlator")
    }

    @Test
    fun `removeChaptersWithIds is a hard delete`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val added = chapterRepository.addAll(listOf(chapter(manga.id, sourceOrder = 0))).single()

        chapterRepository.removeChaptersWithIds(listOf(added.id))

        chapterRepository.getChaptersByIds(listOf(added.id)).shouldBeEmpty()
    }

    // Category ---------------------------------------------------------------------------------

    @Test
    fun `insert stores the parent and the mapper reads the fresh columns back`() = runTest {
        categoryRepository.insert(Category(id = 0, name = "Parent", order = 0, flags = 1))
        val parent = categoryRepository.getAll().last()
        categoryRepository.insert(Category(id = 0, name = "Child", order = 1, flags = 2, parentId = parent.id))

        val child = categoryRepository.getAll().last()
        child.name shouldBe "Child"
        child.order shouldBe 1L
        child.flags shouldBe 2L
        child.parentId shouldBe parent.id
        child.deletedAt.shouldBeNull()
    }

    @Test
    fun `delete refuses the system category before it opens a transaction`() = runTest {
        shouldThrow<IllegalArgumentException> {
            categoryRepository.delete(Category.UNCATEGORIZED_ID)
        }
        categoryRepository.getAll().map { it.id } shouldContainExactly listOf(Category.UNCATEGORIZED_ID)
    }

    @Test
    fun `delete soft-deletes, reparents the children and drops the manga links`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        categoryRepository.insert(Category(id = 0, name = "Parent", order = 0, flags = 0))
        val parent = categoryRepository.getAll().last()
        categoryRepository.insert(Category(id = 0, name = "Child", order = 1, flags = 0, parentId = parent.id))
        val child = categoryRepository.getAll().last()
        mangaRepository.setMangaCategories(manga.id, listOf(parent.id))

        categoryRepository.delete(parent.id)

        categoryRepository.getAll().map { it.name } shouldContainExactly listOf("", "Child")
        categoryRepository.get(child.id)!!.parentId.shouldBeNull()
        categoryRepository.getCategoriesByMangaId(manga.id).shouldBeEmpty()
    }

    @Test
    fun `insert keys the category by its trimmed, lower-cased name`() = runTest {
        categoryRepository.insert(Category(id = 0, name = " Shelf ", order = 0, flags = 0))

        val shelf = categoryRepository.getAll().last()
        syncKey(shelf.id) shouldBe CategorySyncKey.of("shelf")
        syncKey(Category.UNCATEGORIZED_ID).shouldBeNull()
    }

    @Test
    fun `inserting a deleted category's name restores that row under the new name`() = runTest {
        categoryRepository.insert(Category(id = 0, name = "Shelf", order = 0, flags = 0))
        val shelf = categoryRepository.getAll().last()
        categoryRepository.delete(shelf.id)
        val versionBefore = scalarLong("SELECT version FROM categories WHERE _id = ${shelf.id}")!!
        val seqBefore = scalarLong("SELECT coalesce(max(seq), 0) FROM sync_change_log")!!

        categoryRepository.insert(Category(id = 0, name = "SHELF", order = 3, flags = 5))

        // One re-create is one change: one upsert of the row, one version bump.
        scalarString(
            "SELECT group_concat(entity_type || ':' || entity_id || ':' || change_type) FROM sync_change_log " +
                "WHERE seq > $seqBefore",
        ) shouldBe "category:${shelf.id}:upsert"
        scalarLong("SELECT version FROM categories WHERE _id = ${shelf.id}") shouldBe versionBefore + 1
        val restored = categoryRepository.getAll().last()
        restored.id shouldBe shelf.id
        restored.name shouldBe "SHELF"
        restored.order shouldBe 3L
        restored.flags shouldBe 5L
        restored.deletedAt.shouldBeNull()
        categoryRepository.getAll().map { it.name } shouldContainExactly listOf("", "SHELF")
        syncKey(shelf.id) shouldBe CategorySyncKey.of("shelf")
        scalarLong("SELECT restored_at FROM categories WHERE _id = ${shelf.id}").shouldNotBeNull()
    }

    @Test
    fun `a name whose key a renamed live category still holds gets a second row with a random key`() = runTest {
        categoryRepository.insert(Category(id = 0, name = "Shelf", order = 0, flags = 0))
        val renamed = categoryRepository.getAll().last()
        categoryRepository.updateName(renamed.id, "Archive")

        categoryRepository.insert(Category(id = 0, name = "Shelf", order = 1, flags = 0))

        val second = categoryRepository.getAll().last()
        second.id shouldNotBe renamed.id
        categoryRepository.getAll().map { it.name } shouldContainExactly listOf("", "Archive", "Shelf")
        syncKey(renamed.id) shouldBe CategorySyncKey.of("shelf")
        syncKey(second.id).shouldNotBeNull() shouldNotBe CategorySyncKey.of("shelf")
    }

    @Test
    fun `restore clears the tombstone and stamps restored_at`() = runTest {
        categoryRepository.insert(Category(id = 0, name = "Shelf", order = 0, flags = 0))
        val shelf = categoryRepository.getAll().last()
        categoryRepository.delete(shelf.id)
        // The tombstone is the app's clock in milliseconds; the stamp passes it even inside one second.
        val deletedAt = categoryRepository.get(shelf.id)!!.deletedAt!!

        categoryRepository.restore(listOf(shelf.id))

        categoryRepository.get(shelf.id)!!.deletedAt.shouldBeNull()
        scalarLong("SELECT restored_at FROM categories WHERE _id = ${shelf.id}")!! shouldBeGreaterThan deletedAt
    }

    @Test
    fun `updateAllOrders renumbers from zero and updateAllFlags skips a null`() = runTest {
        categoryRepository.insert(Category(id = 0, name = "One", order = 5, flags = 3))
        categoryRepository.insert(Category(id = 0, name = "Two", order = 6, flags = 3))
        val ids = categoryRepository.getAll().filterNot { it.isSystemCategory }.map { it.id }

        categoryRepository.updateAllOrders(ids.reversed())
        categoryRepository.getAll().filterNot { it.isSystemCategory }
            .map { it.name to it.order } shouldContainExactly listOf("Two" to 0L, "One" to 1L)

        categoryRepository.updateAllFlags(null)
        categoryRepository.get(ids.first())!!.flags shouldBe 3L
        categoryRepository.updateAllFlags(8L)
        categoryRepository.get(ids.first())!!.flags shouldBe 8L
    }

    // History ----------------------------------------------------------------------------------

    @Test
    fun `upsertHistory accumulates the read duration and the view carries the cover`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val chapter = chapterRepository.addAll(listOf(chapter(manga.id, sourceOrder = 0))).single()

        historyRepository.upsertHistory(HistoryUpdate(chapter.id, Date(700L), 42L))
        historyRepository.upsertHistory(HistoryUpdate(chapter.id, Date(800L), 8L))

        val row = historyRepository.getHistory("").first().single()
        row.mangaId shouldBe manga.id
        row.chapterId shouldBe chapter.id
        row.title shouldBe "A title"
        row.chapterNumber shouldBe 1.5
        row.readAt shouldBe Date(800L)
        row.readDuration shouldBe 50L
        row.coverData.url shouldBe "/cover.png"
        row.coverData.sourceId shouldBe 7L

        historyRepository.getTotalReadDuration() shouldBe 50L
        historyRepository.getLastHistory()!!.chapterId shouldBe chapter.id
        historyRepository.getHistoryByMangaId(manga.id).single().readDuration shouldBe 50L
    }

    /**
     * A sync keeps a downloaded chapter the source stopped listing as a soft-deleted row, with its
     * history. History must not offer it (the reader cannot open it), and must still list the entry
     * at the latest chapter it can open.
     */
    @Test
    fun `history skips a soft-deleted chapter and shows the entry at its latest live read`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val (kept, removed) = chapterRepository.addAll(
            listOf(chapter(manga.id, sourceOrder = 0), chapter(manga.id, sourceOrder = 1)),
        )
        historyRepository.upsertHistory(HistoryUpdate(kept.id, Date(700L), 1L))
        historyRepository.upsertHistory(HistoryUpdate(removed.id, Date(800L), 1L))
        historyRepository.getHistory("").first().map { it.chapterId } shouldContainExactly listOf(removed.id)

        chapterRepository.softDelete(listOf(removed.id), now = 555L)

        val row = historyRepository.getHistory("").first().single()
        row.chapterId shouldBe kept.id
        row.readAt shouldBe Date(700L)
        historyRepository.getLastHistory()!!.chapterId shouldBe kept.id
    }

    /** The latest-read join matches on the timestamp, so a tie must not bring the removed row back. */
    @Test
    fun `history skips a soft-deleted chapter read at the same moment as a live one`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val (kept, removed) = chapterRepository.addAll(
            listOf(chapter(manga.id, sourceOrder = 0), chapter(manga.id, sourceOrder = 1)),
        )
        historyRepository.upsertHistory(HistoryUpdate(kept.id, Date(700L), 1L))
        historyRepository.upsertHistory(HistoryUpdate(removed.id, Date(700L), 1L))

        chapterRepository.softDelete(listOf(removed.id), now = 555L)

        historyRepository.getHistory("").first().map { it.chapterId } shouldContainExactly listOf(kept.id)
    }

    @Test
    fun `resetHistory removes the row in the same transaction`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val chapter = chapterRepository.addAll(listOf(chapter(manga.id, sourceOrder = 0))).single()
        historyRepository.upsertHistory(HistoryUpdate(chapter.id, Date(700L), 42L))
        val historyId = historyRepository.getHistoryByMangaId(manga.id).single().id

        historyRepository.resetHistory(historyId)

        historyRepository.getHistoryByMangaId(manga.id).shouldBeEmpty()
    }

    @Test
    fun `resetHistoryByMangaId clears every chapter of the manga`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        val chapters = chapterRepository.addAll(
            listOf(chapter(manga.id, sourceOrder = 0), chapter(manga.id, sourceOrder = 1)),
        )
        chapters.forEach { historyRepository.upsertHistory(HistoryUpdate(it.id, Date(700L), 1L)) }

        historyRepository.resetHistoryByMangaId(manga.id)

        historyRepository.getHistoryByMangaId(manga.id).shouldBeEmpty()
        historyRepository.getTotalReadDuration() shouldBe 0L
    }

    // Track ------------------------------------------------------------------------------------

    @Test
    fun `insertAll stores every track column and the mapper reads it back`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()

        trackRepository.insertAll(listOf(track(manga.id, trackerId = 3L)))

        val stored = trackRepository.getTracksByMangaId(manga.id).single()
        stored.trackerId shouldBe 3L
        stored.remoteId shouldBe 11L
        stored.libraryId shouldBe 12L
        stored.title shouldBe "A tracked title"
        stored.lastChapterRead shouldBe 4.0
        stored.totalChapters shouldBe 20L
        stored.status shouldBe 2L
        stored.score shouldBe 8.5
        stored.remoteUrl shouldBe "/remote/11"
        stored.startDate shouldBe 100L
        stored.finishDate shouldBe 200L
        stored.private shouldBe true

        trackRepository.getTrackById(stored.id)!! shouldBe stored
        trackRepository.getTracksAsFlow().first() shouldContainExactly listOf(stored)
        trackRepository.deleteIfBoundTo(manga.id, trackerId = 3L, boundRemoteId = stored.remoteId) shouldBe true
        trackRepository.getTracksByMangaId(manga.id).shouldBeEmpty()
    }

    @Test
    fun `insert of a pair already bound replaces the row under a new id`() = runTest {
        // manga_sync's UNIQUE (manga_id, sync_id) ON CONFLICT REPLACE under a plain INSERT, as in
        // upstream: why the tracker retry store is keyed by manga and tracker (phase 3 unit TQ).
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        trackRepository.insert(track(manga.id, trackerId = 1L))
        val bound = trackRepository.getTracksByMangaId(manga.id).single()

        trackRepository.insert(bound.copy(lastChapterRead = 5.0))

        val stored = trackRepository.getTracksByMangaId(manga.id).single()
        stored.id shouldNotBe bound.id
        stored shouldBe bound.copy(id = stored.id, lastChapterRead = 5.0)
        trackRepository.getTrackById(bound.id).shouldBeNull()
    }

    @Test
    fun `rebind replaces the pair's row and moves the tracker-set clock, where an insert does not`() = runTest {
        // A bind to another entry changes the binding, which travels by the clock (R82); an insert
        // of a bound pair is progress (phase 3 unit T3).
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        trackRepository.insert(track(manga.id, trackerId = 1L))
        val bound = trackRepository.getTracksByMangaId(manga.id).single()
        driver.execute(null, "UPDATE mangas SET trackers_modified_at = 5 WHERE _id = ${manga.id}", 0)

        trackRepository.insert(bound.copy(remoteId = 21L))
        scalarLong("SELECT trackers_modified_at FROM mangas WHERE _id = ${manga.id}") shouldBe 5L

        trackRepository.rebind(bound.copy(remoteId = 31L, title = "Another entry"))

        val stored = trackRepository.getTracksByMangaId(manga.id).single()
        stored.remoteId shouldBe 31L
        stored.title shouldBe "Another entry"
        scalarLong("SELECT trackers_modified_at FROM mangas WHERE _id = ${manga.id}")!! shouldBeGreaterThan 5L
    }

    @Test
    fun `insertIfBoundTo stores only while the pair names the entry, and moves no clock`() = runTest {
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        trackRepository.insert(track(manga.id, trackerId = 1L))
        val bound = trackRepository.getTracksByMangaId(manga.id).single()
        driver.execute(null, "UPDATE mangas SET trackers_modified_at = 5 WHERE _id = ${manga.id}", 0)

        trackRepository.insertIfBoundTo(bound.copy(lastChapterRead = 6.0), boundRemoteId = 11L) shouldBe true
        trackRepository.getTracksByMangaId(manga.id).single().lastChapterRead shouldBe 6.0
        scalarLong("SELECT trackers_modified_at FROM mangas WHERE _id = ${manga.id}") shouldBe 5L

        trackRepository.insertIfBoundTo(bound.copy(lastChapterRead = 7.0), boundRemoteId = 99L) shouldBe false
        trackRepository.getTracksByMangaId(manga.id).single().lastChapterRead shouldBe 6.0

        trackRepository.insertIfBoundTo(track(manga.id, trackerId = 2L), boundRemoteId = 11L) shouldBe false
        trackRepository.getTracksByMangaId(manga.id).map { it.trackerId } shouldBe listOf(1L)
    }

    @Test
    fun `deleteIfBoundTo deletes only while the pair names the entry, and then moves the clock`() = runTest {
        // The track sheet's remove acts on the row the owner opened the dialog on (phase 4 unit TR).
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga())).single()
        trackRepository.insert(track(manga.id, trackerId = 1L))
        val bound = trackRepository.getTracksByMangaId(manga.id).single()
        driver.execute(null, "UPDATE mangas SET trackers_modified_at = 5 WHERE _id = ${manga.id}", 0)

        trackRepository.deleteIfBoundTo(manga.id, trackerId = 1L, boundRemoteId = 99L) shouldBe false
        trackRepository.getTracksByMangaId(manga.id).single() shouldBe bound
        scalarLong("SELECT trackers_modified_at FROM mangas WHERE _id = ${manga.id}") shouldBe 5L

        trackRepository.deleteIfBoundTo(manga.id, trackerId = 2L, boundRemoteId = 11L) shouldBe false
        trackRepository.getTracksByMangaId(manga.id).single() shouldBe bound

        trackRepository.deleteIfBoundTo(manga.id, trackerId = 1L, boundRemoteId = 11L) shouldBe true
        trackRepository.getTracksByMangaId(manga.id).shouldBeEmpty()
        scalarLong("SELECT trackers_modified_at FROM mangas WHERE _id = ${manga.id}")!! shouldBeGreaterThan 5L
    }

    // Updates ----------------------------------------------------------------------------------

    @Test
    fun `subscribeAll folds an empty include list into includeAll`() = runTest {
        val manga = insertUpdatableManga()

        val updates = updatesRepository.subscribeAll(
            after = 0L,
            limit = 100L,
            unread = null,
            started = null,
            bookmarked = null,
            hideExcludedScanlators = false,
            includedCategories = emptyList(),
            excludedCategories = emptyList(),
        ).first()

        updates.single().mangaId shouldBe manga.id
        updates.single().mangaTitle shouldBe "A title"
        updates.single().chapterName shouldBe "Chapter 0"
        updates.single().coverData.url shouldBe "/cover.png"
    }

    @Test
    fun `subscribeAll reads category zero as the uncategorised flag`() = runTest {
        val manga = insertUpdatableManga()
        categoryRepository.insert(Category(id = 0, name = "One", order = 0, flags = 0))
        val categoryId = categoryRepository.getAll().last().id

        // Uncategorised only: the manga has no link row, so it is included.
        updates(included = listOf(Category.UNCATEGORIZED_ID)).size shouldBe 1
        // A real category it is not in: excluded.
        updates(included = listOf(categoryId)).shouldBeEmpty()

        mangaRepository.setMangaCategories(manga.id, listOf(categoryId))
        updates(included = listOf(categoryId)).size shouldBe 1
        updates(included = listOf(Category.UNCATEGORIZED_ID)).shouldBeEmpty()
        updates(excluded = listOf(categoryId)).shouldBeEmpty()
    }

    @Test
    fun `the unread filter is the negation of the read column`() = runTest {
        val manga = insertUpdatableManga()
        val chapterId = chapterRepository.getChapterByMangaId(manga.id).single().id

        updates(unread = true).size shouldBe 1
        updates(unread = false).shouldBeEmpty()

        chapterRepository.update(ChapterUpdate(id = chapterId, read = true))
        updates(unread = true).shouldBeEmpty()
        updates(unread = false).size shouldBe 1
    }

    /**
     * Upstream's three started cases (phase 4 unit UF): a read chapter is neither started nor not
     * started, so it leaves the list under both. The contract's `(...) = :started` kept it under
     * "not started".
     */
    @Test
    fun `the started filter follows upstream's three cases`() = runTest {
        val manga = insertUpdatableManga()
        val chapterId = chapterRepository.getChapterByMangaId(manga.id).single().id
        // The fixture chapter carries progress, so it starts out started.
        chapterRepository.update(ChapterUpdate(id = chapterId, lastPageRead = 0L))

        updates(started = true).shouldBeEmpty()
        updates(started = false).size shouldBe 1

        chapterRepository.update(ChapterUpdate(id = chapterId, lastPageRead = 3L))
        updates(started = true).size shouldBe 1
        updates(started = false).shouldBeEmpty()

        chapterRepository.update(ChapterUpdate(id = chapterId, read = true))
        updates(started = true).shouldBeEmpty()
        updates(started = false).shouldBeEmpty()
        updates().size shouldBe 1
    }

    /** Excluding the default category hides the entries in no category, as upstream's does (unit UF). */
    @Test
    fun `an excluded default category hides the entries in no category`() = runTest {
        val manga = insertUpdatableManga()
        categoryRepository.insert(Category(id = 0, name = "One", order = 0, flags = 0))
        val categoryId = categoryRepository.getAll().last().id

        updates(excluded = listOf(Category.UNCATEGORIZED_ID)).shouldBeEmpty()

        mangaRepository.setMangaCategories(manga.id, listOf(categoryId))
        updates(excluded = listOf(Category.UNCATEGORIZED_ID)).size shouldBe 1
        updates(excluded = listOf(Category.UNCATEGORIZED_ID, categoryId)).shouldBeEmpty()
    }

    /**
     * The Updates tab's window (R107 carry-over 2): the query keeps a chapter uploaded after the
     * limit and drops one uploaded on it or before, whatever its fetch date, as upstream's `dateUpload >
     * :after` does. The tab passes now less three months (`UpdatesViewModelTest`).
     */
    @Test
    fun `the updates window keeps a chapter uploaded just after its limit and drops one on it or before`() = runTest {
        val limit = 1_000_000L
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga().copy(dateAdded = 0L))).single()
        chapterRepository.addAll(
            listOf(
                chapter(manga.id, sourceOrder = 0).copy(dateUpload = limit + 1, dateFetch = 10L),
                chapter(manga.id, sourceOrder = 1).copy(dateUpload = limit, dateFetch = 10L),
                // Fetched well inside the window, uploaded before it: the upload date decides.
                chapter(manga.id, sourceOrder = 2).copy(dateUpload = limit - 1, dateFetch = limit + 1_000),
            ),
        )

        updates(after = limit).map { it.chapterName } shouldContainExactly listOf("Chapter 0")
        updates(after = limit - 2).map { it.chapterName }.toSet() shouldBe setOf("Chapter 0", "Chapter 1", "Chapter 2")
    }

    /**
     * The order the Updates tab's day headers and folds read (R112 carry-over 2), stated in the
     * query: newest fetch first, then by entry, then by chapter id. The rows go in out of that
     * order. A library update stamps every new chapter of one fetch with the same `date_fetch`, so
     * ties are the common case: one entry's chapters of one fetch stay together, in the order they
     * were inserted (the source's order), and two entries tied on the same instant do not interleave.
     *
     * Chapter 2 is read. Without the query's own order the ties came in the plan's scan order, the
     * chapters of an entry by `idx_chapters_manga_id_read`, so unread first: marking a chapter read
     * moved it within its fold, and moved the fold's key, its last chapter.
     */
    @Test
    fun `the updates query answers newest fetch first, then by entry, then by chapter id`() = runTest {
        fun entry(url: String) = networkManga().copy(url = url, dateAdded = 0L)
        val (first, second) = mangaRepository.insertNetworkManga(listOf(entry("/manga/a"), entry("/manga/b")))
        // Inserted oldest fetch first and the two entries interleaved, so neither the insert order
        // nor the chapter id order is the answer.
        chapterRepository.addAll(
            listOf(
                chapter(first.id, sourceOrder = 0).copy(dateFetch = 100L),
                chapter(second.id, sourceOrder = 1).copy(dateFetch = 300L),
                chapter(first.id, sourceOrder = 2).copy(dateFetch = 300L, read = true),
                chapter(second.id, sourceOrder = 3).copy(dateFetch = 200L),
                chapter(first.id, sourceOrder = 4).copy(dateFetch = 300L),
                chapter(second.id, sourceOrder = 5).copy(dateFetch = 300L),
                chapter(first.id, sourceOrder = 6).copy(dateFetch = 100L),
            ),
        )

        updates().map { it.mangaId to it.chapterName } shouldContainExactly listOf(
            first.id to "Chapter 2",
            first.id to "Chapter 4",
            second.id to "Chapter 1",
            second.id to "Chapter 5",
            second.id to "Chapter 3",
            first.id to "Chapter 0",
            first.id to "Chapter 6",
        )
    }

    // Stub source ------------------------------------------------------------------------------

    @Test
    fun `the stub repository upserts and the seed row answers for the local source`() = runTest {
        stubSourceRepository.getStubSource(0L)!!.name shouldBe "Local source"
        stubSourceRepository.getStubSource(42L).shouldBeNull()

        stubSourceRepository.upsertStubSource(42L, lang = "en", name = "Remembered")
        stubSourceRepository.getStubSource(42L)!!.lang shouldBe "en"

        stubSourceRepository.upsertStubSource(42L, lang = "fr", name = "Renamed")
        val stub = stubSourceRepository.getStubSource(42L)!!
        stub.name shouldBe "Renamed"
        stub.lang shouldBe "fr"
        stubSourceRepository.subscribeAll().first().map { it.id } shouldContainExactly listOf(0L, 42L)
    }

    @Test
    fun `StubSourceNames remembers a name over the same table`() = runTest {
        stubSourceNames.find(42L).shouldBeNull()

        stubSourceNames.remember(42L, name = "Remembered", lang = "en")

        stubSourceNames.find(42L) shouldBe StubSourceNames.Entry(id = 42L, name = "Remembered", lang = "en")
        stubSourceRepository.getStubSource(42L)!!.name shouldBe "Remembered"
    }

    // Helpers ----------------------------------------------------------------------------------

    /** The ids clear database's tests follow; every other row of the fixture is one it removes. */
    private data class ClearDatabaseFixture(val library: Long, val read: Long, val softDeleted: Long)

    /**
     * On source 7: a library entry, a non-library entry with no progress, one with a read chapter,
     * and a removed favourite whose row is a soft-deleted tombstone. On source 8: one more
     * non-library entry with no progress.
     */
    private suspend fun insertClearDatabaseFixture(): ClearDatabaseFixture {
        suspend fun insert(url: String, favorite: Boolean, source: Long = 7L) = mangaRepository.insertNetworkManga(
            listOf(networkManga().copy(url = url, favorite = favorite, source = source)),
        ).single()

        val library = insert("/manga/1", favorite = true)
        insert("/manga/2", favorite = false)
        val read = insert("/manga/3", favorite = false)
        chapterRepository.addAll(listOf(chapter(read.id, sourceOrder = 0).copy(read = true)))
        val softDeleted = insert("/manga/4", favorite = true)
        mangaRepository.softDelete(softDeleted.id, now = 999L)
        insert("/manga/5", favorite = false, source = 8L)
        return ClearDatabaseFixture(library = library.id, read = read.id, softDeleted = softDeleted.id)
    }

    private fun remainingMangaIds(): List<Long> =
        scalarString("SELECT group_concat(_id) FROM (SELECT _id FROM mangas ORDER BY _id)")
            .orEmpty().split(',').filter { it.isNotEmpty() }.map { it.toLong() }

    private suspend fun insertUpdatableManga(): Manga {
        // updatesView only shows chapters fetched after the manga was added.
        val manga = mangaRepository.insertNetworkManga(listOf(networkManga().copy(dateAdded = 0L))).single()
        chapterRepository.addAll(listOf(chapter(manga.id, sourceOrder = 0).copy(dateFetch = 10L)))
        return manga
    }

    private suspend fun updates(
        unread: Boolean? = null,
        started: Boolean? = null,
        included: List<Long> = emptyList(),
        excluded: List<Long> = emptyList(),
        after: Long = 0L,
    ) = updatesRepository.subscribeAll(
        after = after,
        limit = 100L,
        unread = unread,
        started = started,
        bookmarked = null,
        hideExcludedScanlators = false,
        includedCategories = included,
        excludedCategories = excluded,
    ).first()

    private fun syncKey(categoryId: Long): String? =
        scalarString("SELECT sync_key FROM categories WHERE _id = $categoryId")

    private fun scalarLong(sql: String): Long? = scalarString(sql)?.toLong()

    private fun scalarString(sql: String): String? = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor: SqlCursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        parameters = 0,
    ).value

    private fun networkManga() = Manga.create().copy(
        source = 7L,
        url = "/manga/1",
        title = "A title",
        artist = "An artist",
        author = "An author",
        description = "A description",
        genre = listOf("action", "drama"),
        status = 2L,
        thumbnailUrl = "/cover.png",
        favorite = true,
        lastUpdate = 111L,
        nextUpdate = 222L,
        initialized = true,
        viewerFlags = 3L,
        chapterFlags = 4L,
        coverLastModified = 333L,
        dateAdded = 444L,
        updateStrategy = UpdateStrategy.ONLY_FETCH_ONCE,
        fetchInterval = 5,
        memo = JsonObject(mapOf("k" to JsonPrimitive("v"))),
    )

    private fun chapter(mangaId: Long, sourceOrder: Long) = Chapter.create().copy(
        mangaId = mangaId,
        url = "/chapter/$sourceOrder",
        name = "Chapter $sourceOrder",
        scanlator = "A scanlator",
        lastPageRead = 3L,
        chapterNumber = 1.5,
        sourceOrder = sourceOrder,
        dateFetch = 10L,
        dateUpload = 20L,
        memo = JsonObject(mapOf("c" to JsonPrimitive(1))),
        volume = 2.0,
    )

    private fun track(mangaId: Long, trackerId: Long) = Track(
        id = -1L,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = 11L,
        libraryId = 12L,
        title = "A tracked title",
        lastChapterRead = 4.0,
        totalChapters = 20L,
        status = 2L,
        score = 8.5,
        remoteUrl = "/remote/11",
        startDate = 100L,
        finishDate = 200L,
        private = true,
    )
}
