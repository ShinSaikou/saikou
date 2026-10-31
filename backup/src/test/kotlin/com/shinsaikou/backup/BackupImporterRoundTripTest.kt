package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences.ChapterSwipeAction
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import java.io.File

/**
 * The whole path, on a file the app never saw before: decode, restore, export, decode again, and
 * restore the result a second time. The second restore must change no row count, because a restore
 * that is not idempotent turns a retry into a duplicated library.
 */
class BackupImporterRoundTripTest {

    @TempDir
    lateinit var tempDir: File

    private val fixture = SyntheticBackupFixture.backup(mangaCount = 120, chaptersPerManga = 6)

    @Test
    fun `a fresh import writes every entity the file carries`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            report.cancelled shouldBe false
            report.resumed shouldBe false
            report.mangaTotal shouldBe fixture.backupManga.size
            report.mangaInserted shouldBe fixture.backupManga.size
            report.mangaFailed shouldBe 0
            env.count("mangas") shouldBe fixture.backupManga.size.toLong()
            // The duplicated url in every fixture manga collapses to one row.
            env.count("chapters") shouldBe (fixture.backupManga.size * 6).toLong()
            env.count("history") shouldBe fixture.backupManga.sumOf { manga ->
                manga.history.count { entry -> manga.chapters.any { it.url == entry.url } }
            }.toLong()
            env.count("manga_sync") shouldBe fixture.backupManga.sumOf { manga ->
                manga.tracking.count { it.syncId.toLong() != SyntheticBackupFixture.UNKNOWN_TRACKER }
            }.toLong()
            env.count("categories") shouldBe (SyntheticBackupFixture.CATEGORY_NAMES.size + 1).toLong()
            env.count("extension_store") shouldBe 3L
            report.extensionStoresUpserted shouldBe 2
        }
    }

    @Test
    fun `a source no provider serves is remembered by name, the local source is not`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            report.stubSourcesRemembered shouldContainExactly listOf(SyntheticBackupFixture.SOURCE_MISSING)
            env.stubSourceNames.remembered.getValue(SyntheticBackupFixture.SOURCE_MISSING).name shouldBe
                SyntheticBackupFixture.SOURCE_MISSING.toString()
        }
    }

    @Test
    fun `viewer flags are stored unmasked and the legacy field is the fallback`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))
            env.importer.import(file, RestoreOptions()) {}

            fixture.backupManga.forEach { entry ->
                val stored = env.mangaRepository.getMangaByUrlAndSourceId(entry.url, entry.source)
                stored shouldNotBe null
                stored!!.viewerFlags shouldBe (entry.viewer_flags ?: entry.viewer).toLong()
            }
            // The one entry with no viewer_flags falls back to the legacy field.
            val legacy = fixture.backupManga.single { it.viewer_flags == null }
            env.mangaRepository.getMangaByUrlAndSourceId(legacy.url, legacy.source)!!.viewerFlags shouldBe 2L
        }
    }

    @Test
    fun `preferences are remapped, gated and filtered`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            // A live value of a different type than the file carries.
            env.preferenceStore.values[SyntheticBackupFixture.COLLIDING_PREFERENCE_KEY] = "a string"
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))

            val report = env.importer.import(file, RestoreOptions()) {}

            val completed = env.categoryRepository.getAll().single { it.name == "Completed" }
            env.preferenceStore.values["default_category"] shouldBe completed.id.toInt()
            env.preferenceStore.values["library_update_categories"] shouldBe
                env.categoryRepository.getAll()
                    .filter { it.name in listOf("Reading", "Completed") }
                    .map { it.id.toString() }
                    .toSet()
            env.preferenceStore.values[SyntheticBackupFixture.COLLIDING_PREFERENCE_KEY] shouldBe "a string"
            // No app state from the file; the one app-state key a restore writes is sync's own flag.
            env.preferenceStore.values.keys.filter { Preference.isAppState(it) } shouldBe
                listOf(env.syncRestorePreferences.restoredSinceSync.key())
            // The legacy repository key never lands as a preference; it becomes a store row.
            env.preferenceStore.values.containsKey("extension_repos") shouldBe false
            env.extensionStoreRepository.getAll()
                .single { it.contact.website == SyntheticBackupFixture.EXTENSION_REPO_URL }
                .isLegacy shouldBe true

            report.errors.count { it.kind == ImportError.Kind.PREFERENCE_TYPE_MISMATCH } shouldBe 1
            report.errors.count { it.kind == ImportError.Kind.UNKNOWN_TRACKER_SKIPPED } shouldBe 1
            report.unknownTrackers shouldContainExactly listOf(SyntheticBackupFixture.UNKNOWN_TRACKER)
        }
    }

    @Test
    fun `what was imported is what is exported, and re-importing it changes nothing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(fixture))
            env.importer.import(file, RestoreOptions()) {}

            val decoded = env.decoder.decode(Buffer().write(env.exportBytes(BackupOptions(privateSettings = true))))

            assertSameLibrary(fixture, decoded)

            val counts = TABLES.associateWith { env.count(it) }
            val again = env.writeImportFile(ProtoBuf.encodeToByteArray(Backup.serializer(), decoded))
            val second = env.importer.import(again, RestoreOptions()) {}

            second.mangaInserted shouldBe 0
            second.mangaMerged shouldBe fixture.backupManga.size
            second.errors.filter { it.kind != ImportError.Kind.UNKNOWN_TRACKER_SKIPPED }.shouldBeEmpty()
            TABLES.forEach { table -> env.count(table) shouldBe counts.getValue(table) }
        }
    }

    @Test
    fun `a Saikou swipe start action survives a Saikou export and a reimport of that same file`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val library = LibraryPreferences(env.preferenceStore)
            // The value the owner sees on a start swipe here, distinct from the default on
            // either key so the assertion cannot pass by coincidence.
            library.swipeToStartAction.set(ChapterSwipeAction.Download)
            library.swipeToEndAction.set(ChapterSwipeAction.ToggleBookmark)

            val file = env.writeImportFile(env.exportBytes(BackupOptions()))

            // A fresh install has no preferences of its own; whatever it ends up with came
            // entirely out of the file just written.
            env.preferenceStore.values.clear()

            env.importer.import(file, RestoreOptions()) {}

            library.swipeToStartAction.get() shouldBe ChapterSwipeAction.Download
            library.swipeToEndAction.get() shouldBe ChapterSwipeAction.ToggleBookmark
        }
    }

    /** Compared by identity key, never by id: the ids in the file belong to the other install. */
    private fun assertSameLibrary(expected: Backup, actual: Backup) {
        val byKey = actual.backupManga.associateBy { it.source to it.url }
        byKey.size shouldBe expected.backupManga.size

        expected.backupManga.forEach { source ->
            val exported = byKey.getValue(source.source to source.url)
            assertSameManga(source, exported)
        }

        actual.backupCategories.map { it.name to it.order } shouldContainExactly
            expected.backupCategories.map { it.name to it.order }
        // Supersets, not equals: a legacy repository preference becomes an extra store row, and
        // the export lists the local source, which the file the fixture models never names.
        actual.backupExtensionStores.map { it.indexUrl } shouldContainAll
            expected.backupExtensionStores.map { it.indexUrl }
        actual.backupSources.map { it.sourceId } shouldContainAll
            expected.backupSources.map { it.sourceId }
    }

    private fun assertSameManga(expected: BackupManga, actual: BackupManga) {
        actual.title shouldBe expected.title
        actual.author shouldBe expected.author
        actual.artist shouldBe expected.artist
        actual.description shouldBe expected.description
        actual.genre shouldBe expected.genre
        actual.status shouldBe expected.status
        actual.thumbnailUrl shouldBe expected.thumbnailUrl
        actual.favorite shouldBe expected.favorite
        actual.chapterFlags shouldBe expected.chapterFlags
        actual.viewer_flags shouldBe (expected.viewer_flags ?: expected.viewer)
        actual.notes shouldBe expected.notes
        actual.initialized shouldBe expected.initialized
        actual.version shouldBe expected.version
        actual.updateStrategy shouldBe expected.updateStrategy
        actual.dateAdded shouldBe expected.dateAdded
        actual.excludedScanlators.sorted() shouldContainExactly expected.excludedScanlators.sorted()

        val chapters = actual.chapters.associateBy { it.url }
        chapters.size shouldBe expected.chapters.distinctBy { it.url }.size
        expected.chapters.distinctBy { it.url }.forEach { source ->
            val exported = chapters.getValue(source.url)
            exported.name shouldBe source.name
            exported.scanlator shouldBe source.scanlator
            exported.read shouldBe source.read
            exported.bookmark shouldBe source.bookmark
            exported.lastPageRead shouldBe source.lastPageRead
            exported.chapterNumber shouldBe source.chapterNumber
            exported.sourceOrder shouldBe source.sourceOrder
            exported.dateUpload shouldBe source.dateUpload
        }

        val history = actual.history.associateBy { it.url }
        expected.history
            .filter { entry -> expected.chapters.any { it.url == entry.url } }
            .forEach { entry ->
                history.getValue(entry.url).lastRead shouldBe entry.lastRead
                history.getValue(entry.url).readDuration shouldBe entry.readDuration
            }

        val tracks = actual.tracking.associateBy { it.syncId }
        expected.tracking
            .filter { it.syncId.toLong() != SyntheticBackupFixture.UNKNOWN_TRACKER }
            .forEach { source ->
                val exported = tracks.getValue(source.syncId)
                exported.title shouldBe source.title
                exported.lastChapterRead shouldBe source.lastChapterRead
                exported.totalChapters shouldBe source.totalChapters
                exported.score shouldBe source.score
                exported.status shouldBe source.status
                exported.trackingUrl shouldBe source.trackingUrl
                @Suppress("DEPRECATION")
                val remote = if (source.mediaIdInt != 0) source.mediaIdInt.toLong() else source.mediaId
                exported.mediaId shouldBe remote
            }
        actual.categories.size shouldBeGreaterThan 0
    }

    private companion object {
        val TABLES = listOf(
            "mangas", "chapters", "history", "manga_sync", "categories",
            "mangas_categories", "excluded_scanlators", "extension_store",
        )
    }
}
