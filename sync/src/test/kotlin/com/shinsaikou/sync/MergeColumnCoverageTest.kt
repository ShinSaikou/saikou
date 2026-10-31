package com.shinsaikou.sync

import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.merge.BookmarkNoteRecord
import com.shinsaikou.sync.merge.CategoryRecord
import com.shinsaikou.sync.merge.ChapterRecord
import com.shinsaikou.sync.merge.HistoryRecord
import com.shinsaikou.sync.merge.MangaRecord
import com.shinsaikou.sync.merge.TrackRecord
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Every column of every synced table either feeds a merge record field or is excluded here by
 * name with its reason, so a column added to a `.sq` file fails this test until someone decides
 * which. Every record field is fed by a column, and every record value field is one the conflict
 * report can name or one of the clocks listed here, so a field added to a record fails too.
 *
 * The columns are read from `:data`'s `.sq` sources, the directory the build passes in. The Q6 and
 * Q7 columns unit C adds are expected either all absent (before its migration merges) or all
 * present; any other mix means the names drifted between the two units.
 */
class MergeColumnCoverageTest {

    private class Table(val name: String, val fields: Map<String, String>, val excluded: Map<String, String>)

    private val tables = listOf(
        Table(
            "mangas",
            fields = mapOf(
                "source" to "MangaKey.source", "url" to "MangaKey.url", "artist" to "MangaRecord.artist",
                "author" to "MangaRecord.author", "description" to "MangaRecord.description",
                "genre" to "MangaRecord.genre", "title" to "MangaRecord.title", "status" to "MangaRecord.status",
                "thumbnail_url" to "MangaRecord.thumbnailUrl", "favorite" to "MangaRecord.favorite",
                "viewer" to "MangaRecord.viewerFlags", "chapter_flags" to "MangaRecord.chapterFlags",
                "date_added" to "MangaRecord.dateAdded", "update_strategy" to "MangaRecord.updateStrategy",
                "favorite_modified_at" to "MangaRecord.favoriteModifiedAt", "version" to "MangaRecord.version",
                "notes" to "MangaRecord.notes", "memo" to "MangaRecord.memo",
                "manual_position" to "MangaRecord.manualPosition", "deleted_at" to "MangaRecord.deletedAt",
                "restored_at" to "MangaRecord.restoredAt",
                "display_modified_at" to "MangaRecord.displayModifiedAt",
                "notes_modified_at" to "MangaRecord.notesModifiedAt",
                "position_modified_at" to "MangaRecord.positionModifiedAt",
                "categories_modified_at" to "MangaRecord.categoriesModifiedAt",
                "exclusions_modified_at" to "MangaRecord.exclusionsModifiedAt",
                "priority_modified_at" to "MangaRecord.priorityModifiedAt",
                "trackers_modified_at" to "MangaRecord.trackersModifiedAt",
                "title_override" to "MangaRecord.titleOverride",
                "description_override" to "MangaRecord.descriptionOverride",
                "details_modified_at" to "MangaRecord.detailsModifiedAt",
            ),
            excluded = mapOf(
                "_id" to LOCAL_ID,
                "last_update" to "when this device's library update last found a chapter; each device keeps its own",
                "next_update" to "this device's next scheduled library update",
                "initialized" to "not merged: syncInsert derives it from whether any details arrived, and an existing row keeps its own",
                "cover_last_modified" to "this device's cover cache stamp",
                "calculate_interval" to "the fetch interval this device's library update computes",
                "last_modified_at" to "a stamp of any local write, which decides nothing under Q6",
                "is_syncing" to IS_SYNCING,
            ),
        ),
        Table(
            "chapters",
            fields = mapOf(
                "manga_id" to "ChapterKey.manga", "url" to "ChapterKey.url", "name" to "ChapterRecord.name",
                "scanlator" to "ChapterRecord.scanlator", "read" to "ChapterRecord.read",
                "bookmark" to "ChapterRecord.bookmark", "last_page_read" to "ChapterRecord.lastPageRead",
                "chapter_number" to "ChapterRecord.chapterNumber", "date_fetch" to "ChapterRecord.dateFetch",
                "date_upload" to "ChapterRecord.dateUpload", "version" to "ChapterRecord.version",
                "volume" to "ChapterRecord.volume", "deleted_at" to "ChapterRecord.deletedAt",
                "restored_at" to "ChapterRecord.restoredAt",
                "bookmark_notes_modified_at" to "ChapterRecord.bookmarkNotesModifiedAt",
            ),
            excluded = mapOf(
                "_id" to LOCAL_ID,
                "source_order" to "its place in this device's last fetch of the list; the next fetch rewrites it",
                "last_modified_at" to "a stamp of any local write, which decides nothing under Q6",
                "is_syncing" to IS_SYNCING,
                "memo" to "no Q6 row names it: the source's own per-chapter data, rewritten by each device's fetch",
            ),
        ),
        Table(
            "categories",
            fields = mapOf(
                "name" to "CategoryRecord.name", "sort" to "CategoryRecord.order", "flags" to "CategoryRecord.flags",
                "parent_id" to "CategoryRecord.parentKey", "deleted_at" to "CategoryRecord.deletedAt",
                "modified_at" to "CategoryRecord.modifiedAt", "restored_at" to "CategoryRecord.restoredAt",
                "sync_key" to "CategoryRecord.syncKey",
            ),
            excluded = mapOf(
                "_id" to "a local id; a category travels by its sync key (Q7)",
                "version" to "counts writes; Q6 decides every category value by modified_at instead",
                "is_syncing" to IS_SYNCING,
            ),
        ),
        Table(
            "mangas_categories",
            fields = mapOf("manga_id" to PARENT, "category_id" to "MangaRecord.categories"),
            excluded = mapOf("_id" to LOCAL_ID),
        ),
        Table(
            "history",
            fields = mapOf(
                "chapter_id" to "HistoryRecord.chapter", "last_read" to "HistoryRecord.readAt",
                "time_read" to "HistoryRecord.readDuration",
            ),
            excluded = mapOf("_id" to LOCAL_ID),
        ),
        Table(
            "manga_sync",
            fields = mapOf(
                "manga_id" to PARENT, "sync_id" to "MangaRecord.tracks", "remote_id" to "TrackRecord.remoteId",
                "library_id" to "TrackRecord.libraryId", "title" to "TrackRecord.title",
                "last_chapter_read" to "TrackRecord.lastChapterRead", "total_chapters" to "TrackRecord.totalChapters",
                "status" to "TrackRecord.status", "score" to "TrackRecord.score",
                "remote_url" to "TrackRecord.remoteUrl",
                "start_date" to "TrackRecord.startDate", "finish_date" to "TrackRecord.finishDate",
                "private" to "TrackRecord.private",
            ),
            excluded = mapOf("_id" to LOCAL_ID),
        ),
        Table(
            "excluded_scanlators",
            fields = mapOf("manga_id" to PARENT, "scanlator" to "MangaRecord.excludedScanlators"),
            excluded = emptyMap(),
        ),
        Table(
            "scanlator_priority",
            fields = mapOf(
                "manga_id" to PARENT, "scanlator" to "MangaRecord.scanlatorPriority",
                "priority" to "MangaRecord.scanlatorPriority",
            ),
            excluded = mapOf("_id" to LOCAL_ID),
        ),
        Table(
            "bookmark_notes",
            fields = mapOf(
                "chapter_id" to PARENT, "page_index" to "ChapterRecord.bookmarkNotes",
                "note" to "BookmarkNoteRecord.note", "date_added" to "BookmarkNoteRecord.dateAdded",
            ),
            excluded = mapOf("_id" to LOCAL_ID),
        ),
    )

    /** Unit C's columns (Q6's clocks and Q7's key), by table. */
    private val addedByUnitC = mapOf(
        "mangas" to setOf(
            "display_modified_at", "notes_modified_at", "position_modified_at", "categories_modified_at",
            "exclusions_modified_at", "priority_modified_at", "trackers_modified_at", "restored_at",
        ),
        "chapters" to setOf("restored_at", "bookmark_notes_modified_at"),
        "categories" to setOf("modified_at", "restored_at", "sync_key"),
    )

    /** Record fields no column feeds directly: the keys, whose own fields the columns feed. */
    private val structural = setOf("MangaRecord.key", "ChapterRecord.key", "ChapterKey.manga")

    private val clocks = mapOf(
        MangaRecord::class.java to setOf(
            "favoriteModifiedAt", "restoredAt", "version", "displayModifiedAt", "notesModifiedAt", "positionModifiedAt",
            "categoriesModifiedAt", "exclusionsModifiedAt", "priorityModifiedAt", "trackersModifiedAt",
            "detailsModifiedAt",
        ),
        ChapterRecord::class.java to setOf("version", "restoredAt", "bookmarkNotesModifiedAt"),
        CategoryRecord::class.java to setOf("modifiedAt", "restoredAt"),
    )

    @Test
    fun `every column of every synced table is merged or excluded by name`() {
        val schema = SqlSchema.read()
        for (table in tables) {
            val columns = schema.getValue(table.name)
            withClue("table ${table.name}") {
                (table.fields.keys intersect table.excluded.keys).shouldBeEmpty()
                (columns - table.fields.keys - table.excluded.keys).shouldBeEmpty()
                (table.excluded.keys - columns).shouldBeEmpty()
                val missing = table.fields.keys - columns
                val expected = addedByUnitC[table.name].orEmpty()
                (missing.isEmpty() || missing == expected) shouldBe true
            }
        }
    }

    @Test
    fun `every record field is fed by a column`() {
        val fed = tables.flatMap { it.fields.values }.toSet() - PARENT
        val records = listOf(
            MangaKey::class.java, ChapterKey::class.java, MangaRecord::class.java, TrackRecord::class.java,
            ChapterRecord::class.java, BookmarkNoteRecord::class.java, CategoryRecord::class.java,
            HistoryRecord::class.java,
        )
        val declared = records.flatMap { type -> fieldsOf(type).map { "${type.simpleName}.$it" } }.toSet()

        (declared - fed - structural).shouldBeEmpty()
        (fed + structural - declared).shouldBeEmpty()
    }

    @Test
    fun `every record value field is reported or is a clock`() {
        val reported = mapOf(
            MangaRecord::class.java to MergeEngine.MANGA_VALUES.map { it.name },
            ChapterRecord::class.java to MergeEngine.CHAPTER_VALUES.map { it.name },
            CategoryRecord::class.java to MergeEngine.CATEGORY_VALUES.map { it.name },
            HistoryRecord::class.java to MergeEngine.HISTORY_VALUES.map { it.name },
            TrackRecord::class.java to MergeEngine.TRACK_VALUES.map { it.name },
        )
        val keys = setOf("key", "syncKey", "chapter")
        for ((type, names) in reported) {
            val fields = fieldsOf(type).toSet()
            withClue(type.simpleName) {
                (names.toSet() intersect clocks[type].orEmpty()).shouldBeEmpty()
                names.toSet() + clocks[type].orEmpty() + (fields intersect keys) shouldBe fields
            }
        }
    }

    private fun fieldsOf(type: Class<*>): List<String> =
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }

    private companion object {
        const val LOCAL_ID = "a local id; a row travels by its natural key (Q5)"
        const val IS_SYNCING = "the local latch that silences the triggers while a restore or a sync writes"
        const val PARENT = "(the parent row, whose record holds this collection)"
    }
}

/** The column names of each `CREATE TABLE` in `:data`'s `.sq` sources. */
internal object SqlSchema {
    private val createTable = Regex("""CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s*\(""", RegexOption.IGNORE_CASE)
    private val constraints = setOf("UNIQUE", "FOREIGN", "PRIMARY", "CHECK", "CONSTRAINT")

    fun read(): Map<String, Set<String>> {
        val dir = System.getProperty("saikou.sqldelight.dir")
            ?: error("saikou.sqldelight.dir is not set; sync/build.gradle.kts passes it to the test task")
        val root = File(dir)
        require(root.isDirectory) { "No .sq directory at $root" }
        return root.walkTopDown().filter { it.extension == "sq" }.flatMap { parse(it.readText()).entries }
            .associate { it.key to it.value }
    }

    fun parse(sql: String): Map<String, Set<String>> {
        val text = sql.lines().joinToString("\n") { it.substringBefore("--") }
        return createTable.findAll(text).associate { match ->
            val body = bodyAfter(text, match.range.last + 1)
            match.groupValues[1] to splitTopLevel(body)
                .map { it.trim().split(Regex("""\s+"""), limit = 2).first() }
                .filter { it.isNotEmpty() && it.uppercase() !in constraints }
                .toSet()
        }
    }

    private fun bodyAfter(text: String, start: Int): String {
        var depth = 1
        var i = start
        while (depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(start, i - 1)
    }

    private fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var from = 0
        body.forEachIndexed { i, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    parts += body.substring(from, i)
                    from = i + 1
                }
            }
        }
        parts += body.substring(from)
        return parts
    }
}
