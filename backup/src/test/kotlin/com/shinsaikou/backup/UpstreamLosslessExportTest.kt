package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.ConfigurableFakeSource
import com.shinsaikou.backup.fixture.FakeSource
import com.shinsaikou.backup.fixture.SyntheticBackupFixture.SOURCE_A
import com.shinsaikou.backup.fixture.SyntheticBackupFixture.SOURCE_A_NAME
import com.shinsaikou.backup.fixture.SyntheticBackupFixture.SOURCE_B
import com.shinsaikou.backup.fixture.SyntheticBackupFixture.SOURCE_B_NAME
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.model.UpdateStrategy
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.GzipSource
import okio.buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Phase 3 unit R2, part 2: what Saikou exports is what upstream's creator would have written from the
 * same rows, field by field, so a restore into upstream keeps everything its own backup keeps.
 *
 * A fixture library is written straight into the JVM database with SQL, setting every column the
 * file carries to a value other than the field's default (and every Saikou column too, so none of them
 * can leak into a field). [BackupExporter] builds and writes it with the default options, and the
 * bytes are decoded as upstream's `BackupDecoder` decodes them: gzip by its magic bytes, then the
 * default [ProtoBuf] (part 1 holds the decoding classes to upstream's). Every field of every message is
 * then compared with the rows it comes from under the rules of upstream v0.20.4's creators
 * (`MangaBackupCreator`, `CategoriesBackupCreator`, `ExtensionStoresBackupCreator`,
 * `SourcesBackupCreator`, `PreferenceBackupCreator`): categories travel as order values, the
 * chapter number, the tracked chapter and the score narrow to Float, the legacy viewer field is the
 * flags under upstream's reading mode mask, and a null library id is written as 0.
 *
 * Two lists keep this honest as the schema grows. [NOT_IN_TACHIBK] names each column of an exported
 * table that the file does not carry, with the reason, and a test fails on a column that is neither
 * mapped nor named. [NOT_EXPORTED_TABLES] does the same for whole tables.
 */
class UpstreamLosslessExportTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var env: BackupTestEnvironment
    private lateinit var exported: Backup
    private lateinit var bytes: ByteArray
    private lateinit var rows: Map<String, List<Row>>

    @BeforeEach
    fun export() = runTest {
        env = BackupTestEnvironment(
            tempDir,
            sources = listOf(
                FakeSource(SOURCE_A, SOURCE_A_NAME),
                ConfigurableFakeSource(SOURCE_B, SOURCE_B_NAME, lang = "en"),
                FakeSource(SourceIds.LOCAL, "Local source", lang = "other"),
            ),
        )
        LosslessFixture.seed(env)
        rows = EXPORTED_TABLES.associateWith(env::rows)
        bytes = env.exportBytes(OPTIONS)
        exported = decodeAsUpstream(bytes)
    }

    @AfterEach
    fun close() = env.close()

    @Test
    fun `the file is gzip, as upstream's creator writes it, and upstream's decoding reads it`() {
        ((bytes[0].toInt() and 0xff) shl 8 or (bytes[1].toInt() and 0xff)) shouldBe GZIP_MAGIC
        exported.backupManga.size shouldBe expectedManga().size
    }

    @Test
    fun `the file holds the library entries upstream's creator would write, and no others`() {
        exported.backupManga.map { it.source to it.url }
            .shouldContainExactlyInAnyOrder(expectedManga().map { it.long("source") to it.text("url") })
    }

    @Test
    fun `every field of every library entry matches its rows`() {
        for (row in expectedManga()) {
            val entry = exported.backupManga.single { it.source == row.long("source") && it.url == row.text("url") }
            for (field in mangaFields) {
                withClue("manga ${row.text("url")}, field ${field.name}") {
                    field.actual(entry) shouldBe field.expected(row)
                }
            }
        }
    }

    /**
     * Held without the expected rows, so a lookup the oracle shares with the exporter cannot hide
     * it: upstream's restore drops a history entry whose url names no chapter of its entry, so every
     * one must name a chapter the file carries for that entry.
     */
    @Test
    fun `every history entry names a chapter of its own entry`() {
        exported.backupManga.flatMap { it.history }.size shouldNotBe 0
        for (entry in exported.backupManga) {
            val urls = entry.chapters.map { it.url }.toSet()
            withClue("manga ${entry.url}") {
                entry.history.map { it.url }.filterNot { it in urls } shouldBe emptyList()
            }
        }
    }

    @Test
    fun `categories and extension stores match their rows`() {
        val categories = rows.getValue("categories").filter { it.long("_id") > 0 && it["deleted_at"] == null }
        exported.backupCategories.map { render(categoryFields, it) }
            .shouldContainExactlyInAnyOrder(categories.map { expect(categoryFields, it) })

        exported.backupExtensionStores.map { render(storeFields, it) }
            .shouldContainExactlyInAnyOrder(rows.getValue("extension_store").map { expect(storeFields, it) })
    }

    /**
     * The one field compared under Saikou's rule rather than upstream's. Contract 06 section 1.7 writes a
     * source's `toString()`, "Name (EN)" for an installed source, where upstream's `SourcesBackupCreator`
     * writes `Source.name`. Upstream reads the name only to label a missing source in its restore dialog
     * and its error log, so nothing a restore keeps depends on it; the unit's ruling draft carries it.
     */
    @Test
    fun `sources list each exported entry's source once, named as contract 06 names them`() {
        val ids = expectedManga().map { it.long("source") }.distinct()
        exported.backupSources.map { it.sourceId }.shouldContainExactlyInAnyOrder(ids)
        exported.backupSources.forEach { it.name shouldBe env.sourceManager.getOrStub(it.sourceId).toString() }
        exported.backupSources.single { it.sourceId == SOURCE_B }.name shouldNotBe SOURCE_B_NAME
    }

    @Test
    fun `app and source preferences travel as upstream's creator writes them, in upstream's key spelling`() {
        exported.backupPreferences.shouldContainExactlyInAnyOrder(LosslessFixture.expectedAppPreferences)
        exported.backupSourcePreferences.map { it.sourceKey to it.prefs.toSet() }
            .shouldContainExactlyInAnyOrder(
                LosslessFixture.expectedSourcePreferences.map { it.sourceKey to it.prefs.toSet() },
            )
    }

    @Test
    fun `every column of every exported table is mapped or named as not in the file`() {
        val mapped = allFields.flatMap { it.columns }.toSet() + RELATION_COLUMNS
        val named = NOT_IN_TACHIBK.keys
        (mapped intersect named) shouldBe emptySet()
        for (table in EXPORTED_TABLES) {
            val columns = env.columns(table).map { "$table.${it.first}" }.toSet()
            withClue("table $table") {
                (columns - mapped - named) shouldBe emptySet()
                ((mapped + named).filter { it.startsWith("$table.") }.toSet() - columns) shouldBe emptySet()
            }
        }
    }

    @Test
    fun `every table is exported or named as not exported`() {
        env.tables().toSet() shouldBe (EXPORTED_TABLES + NOT_EXPORTED_TABLES.keys).toSet()
    }

    @Test
    fun `every field of every message is compared`() {
        fun names(descriptor: SerialDescriptor) = descriptor.elementNames.toSet()
        // The import-only fields are held by the test below them.
        (mangaFields.map { it.name } + IMPORT_ONLY_MANGA_FIELDS).toSet() shouldBe
            names(BackupManga.serializer().descriptor)
        chapterFields.map { it.name }.toSet() shouldBe names(BackupChapter.serializer().descriptor)
        trackFields.map { it.name }.toSet() shouldBe names(BackupTracking.serializer().descriptor)
        historyFields.map { it.name }.toSet() shouldBe names(BackupHistory.serializer().descriptor)
        categoryFields.map { it.name }.toSet() shouldBe names(BackupCategory.serializer().descriptor)
        storeFields.map { it.name }.toSet() shouldBe names(BackupExtensionStore.serializer().descriptor)
        // Backup's own six lists are each compared by a test above: the entries, categories and
        // stores against their rows, sources by id, and both preference lists against the fixture.
        names(Backup.serializer().descriptor) shouldBe setOf(
            "backupManga", "backupCategories", "backupSources", "backupPreferences",
            "backupSourcePreferences", "backupExtensionStores",
        )
        names(BackupSource.serializer().descriptor) shouldBe setOf("name", "sourceId")
    }

    /**
     * The edited title and description the import reads at fields 800 and 804 (R162 item 8) are never
     * written: every fixture entry carries an edit, and the file still holds only upstream's fields.
     */
    @Test
    fun `the fields the import reads from other readers stay out of the file`() {
        expectedManga().forEach { it.text("title_override") shouldNotBe null }
        for (entry in exported.backupManga) {
            withClue("manga ${entry.url}") {
                entry.customTitle shouldBe null
                entry.customDescription shouldBe null
            }
        }
    }

    /**
     * A field the fixture left at its default would compare equal even if the exporter dropped it,
     * so each mapped field must differ from the default in at least one exported message. The
     * default is read off a message built with only its required fields.
     */
    @Test
    fun `the fixture gives every mapped field a value other than its default`() {
        val manga = exported.backupManga
        val chapters = manga.flatMap { it.chapters }
        val tracks = manga.flatMap { it.tracking }
        val history = manga.flatMap { it.history }
        assertMoved(mangaFields, manga, BackupManga(source = 0, url = ""))
        assertMoved(chapterFields, chapters, BackupChapter(url = "", name = ""))
        val trackDefault = BackupTracking(syncId = 0, libraryId = 0)
        assertMoved(trackFields.filterNot { it.name == "mediaIdInt" }, tracks, trackDefault)
        assertMoved(historyFields, history, BackupHistory(url = "", lastRead = 0))
        assertMoved(categoryFields, exported.backupCategories, BackupCategory(name = ""))
        assertMoved(
            storeFields,
            exported.backupExtensionStores,
            BackupExtensionStore("", "", null, "", "", null, null, null),
        )
    }

    private fun <M> assertMoved(fields: List<Field<M>>, messages: List<M>, default: M) {
        for (field in fields) {
            withClue("${field.name} keeps its default in every exported message") {
                messages.any { field.actual(it) != field.actual(default) } shouldBe true
            }
        }
    }

    // What upstream's creator writes from a row, field by field.

    private val mangaFields: List<Field<BackupManga>> = listOf(
        Field("source", "mangas.source", { it.long("source") }, { it.source }),
        Field("url", "mangas.url", { it.text("url") }, { it.url }),
        Field("title", "mangas.title", { it.text("title") }, { it.title }),
        Field("artist", "mangas.artist", { it.text("artist") }, { it.artist }),
        Field("author", "mangas.author", { it.text("author") }, { it.author }),
        Field("description", "mangas.description", { it.text("description") }, { it.description }),
        // Upstream's StringListColumnAdapter splits on ", ", and the creator writes genre.orEmpty().
        Field(
            "genre",
            "mangas.genre",
            { row -> row.text("genre")?.takeIf { it.isNotEmpty() }?.split(", ").orEmpty() },
            { it.genre },
        ),
        Field("status", "mangas.status", { it.long("status").toInt() }, { it.status }),
        Field("thumbnailUrl", "mangas.thumbnail_url", { it.text("thumbnail_url") }, { it.thumbnailUrl }),
        Field("dateAdded", "mangas.date_added", { it.long("date_added") }, { it.dateAdded }),
        Field(
            "viewer",
            "mangas.viewer",
            { it.long("viewer").toInt() and UPSTREAM_READING_MODE_MASK },
            { it.viewer },
        ),
        Field("viewer_flags", "mangas.viewer", { it.long("viewer").toInt() }, { it.viewer_flags }),
        Field("favorite", "mangas.favorite", { it.flag("favorite") }, { it.favorite }),
        Field("chapterFlags", "mangas.chapter_flags", { it.long("chapter_flags").toInt() }, { it.chapterFlags }),
        Field(
            "updateStrategy",
            "mangas.update_strategy",
            { UpdateStrategy.entries[it.long("update_strategy").toInt()] },
            { it.updateStrategy },
        ),
        Field("lastModifiedAt", "mangas.last_modified_at", { it.long("last_modified_at") }, { it.lastModifiedAt }),
        Field(
            "favoriteModifiedAt",
            "mangas.favorite_modified_at",
            { it["favorite_modified_at"] as Long? },
            { it.favoriteModifiedAt },
        ),
        Field("version", "mangas.version", { it.long("version") }, { it.version }),
        Field("notes", "mangas.notes", { it.text("notes") }, { it.notes }),
        Field("initialized", "mangas.initialized", { it.flag("initialized") }, { it.initialized }),
        Field("memo", "mangas.memo", { it.blob("memo") }, { it.memo.toList() }),
        Field(
            "chapters",
            "chapters.manga_id",
            { manga -> chaptersOf(manga).map { expect(chapterFields, it) }.sortedBy { it["url"] as String } },
            { entry -> entry.chapters.map { render(chapterFields, it) }.sortedBy { it["url"] as String } },
        ),
        // Order values, never ids: upstream's restore finds each category by its order in the file.
        Field(
            "categories",
            "categories.sort",
            { manga -> categoriesOf(manga).map(::place).sorted() },
            { it.categories.sorted() },
        ),
        Field(
            "tracking",
            "manga_sync.manga_id",
            { manga -> tracksOf(manga).map { expect(trackFields, it) }.sortedBy { it["syncId"] as Int } },
            { entry -> entry.tracking.map { render(trackFields, it) }.sortedBy { it["syncId"] as Int } },
        ),
        Field(
            "history",
            "history.chapter_id",
            { manga -> historyOf(manga).map { expect(historyFields, it) }.sortedBy { it["url"] as String } },
            { entry -> entry.history.map { render(historyFields, it) }.sortedBy { it["url"] as String } },
        ),
        Field(
            "excludedScanlators",
            "excluded_scanlators.scanlator",
            { manga -> exclusionsOf(manga).map { it.text("scanlator")!! }.sorted() },
            { it.excludedScanlators.sorted() },
        ),
    )

    private val chapterFields: List<Field<BackupChapter>> = listOf(
        Field("url", "chapters.url", { it.text("url") }, { it.url }),
        Field("name", "chapters.name", { it.text("name") }, { it.name }),
        Field("scanlator", "chapters.scanlator", { it.text("scanlator") }, { it.scanlator }),
        Field("read", "chapters.read", { it.flag("read") }, { it.read }),
        Field("bookmark", "chapters.bookmark", { it.flag("bookmark") }, { it.bookmark }),
        Field("lastPageRead", "chapters.last_page_read", { it.long("last_page_read") }, { it.lastPageRead }),
        Field("dateFetch", "chapters.date_fetch", { it.long("date_fetch") }, { it.dateFetch }),
        Field("dateUpload", "chapters.date_upload", { it.long("date_upload") }, { it.dateUpload }),
        Field(
            "chapterNumber",
            "chapters.chapter_number",
            { it.double("chapter_number").toFloat() },
            { it.chapterNumber },
        ),
        Field("sourceOrder", "chapters.source_order", { it.long("source_order") }, { it.sourceOrder }),
        Field("lastModifiedAt", "chapters.last_modified_at", { it.long("last_modified_at") }, { it.lastModifiedAt }),
        Field("version", "chapters.version", { it.long("version") }, { it.version }),
        Field("memo", "chapters.memo", { it.blob("memo") }, { it.memo.toList() }),
    )

    @Suppress("DEPRECATION")
    private val trackFields: List<Field<BackupTracking>> = listOf(
        Field("syncId", "manga_sync.sync_id", { it.long("sync_id").toInt() }, { it.syncId }),
        Field("libraryId", "manga_sync.library_id", { it["library_id"] as Long? ?: 0L }, { it.libraryId }),
        // Upstream's backupTrackMapper never sets the deprecated Int id, so it is always its default.
        Field("mediaIdInt", null, { 0 }, { it.mediaIdInt }),
        Field("trackingUrl", "manga_sync.remote_url", { it.text("remote_url") }, { it.trackingUrl }),
        Field("title", "manga_sync.title", { it.text("title") }, { it.title }),
        Field(
            "lastChapterRead",
            "manga_sync.last_chapter_read",
            { it.double("last_chapter_read").toFloat() },
            { it.lastChapterRead },
        ),
        Field(
            "totalChapters",
            "manga_sync.total_chapters",
            { it.long("total_chapters").toInt() },
            { it.totalChapters },
        ),
        Field("score", "manga_sync.score", { it.double("score").toFloat() }, { it.score }),
        Field("status", "manga_sync.status", { it.long("status").toInt() }, { it.status }),
        Field("startedReadingDate", "manga_sync.start_date", { it.long("start_date") }, { it.startedReadingDate }),
        Field("finishedReadingDate", "manga_sync.finish_date", { it.long("finish_date") }, { it.finishedReadingDate }),
        Field("private", "manga_sync.private", { it.flag("private") }, { it.private }),
        Field("mediaId", "manga_sync.remote_id", { it.long("remote_id") }, { it.mediaId }),
    )

    private val historyFields: List<Field<BackupHistory>> = listOf(
        Field("url", "history.chapter_id", { row -> chapter(row.long("chapter_id")).text("url") }, { it.url }),
        // Upstream writes a null last_read as 0; Saikou drops that row (contract 06 section 1.7), and no
        // Saikou writer stores one, so the fixture has none. The unit's report records the difference.
        Field("lastRead", "history.last_read", { it["last_read"] as Long? ?: 0L }, { it.lastRead }),
        Field("readDuration", "history.time_read", { it.long("time_read") }, { it.readDuration }),
    )

    private val categoryFields: List<Field<BackupCategory>> = listOf(
        Field("name", "categories.name", { it.text("name") }, { it.name }),
        Field("order", "categories.sort", ::place, { it.order }),
        Field("id", "categories._id", { it.long("_id") }, { it.id }),
        Field("flags", "categories.flags", { it.long("flags") }, { it.flags }),
    )

    private val storeFields: List<Field<BackupExtensionStore>> = listOf(
        Field("indexUrl", "extension_store.index_url", { it.text("index_url") }, { it.indexUrl }),
        Field("name", "extension_store.name", { it.text("name") }, { it.name }),
        Field("badgeLabel", "extension_store.badge_label", { it.text("badge_label") }, { it.badgeLabel }),
        Field("signingKey", "extension_store.signing_key", { it.text("signing_key") }, { it.signingKey }),
        Field(
            "contactWebsite",
            "extension_store.contact_website",
            { it.text("contact_website") },
            { it.contactWebsite },
        ),
        Field(
            "contactDiscord",
            "extension_store.contact_discord",
            { it.text("contact_discord") },
            { it.contactDiscord },
        ),
        Field("isLegacy", "extension_store.is_legacy", { it.flag("is_legacy") }, { it.isLegacy }),
        Field(
            "extensionListUrl",
            "extension_store.extension_list_url",
            { it.text("extension_list_url") },
            { it.extensionListUrl },
        ),
    )

    private val allFields: List<Field<*>> =
        mangaFields + chapterFields + trackFields + historyFields + categoryFields + storeFields

    // The rows each creator reads, with Saikou's soft-deleted rows left out: a tombstone is a sync
    // entity, and upstream has no row for it to write.

    /** Upstream's manga set: the library, and every other entry with a read or started chapter. */
    private fun expectedManga(): List<Row> {
        val started = rows.getValue("chapters")
            .filter { it.flag("read") || it.long("last_page_read") != 0L }
            .map { it.long("manga_id") }
            .toSet()
        return rows.getValue("mangas")
            .filter { it["deleted_at"] == null }
            .filter { it.flag("favorite") || it.long("_id") in started }
    }

    private fun chaptersOf(manga: Row) = rows.getValue("chapters")
        .filter { it.long("manga_id") == manga.long("_id") && it["deleted_at"] == null }

    private fun categoriesOf(manga: Row): List<Row> {
        val ids = rows.getValue("mangas_categories").filter { it.long("manga_id") == manga.long("_id") }
            .map { it.long("category_id") }.toSet()
        return rows.getValue("categories").filter { it.long("_id") in ids && it["deleted_at"] == null }
    }

    /**
     * Where upstream's creator writes the sort value, Saikou writes the category's place among the
     * live ones by sort value and then id, so two categories that share a value stay apart.
     */
    private fun place(category: Row): Long = rows.getValue("categories")
        .filter { it.long("_id") > 0 && it["deleted_at"] == null }
        .sortedWith(compareBy({ it.long("sort") }, { it.long("_id") }))
        .indexOfFirst { it.long("_id") == category.long("_id") }
        .toLong()

    private fun tracksOf(manga: Row) = rows.getValue("manga_sync").filter { it.long("manga_id") == manga.long("_id") }

    private fun exclusionsOf(manga: Row) =
        rows.getValue("excluded_scanlators").filter { it.long("manga_id") == manga.long("_id") }

    // Through chaptersOf, so a soft-deleted chapter's history is left out with the chapter.
    private fun historyOf(manga: Row): List<Row> {
        val chapters = chaptersOf(manga).map { it.long("_id") }.toSet()
        return rows.getValue("history").filter { it.long("chapter_id") in chapters }
    }

    private fun chapter(id: Long): Row = rows.getValue("chapters").single { it.long("_id") == id }

    private fun <M> expect(fields: List<Field<M>>, row: Row): Map<String, Any?> =
        fields.associate { it.name to it.expected(row) }

    private fun <M> render(fields: List<Field<M>>, message: M): Map<String, Any?> =
        fields.associate { it.name to it.actual(message) }

    /** Upstream's `BackupDecoder.decode` without its Context: gzip by the magic bytes, then the default ProtoBuf. */
    private fun decodeAsUpstream(file: ByteArray): Backup {
        val source = Buffer().write(file)
        val magic = source.peek().readShort().toInt()
        val raw = if (magic == GZIP_MAGIC) {
            GzipSource(source).buffer().use { it.readByteArray() }
        } else {
            source.readByteArray()
        }
        return ProtoBuf.decodeFromByteArray(Backup.serializer(), raw)
    }

    /** One wire field: the column it is written from (null when none), and its value on each side. */
    private class Field<M>(
        val name: String,
        column: String?,
        val expected: (Row) -> Any?,
        val actual: (M) -> Any?,
    ) {
        val columns: Set<String> = setOfNotNull(column)
    }

    private companion object {
        val OPTIONS = BackupOptions()

        /** The two bytes upstream's decoder peeks for gzip. */
        const val GZIP_MAGIC = 0x1f8b

        /** BackupManga's fields the import reads from other readers' files and the export never fills. */
        val IMPORT_ONLY_MANGA_FIELDS = setOf("customTitle", "customDescription")

        /** Upstream v0.20.4's `ReadingMode.MASK`. */
        const val UPSTREAM_READING_MODE_MASK = 0x00000007

        val EXPORTED_TABLES = listOf(
            "mangas", "chapters", "categories", "mangas_categories", "history", "manga_sync",
            "excluded_scanlators", "extension_store",
        )

        /** Columns that decide which rows travel, or where, rather than being written into a field. */
        val RELATION_COLUMNS = setOf(
            "chapters.manga_id", "mangas_categories.manga_id", "mangas_categories.category_id",
            "manga_sync.manga_id", "excluded_scanlators.manga_id",
        )

        /**
         * The plan's "Saikou only, not in .tachibk" list: every column of an exported table that no
         * field carries. It names Saikou's own columns, and the upstream columns that upstream's creator
         * leaves out of its own file too, each with its reason.
         */
        val NOT_IN_TACHIBK = mapOf(
            "mangas._id" to "this install's row id; upstream's creator writes no manga id",
            "mangas.last_update" to "Upstream's column, not written by upstream's creator either",
            "mangas.next_update" to "Upstream's column, not written by upstream's creator either",
            "mangas.cover_last_modified" to "Upstream's column, not written by upstream's creator either",
            "mangas.calculate_interval" to "Upstream's column, not written by upstream's creator either",
            "mangas.is_syncing" to "Upstream's column, not written by upstream's creator either",
            "mangas.manual_position" to "Saikou only: the library's manual order, a sync entity (contract 06 F10)",
            "mangas.deleted_at" to "Saikou only: a soft-deleted entry is left out of the file",
            "mangas.display_modified_at" to "Saikou only: an owner clock of the sync merge (R82, R85)",
            "mangas.notes_modified_at" to "Saikou only: an owner clock of the sync merge (R82, R85)",
            "mangas.position_modified_at" to "Saikou only: an owner clock of the sync merge (R82, R85)",
            "mangas.categories_modified_at" to "Saikou only: an owner clock of the sync merge (R82, R85)",
            "mangas.exclusions_modified_at" to "Saikou only: an owner clock of the sync merge (R82, R85)",
            "mangas.priority_modified_at" to "Saikou only: an owner clock of the sync merge (R82, R85)",
            "mangas.trackers_modified_at" to "Saikou only: an owner clock of the sync merge (R82, R85)",
            "mangas.restored_at" to "Saikou only: the restore clock of the sync merge (R82)",
            "mangas.title_override" to
                "Saikou only: the owner's title edit; the file writes the source's title (R138 item 2, F10, R19)",
            "mangas.description_override" to
                "Saikou only: the owner's description edit; the file writes the source's text (R138 item 2, F10, R19)",
            "mangas.details_modified_at" to "Saikou only: the owner clock of the two edits, a sync value (R138 item 2)",
            "chapters._id" to "this install's row id; a chapter travels by its url",
            "chapters.is_syncing" to "Upstream's column, not written by upstream's creator either",
            "chapters.volume" to "Saikou only: the format has no volume field (contract 06 section 1.7)",
            "chapters.deleted_at" to "Saikou only: a soft-deleted chapter is left out of the file",
            "chapters.bookmark_notes_modified_at" to "Saikou only: an owner clock of the sync merge (R85)",
            "chapters.restored_at" to "Saikou only: the restore clock of the sync merge (R82)",
            "categories.parent_id" to "Saikou only: the format has no category parent (contract 06 section 1.7)",
            "categories.version" to "Saikou only: the category's sync version",
            "categories.is_syncing" to "Saikou only: the sync apply flag",
            "categories.deleted_at" to "Saikou only: a soft-deleted category is left out of the file",
            "categories.modified_at" to "Saikou only: an owner clock of the sync merge (R85)",
            "categories.restored_at" to "Saikou only: the restore clock of the sync merge (R82)",
            "categories.sync_key" to "Saikou only: the key two devices match a category by (Q7, R85)",
            "mangas_categories._id" to "this install's row id; a membership travels as an order value",
            "history._id" to "this install's row id; history travels by its chapter's url",
            "manga_sync._id" to "this install's row id; a track travels by its tracker id",
        )

        /** Tables the exporter never reads, as upstream's creator reads none of their counterparts. */
        val NOT_EXPORTED_TABLES = mapOf(
            "sources" to "stub source names, which upstream's creator does not write either; " +
                "the file names sources from the registry",
            "source_alias" to "Saikou only: source migration aliases",
            "scanlator_priority" to "Saikou only: the scanlator ranking, a sync entity",
            "bookmark_notes" to "Saikou only: page bookmark notes, a sync entity",
            "download_queue" to "Saikou only: this device's download queue",
            "downloads_manifest" to "Saikou only: this device's downloaded files",
            "sync_change_log" to "Saikou only: the sync engine's change log",
            "library_summary" to "Saikou only: the library's counts, derived from the chapters and history by " +
                "triggers (schema version 3) and written again by a restore's inserts",
        )
    }
}

private typealias Row = Map<String, Any?>

private fun Row.long(column: String): Long = this[column] as Long
private fun Row.double(column: String): Double = this[column] as Double
private fun Row.text(column: String): String? = this[column] as String?
private fun Row.flag(column: String): Boolean = long(column) == 1L
private fun Row.blob(column: String): List<Byte> = (this[column] as ByteArray).toList()

/**
 * A library whose every exported column differs from the field's default somewhere, written with SQL
 * so no repository chooses a value, with a soft-deleted entry, chapter (read, with its history) and
 * category that must not travel and an entry outside the library with nothing read that upstream's
 * creator leaves out.
 */
private object LosslessFixture {

    const val LIBRARY = 21L
    const val READ_ELSEWHERE = 22L
    private const val T = 1_726_000_000_000L

    fun seed(env: BackupTestEnvironment) {
        category(env, 11, "Reading now", sort = 4, flags = 0x2D, parent = null, key = "fixture-reading")
        category(env, 12, "Later", sort = 9, flags = 7, parent = 11, key = "fixture-later")
        category(env, 13, "Gone", sort = 2, flags = 1, parent = null, key = "fixture-gone", deletedAt = T + 13)

        manga(env, LIBRARY, SOURCE_A, "/fixture/lossless/1", favorite = true) {
            put("artist", "Fixture Artist")
            put("author", "Fixture Author")
            put("description", "A description, with a comma")
            put("genre", "Action, Slice of Life, Drama")
            put("status", 2L)
            put("thumbnail_url", "https://example.invalid/cover/1.jpg")
            put("initialized", 1L)
            put("viewer", 0xD3L)
            put("chapter_flags", 0x302L)
            put("update_strategy", 1L)
            put("favorite_modified_at", T + 7)
            put("notes", "A note")
            put("memo", """{"k":"v","n":[1,2]}""".encodeToByteArray())
        }
        manga(env, READ_ELSEWHERE, SOURCE_B, "/fixture/lossless/2", favorite = false) {
            put("status", 1L)
            put("viewer", 0x11L)
            put("chapter_flags", 0x1L)
            put("memo", """{"m":true}""".encodeToByteArray())
        }
        manga(env, 23, SOURCE_A, "/fixture/lossless/deleted", favorite = true) { put("deleted_at", T + 23) }
        manga(env, 24, SOURCE_A, "/fixture/lossless/unread", favorite = false) {}

        chapter(env, 31, LIBRARY, "/fixture/lossless/1/c1", 12.3, read = true, bookmark = true, scanlator = "Group A")
        chapter(env, 32, LIBRARY, "/fixture/lossless/1/c2", 13.7, read = false, bookmark = false, scanlator = "Group B")
        chapter(
            env, 33, LIBRARY, "/fixture/lossless/1/gone", 14.0,
            read = true, bookmark = false, scanlator = null, deletedAt = T + 33,
        )
        chapter(env, 34, READ_ELSEWHERE, "/fixture/lossless/2/c1", 1.1, read = true, bookmark = false, scanlator = null)
        chapter(
            env, 35, 24, "/fixture/lossless/unread/c1", 1.0,
            read = false, bookmark = false, scanlator = null, lastPage = 0,
        )

        env.insert("history", mapOf("_id" to 41L, "chapter_id" to 31L, "last_read" to T + 41, "time_read" to 123_456L))
        env.insert("history", mapOf("_id" to 42L, "chapter_id" to 34L, "last_read" to T + 42, "time_read" to 99L))
        // Read before the source dropped it: the soft delete leaves the history row in place.
        env.insert("history", mapOf("_id" to 43L, "chapter_id" to 33L, "last_read" to T + 43, "time_read" to 55L))

        track(
            env, 51, LIBRARY, syncId = 2, remoteId = 9_876_543_210L, libraryId = 555L,
            lastRead = 11.7, score = 8.6, private = true,
        )
        track(
            env, 52, READ_ELSEWHERE, syncId = 1, remoteId = 77L, libraryId = null,
            lastRead = 0.3, score = 3.3, private = false,
        )

        env.insert("excluded_scanlators", mapOf("manga_id" to LIBRARY, "scanlator" to "Group B"))
        env.insert("excluded_scanlators", mapOf("manga_id" to LIBRARY, "scanlator" to "Group C"))

        env.insert("mangas_categories", mapOf("_id" to 61L, "manga_id" to LIBRARY, "category_id" to 11L))
        env.insert("mangas_categories", mapOf("_id" to 62L, "manga_id" to LIBRARY, "category_id" to 12L))
        env.insert("mangas_categories", mapOf("_id" to 63L, "manga_id" to LIBRARY, "category_id" to 13L))
        env.insert("mangas_categories", mapOf("_id" to 64L, "manga_id" to READ_ELSEWHERE, "category_id" to 12L))

        env.insert(
            "extension_store",
            mapOf(
                "index_url" to "https://example.invalid/store-one/index.pb", "name" to "Store One",
                "badge_label" to "One", "signing_key" to "ab".repeat(32),
                "contact_website" to "https://example.invalid/store-one",
                "contact_discord" to "https://example.invalid/chat",
                "is_legacy" to 1L, "extension_list_url" to "https://example.invalid/store-one/list.pb",
            ),
        )
        env.insert(
            "extension_store",
            mapOf(
                "index_url" to "https://example.invalid/store-two/index.pb", "name" to "Store Two",
                "badge_label" to "", "signing_key" to "cd".repeat(32),
                "contact_website" to "https://example.invalid/store-two", "contact_discord" to null,
                "is_legacy" to 0L, "extension_list_url" to null,
            ),
        )

        env.preferenceStore.values.putAll(appPreferences)
        env.context.getSharedPreferences("source_$SOURCE_B", 0).edit()
            .putInt("fixture_quality", 2)
            .putString("fixture_server", "second")
            .putString("__PRIVATE_fixture_token", "secret")
            .apply()
    }

    private val appPreferences: Map<String, Any> = mapOf(
        "fixture_int" to 7,
        "fixture_long" to 7_000_000_000L,
        "fixture_float" to 1.5f,
        "fixture_string" to "text",
        "fixture_boolean" to true,
        "fixture_set" to setOf("a", "b"),
        // R19: Saikou's swipe start key is upstream's swipe end key, and the _v2 rename is undone.
        UpstreamPreferenceKeys.SWIPE_START_ACTION to "ToggleRead",
        UpstreamPreferenceKeys.FILTER_INTERVAL_CUSTOM_V2 to 3,
        "__PRIVATE_fixture_login" to "private",
        "__APP_STATE_fixture_state" to "state",
    )

    /** Upstream's createApp(includePrivatePreferences = false) over the same store, in upstream's key spelling. */
    val expectedAppPreferences = listOf(
        BackupPreference("fixture_int", IntPreferenceValue(7)),
        BackupPreference("fixture_long", LongPreferenceValue(7_000_000_000L)),
        BackupPreference("fixture_float", FloatPreferenceValue(1.5f)),
        BackupPreference("fixture_string", StringPreferenceValue("text")),
        BackupPreference("fixture_boolean", BooleanPreferenceValue(true)),
        BackupPreference("fixture_set", StringSetPreferenceValue(setOf("a", "b"))),
        BackupPreference(UpstreamPreferenceKeys.SWIPE_END_ACTION, StringPreferenceValue("ToggleRead")),
        BackupPreference(UpstreamPreferenceKeys.FILTER_INTERVAL_CUSTOM, IntPreferenceValue(3)),
    )

    val expectedSourcePreferences = listOf(
        BackupSourcePreferences(
            "source_$SOURCE_B",
            listOf(
                BackupPreference("fixture_quality", IntPreferenceValue(2)),
                BackupPreference("fixture_server", StringPreferenceValue("second")),
            ),
        ),
    )

    private fun category(
        env: BackupTestEnvironment,
        id: Long,
        name: String,
        sort: Long,
        flags: Long,
        parent: Long?,
        key: String,
        deletedAt: Long? = null,
    ) = env.insert(
        "categories",
        mapOf(
            "_id" to id, "name" to name, "sort" to sort, "flags" to flags, "parent_id" to parent,
            "version" to id, "is_syncing" to 0L, "deleted_at" to deletedAt, "modified_at" to T + id,
            "restored_at" to T + id + 1, "sync_key" to key,
        ),
    )

    private fun manga(
        env: BackupTestEnvironment,
        id: Long,
        source: Long,
        url: String,
        favorite: Boolean,
        values: MutableMap<String, Any?>.() -> Unit,
    ) = env.insert(
        "mangas",
        mutableMapOf<String, Any?>(
            "_id" to id, "source" to source, "url" to url, "artist" to null, "author" to null,
            "description" to null, "genre" to null, "title" to "Lossless $id", "status" to 0L,
            "thumbnail_url" to null, "favorite" to favorite, "last_update" to T + 1, "next_update" to T + 2,
            "initialized" to 0L, "viewer" to 0L, "chapter_flags" to 0L, "cover_last_modified" to T + 3,
            "date_added" to T - id, "update_strategy" to 0L, "calculate_interval" to 7L,
            "last_modified_at" to T + 4, "favorite_modified_at" to null, "version" to id, "is_syncing" to 0L,
            "notes" to "", "memo" to "{}".encodeToByteArray(), "manual_position" to id, "deleted_at" to null,
            "display_modified_at" to T + 5, "notes_modified_at" to T + 6, "position_modified_at" to T + 8,
            "categories_modified_at" to T + 9, "exclusions_modified_at" to T + 10, "priority_modified_at" to T + 11,
            "trackers_modified_at" to T + 12, "restored_at" to T + 14,
            // Every entry carries an edit, so a file that wrote it in place of the source's text fails.
            "title_override" to "Edited $id", "description_override" to "Edited description $id",
            "details_modified_at" to T + 15,
        ).apply(values),
    )

    private fun chapter(
        env: BackupTestEnvironment,
        id: Long,
        manga: Long,
        url: String,
        number: Double,
        read: Boolean,
        bookmark: Boolean,
        scanlator: String?,
        lastPage: Long = id - 13,
        deletedAt: Long? = null,
    ) = env.insert(
        "chapters",
        mapOf(
            "_id" to id, "manga_id" to manga, "url" to url, "name" to "Chapter $id", "scanlator" to scanlator,
            "read" to read, "bookmark" to bookmark, "last_page_read" to lastPage, "chapter_number" to number,
            "source_order" to id - 30, "date_fetch" to T + id, "date_upload" to T - id,
            "last_modified_at" to T + id * 2,
            "version" to id - 28, "is_syncing" to 0L, "memo" to """{"c":$id}""".encodeToByteArray(), "volume" to 2.5,
            "deleted_at" to deletedAt, "bookmark_notes_modified_at" to T + id * 3, "restored_at" to T + id * 4,
        ),
    )

    private fun track(
        env: BackupTestEnvironment,
        id: Long,
        manga: Long,
        syncId: Long,
        remoteId: Long,
        libraryId: Long?,
        lastRead: Double,
        score: Double,
        private: Boolean,
    ) = env.insert(
        "manga_sync",
        mapOf(
            "_id" to id, "manga_id" to manga, "sync_id" to syncId, "remote_id" to remoteId, "library_id" to libraryId,
            "title" to "Tracked $id", "last_chapter_read" to lastRead, "total_chapters" to 40L + id,
            "status" to 3L, "score" to score, "remote_url" to "https://example.invalid/track/$id",
            "start_date" to T - id, "finish_date" to T + id, "private" to private,
        ),
    )
}
