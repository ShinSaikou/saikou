package com.shinsaikou.sync.engine

import android.content.Context
import com.shinsaikou.backup.export.BackupScheduler
import com.shinsaikou.backup.importer.BackupDatabaseWriter
import com.shinsaikou.backup.importer.BackupImporter
import com.shinsaikou.backup.importer.ImportCheckpoint
import com.shinsaikou.backup.importer.ImportCheckpointStore
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.backup.importer.PreferenceRestorer
import com.shinsaikou.data.extension.ExtensionStoreRepositoryImpl
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.libraryupdate.LibraryUpdateScheduler
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSourceNames
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.TrackerManager
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.FetchInterval
import java.io.File

/*
 * The convergence suite's backup (phase 3 unit E2): one generated `.tachibk`, restored onto each
 * device through the real `BackupImporter`, as the owner's phone and tablet were restored from one
 * upstream backup before they ever synced. The owner's own backup is never read; every name here is
 * invented (`CONTRIBUTING.md` binds fixtures as it binds shipped code).
 */

/** The categories of [syncBackup], in order. */
internal val BACKUP_CATEGORIES = listOf("Reading", "Completed", "On hold", "Plan to read", "Dropped", "Favourites")

/**
 * A library of [entries] favourites on [SyncTestDevice.SOURCE], urls `/series/<n>`, each with
 * [chapters] chapters `/series/<n>/chapter/<c>`, one or two categories, history for every read
 * chapter, a tracker binding on every fourth entry and excluded scanlators on every seventh.
 */
internal fun syncBackup(entries: Int, chapters: Int): Backup = Backup(
    backupManga = (1..entries).map { n ->
        val chapterList = (1..chapters).map { c ->
            BackupChapter(
                url = "/series/$n/chapter/$c",
                name = "Chapter $c",
                scanlator = if (c % 2 == 0) "Group A" else "Group B",
                read = c <= n % chapters,
                bookmark = (n + c) % 9 == 0,
                lastPageRead = if (c == n % chapters + 1) (n % 13).toLong() else 0L,
                dateFetch = 1_650_000_000_000L + c,
                dateUpload = 1_640_000_000_000L + c,
                chapterNumber = c.toFloat(),
                sourceOrder = (chapters - c).toLong(),
            )
        }
        BackupManga(
            source = SyncTestDevice.SOURCE,
            url = "/series/$n",
            title = "Title $n",
            artist = "Artist ${n % 40}",
            author = "Author ${n % 50}",
            description = "Description of entry $n.",
            genre = listOf("Genre ${n % 7}", "Genre ${n % 11}"),
            status = n % 3,
            thumbnailUrl = "https://example.invalid/cover/$n.jpg",
            dateAdded = 1_600_000_000_000L + n,
            chapters = chapterList,
            categories = listOfNotNull((n % BACKUP_CATEGORIES.size).toLong(), if (n % 5 == 0) 0L else null).distinct(),
            tracking = if (n % 4 == 0) {
                listOf(
                    BackupTracking(
                        syncId = TrackerIds.ANILIST.toInt(),
                        libraryId = n.toLong(),
                        trackingUrl = "https://example.invalid/track/$n",
                        title = "Title $n",
                        lastChapterRead = (n % chapters).toFloat(),
                        totalChapters = chapters,
                        score = 7f,
                        status = 1,
                        mediaId = 9_000L + n,
                    ),
                )
            } else {
                emptyList()
            },
            favorite = true,
            history = chapterList.filter { it.read }.mapIndexed { index, chapter ->
                BackupHistory(url = chapter.url, lastRead = 1_660_000_000_000L + n * 100L + index, readDuration = 60_000L)
            },
            excludedScanlators = if (n % 7 == 0) listOf("Group C") else emptyList(),
            version = 1,
            notes = if (n % 10 == 0) "Notes $n" else "",
        )
    },
    backupCategories = BACKUP_CATEGORIES.mapIndexed { index, name ->
        BackupCategory(name = name, order = index.toLong(), id = index + 1L, flags = 0)
    },
    backupSources = listOf(BackupSource(name = "Fixture Source", sourceId = SyncTestDevice.SOURCE)),
)

/** [backup] as the raw protobuf a `.tachibk` holds; the importer reads it ungzipped too. */
internal fun Backup.bytes(): ByteArray = ProtoBuf.encodeToByteArray(Backup.serializer(), this)

/**
 * Restores [bytes] onto this device through the real [BackupImporter]: library entries and
 * categories, no settings, as a restore of an upstream backup onto a device leaves its library. The
 * importer's own files go under [filesDir]. It sets the restore flag this device's engine reads.
 */
internal fun SyncTestDevice.restore(bytes: ByteArray, filesDir: File): ImportReport {
    val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
    // No source is installed: every entry restores against a stub, which the importer allows.
    val sourceManager = mockk<SourceManager>()
    every { sourceManager.isInitialized } returns MutableStateFlow(true)
    every { sourceManager.get(any()) } returns null
    every { sourceManager.getOrStub(any()) } returns mockk<Source>(relaxed = true)
    val chapterRepository = ChapterRepositoryImpl(handler)
    val fetchInterval = FetchInterval(GetChaptersByMangaId(chapterRepository))
    val decoder = BackupDecoder(context, ProtoBuf)
    val importer = BackupImporter(
        decoder = decoder,
        validator = BackupFileValidator(decoder, sourceManager, TrackerManager(emptySet())),
        handler = handler,
        writer = BackupDatabaseWriter(handler),
        categoryRepository = CategoryRepositoryImpl(handler),
        extensionStoreRepository = ExtensionStoreRepositoryImpl(handler),
        sourceAliasRepository = aliases,
        stubSourceNames = mockk<StubSourceNames>(relaxed = true),
        sourceManager = sourceManager,
        preferenceRestorer = mockk<PreferenceRestorer>(relaxed = true),
        updateManga = UpdateManga(MangaRepositoryImpl(handler), fetchInterval),
        fetchInterval = fetchInterval,
        libraryPreferences = mockk<LibraryPreferences>(relaxed = true),
        checkpoints = FileCheckpoints(filesDir),
        libraryUpdateScheduler = mockk<LibraryUpdateScheduler>(relaxed = true),
        backupScheduler = mockk<BackupScheduler>(relaxed = true),
        syncRestorePreferences = restorePreferences,
        json = Json { ignoreUnknownKeys = true },
    )
    val options = RestoreOptions(appSettings = false, extensionStores = false, sourceSettings = false)
    val file = File(filesDir, "restore-${System.nanoTime()}.tachibk").apply { writeBytes(bytes) }
    return runBlocking { importer.import(file, options) {} }
}

/** The importer's checkpoint in memory and its files under [directory]. */
private class FileCheckpoints(private val directory: File) : ImportCheckpointStore {
    private var checkpoint: ImportCheckpoint? = null

    override fun load(): ImportCheckpoint? = checkpoint

    override fun save(checkpoint: ImportCheckpoint) {
        this.checkpoint = checkpoint
    }

    override fun clear() {
        checkpoint = null
    }

    override val importFile: File get() = File(directory, "current.tachibk")
    override val errorLog: File get() = File(directory, "errors.jsonl")
    override val reportFile: File get() = File(directory, "last-report.json")
}
