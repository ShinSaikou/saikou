package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.fixture.SyntheticBackupFixture.SOURCE_A
import com.shinsaikou.backup.fixture.SyntheticBackupFixture.SOURCE_B
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.source.model.UpdateStrategy
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.extensions.JsonObjectEmptyBytes
import java.io.File

/**
 * R165 item 3: what the owner made in Saikou travels inside the `.tachibk`, in TachiyomiSY's custom
 * info (602 and 800 to 805) and in Saikou's own block, and a file without it restores as it always did. The library is
 * written with SQL, exported through the real exporter and restored through the real importer into
 * a second database. [UpstreamBackup] is a copy of upstream v0.20.4's models with nothing added, so
 * what upstream reads of a Saikou file is checked on the wire.
 */
class SaikouBackupDataTest {

    @TempDir
    lateinit var tempDir: File

    private val options = RestoreOptions(installExtensions = false)

    @Test
    fun `every kind of Saikou data comes back in an empty database`() = runTest {
        val bytes = exportLibrary()
        BackupTestEnvironment(File(tempDir, "to")).use { env ->
            val report = restore(env, bytes)

            // Sub-categories: the child under its parent, found by the parent's order and name.
            val parent = category(env, "Parent")
            val child = category(env, "Child")
            child["parent_id"] shouldBe parent["_id"]
            parent["parent_id"].shouldBeNull()
            env.rows("mangas_categories").single()["category_id"] shouldBe child["_id"]

            // The library's manual order, and the overrides, the source's text staying under them.
            row(env, ONE)["manual_position"] shouldBe 3L
            row(env, TWO)["manual_position"] shouldBe 1L
            row(env, THREE)["manual_position"] shouldBe 2L
            row(env, ONE)["title_override"] shouldBe "Edited one"
            row(env, ONE)["description_override"] shouldBe "Edited text"
            row(env, ONE)["title"] shouldBe "Title 21"
            row(env, TWO)["title_override"].shouldBeNull()
            // The other four edits, an edit to Unknown (0) among them, in the column's joined form.
            row(env, ONE)["author_override"] shouldBe "Edited author"
            row(env, ONE)["artist_override"] shouldBe "Edited artist"
            row(env, ONE)["status_override"] shouldBe 0L
            row(env, ONE)["genre_override"] shouldBe "Edited genre, Second genre"
            row(env, ONE)["author"].shouldBeNull()
            row(env, THREE)["status_override"] shouldBe 4L
            for (column in DETAIL_EDITS) row(env, TWO)[column].shouldBeNull()

            // A chapter's volume, its page notes with their dates, and the scanlator priority.
            chapter(env, "$ONE/c1")["volume"] shouldBe 2.0
            chapter(env, "$TWO/c1")["volume"].shouldBeNull()
            val notes = env.rows("bookmark_notes")
            notes.map { Triple(it["chapter_id"], it["page_index"], it["note"]) } shouldContainExactly
                listOf(Triple(chapter(env, "$ONE/c1")["_id"], 3L, "A note"))
            notes.single()["date_added"] shouldBe T + 1
            priorities(env, ONE) shouldContainExactly listOf("Group A" to 0L, "Group B" to 1L)

            // The merged entry, whatever the switch says (it is off here), with its options.
            val group = env.mergedGroupRepository.getLiveGroups().single()
            group.mainMangaId shouldBe id(env, ONE)
            group.members.map { it.url } shouldContainExactly listOf(TWO, ONE, THREE)
            group.members.map { it.checkUpdates } shouldContainExactly listOf(false, true, true)
            group.showAll shouldBe true
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
            report.mergedTitles.shouldNotBeNull().switchOn shouldBe false

            // The custom cover, byte for byte, with the cover clock moved so it shows.
            env.customCovers.files.getValue(id(env, ONE)).toList() shouldBe COVER.toList()
            env.customCovers.files.keys shouldBe setOf(id(env, ONE))
            (row(env, ONE)["cover_last_modified"] as Long != 0L) shouldBe true
            report.customCoversRestored shouldBe 1
            report.errors.shouldBeEmpty()
        }
    }

    @Test
    fun `the restore writes the Saikou data as it writes other rows, unlogged under the latches, the merge logged`() =
        runTest {
            val bytes = exportLibrary()
            BackupTestEnvironment(File(tempDir, "to")).use { env ->
                restore(env, bytes)

                val logged = env.rows("sync_change_log").map { it["entity_type"] }
                logged shouldNotContain "bookmark_note"
                logged shouldNotContain "scanlator_priority"
                // The merge goes through the repository, as the SY import's does, so sync sends it on.
                logged shouldContain "merged_group"
                env.rows("mangas").forEach { manga ->
                    withClue(manga["url"]) {
                        manga["position_modified_at"].shouldBeNull()
                        manga["priority_modified_at"].shouldBeNull()
                        manga["details_modified_at"].shouldBeNull()
                        manga["is_syncing"] shouldBe 0L
                    }
                }
                env.rows("chapters").forEach { it["bookmark_notes_modified_at"].shouldBeNull() }
                env.rows("chapters").forEach { it["is_syncing"] shouldBe 0L }
                // A created category holds no clock, its parent's write included (phase 5 unit CT).
                category(env, "Child")["modified_at"].shouldBeNull()
            }
        }

    @Test
    fun `upstream's models read a Saikou file as the same upstream data, skipping the rest`() = runTest {
        BackupTestEnvironment(File(tempDir, "from")).use { env ->
            seedLibrary(env)
            env.preferenceStore.values["fixture_string"] = "text"
            env.preferenceStore.values["fixture_set"] = setOf("a", "b")
            val raw = gunzip(env.exportBytes(BackupOptions()))

            val upstream = ProtoBuf.decodeFromByteArray(UpstreamBackup.serializer(), raw)
            val saikou = ProtoBuf.decodeFromByteArray(Backup.serializer(), raw)
            // There is Saikou data in the file for upstream to skip.
            saikou.mergedGroups.size shouldBe 1
            saikou.customCovers.size shouldBe 1
            saikou.backupManga.single { it.url == ONE }.customTitle shouldBe "Edited one"
            saikou.backupManga.single { it.url == ONE }.customGenre shouldBe listOf("Edited genre", "Second genre")
            saikou.backupManga.single { it.url == ONE }.statusOverride shouldBe 0
            saikou.backupManga.single { it.url == THREE }.customStatus shouldBe 4
            upstream.backupManga.single { it.url == ONE }.title shouldBe "Title 21"
            // Upstream's own fields keep the source's details under the edits.
            upstream.backupManga.single { it.url == ONE }.author.shouldBeNull()
            upstream.backupManga.single { it.url == ONE }.genre.shouldBeEmpty()
            upstream.backupManga.single { it.url == THREE }.status shouldBe 0

            val asUpstream = ProtoBuf.encodeToByteArray(UpstreamBackup.serializer(), upstream)
            val saikouLessItsOwn = ProtoBuf.encodeToByteArray(Backup.serializer(), saikou.withoutSaikouData())
            asUpstream.toList() shouldBe saikouLessItsOwn.toList()
        }
    }

    @Test
    fun `a plain upstream file restores no Saikou data`() = runTest {
        val plain = BackupTestEnvironment(File(tempDir, "from")).use { env ->
            seedLibrary(env)
            val raw = gunzip(env.exportBytes(BackupOptions()))
            val upstream = ProtoBuf.decodeFromByteArray(UpstreamBackup.serializer(), raw)
            ProtoBuf.encodeToByteArray(UpstreamBackup.serializer(), upstream)
        }
        BackupTestEnvironment(File(tempDir, "to")).use { env ->
            val report = restore(env, plain)

            report.mangaInserted shouldBe 3
            env.rows("mangas").forEach { manga ->
                withClue(manga["url"]) {
                    manga["manual_position"] shouldBe 0L
                    for (column in DETAIL_EDITS) manga[column].shouldBeNull()
                }
            }
            env.rows("chapters").forEach { it["volume"].shouldBeNull() }
            env.rows("categories").forEach { it["parent_id"].shouldBeNull() }
            env.rows("bookmark_notes").shouldBeEmpty()
            env.rows("scanlator_priority").shouldBeEmpty()
            env.rows("merged_groups").shouldBeEmpty()
            env.customCovers.files.keys.shouldBeEmpty()
            report.mergedTitles.shouldBeNull()
            report.customCoversRestored shouldBe 0
            report.errors.shouldBeEmpty()
        }
    }

    /**
     * Saikou writes the overrides in TachiyomiSY's custom info, which the TachiyomiSY path reads: one
     * path, so a Saikou file's edit is filled once, and a second restore of it writes nothing.
     */
    @Test
    fun `Saikou's own custom info goes through the TachiyomiSY path once`() = runTest {
        val bytes = exportLibrary()
        BackupTestEnvironment(File(tempDir, "to")).use { env ->
            restore(env, bytes)
            val first = env.rows("mangas")

            val report = restore(env, bytes)

            env.rows("mangas").map { manga -> DETAIL_EDITS.map { manga[it] } } shouldBe
                first.map { manga -> DETAIL_EDITS.map { manga[it] } }
            env.rows("mangas").map { it["version"] } shouldBe first.map { it["version"] }
            env.rows("bookmark_notes").size shouldBe 1
            priorities(env, ONE) shouldContainExactly listOf("Group A" to 0L, "Group B" to 1L)
            env.mergedGroupRepository.getLiveGroups().size shouldBe 1
            report.customCoversRestored shouldBe 0
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
            report.errors.shouldBeEmpty()
        }
    }

    @Test
    fun `the file fills only what the device does not hold`() = runTest {
        val bytes = exportLibrary(extraNote = true)
        BackupTestEnvironment(File(tempDir, "to")).use { env ->
            env.insertCategory(11, "Child", sort = 0, parent = null)
            env.insertManga(
                31, SOURCE_A, ONE, manualPosition = 7, titleOverride = "Mine", descriptionOverride = "",
                authorOverride = "My author", statusOverride = 3,
            )
            env.insertChapter(41, 31, "$ONE/c1", volume = 5.0)
            env.insertNote(41, page = 3, note = "My note", dateAdded = T)
            env.insert("scanlator_priority", mapOf("manga_id" to 31L, "scanlator" to "Group C", "priority" to 0L))
            env.insertManga(32, SOURCE_B, TWO)
            env.insertChapter(42, 32, "$TWO/c1", volume = null)
            env.customCovers.files[31L] = byteArrayOf(9, 9)

            val report = restore(env, bytes)

            // Kept: the owner's edits here, an emptied description included.
            row(env, ONE)["manual_position"] shouldBe 7L
            row(env, ONE)["title_override"] shouldBe "Mine"
            row(env, ONE)["description_override"] shouldBe ""
            row(env, ONE)["author_override"] shouldBe "My author"
            row(env, ONE)["status_override"] shouldBe 3L
            chapter(env, "$ONE/c1")["volume"] shouldBe 5.0
            priorities(env, ONE) shouldContainExactly listOf("Group C" to 0L)
            env.customCovers.files.getValue(31L).toList() shouldBe listOf<Byte>(9, 9)
            // A category already here keeps its own place: the file's parent is for the ones it creates.
            category(env, "Child")["parent_id"].shouldBeNull()
            // Filled: what was empty. The page with a note keeps it, the other page gets the file's.
            row(env, TWO)["manual_position"] shouldBe 1L
            row(env, ONE)["artist_override"] shouldBe "Edited artist"
            row(env, ONE)["genre_override"] shouldBe "Edited genre, Second genre"
            notes(env, "$ONE/c1") shouldContainExactly listOf(3L to "My note", 5L to "Another note")
            report.customCoversRestored shouldBe 0
        }
    }

    @Test
    fun `a member already in another group stays there and is reported`() = runTest {
        val bytes = exportLibrary()
        BackupTestEnvironment(File(tempDir, "to")).use { env ->
            env.insertManga(33, SOURCE_A, THREE)
            env.insertManga(39, SOURCE_B, NINE)
            env.mergedGroupRepository.merge(39L, listOf(39L, 33L))

            val report = restore(env, bytes)

            val groups = env.mergedGroupRepository.getLiveGroups().associateBy { it.mainMangaId }
            groups.getValue(39L).members.map { it.url } shouldContainExactly listOf(NINE, THREE)
            groups.getValue(id(env, ONE)).members.map { it.url } shouldContainExactly listOf(TWO, ONE)
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
            val error = report.errors.single { it.kind == ImportError.Kind.MERGED_TITLE }
            error.subject shouldBe "Edited one"
            error.message shouldContain "Title 33"
        }
    }

    @Test
    fun `a group restores the members that exist, and names the one that does not`() = runTest {
        val backup = BackupTestEnvironment(File(tempDir, "from")).use { env ->
            seedLibrary(env)
            env.export(BackupOptions())
        }
        val withoutThree = backup.copy(backupManga = backup.backupManga.filterNot { it.url == THREE })
        BackupTestEnvironment(File(tempDir, "to")).use { env ->
            val report = restore(env, SyntheticBackupFixture.tachibk(withoutThree))

            env.mergedGroupRepository.getLiveGroups().single().members.map { it.url } shouldContainExactly
                listOf(TWO, ONE)
            report.mergedTitles.shouldNotBeNull().missing.map { it.title to it.url } shouldContainExactly
                listOf("Edited one" to THREE)
        }
        val onlyOne = backup.copy(backupManga = backup.backupManga.filter { it.url == ONE })
        BackupTestEnvironment(File(tempDir, "alone")).use { env ->
            val report = restore(env, SyntheticBackupFixture.tachibk(onlyOne))

            env.rows("merged_groups").shouldBeEmpty()
            report.mergedTitles.shouldNotBeNull().unmerged shouldContainExactly listOf("Edited one")
            report.mergedTitles.shouldNotBeNull().merged shouldBe 0
        }
    }

    // The library: three entries in two categories, one merged entry of all three, and one of each
    // kind of Saikou data.

    private suspend fun exportLibrary(extraNote: Boolean = false): ByteArray =
        BackupTestEnvironment(File(tempDir, "from")).use { env ->
            seedLibrary(env)
            if (extraNote) {
                env.insertNote(41, page = 5, note = "Another note", dateAdded = T)
            }
            env.exportBytes(BackupOptions())
        }

    private suspend fun seedLibrary(env: BackupTestEnvironment) {
        env.insertCategory(11, "Parent", sort = 0, parent = null)
        env.insertCategory(12, "Child", sort = 1, parent = 11)
        env.insertManga(
            21, SOURCE_A, ONE, manualPosition = 3, titleOverride = "Edited one", descriptionOverride = "Edited text",
            authorOverride = "Edited author", artistOverride = "Edited artist", statusOverride = 0,
            genreOverride = "Edited genre, Second genre",
        )
        env.insertManga(22, SOURCE_B, TWO, manualPosition = 1)
        env.insertManga(23, SOURCE_A, THREE, manualPosition = 2, statusOverride = 4)
        env.insertChapter(41, 21, "$ONE/c1", volume = 2.0)
        env.insertChapter(42, 22, "$TWO/c1", volume = null)
        env.insertChapter(43, 23, "$THREE/c1", volume = 1.0)
        env.insert("mangas_categories", mapOf("manga_id" to 21L, "category_id" to 12L))
        env.insertNote(41, page = 3, note = "A note", dateAdded = T + 1)
        env.insert("scanlator_priority", mapOf("manga_id" to 21L, "scanlator" to "Group B", "priority" to 1L))
        env.insert("scanlator_priority", mapOf("manga_id" to 21L, "scanlator" to "Group A", "priority" to 0L))
        env.mergedGroupRepository.merge(21L, listOf(22L, 21L, 23L))
        env.mergedGroupRepository.setCheckUpdates(22L, false)
        env.mergedGroupRepository.setShowAll(env.mergedGroupRepository.getLiveGroup(21L)!!.id, true)
        env.customCovers.files[21L] = COVER
    }

    private suspend fun restore(env: BackupTestEnvironment, bytes: ByteArray): ImportReport =
        env.importer.import(env.writeImportFile(bytes), options) {}

    private fun row(env: BackupTestEnvironment, url: String) = env.rows("mangas").single { it["url"] == url }

    private fun id(env: BackupTestEnvironment, url: String): Long = row(env, url)["_id"] as Long

    private fun chapter(env: BackupTestEnvironment, url: String) = env.rows("chapters").single { it["url"] == url }

    private fun category(env: BackupTestEnvironment, name: String) =
        env.rows("categories").single { it["name"] == name }

    private fun priorities(env: BackupTestEnvironment, url: String) = env.rows("scanlator_priority")
        .filter { it["manga_id"] == id(env, url) }
        .sortedBy { it["priority"] as Long }
        .map { it["scanlator"] as String to it["priority"] as Long }

    private fun notes(env: BackupTestEnvironment, chapterUrl: String) = env.rows("bookmark_notes")
        .filter { it["chapter_id"] == chapter(env, chapterUrl)["_id"] }
        .sortedBy { it["page_index"] as Long }
        .map { it["page_index"] as Long to it["note"] as String }

    private fun gunzip(bytes: ByteArray): ByteArray =
        GzipSource(Buffer().write(bytes)).buffer().use { it.readByteArray() }

    /** The file as upstream's models would encode it: Saikou's own fields, and SY's custom info, at their defaults. */
    private fun Backup.withoutSaikouData(): Backup = apply {
        mergedGroups = emptyList()
        customCovers = emptyList()
        backupCategories.forEach { it.parentOrder = null }
        backupManga.forEach { manga ->
            manga.customStatus = 0
            manga.customTitle = null
            manga.customArtist = null
            manga.customAuthor = null
            manga.customDescription = null
            manga.customGenre = null
            manga.manualPosition = 0
            manga.scanlatorPriority = emptyList()
            manga.statusOverride = null
            manga.chapters.forEach { chapter ->
                chapter.volume = null
                chapter.pageNotes = emptyList()
            }
        }
    }

    private companion object {
        const val T = 1_726_000_000_000L
        const val ONE = "/saikou/1"
        const val TWO = "/saikou/2"
        const val THREE = "/saikou/3"
        const val NINE = "/saikou/9"
        val COVER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

        /** The six override columns. */
        val DETAIL_EDITS = listOf(
            "title_override", "description_override", "author_override", "artist_override", "status_override",
            "genre_override",
        )

        fun BackupTestEnvironment.insertCategory(id: Long, name: String, sort: Long, parent: Long?) = insert(
            "categories",
            mapOf(
                "_id" to id, "name" to name, "sort" to sort, "flags" to 0L, "parent_id" to parent,
                "sync_key" to "key-$id",
            ),
        )

        fun BackupTestEnvironment.insertNote(chapter: Long, page: Long, note: String, dateAdded: Long) = insert(
            "bookmark_notes",
            mapOf("chapter_id" to chapter, "page_index" to page, "note" to note, "date_added" to dateAdded),
        )

        fun BackupTestEnvironment.insertManga(
            id: Long,
            source: Long,
            url: String,
            manualPosition: Long = 0,
            titleOverride: String? = null,
            descriptionOverride: String? = null,
            authorOverride: String? = null,
            artistOverride: String? = null,
            statusOverride: Long? = null,
            genreOverride: String? = null,
        ) = insert(
            "mangas",
            mapOf(
                "_id" to id, "source" to source, "url" to url, "title" to "Title $id", "status" to 0L,
                "favorite" to true, "initialized" to 1L, "viewer" to 0L, "chapter_flags" to 0L,
                "cover_last_modified" to 0L, "date_added" to T + id, "memo" to "{}".encodeToByteArray(),
                "manual_position" to manualPosition, "title_override" to titleOverride,
                "description_override" to descriptionOverride, "author_override" to authorOverride,
                "artist_override" to artistOverride, "status_override" to statusOverride,
                "genre_override" to genreOverride,
            ),
        )

        fun BackupTestEnvironment.insertChapter(id: Long, manga: Long, url: String, volume: Double?) = insert(
            "chapters",
            mapOf(
                "_id" to id, "manga_id" to manga, "url" to url, "name" to "Chapter $id", "read" to 0L,
                "bookmark" to 0L, "last_page_read" to 0L, "chapter_number" to 1.0, "source_order" to 0L,
                "date_fetch" to T, "date_upload" to T, "memo" to "{}".encodeToByteArray(), "volume" to volume,
            ),
        )
    }
}

// Upstream v0.20.4's backup models with nothing added: what upstream and the readers that keep its
// models read of a file. A sealed subclass's serial name is on the wire, so it is upstream's.

@Serializable
private data class UpstreamBackup(
    @ProtoNumber(1) val backupManga: List<UpstreamManga>,
    @ProtoNumber(2) var backupCategories: List<UpstreamCategory> = emptyList(),
    @ProtoNumber(101) var backupSources: List<UpstreamSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<UpstreamPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<UpstreamSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionStores: List<UpstreamExtensionStore> = emptyList(),
)

@Serializable
private class UpstreamManga(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(13) var dateAdded: Long = 0,
    @ProtoNumber(14) var viewer: Int = 0,
    @ProtoNumber(16) var chapters: List<UpstreamChapter> = emptyList(),
    @ProtoNumber(17) var categories: List<Long> = emptyList(),
    @ProtoNumber(18) var tracking: List<UpstreamTracking> = emptyList(),
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(101) var chapterFlags: Int = 0,
    @ProtoNumber(103) var viewer_flags: Int? = null,
    @ProtoNumber(104) var history: List<UpstreamHistory> = emptyList(),
    @ProtoNumber(105) var updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    @ProtoNumber(106) var lastModifiedAt: Long = 0,
    @ProtoNumber(107) var favoriteModifiedAt: Long? = null,
    @ProtoNumber(108) var excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(109) var version: Long = 0,
    @ProtoNumber(110) var notes: String = "",
    @ProtoNumber(111) var initialized: Boolean = false,
    @ProtoNumber(112) var memo: ByteArray = JsonObjectEmptyBytes,
)

@Serializable
private class UpstreamChapter(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var read: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    @ProtoNumber(6) var lastPageRead: Long = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    @ProtoNumber(9) var chapterNumber: Float = 0F,
    @ProtoNumber(10) var sourceOrder: Long = 0,
    @ProtoNumber(11) var lastModifiedAt: Long = 0,
    @ProtoNumber(12) var version: Long = 0,
    @ProtoNumber(13) var memo: ByteArray = JsonObjectEmptyBytes,
)

@Serializable
private class UpstreamCategory(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var order: Long = 0,
    @ProtoNumber(3) var id: Long = 0,
    @ProtoNumber(100) var flags: Long = 0,
)

@Serializable
private data class UpstreamHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastRead: Long,
    @ProtoNumber(3) var readDuration: Long = 0,
)

@Serializable
private data class UpstreamTracking(
    @ProtoNumber(1) var syncId: Int,
    @ProtoNumber(2) var libraryId: Long,
    @ProtoNumber(3) var mediaIdInt: Int = 0,
    @ProtoNumber(4) var trackingUrl: String = "",
    @ProtoNumber(5) var title: String = "",
    @ProtoNumber(6) var lastChapterRead: Float = 0F,
    @ProtoNumber(7) var totalChapters: Int = 0,
    @ProtoNumber(8) var score: Float = 0F,
    @ProtoNumber(9) var status: Int = 0,
    @ProtoNumber(10) var startedReadingDate: Long = 0,
    @ProtoNumber(11) var finishedReadingDate: Long = 0,
    @ProtoNumber(12) var private: Boolean = false,
    @ProtoNumber(100) var mediaId: Long = 0,
)

@Serializable
private data class UpstreamSource(
    @ProtoNumber(1) var name: String = "",
    @ProtoNumber(2) var sourceId: Long,
)

@Serializable
private data class UpstreamPreference(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: UpstreamPreferenceValue,
)

@Serializable
private data class UpstreamSourcePreferences(
    @ProtoNumber(1) val sourceKey: String,
    @ProtoNumber(2) val prefs: List<UpstreamPreference>,
)

@Serializable
private sealed class UpstreamPreferenceValue

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue")
private data class UpstreamIntPreferenceValue(val value: Int) : UpstreamPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue")
private data class UpstreamLongPreferenceValue(val value: Long) : UpstreamPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue")
private data class UpstreamFloatPreferenceValue(val value: Float) : UpstreamPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue")
private data class UpstreamStringPreferenceValue(val value: String) : UpstreamPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue")
private data class UpstreamBooleanPreferenceValue(val value: Boolean) : UpstreamPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue")
private data class UpstreamStringSetPreferenceValue(val value: Set<String>) : UpstreamPreferenceValue()

@Serializable
private class UpstreamExtensionStore(
    @ProtoNumber(1) var indexUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var badgeLabel: String?,
    @ProtoNumber(5) var signingKey: String,
    @ProtoNumber(4) var contactWebsite: String,
    @ProtoNumber(6) var contactDiscord: String?,
    @ProtoNumber(7) var isLegacy: Boolean?,
    @ProtoNumber(8) var extensionListUrl: String?,
)
