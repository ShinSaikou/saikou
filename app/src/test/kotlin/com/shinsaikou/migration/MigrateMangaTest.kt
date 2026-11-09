package com.shinsaikou.migration

import app.cash.sqldelight.db.QueryResult
import com.shinsaikou.domain.manga.model.MangaDetailsOverrides
import com.shinsaikou.migration.MigrationTestEnvironment.Companion.chapter
import com.shinsaikou.migration.MigrationTestEnvironment.Companion.info
import com.shinsaikou.downloads.MigrationDownloads
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.tracking.DelayedTrackingStore
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import tachiyomi.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import java.io.File
import java.io.IOException

/**
 * Phase 4 unit MA's migration of one entry, [MigrateManga], against the real schema and triggers:
 * each of upstream's flags on and off with the fields upstream's use case writes, the three shapes of a
 * copied track and the tracker-set clock, the guards and the one transaction, the file steps after
 * the commit, the queued tracker push, and nothing handed to auto-download, the delete after reading
 * or a tracker push.
 */
class MigrateMangaTest {

    @TempDir
    lateinit var tempDir: File

    private val environments = mutableListOf<MigrationTestEnvironment>()

    @AfterEach
    fun tearDown() {
        environments.forEach { it.close() }
    }

    private fun env() = MigrationTestEnvironment(tempDir).also { environments += it }

    /** The old entry and the target, with their chapters, as a test starts from them. */
    private class Seeded(val current: Manga, val target: Manga, val targetChapters: List<Chapter>)

    /**
     * The old entry on [OLD_SOURCE], in the library, read to chapter 2 with chapters 2 and 3
     * bookmarked; the target on [NEW_SOURCE], outside the library unless [targetFavorite], whose
     * chapter 1 is bookmarked (so a false bookmark is copied onto it), with a chapter 4.5 the old
     * entry lacks and a chapter with no recognised number. The target's source lists exactly the
     * target's chapters, so the re-fetch inserts nothing.
     */
    private suspend fun MigrationTestEnvironment.seed(targetFavorite: Boolean = false, currentSourceInstalled: Boolean = true): Seeded {
        val current = manga(OLD_SOURCE, "/old", "Same title") {
            it.copy(
                favorite = true,
                dateAdded = OLD_DATE_ADDED,
                chapterFlags = OLD_CHAPTER_FLAGS,
                viewerFlags = OLD_VIEWER_FLAGS,
                notes = "old notes",
                manualPosition = OLD_POSITION,
            )
        }
        chapters(
            current.id,
            chapter("/old/1", 1.0, read = true, dateFetch = 111),
            chapter("/old/2", 2.0, read = true, bookmark = true, dateFetch = 222),
            chapter("/old/3", 3.0, bookmark = true, dateFetch = 333),
            chapter("/old/4", 4.0, dateFetch = 444),
        )
        val target = manga(NEW_SOURCE, "/new", "Same title") {
            if (targetFavorite) it.copy(favorite = true, dateAdded = TARGET_DATE_ADDED, notes = "target notes") else it
        }
        val targetChapters = chapters(
            target.id,
            chapter("/new/1", 1.0, bookmark = true, dateFetch = 9),
            chapter("/new/2", 2.0, dateFetch = 9),
            chapter("/new/3", 3.0, dateFetch = 9),
            chapter("/new/4.5", 4.5, dateFetch = 9),
            chapter("/new/extra", -1.0, dateFetch = 9).copy(name = "Extra"),
        )
        source(NEW_SOURCE, targetChapters.map(::info))
        if (currentSourceInstalled) source(OLD_SOURCE, emptyList())
        calls.clear()
        return Seeded(current, target, targetChapters)
    }

    private suspend fun MigrationTestEnvironment.chaptersOf(mangaId: Long): Map<String, Chapter> =
        chapterRepository.getChapterByMangaId(mangaId).associateBy { it.url }

    @Test
    fun `with Chapters on, read flags are raised to the old entry's highest read chapter and number matches take its bookmark and fetch date`() = runTest {
        val env = env()
        val (current, target) = env.seed().let { it.current to it.target }

        env.migrateManga(current, target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

        val chapters = env.chaptersOf(target.id)
        assertSoftly {
            chapters.getValue("/new/1").read shouldBe true
            chapters.getValue("/new/1").bookmark shouldBe false
            chapters.getValue("/new/1").dateFetch shouldBe 111
            chapters.getValue("/new/2").read shouldBe true
            chapters.getValue("/new/2").bookmark shouldBe true
            chapters.getValue("/new/2").dateFetch shouldBe 222
            chapters.getValue("/new/3").read shouldBe false
            chapters.getValue("/new/3").bookmark shouldBe true
            chapters.getValue("/new/3").dateFetch shouldBe 333
            chapters.getValue("/new/4.5").read shouldBe false
            chapters.getValue("/new/4.5").dateFetch shouldBe 9
            chapters.getValue("/new/extra").read shouldBe false
            chapters.getValue("/new/extra").dateFetch shouldBe 9
        }
    }

    @Test
    fun `with Chapters off, no target chapter is written`() = runTest {
        val env = env()
        val seeded = env.seed()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.chapterUpdates.shouldBeEmpty()
        env.chaptersOf(seeded.target.id).values.sortedBy { it.id }.map { Triple(it.read, it.bookmark, it.dateFetch) } shouldBe
            seeded.targetChapters.map { Triple(it.read, it.bookmark, it.dateFetch) }
    }

    @Test
    fun `the chapter write holds only read, bookmark and fetch date, and leaves every other field as stored`() = runTest {
        val env = env()
        val seeded = env.seed()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

        env.chapterUpdates.map { it.id }.toSet() shouldBe seeded.targetChapters.take(3).map { it.id }.toSet()
        env.chapterUpdates.forEach { update ->
            update.copy(read = null, bookmark = null, dateFetch = null) shouldBe ChapterUpdate(id = update.id)
        }
        val after = env.chaptersOf(seeded.target.id)
        seeded.targetChapters.forEach { before ->
            val now = after.getValue(before.url)
            assertSoftly {
                now.name shouldBe before.name
                now.chapterNumber shouldBe before.chapterNumber
                now.sourceOrder shouldBe before.sourceOrder
                now.dateUpload shouldBe before.dateUpload
                now.scanlator shouldBe before.scanlator
                now.lastPageRead shouldBe before.lastPageRead
            }
        }
    }

    /** Writes [chapterId]'s history row as the reader leaves it. */
    private fun MigrationTestEnvironment.history(chapterId: Long, readAt: Long, timeRead: Long) =
        execute("INSERT INTO history(chapter_id, last_read, time_read) VALUES ($chapterId, $readAt, $timeRead)")

    /** Each chapter's history of [mangaId] by url, as (read at, time read). */
    private suspend fun MigrationTestEnvironment.historyOf(mangaId: Long): Map<String, Pair<Long?, Long>> {
        val urls = chapterRepository.getChapterByMangaId(mangaId).associate { it.id to it.url }
        return historyRepository.getHistoryByMangaId(mangaId).associate { urls.getValue(it.chapterId) to (it.readAt?.time to it.readDuration) }
    }

    /** The old entry's chapters by url. */
    private suspend fun MigrationTestEnvironment.oldChapterIds(mangaId: Long): Map<String, Long> =
        chapterRepository.getChapterByMangaId(mangaId).associate { it.url to it.id }

    @Test
    fun `with Chapters on, Migrate moves the history onto the target's chapters of the same number, reading time included`() = runTest {
        val env = env()
        val seeded = env.seed()
        val old = env.oldChapterIds(seeded.current.id)
        env.history(old.getValue("/old/2"), readAt = 2_000, timeRead = 60)
        env.history(old.getValue("/old/3"), readAt = 3_000, timeRead = 30)
        // The target has no chapter 4, so its history has nowhere to go and stays.
        env.history(old.getValue("/old/4"), readAt = 1_500, timeRead = 10)
        val totalBefore = env.historyRepository.getTotalReadDuration()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

        assertSoftly {
            env.historyOf(seeded.target.id) shouldBe mapOf("/new/2" to (2_000L to 60L), "/new/3" to (3_000L to 30L))
            env.historyOf(seeded.current.id) shouldBe mapOf("/old/4" to (1_500L to 10L))
            env.historyRepository.getTotalReadDuration() shouldBe totalBefore
            // History lists the entry under its new source, at the chapter read last.
            env.historyRepository.getHistory("").first().map { it.mangaId to it.chapterNumber } shouldContainExactly
                listOf(seeded.target.id to 3.0, seeded.current.id to 4.0)
        }
    }

    @Test
    fun `Copy copies the read dates and leaves the old entry's history and the reading total as they were`() = runTest {
        val env = env()
        val seeded = env.seed()
        val old = env.oldChapterIds(seeded.current.id)
        env.history(old.getValue("/old/2"), readAt = 2_000, timeRead = 60)
        val before = env.historyOf(seeded.current.id)
        val totalBefore = env.historyRepository.getTotalReadDuration()

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = setOf(MigrationFlag.CHAPTER))

        assertSoftly {
            env.historyOf(seeded.target.id) shouldBe mapOf("/new/2" to (2_000L to 0L))
            env.historyOf(seeded.current.id) shouldBe before
            env.historyRepository.getTotalReadDuration() shouldBe totalBefore
        }
    }

    @Test
    fun `history the target already has keeps the later read date and, on Migrate, adds the reading time`() = runTest {
        listOf(true, false).forEach { replace ->
            val env = env()
            val seeded = env.seed()
            val old = env.oldChapterIds(seeded.current.id)
            env.history(old.getValue("/old/2"), readAt = 2_000, timeRead = 60)
            env.history(old.getValue("/old/3"), readAt = 9_000, timeRead = 30)
            env.history(seeded.targetChapters[1].id, readAt = 5_000, timeRead = 20)
            env.history(seeded.targetChapters[2].id, readAt = 4_000, timeRead = 5)

            env.migrateManga(seeded.current, seeded.target, replace = replace, flags = setOf(MigrationFlag.CHAPTER))

            env.historyOf(seeded.target.id) shouldBe if (replace) {
                mapOf("/new/2" to (5_000L to 80L), "/new/3" to (9_000L to 35L))
            } else {
                mapOf("/new/2" to (5_000L to 20L), "/new/3" to (9_000L to 5L))
            }
        }
    }

    @Test
    fun `two copies of one number fold onto one target chapter, the copy its source lists first`() = runTest {
        val env = env()
        val current = env.manga(OLD_SOURCE, "/old", "Same title") { it.copy(favorite = true) }
        val oldChapters = env.chapters(
            current.id,
            chapter("/old/2a", 2.0).copy(scanlator = "Group A"),
            chapter("/old/2b", 2.0).copy(scanlator = "Group B"),
        )
        val target = env.manga(NEW_SOURCE, "/new", "Same title")
        val targetChapters = env.chapters(
            target.id,
            chapter("/new/2a", 2.0).copy(scanlator = "Group A"),
            chapter("/new/2b", 2.0).copy(scanlator = "Group B"),
        )
        env.source(NEW_SOURCE, targetChapters.map(::info))
        env.history(oldChapters[0].id, readAt = 2_000, timeRead = 60)
        env.history(oldChapters[1].id, readAt = 2_500, timeRead = 15)

        env.migrateManga(current, target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

        env.historyOf(target.id) shouldBe mapOf("/new/2a" to (2_500L to 75L))
        env.historyOf(current.id) shouldBe emptyMap()
    }

    @Test
    fun `with Chapters off, no history is carried`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.history(env.oldChapterIds(seeded.current.id).getValue("/old/2"), readAt = 2_000, timeRead = 60)
        val before = env.historyOf(seeded.current.id)

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.historyOf(seeded.target.id) shouldBe emptyMap()
        env.historyOf(seeded.current.id) shouldBe before
        env.calls.none { it.startsWith("history") } shouldBe true
    }

    @Test
    fun `the carried history is logged for sync on the target's chapters, and the moved rows as deletes`() = runTest {
        val env = env()
        val seeded = env.seed()
        val old = env.oldChapterIds(seeded.current.id)
        env.history(old.getValue("/old/2"), readAt = 2_000, timeRead = 60)
        val seqBefore = env.long("SELECT coalesce(max(seq), 0) FROM sync_change_log")!!

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

        val rows = "SELECT entity_id || ' ' || change_type FROM sync_change_log WHERE entity_type = 'history' AND seq > $seqBefore"
        val logged = buildList {
            env.driver.executeQuery(null, "$rows ORDER BY seq", { cursor ->
                while (cursor.next().value) add(cursor.getString(0)!!)
                QueryResult.Unit
            }, 0).value
        }
        logged shouldContainExactly listOf("${seeded.targetChapters[1].id} upsert", "${old.getValue("/old/2")} delete")
    }

    @Test
    fun `with Categories on the target takes the old entry's categories, and with it off keeps its own`() = runTest {
        val env = env()
        val seeded = env.seed(targetFavorite = true)
        val reading = env.category("Reading")
        val later = env.category("Later")
        env.mangaRepository.setMangaCategories(seeded.current.id, listOf(reading.id))
        env.mangaRepository.setMangaCategories(seeded.target.id, listOf(later.id))

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = emptySet())
        env.categoryRepository.getCategoriesByMangaId(seeded.target.id).map { it.id } shouldContainExactly listOf(later.id)

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = setOf(MigrationFlag.CATEGORY))
        env.categoryRepository.getCategoriesByMangaId(seeded.target.id).map { it.id } shouldContainExactly listOf(reading.id)
    }

    @Test
    fun `with Notes on the target takes the old entry's notes, and with it off keeps its own`() = runTest {
        val env = env()
        val seeded = env.seed(targetFavorite = true)

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = emptySet())
        env.mangaRepository.getMangaById(seeded.target.id).notes shouldBe "target notes"

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = setOf(MigrationFlag.NOTES))
        env.mangaRepository.getMangaById(seeded.target.id).notes shouldBe "old notes"
    }

    @Test
    fun `the owner's edited title and description follow the entry onto the target, before the favourite write`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mangaRepository.setDetailsOverride(
            seeded.current.id,
            MangaDetailsOverrides(title = "Edited title", description = "Edited description"),
        )
        env.calls.clear()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        val new = env.mangaRepository.getMangaById(seeded.target.id)
        assertSoftly {
            new.titleOverride shouldBe "Edited title"
            new.descriptionOverride shouldBe "Edited description"
            new.displayTitle shouldBe "Edited title"
            new.title shouldBe "Same title"
            env.calls.map { it.substringBefore(' ') } shouldContainExactly listOf("details", "manga")
        }
    }

    @Test
    fun `Copy carries the edits too and leaves the old entry's own`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mangaRepository.setDetailsOverride(seeded.current.id, MangaDetailsOverrides(title = "Edited title"))

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = emptySet())

        env.mangaRepository.getMangaById(seeded.target.id).titleOverride shouldBe "Edited title"
        env.mangaRepository.getMangaById(seeded.current.id).titleOverride shouldBe "Edited title"
    }

    @Test
    fun `a field the old entry never edited keeps the target's edit, and an edit equal to the target's text is none`() = runTest {
        val env = env()
        val seeded = env.seed()
        // The old entry's title edit is the target source's own title, so it is no edit there.
        env.mangaRepository.setDetailsOverride(
            seeded.current.id,
            MangaDetailsOverrides(title = "Same title", description = "Edited description"),
        )
        env.mangaRepository.setDetailsOverride(
            seeded.target.id,
            MangaDetailsOverrides(description = "Target description"),
        )

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        val new = env.mangaRepository.getMangaById(seeded.target.id)
        new.titleOverride.shouldBeNull()
        new.descriptionOverride shouldBe "Edited description"

        val otherEnv = env()
        val other = otherEnv.seed()
        otherEnv.mangaRepository.setDetailsOverride(
            other.current.id,
            MangaDetailsOverrides(description = "Old description"),
        )
        otherEnv.mangaRepository.setDetailsOverride(other.target.id, MangaDetailsOverrides(title = "Target title"))

        otherEnv.migrateManga(other.current, other.target, replace = true, flags = emptySet())

        otherEnv.mangaRepository.getMangaById(other.target.id).titleOverride shouldBe "Target title"
    }

    @Test
    fun `the edited author, artist, status and genres follow the entry, a detail never edited keeping the target's`() =
        runTest {
            val env = env()
            val seeded = env.seed()
            env.mangaRepository.setDetailsOverride(
                seeded.current.id,
                MangaDetailsOverrides(author = "Edited author", status = 2L, genre = listOf("Comedy", "Mystery")),
            )
            env.mangaRepository.setDetailsOverride(seeded.target.id, MangaDetailsOverrides(artist = "Target artist"))

            env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

            val new = env.mangaRepository.getMangaById(seeded.target.id)
            assertSoftly {
                new.authorOverride shouldBe "Edited author"
                new.artistOverride shouldBe "Target artist"
                new.statusOverride shouldBe 2L
                new.genreOverride shouldBe listOf("Comedy", "Mystery")
                new.displayAuthor shouldBe "Edited author"
                new.titleOverride.shouldBeNull()
            }
        }

    @Test
    fun `an entry with no edit writes no details and keeps the target's`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mangaRepository.setDetailsOverride(seeded.target.id, MangaDetailsOverrides(title = "Target title"))
        env.calls.clear()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.calls.none { it.startsWith("details") } shouldBe true
        env.mangaRepository.getMangaById(seeded.target.id).titleOverride shouldBe "Target title"
    }

    @Test
    fun `with Custom cover on the old entry's custom cover is copied to the target, and with it off it is not`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.coverCache.getCustomCoverFile(seeded.current.id).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = emptySet())
        env.coverCache.getCustomCoverFile(seeded.target.id).exists() shouldBe false

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = setOf(MigrationFlag.CUSTOM_COVER))
        env.coverCache.getCustomCoverFile(seeded.target.id).readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
    }

    @Test
    fun `with Delete downloaded on, Migrate empties the old entry's queue rows before deleting its downloads`() = runTest {
        val env = env()
        val seeded = env.seed()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.REMOVE_DOWNLOAD))

        coVerifyOrder {
            env.downloadQueue.removeManga(seeded.current.id)
            env.downloadManifest.deleteManga(seeded.current.id)
        }
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(seeded.target.id) }
    }

    @Test
    fun `with Delete downloaded on, Copy deletes the old entry's downloads too`() = runTest {
        val env = env()
        val seeded = env.seed()

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = setOf(MigrationFlag.REMOVE_DOWNLOAD))

        coVerifyOrder {
            env.downloadQueue.removeManga(seeded.current.id)
            env.downloadManifest.deleteManga(seeded.current.id)
        }
    }

    @Test
    fun `with Delete downloaded off, nothing is deleted`() = runTest {
        val env = env()
        val seeded = env.seed()
        val flags = MigrationFlag.entries.toSet() - MigrationFlag.REMOVE_DOWNLOAD

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = flags)

        coVerify(exactly = 0) { env.downloadQueue.removeManga(any()) }
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
    }

    @Test
    fun `while the old entry's source is not installed, Delete downloaded still deletes its downloads`() = runTest {
        val env = env()
        val seeded = env.seed(currentSourceInstalled = false)

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.REMOVE_DOWNLOAD))

        coVerifyOrder {
            env.downloadQueue.removeManga(seeded.current.id)
            env.downloadManifest.deleteManga(seeded.current.id)
        }
        env.mangaRepository.getMangaById(seeded.target.id).favorite shouldBe true
    }

    @Test
    fun `with Downloaded chapters on, Migrate moves the old entry's downloads and returns the ones left behind`() = runTest {
        val env = env()
        val seeded = env.seed(currentSourceInstalled = false)
        coEvery { env.migrationDownloads.transfer(any(), any(), any()) } returns
            MigrationDownloads.Outcome(transferred = listOf(1L), leftBehind = listOf(7L, 8L))

        val result = env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.DOWNLOADS))

        result shouldBe MigrateManga.Result(leftBehindDownloads = listOf(7L, 8L), carried = true)
        coVerify(exactly = 1) {
            env.migrationDownloads.transfer(
                match { it.id == seeded.current.id },
                match { it.id == seeded.target.id },
                true,
            )
        }
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
    }

    @Test
    fun `with Downloaded chapters on, Copy copies and reports nothing left behind, since the old entry keeps its downloads`() =
        runTest {
            val env = env()
            val seeded = env.seed()
            coEvery { env.migrationDownloads.transfer(any(), any(), any()) } returns
                MigrationDownloads.Outcome(transferred = emptyList(), leftBehind = listOf(7L))

            val result = env.migrateManga(seeded.current, seeded.target, replace = false, flags = setOf(MigrationFlag.DOWNLOADS))

            result shouldBe MigrateManga.Result(leftBehindDownloads = emptyList(), carried = true)
            coVerify(exactly = 1) { env.migrationDownloads.transfer(any(), any(), false) }
        }

    @Test
    fun `with Delete downloaded on as well, the rest are deleted without asking and a Copy moves`() = runTest {
        val env = env()
        val seeded = env.seed()
        coEvery { env.migrationDownloads.transfer(any(), any(), any()) } returns
            MigrationDownloads.Outcome(transferred = emptyList(), leftBehind = listOf(7L))
        val flags = setOf(MigrationFlag.DOWNLOADS, MigrationFlag.REMOVE_DOWNLOAD)

        val migrated = env.migrateManga(seeded.current, seeded.target, replace = true, flags = flags)
        val copied = env.migrateManga(seeded.current, seeded.target, replace = false, flags = flags)

        migrated shouldBe MigrateManga.Result(leftBehindDownloads = emptyList(), carried = true)
        copied shouldBe MigrateManga.Result(leftBehindDownloads = emptyList(), carried = true)
        coVerify(exactly = 2) { env.migrationDownloads.transfer(any(), any(), true) }
        coVerifyOrder {
            env.migrationDownloads.transfer(any(), any(), true)
            env.downloadQueue.removeManga(seeded.current.id)
            env.downloadManifest.deleteManga(seeded.current.id)
        }
    }

    @Test
    fun `when Delete downloaded fails, the chapters left behind are still returned`() = runTest {
        val env = env()
        val seeded = env.seed()
        coEvery { env.migrationDownloads.transfer(any(), any(), any()) } returns
            MigrationDownloads.Outcome(transferred = emptyList(), leftBehind = listOf(7L))
        coEvery { env.downloadManifest.deleteManga(any()) } throws IOException("fixture: disk")

        val result = env.migrateManga(
            seeded.current,
            seeded.target,
            replace = true,
            flags = setOf(MigrationFlag.DOWNLOADS, MigrationFlag.REMOVE_DOWNLOAD),
        )

        result shouldBe MigrateManga.Result(leftBehindDownloads = listOf(7L), carried = true)
    }

    @Test
    fun `with Downloaded chapters and Delete downloaded off, Migrate moves nothing and reports every downloaded chapter of the old entry as kept`() =
        runTest {
            val env = env()
            val seeded = env.seed()
            coEvery { env.migrationDownloads.downloadedChapters(seeded.current.id) } returns listOf(7L, 8L)

            val result = env.migrateManga(
                seeded.current,
                seeded.target,
                replace = true,
                flags = MigrationFlag.entries.toSet() - MigrationFlag.DOWNLOADS - MigrationFlag.REMOVE_DOWNLOAD,
            )

            result shouldBe MigrateManga.Result(leftBehindDownloads = listOf(7L, 8L), carried = false)
            coVerify(exactly = 0) { env.migrationDownloads.transfer(any(), any(), any()) }
            coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
            coVerify(exactly = 0) { env.migrationDownloads.downloadedChapters(seeded.target.id) }
        }

    @Test
    fun `with Downloaded chapters off and Delete downloaded on, Migrate deletes the downloads and reports none`() = runTest {
        val env = env()
        val seeded = env.seed()
        coEvery { env.migrationDownloads.downloadedChapters(any()) } returns listOf(7L)

        val result = env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.REMOVE_DOWNLOAD))

        result shouldBe MigrateManga.Result(leftBehindDownloads = emptyList(), carried = false)
        coVerify(exactly = 1) { env.downloadManifest.deleteManga(seeded.current.id) }
        coVerify(exactly = 0) { env.migrationDownloads.downloadedChapters(any()) }
    }

    @Test
    fun `with Downloaded chapters off, Copy reports none, since the old entry stays in the library with its downloads`() = runTest {
        val env = env()
        val seeded = env.seed()
        coEvery { env.migrationDownloads.downloadedChapters(any()) } returns listOf(7L)

        val result = env.migrateManga(seeded.current, seeded.target, replace = false, flags = setOf(MigrationFlag.CHAPTER))

        result shouldBe MigrateManga.Result(leftBehindDownloads = emptyList(), carried = false)
        coVerify(exactly = 0) { env.migrationDownloads.downloadedChapters(any()) }
        coVerify(exactly = 0) { env.migrationDownloads.transfer(any(), any(), any()) }
    }

    @Test
    fun `with Downloaded chapters off and a Delete downloaded that fails, Migrate reports what is left, read after the delete`() =
        runTest {
            val env = env()
            val seeded = env.seed()
            coEvery { env.downloadManifest.deleteManga(any()) } throws IOException("fixture: disk")
            coEvery { env.migrationDownloads.downloadedChapters(seeded.current.id) } returns listOf(8L)

            val result = env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.REMOVE_DOWNLOAD))

            result shouldBe MigrateManga.Result(leftBehindDownloads = listOf(8L), carried = false)
            coVerifyOrder {
                env.downloadManifest.deleteManga(seeded.current.id)
                env.migrationDownloads.downloadedChapters(seeded.current.id)
            }
        }

    @Test
    fun `with Downloaded chapters off, a read of the old downloads that throws does not fail the migration and reports none`() =
        runTest {
            val env = env()
            val seeded = env.seed()
            coEvery { env.migrationDownloads.downloadedChapters(any()) } throws IOException("fixture: disk")

            val result = env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

            result shouldBe MigrateManga.Result(leftBehindDownloads = emptyList(), carried = false)
            env.mangaRepository.getMangaById(seeded.target.id).favorite shouldBe true
            env.mangaRepository.getMangaById(seeded.current.id).favorite shouldBe false
        }

    @Test
    fun `a transfer that throws does not fail the migration`() = runTest {
        val env = env()
        val seeded = env.seed()
        coEvery { env.migrationDownloads.transfer(any(), any(), any()) } throws IOException("fixture: disk")

        val result = env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.DOWNLOADS))

        result shouldBe MigrateManga.Result(leftBehindDownloads = emptyList(), carried = true)
        env.mangaRepository.getMangaById(seeded.target.id).favorite shouldBe true
    }

    @Test
    fun `Delete them deletes the chapters left behind through the transfer's locked delete`() = runTest {
        val env = env()

        env.migrateManga.deleteLeftBehindDownloads(listOf(7L, 8L))

        coVerify(exactly = 1) { env.migrationDownloads.delete(listOf(7L, 8L)) }
    }

    @Test
    fun `a copied track on a target with no pair is inserted and moves the target's tracker-set clock`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500, lastChapterRead = 2.0)
        val before = env.long("SELECT trackers_modified_at FROM mangas WHERE _id = ${seeded.target.id}")

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.calls.filter { it.startsWith("track") } shouldContainExactly listOf("track insert ${seeded.target.id} 1 500")
        val tracks = env.trackRepository.getTracksByMangaId(seeded.target.id)
        tracks.map { it.remoteId to it.lastChapterRead } shouldContainExactly listOf(500L to 2.0)
        before.shouldBeNull()
        env.long("SELECT trackers_modified_at FROM mangas WHERE _id = ${seeded.target.id}").shouldNotBeNull()
        env.trackRepository.getTracksByMangaId(seeded.current.id).map { it.remoteId } shouldContainExactly listOf(500L)
    }

    @Test
    fun `a copied track on a target whose pair names the same remote id is stored as progress and moves no clock`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500, lastChapterRead = 2.0)
        env.track(seeded.target.id, trackerId = 1, remoteId = 500, lastChapterRead = 0.0)
        val before = env.long("SELECT trackers_modified_at FROM mangas WHERE _id = ${seeded.target.id}")

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.calls.filter { it.startsWith("track") } shouldContainExactly listOf("track insert ${seeded.target.id} 1 500")
        env.trackRepository.getTracksByMangaId(seeded.target.id).map { it.remoteId to it.lastChapterRead } shouldContainExactly
            listOf(500L to 2.0)
        before.shouldNotBeNull()
        env.long("SELECT trackers_modified_at FROM mangas WHERE _id = ${seeded.target.id}") shouldBe before
    }

    @Test
    fun `a copied track on a target whose pair names another remote id is rebound and moves the clock`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500, lastChapterRead = 2.0)
        env.track(seeded.target.id, trackerId = 1, remoteId = 600, lastChapterRead = 9.0)
        val before = env.long("SELECT trackers_modified_at FROM mangas WHERE _id = ${seeded.target.id}")!!

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.calls.filter { it.startsWith("track") } shouldContainExactly listOf("track rebind ${seeded.target.id} 1 500")
        env.trackRepository.getTracksByMangaId(seeded.target.id).map { it.remoteId to it.lastChapterRead } shouldContainExactly
            listOf(500L to 2.0)
        (env.long("SELECT trackers_modified_at FROM mangas WHERE _id = ${seeded.target.id}")!! > before) shouldBe true
    }

    @Test
    fun `Migrate takes the old entry out of the library and gives the target its date added, display pair and position`() = runTest {
        val env = env()
        val seeded = env.seed()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        val old = env.mangaRepository.getMangaById(seeded.current.id)
        val new = env.mangaRepository.getMangaById(seeded.target.id)
        assertSoftly {
            old.favorite shouldBe false
            old.dateAdded shouldBe 0L
            new.favorite shouldBe true
            new.dateAdded shouldBe OLD_DATE_ADDED
            new.chapterFlags shouldBe OLD_CHAPTER_FLAGS
            new.viewerFlags shouldBe OLD_VIEWER_FLAGS
            new.manualPosition shouldBe OLD_POSITION
        }
    }

    @Test
    fun `Copy adds the target now, carries no position, and leaves every row of the old entry as it was`() = runTest {
        val env = env()
        val seeded = env.seed()
        val reading = env.category("Reading")
        env.mangaRepository.setMangaCategories(seeded.current.id, listOf(reading.id))
        env.track(seeded.current.id, trackerId = 1, remoteId = 500, lastChapterRead = 2.0)
        val before = env.snapshot(seeded.current.id)
        val start = System.currentTimeMillis()

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = MigrationFlag.entries.toSet())

        env.snapshot(seeded.current.id) shouldBe before
        val new = env.mangaRepository.getMangaById(seeded.target.id)
        assertSoftly {
            new.favorite shouldBe true
            (new.dateAdded >= start) shouldBe true
            new.manualPosition shouldBe 0L
            new.chapterFlags shouldBe OLD_CHAPTER_FLAGS
            new.viewerFlags shouldBe OLD_VIEWER_FLAGS
        }
    }

    @Test
    fun `the favourite pair is the last write, after the chapters, the categories and the tracks`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500)

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())

        env.calls.map { it.substringBefore(' ') } shouldContainExactly listOf("chapters", "categories", "track", "manga")
        env.calls.last() shouldBe "manga ${seeded.current.id} favorite=false, ${seeded.target.id} favorite=true"
    }

    @Test
    fun `the library-add trigger logs the copied read and bookmark flags once the target is a favourite`() = runTest {
        val env = env()
        val seeded = env.seed()

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

        val logged = seeded.targetChapters.take(3).map { it.id }
        // Once each, by the library-add trigger: a favourite written before the chapters would log
        // the bookmarked chapter 1 twice, once on the add and once on its chapter write.
        logged.forEach { id ->
            env.long("SELECT count(*) FROM sync_change_log WHERE entity_type = 'chapter' AND entity_id = $id") shouldBe 1L
        }
    }

    @Test
    fun `a fetch date copied onto a chapter of a target that enters the library is logged once, and onto a target already in it is not logged`() = runTest {
        // Phase 4 unit NB, amending R114 item 7: a library add logs every chapter the entry holds, so
        // the copied fetch date travels with a target that enters the library. No trigger tracks
        // date_fetch alone, so on a target already in the library it stays on this device.
        listOf(false, true).forEach { targetFavorite ->
            val env = env()
            val current = env.manga(OLD_SOURCE, "/old", "Same title") { it.copy(favorite = true, dateAdded = OLD_DATE_ADDED) }
            env.chapters(current.id, chapter("/old/7", 7.0, dateFetch = 700))
            val target = env.manga(NEW_SOURCE, "/new", "Same title") { if (targetFavorite) it.copy(favorite = true) else it }
            val targetChapter = env.chapters(target.id, chapter("/new/7", 7.0, dateFetch = 9)).single()
            env.source(NEW_SOURCE, listOf(info(targetChapter)))
            val seqBefore = env.long("SELECT coalesce(max(seq), 0) FROM sync_change_log")!!

            env.migrateManga(current, target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

            val after = env.chaptersOf(target.id).getValue("/new/7")
            val chapterRows = "SELECT entity_id FROM sync_change_log WHERE entity_type = 'chapter' AND seq > $seqBefore"
            assertSoftly {
                after.read shouldBe false
                after.bookmark shouldBe false
                after.dateFetch shouldBe 700
                // The old entry's chapter is never logged by its removal.
                env.long("SELECT count(*) FROM ($chapterRows)") shouldBe if (targetFavorite) 0L else 1L
                if (!targetFavorite) env.long(chapterRows) shouldBe targetChapter.id
            }
        }
    }

    @Test
    fun `Migrate onto a tombstoned target undeletes it before the favourite write, so it enters the library live`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.execute("UPDATE mangas SET deleted_at = 5000 WHERE _id = ${seeded.target.id}")

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())

        env.calls.map { it.substringBefore(' ') } shouldContainExactly listOf("chapters", "categories", "undelete", "manga")
        val new = env.mangaRepository.getMangaById(seeded.target.id)
        assertSoftly {
            new.favorite shouldBe true
            new.deletedAt.shouldBeNull()
            env.mangaRepository.getFavorites().map { it.id } shouldContainExactly listOf(seeded.target.id)
            (env.long("SELECT restored_at FROM mangas WHERE _id = ${seeded.target.id}")!! > 5000) shouldBe true
            env.long(
                "SELECT count(*) FROM sync_change_log WHERE entity_type = 'manga' AND entity_id = ${seeded.target.id} " +
                    "AND change_type = 'delete'",
            ) shouldBe 0L
            env.long(
                "SELECT count(*) FROM sync_change_log WHERE entity_type = 'manga' AND entity_id = ${seeded.target.id} " +
                    "AND change_type = 'upsert'",
            ) shouldBe 1L
        }
    }

    @Test
    fun `Copy onto a tombstoned target undeletes it too`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.execute("UPDATE mangas SET deleted_at = 5000 WHERE _id = ${seeded.target.id}")

        env.migrateManga(seeded.current, seeded.target, replace = false, flags = emptySet())

        val new = env.mangaRepository.getMangaById(seeded.target.id)
        assertSoftly {
            new.favorite shouldBe true
            new.deletedAt.shouldBeNull()
            env.mangaRepository.getFavorites().map { it.id }.toSet() shouldBe setOf(seeded.current.id, seeded.target.id)
        }
    }

    @Test
    fun `a live target is not undeleted, and a failed favourite write rolls a tombstoned target's undelete back`() = runTest {
        val live = env()
        val liveSeeded = live.seed()
        live.migrateManga(liveSeeded.current, liveSeeded.target, replace = true, flags = emptySet())
        live.calls.map { it.substringBefore(' ') } shouldContainExactly listOf("manga")

        val env = env()
        val seeded = env.seed()
        env.execute("UPDATE mangas SET deleted_at = 5000 WHERE _id = ${seeded.target.id}")
        val before = env.snapshot()
        env.mangaWriteAnswersFalse = true

        shouldThrow<IllegalStateException> {
            env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())
        }

        env.calls.map { it.substringBefore(' ') } shouldContainExactly listOf("undelete", "manga")
        env.snapshot() shouldBe before
    }

    @Test
    fun `a target row that is gone by the time the transaction runs is refused before any write`() = runTest {
        val env = env()
        val seeded = env.seed()
        val before = env.snapshot()
        // Inside the migration's transaction, so its rollback brings the row back with it.
        env.beforeMigrationTransaction = { env.execute("DELETE FROM mangas WHERE _id = ${seeded.target.id}") }

        shouldThrow<Exception> { env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet()) }

        env.calls.shouldBeEmpty()
        env.snapshot() shouldBe before
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
    }

    @Test
    fun `an entry is refused as its own target before the fetch and before any write`() = runTest {
        val env = env()
        val seeded = env.seed()
        val source = env.source(OLD_SOURCE, listOf(info(chapter("/old/1", 1.0))))
        val before = env.snapshot()

        val failure = shouldThrow<IllegalStateException> {
            env.migrateManga(seeded.current, seeded.current, replace = true, flags = MigrationFlag.entries.toSet())
        }

        failure.message.orEmpty() shouldStartWith "Entry ${seeded.current.id} cannot be migrated onto itself"
        source.chapterCalls shouldBe 0
        env.calls.shouldBeEmpty()
        env.snapshot() shouldBe before
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
    }

    @Test
    fun `Migrate refuses an old entry that is no longer in the library before any write`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mangaRepository.update(MangaUpdate(id = seeded.current.id, favorite = false))
        val before = env.snapshot()

        val failure = shouldThrow<IllegalStateException> {
            env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())
        }

        failure.message.orEmpty() shouldBe "Entry ${seeded.current.id} is no longer in the library"
        env.calls.shouldBeEmpty()
        env.snapshot() shouldBe before
    }

    @Test
    fun `Migrate refuses an old entry that has been deleted before any write`() = runTest {
        val env = env()
        val seeded = env.seed()
        // The product's soft delete clears the favourite too; the flag is kept here so the deletion
        // time alone is what the guard has to refuse.
        env.execute("UPDATE mangas SET deleted_at = 5000 WHERE _id = ${seeded.current.id}")
        val before = env.snapshot()

        shouldThrow<IllegalStateException> {
            env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())
        }

        env.calls.shouldBeEmpty()
        env.snapshot() shouldBe before
    }

    @Test
    fun `a failure after the first write leaves nothing written`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500)
        val reading = env.category("Reading")
        env.mangaRepository.setMangaCategories(seeded.current.id, listOf(reading.id))
        env.calls.clear()
        val before = env.snapshot()
        env.failOn = "track"

        shouldThrow<IOException> { env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet()) }

        env.calls.map { it.substringBefore(' ') } shouldContainExactly listOf("chapters", "categories", "track")
        env.snapshot() shouldBe before
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
    }

    @Test
    fun `a favourite write the repository answers false for is thrown and rolls every write back`() = runTest {
        val env = env()
        val seeded = env.seed()
        val before = env.snapshot()
        env.mangaWriteAnswersFalse = true

        shouldThrow<IllegalStateException> {
            env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())
        }

        env.snapshot() shouldBe before
    }

    @Test
    fun `the flags passed are used over the stored key`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.sourcePreferences.migrationFlags.set(MigrationFlag.entries.toSet())
        val reading = env.category("Reading")
        env.mangaRepository.setMangaCategories(seeded.current.id, listOf(reading.id))

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.NOTES))

        env.categoryRepository.getCategoriesByMangaId(seeded.target.id).shouldBeEmpty()
        env.chapterUpdates.shouldBeEmpty()
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
        env.mangaRepository.getMangaById(seeded.target.id).notes shouldBe "old notes"
    }

    @Test
    fun `with no flags passed the stored key decides`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.sourcePreferences.migrationFlags.set(setOf(MigrationFlag.REMOVE_DOWNLOAD))

        env.migrateManga(seeded.current, seeded.target, replace = true)

        env.chapterUpdates.shouldBeEmpty()
        coVerify(exactly = 1) { env.downloadManifest.deleteManga(seeded.current.id) }
        env.mangaRepository.getMangaById(seeded.target.id).notes shouldBe ""
    }

    @Test
    fun `a cover or download step that fails does not fail the migration`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.coverCache.getCustomCoverFile(seeded.current.id).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1))
        // A directory where the target's cover file goes: the copy cannot open it for writing.
        env.coverCache.getCustomCoverFile(seeded.target.id).mkdirs()
        coEvery { env.downloadManifest.deleteManga(any()) } throws IOException("fixture: disk")

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())

        env.mangaRepository.getMangaById(seeded.target.id).favorite shouldBe true
        env.mangaRepository.getMangaById(seeded.current.id).favorite shouldBe false
        coVerify(exactly = 1) { env.downloadManifest.deleteManga(seeded.current.id) }
    }

    @Test
    fun `a queue step that fails skips the delete and does not fail the migration`() = runTest {
        val env = env()
        val seeded = env.seed()
        coEvery { env.downloadQueue.removeManga(any()) } throws IOException("fixture: queue")

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())

        coVerify(exactly = 0) { env.downloadManifest.deleteManga(any()) }
        env.mangaRepository.getMangaById(seeded.target.id).favorite shouldBe true
    }

    @Test
    fun `the old entry's queued push for a copied track is queued for the target and the worker is scheduled`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500)
        env.track(seeded.current.id, trackerId = 2, remoteId = 700)
        env.delayedTrackingStore.add(seeded.current.id, trackerId = 1, remoteId = 500, chapterNumber = 2.0)
        // Recorded against an entry the old row no longer names: not the copied track's progress.
        env.delayedTrackingStore.add(seeded.current.id, trackerId = 2, remoteId = 799, chapterNumber = 5.0)

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.delayedTrackingStore.current(seeded.target.id, 1) shouldBe
            DelayedTrackingStore.DelayedTrackingItem(seeded.target.id, 1, 500, 2.0)
        env.delayedTrackingStore.current(seeded.target.id, 2).shouldBeNull()
        env.delayedTrackingStore.current(seeded.current.id, 1).shouldNotBeNull()
        env.schedules shouldBe 1
    }

    @Test
    fun `a tracker queue write that fails after the commit is logged and the migration stands`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500)
        env.delayedTrackingStore.add(seeded.current.id, trackerId = 1, remoteId = 500, chapterNumber = 2.0)
        env.trackingWriteFails = true

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.mangaRepository.getMangaById(seeded.target.id).favorite shouldBe true
        env.mangaRepository.getMangaById(seeded.current.id).favorite shouldBe false
        env.delayedTrackingStore.current(seeded.target.id, 1).shouldBeNull()
        // The old entry's push stays queued for the same remote entry.
        env.delayedTrackingStore.current(seeded.current.id, 1) shouldBe
            DelayedTrackingStore.DelayedTrackingItem(seeded.current.id, 1, 500, 2.0)
    }

    @Test
    fun `with no queued push nothing is queued and the worker is not asked for`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.track(seeded.current.id, trackerId = 1, remoteId = 500)

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = emptySet())

        env.delayedTrackingStore.getItems().shouldBeEmpty()
        env.schedules shouldBe 0
    }

    @Test
    fun `the target's chapters are fetched first, chapters only, and a new one is raised like the rest`() = runTest {
        val env = env()
        val seeded = env.seed()
        val listed = seeded.targetChapters.map(::info) + info(chapter("/new/0.5", 0.5))
        val source = env.source(NEW_SOURCE, listed)

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = setOf(MigrationFlag.CHAPTER))

        source.chapterCalls shouldBe 1
        source.detailsCalls shouldBe 0
        env.chaptersOf(seeded.target.id).getValue("/new/0.5").read shouldBe true
    }

    @Test
    fun `a target whose source is not installed is refused before the fetch and before any write`() = runTest {
        val env = env()
        val seeded = env.seed()
        val elsewhere = seeded.target.copy(source = 12_345L)
        val before = env.snapshot()

        shouldThrow<SourceNotInstalledException> {
            env.migrateManga(seeded.current, elsewhere, replace = true, flags = MigrationFlag.entries.toSet())
        }

        env.calls.shouldBeEmpty()
        env.snapshot() shouldBe before
    }

    @Test
    fun `a target that cannot be fetched is thrown and nothing is written`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.source(NEW_SOURCE, emptyList())
        val before = env.snapshot()

        shouldThrow<Exception> { env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet()) }

        env.calls.shouldBeEmpty()
        env.snapshot() shouldBe before
    }

    /**
     * Row A7 of the batch design (data challenge C10): with "After manually marked as read" on and
     * a target in the library whose downloaded chapters the copy marks read, the migration hands
     * nothing to auto-download, nothing to the delete after reading and no chapter to a tracker
     * push, and deletes none of the target's downloads.
     */
    @Test
    fun `a migration reaches no auto-download, no delete after reading and no tracker push`() = runTest {
        val env = env()
        val seeded = env.seed(targetFavorite = true)
        env.track(seeded.current.id, trackerId = 1, remoteId = 500, lastChapterRead = 2.0)
        val listed = seeded.targetChapters.map(::info) + info(chapter("/new/9", 9.0))
        env.source(NEW_SOURCE, listed)

        env.migrateManga(seeded.current, seeded.target, replace = true, flags = MigrationFlag.entries.toSet())

        assertSoftly {
            env.chaptersOf(seeded.target.id).getValue("/new/1").read shouldBe true
            env.chaptersOf(seeded.target.id).getValue("/new/9").read shouldBe false
            env.autoDownload.calls.shouldBeEmpty()
            env.cleanup.marked.shouldBeEmpty()
            env.trackProgress.recorded.shouldBeEmpty()
            env.delayedTrackingStore.getItems().shouldBeEmpty()
        }
        coVerify(exactly = 0) { env.downloadManifest.delete(any()) }
        coVerify(exactly = 0) { env.downloadManifest.deleteManga(seeded.target.id) }
        coVerify(exactly = 0) { env.downloadQueue.enqueue(any(), any(), any()) }
        coVerify(exactly = 0) { env.downloadQueue.start() }
    }

    private companion object {
        const val OLD_SOURCE = 100L
        const val NEW_SOURCE = 200L
        const val OLD_DATE_ADDED = 1_000L
        const val TARGET_DATE_ADDED = 2_000L
        const val OLD_CHAPTER_FLAGS = 0x100L
        const val OLD_VIEWER_FLAGS = 0x3L
        const val OLD_POSITION = 7L
    }
}
