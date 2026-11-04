package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.BackupImporter
import com.shinsaikou.backup.importer.mapChapter
import com.shinsaikou.backup.importer.mapManga
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * A restore is allowed to add. It is not allowed to take away. Every case here is a backup that is
 * behind the database, which is what an old file, a second device or a re-run looks like.
 */
class BackupImporterMergeTest {

    @TempDir
    lateinit var tempDir: File

    private val url = "/manga/1"
    private val source = SyntheticBackupFixture.SOURCE_A

    @Test
    fun `read state, bookmarks and page progress never regress`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            seedChapter(env, read = true, bookmark = true, lastPageRead = 42L)

            env.importer.import(fileOf(env, backupWith(regressed())), RestoreOptions()) {}

            val chapter = env.chapterRepository.getChapterByMangaId(1L).single()
            chapter.read shouldBe true
            chapter.bookmark shouldBe true
            chapter.lastPageRead shouldBe 42L
        }
    }

    @Test
    fun `a page a backup is ahead on is taken`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            seedChapter(env, read = false, bookmark = false, lastPageRead = 0L)

            val ahead = regressed().also { it.lastPageRead = 9L }
            env.importer.import(fileOf(env, backupWith(ahead)), RestoreOptions()) {}

            env.chapterRepository.getChapterByMangaId(1L).single().lastPageRead shouldBe 9L
        }
    }

    @Test
    fun `two sides that both advanced keep the higher page`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            seedChapter(env, read = false, bookmark = false, lastPageRead = 50L)

            val behind = regressed().also { it.lastPageRead = 3L }
            env.importer.import(fileOf(env, backupWith(behind)), RestoreOptions()) {}

            val chapter = env.chapterRepository.getChapterByMangaId(1L).single()
            chapter.lastPageRead shouldBe 50L
            chapter.read shouldBe false
        }
    }

    @Test
    fun `a backup ahead of a device that has already started wins`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            seedChapter(env, read = false, bookmark = false, lastPageRead = 3L)

            val ahead = regressed().also { it.lastPageRead = 50L }
            env.importer.import(fileOf(env, backupWith(ahead)), RestoreOptions()) {}

            val chapter = env.chapterRepository.getChapterByMangaId(1L).single()
            chapter.lastPageRead shouldBe 50L
            chapter.read shouldBe false
        }
    }

    @Test
    fun `history keeps the later timestamp and the longer duration, never the sum`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            seedChapter(env, read = true, bookmark = false, lastPageRead = 0L)
            env.writer.upsertHistory(chapterId = 1L, readAt = 2_000L, readDuration = 500L)

            val entry = BackupHistory(url = "$url/ch/1", lastRead = 1_000L, readDuration = 300L)
            env.importer.import(fileOf(env, backupWith(regressed(), history = listOf(entry))), RestoreOptions()) {}

            val history = env.historyRepository.getHistoryByMangaId(1L).single()
            history.readAt?.time shouldBe 2_000L
            history.readDuration shouldBe 500L
        }
    }

    @Test
    fun `a longer duration in the backup wins without double counting`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            seedChapter(env, read = true, bookmark = false, lastPageRead = 0L)
            env.writer.upsertHistory(chapterId = 1L, readAt = 1_000L, readDuration = 300L)

            val entry = BackupHistory(url = "$url/ch/1", lastRead = 4_000L, readDuration = 900L)
            env.importer.import(fileOf(env, backupWith(regressed(), history = listOf(entry))), RestoreOptions()) {}

            val history = env.historyRepository.getHistoryByMangaId(1L).single()
            history.readAt?.time shouldBe 4_000L
            history.readDuration shouldBe 900L
        }
    }

    @Test
    fun `the higher chapter count on a track wins`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            seedChapter(env, read = false, bookmark = false, lastPageRead = 0L)
            env.writer.insertTracks(listOf(trackRow(lastChapterRead = 30.0)))

            val track = BackupTracking(syncId = 1, libraryId = 0L, lastChapterRead = 5f, mediaId = 7L)
            env.importer.import(fileOf(env, backupWith(regressed(), tracking = listOf(track))), RestoreOptions()) {}

            val stored = env.trackRepository.getTracksByMangaId(1L).single()
            stored.lastChapterRead shouldBe 30.0
            stored.remoteId shouldBe 7L
        }
    }

    /**
     * Contract 06 section 5.1's track rule as phase 3 decision Q6 completes it. The first import
     * creates the row; a second one that read further changes every field, and a third that read
     * less changes none, whatever it says.
     */
    @Test
    fun `a second import that read further on a track takes every field, and one that read less takes none`() =
        runTest {
            BackupTestEnvironment(tempDir).use { env ->
                seedManga(env, favorite = true, version = 0L)
                seedChapter(env, read = false, bookmark = false, lastPageRead = 0L)

                env.importer.import(fileOf(env, backupWith(regressed(), tracking = listOf(FIRST))), RestoreOptions()) {}
                val first = env.trackRepository.getTracksByMangaId(1L).single()
                first.fields() shouldBe FIRST.getTrackImpl().fields()

                val further = env.importer.import(
                    fileOf(env, backupWith(regressed(), tracking = listOf(FURTHER))),
                    RestoreOptions(),
                ) {}
                val updated = env.trackRepository.getTracksByMangaId(1L).single()
                updated.id shouldBe first.id
                updated.fields() shouldBe FURTHER.getTrackImpl().fields()
                further.tracksWritten shouldBe 1

                val behind = env.importer.import(
                    fileOf(env, backupWith(regressed(), tracking = listOf(BEHIND))),
                    RestoreOptions(),
                ) {}
                env.trackRepository.getTracksByMangaId(1L).single() shouldBe updated
                behind.tracksWritten shouldBe 0
            }
        }

    /**
     * On a tie the row is kept whole (triage E86). It used to take the backup's remote ids onto its
     * own title and url, so it named one remote entry by id and another by name.
     */
    @Test
    fun `a second import at the same chapter keeps the row whole, its remote ids too`() =
        runTest {
            BackupTestEnvironment(tempDir).use { env ->
                seedManga(env, favorite = true, version = 0L)
                seedChapter(env, read = false, bookmark = false, lastPageRead = 0L)
                env.importer.import(fileOf(env, backupWith(regressed(), tracking = listOf(FIRST))), RestoreOptions()) {}
                val first = env.trackRepository.getTracksByMangaId(1L).single()

                val tie = BEHIND.copy(lastChapterRead = FIRST.lastChapterRead)
                (tie.mediaId == first.remoteId) shouldBe false
                val report = env.importer.import(
                    fileOf(env, backupWith(regressed(), tracking = listOf(tie))),
                    RestoreOptions(),
                ) {}

                env.trackRepository.getTracksByMangaId(1L).single() shouldBe first
                report.tracksWritten shouldBe 0
            }
        }

    @Test
    fun `favorite is the union and a tombstone loses to an explicit library entry`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = false, version = 0L, deletedAt = 1_000L)

            env.importer.import(fileOf(env, backupWith()), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }
            stored.favorite shouldBe true
            stored.deletedAt shouldBe null
        }
    }

    @Test
    fun `an undelete stamps the restore clock past the tombstone it clears, and a re-run moves nothing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            // Ahead of the wall clock, so only the "past the tombstone" term can produce the value.
            val tombstone = System.currentTimeMillis() + 1_000_000_000L
            seedManga(env, favorite = false, version = 0L, deletedAt = tombstone)

            env.importer.import(fileOf(env, backupWith()), RestoreOptions()) {}
            val restored = env.handler.awaitOne { mangasQueries.getMangaById(1L) }
            restored.deleted_at shouldBe null
            restored.restored_at shouldBe tombstone + 1

            // The entry is a live favourite now, so the same file again adds nothing and stamps nothing.
            env.importer.import(fileOf(env, backupWith()), RestoreOptions()) {}
            env.handler.awaitOne { mangasQueries.getMangaById(1L) }.restored_at shouldBe tombstone + 1
        }
    }

    @Test
    fun `a library add over a live row stamps the restore clock, which the latch keeps the trigger from doing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = false, version = 0L)
            // The schema's clock is strftime('%s', 'now') * 1000, whole seconds.
            val before = System.currentTimeMillis() / 1000 * 1000

            env.importer.import(fileOf(env, backupWith()), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L) }
            stored.favorite shouldBe true
            (stored.restored_at!! >= before) shouldBe true
        }
    }

    /**
     * Contract 06's favourite clock (phase 3 unit SP): upstream's files carry seconds and the row's clock
     * is the milliseconds the sync merge compares, so a merge keeps the row's clock whatever the file
     * says, and the merged object says so too.
     */
    @Test
    fun `a merge over a live favourite keeps the row's favourite clock, not the file's seconds`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            env.handler.await { mangasQueries.syncSetFavoriteModifiedAt(ROW_FAVORITE_CLOCK, 1L) }

            env.importer.import(fileOf(env, backupWith(favoriteModifiedAt = UPSTREAM_SECONDS)), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L) }
            stored.favorite shouldBe true
            stored.favorite_modified_at shouldBe ROW_FAVORITE_CLOCK
            val db = env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }
            BackupImporter.mergeManga(db, db.copy(favoriteModifiedAt = UPSTREAM_SECONDS)).favoriteModifiedAt shouldBe
                ROW_FAVORITE_CLOCK
        }
    }

    @Test
    fun `a favourite the merge turns on is stamped with the device's milliseconds, never the file's value`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = false, version = 0L)
            // The trigger's clock is strftime('%s', 'now') * 1000, whole seconds.
            val before = System.currentTimeMillis() / 1000 * 1000

            env.importer.import(fileOf(env, backupWith(favoriteModifiedAt = UPSTREAM_SECONDS)), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L) }
            stored.favorite shouldBe true
            val clock = stored.favorite_modified_at.shouldNotBeNull()
            clock shouldNotBe UPSTREAM_SECONDS
            clock shouldBeGreaterThanOrEqual before
        }
    }

    @Test
    fun `a fresh insert leaves the favourite clock null, whatever the file says`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(fileOf(env, backupWith(favoriteModifiedAt = UPSTREAM_SECONDS)), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaByUrlAndSource(url, source) }
            stored.favorite shouldBe true
            stored.favorite_modified_at shouldBe null
        }
    }

    @Test
    fun `metadata follows the higher version and a tie keeps the database row`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 5L, author = "Database author")

            env.importer.import(fileOf(env, backupWith(version = 5L)), RestoreOptions()) {}
            env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }.author shouldBe "Database author"

            env.importer.import(fileOf(env, backupWith(version = 6L)), RestoreOptions()) {}
            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }
            stored.author shouldBe "Backup author"
            stored.version shouldBe 6L
        }
    }

    /**
     * Phase 5 unit ED (R138 item 2, decision 5): an upstream file carries no edit, so a restore leaves
     * the entry's overrides and their clock as they are while it merges the source's values under
     * them. `OtherReaderEditRestoreTest` holds the files that do carry one (R162 item 8).
     */
    @Test
    fun `a restore leaves the entry's title and description edit and its clock as they are`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedManga(env, favorite = true, version = 0L)
            env.handler.await {
                mangasQueries.setDetailsOverride(
                    titleOverride = "Edited title",
                    descriptionOverride = "Edited text",
                    mangaId = 1L,
                )
            }
            val clock = env.rows("mangas").single()["details_modified_at"].shouldNotBeNull()

            env.importer.import(fileOf(env, backupWith(version = 6L)), RestoreOptions()) {}

            val row = env.rows("mangas").single()
            row["title_override"] shouldBe "Edited title"
            row["description_override"] shouldBe "Edited text"
            row["details_modified_at"] shouldBe clock
            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }
            stored.author shouldBe "Backup author"
            stored.displayTitle shouldBe "Edited title"
        }
    }

    @Test
    fun `a restored entry new to the device has no edit`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(fileOf(env, backupWith(version = 6L)), RestoreOptions()) {}

            val row = env.rows("mangas").single()
            row["title_override"] shouldBe null
            row["description_override"] shouldBe null
            row["details_modified_at"] shouldBe null
        }
    }

    /**
     * A file has no interval, last update or next update, so the fetch interval is recomputed from
     * the merged row. The owner's chosen interval (stored negative) and the cycle it sits on survive.
     */
    @Test
    fun `a merge restore keeps a chosen negative interval and does not re-base its next update to today`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.now().toLocalDateTime(zone).date
            // Eleven days into a 7 day cycle, so the next check is the cycle end at day 14, three days from now.
            // The last update is a calendar day start, as the recompute reads it, so DST cannot skew it.
            val lastDay = today.minus(11, DateTimeUnit.DAY).atStartOfDayIn(zone)
            val lastUpdate = lastDay.toEpochMilliseconds()
            val nextUpdate = (lastDay + 14.days).toEpochMilliseconds()
            seedManga(
                env,
                favorite = true,
                version = 0L,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                calculateInterval = -7L,
            )

            env.importer.import(fileOf(env, backupWith()), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }
            stored.fetchInterval shouldBe -7
            stored.lastUpdate shouldBe lastUpdate
            stored.nextUpdate shouldBe nextUpdate
        }
    }

    @Test
    fun `a merge restore over an estimated interval keeps the estimate`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.now().toLocalDateTime(zone).date.atStartOfDayIn(zone)
            seedManga(
                env,
                favorite = true,
                version = 0L,
                lastUpdate = today.toEpochMilliseconds(),
                nextUpdate = (today + 7.days).toEpochMilliseconds(),
                calculateInterval = 7L,
            )

            env.importer.import(fileOf(env, backupWith()), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }
            stored.fetchInterval shouldBe 7
            stored.nextUpdate shouldBe (today + 7.days).toEpochMilliseconds()
        }
    }

    @Test
    fun `a fresh insert gets an estimated interval and a next update`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.importer.import(fileOf(env, backupWith()), RestoreOptions()) {}

            val stored = env.handler.awaitOne { mangasQueries.getMangaById(1L, ::mapManga) }
            stored.fetchInterval shouldBe 7
            stored.nextUpdate shouldBeGreaterThan 0L
        }
    }

    private fun regressed() = BackupChapter(
        url = "$url/ch/1",
        name = "Chapter 1",
        read = false,
        bookmark = false,
        lastPageRead = 0L,
        chapterNumber = 1f,
        sourceOrder = 1L,
    )

    private fun trackRow(lastChapterRead: Double) = tachiyomi.domain.track.model.Track(
        id = -1L,
        mangaId = 1L,
        trackerId = 1L,
        remoteId = 7L,
        libraryId = 0L,
        title = "Fixture Manga 1",
        lastChapterRead = lastChapterRead,
        totalChapters = 100L,
        status = 1L,
        score = 5.0,
        remoteUrl = "https://example.invalid/track/1",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    /** Every field the merge decides, so an assertion names any one that did not move. */
    private fun tachiyomi.domain.track.model.Track.fields() = copy(id = 0L, mangaId = 0L)

    private companion object {
        /** A favourite clock as upstream writes one: epoch seconds (2025-10-09). */
        const val UPSTREAM_SECONDS = 1_760_000_000L

        /** A favourite clock as Saikou's trigger writes one: epoch milliseconds (2025-06-15). */
        const val ROW_FAVORITE_CLOCK = 1_750_000_000_000L

        /** A track as a first import writes it. */
        val FIRST = BackupTracking(
            syncId = 2,
            libraryId = 100L,
            trackingUrl = "https://example.invalid/track/a",
            title = "Fixture Track A",
            lastChapterRead = 10f,
            totalChapters = 50,
            score = 6f,
            status = 1,
            startedReadingDate = 1_000L,
            finishedReadingDate = 0L,
            private = false,
            mediaId = 7L,
        )

        /** The same track read further, with every other field changed too. */
        val FURTHER = BackupTracking(
            syncId = 2,
            libraryId = 200L,
            trackingUrl = "https://example.invalid/track/b",
            title = "Fixture Track B",
            lastChapterRead = 20f,
            totalChapters = 60,
            score = 9f,
            status = 2,
            startedReadingDate = 2_000L,
            finishedReadingDate = 3_000L,
            private = true,
            mediaId = 8L,
        )

        /** Read less than [FURTHER], with every other field changed again. */
        val BEHIND = BackupTracking(
            syncId = 2,
            libraryId = 300L,
            trackingUrl = "https://example.invalid/track/c",
            title = "Fixture Track C",
            lastChapterRead = 15f,
            totalChapters = 70,
            score = 3f,
            status = 3,
            startedReadingDate = 4_000L,
            finishedReadingDate = 5_000L,
            private = false,
            mediaId = 9L,
        )
    }

    private fun backupWith(
        vararg chapters: BackupChapter,
        history: List<BackupHistory> = emptyList(),
        tracking: List<BackupTracking> = emptyList(),
        version: Long = 0L,
        favoriteModifiedAt: Long? = null,
    ) = Backup(
        backupManga = listOf(
            BackupManga(
                source = source,
                url = url,
                title = "Fixture Manga 1",
                author = "Backup author",
                chapters = chapters.toList(),
                tracking = tracking,
                favorite = true,
                history = history,
                version = version,
                favoriteModifiedAt = favoriteModifiedAt,
            ),
        ),
    )

    private fun fileOf(env: BackupTestEnvironment, backup: Backup): File =
        env.writeImportFile(SyntheticBackupFixture.tachibk(backup))

    private suspend fun seedManga(
        env: BackupTestEnvironment,
        favorite: Boolean,
        version: Long,
        author: String = "Database author",
        deletedAt: Long? = null,
        lastUpdate: Long = 0L,
        nextUpdate: Long = 0L,
        calculateInterval: Long = 0L,
    ) {
        env.handler.await {
            mangasQueries.insert(
                source = source,
                url = url,
                artist = null,
                author = author,
                description = null,
                genre = null,
                title = "Fixture Manga 1",
                status = 1L,
                thumbnailUrl = null,
                favorite = favorite,
                lastUpdate = lastUpdate,
                nextUpdate = nextUpdate,
                initialized = true,
                viewer = 0L,
                chapterFlags = 0L,
                coverLastModified = 0L,
                dateAdded = 1L,
                updateStrategy = com.shinsaikou.source.model.UpdateStrategy.ALWAYS_UPDATE,
                calculateInterval = calculateInterval,
                lastModifiedAt = 0L,
                version = version,
                notes = "",
                memo = kotlinx.serialization.json.JsonObject(emptyMap()),
                manualPosition = 0L,
                deletedAt = deletedAt,
            )
        }
    }

    private suspend fun seedChapter(
        env: BackupTestEnvironment,
        read: Boolean,
        bookmark: Boolean,
        lastPageRead: Long,
    ) {
        env.handler.await {
            chaptersQueries.insert(
                mangaId = 1L,
                url = "$url/ch/1",
                name = "Chapter 1",
                scanlator = null,
                read = read,
                bookmark = bookmark,
                lastPageRead = lastPageRead,
                chapterNumber = 1.0,
                sourceOrder = 1L,
                dateFetch = 0L,
                dateUpload = 0L,
                version = 0L,
                memo = kotlinx.serialization.json.JsonObject(emptyMap()),
                volume = null,
                deletedAt = null,
            )
        }
        env.handler.awaitList { chaptersQueries.getChaptersByMangaId(1L, 0L, ::mapChapter) }
    }
}
