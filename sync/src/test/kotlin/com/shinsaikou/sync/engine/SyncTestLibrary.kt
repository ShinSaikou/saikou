package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.format.SegmentReader
import com.shinsaikou.sync.format.SyncHeader
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.format.SyncRow
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/*
 * What the engine's tests build and compare on a [SyncTestDevice]: a library with a row in every
 * synced table, and every synced value of every row by natural key, so two devices' libraries
 * compare as lists.
 */

private val mangaColumns = listOf(
    "source", "url", "title", "author", "artist", "description", "genre", "status", "thumbnail_url",
    "favorite", "date_added", "favorite_modified_at", "deleted_at", "restored_at", "version",
    "update_strategy", "CAST(memo AS TEXT)", "viewer", "chapter_flags", "display_modified_at", "notes",
    "notes_modified_at", "manual_position", "position_modified_at", "categories_modified_at",
    "exclusions_modified_at", "priority_modified_at", "trackers_modified_at", "title_override",
    "description_override", "details_modified_at",
)

private val chapterColumns = listOf(
    "M.source", "M.url", "C.url", "C.name", "C.scanlator", "C.read", "C.bookmark", "C.last_page_read",
    "C.chapter_number", "C.source_order", "C.date_fetch", "C.date_upload", "C.version", "C.volume",
    "C.deleted_at", "C.restored_at", "C.bookmark_notes_modified_at", "C.read_modified_at", "C.bookmark_modified_at",
)

/** Every synced value of every row, by natural key, so two devices' libraries compare as lists. */
internal fun SyncTestDevice.library(): Map<String, List<List<String?>>> = mapOf(
    "mangas" to rows("SELECT ${mangaColumns.joinToString()} FROM mangas ORDER BY source, url", mangaColumns.size),
    "chapters" to rows(
        "SELECT ${chapterColumns.joinToString()} FROM chapters C JOIN mangas M ON M._id = C.manga_id ORDER BY M.url, C.url",
        chapterColumns.size,
    ),
    "categories" to rows(
        """
        SELECT C.sync_key, C.name, C.sort, C.flags, P.sync_key, C.modified_at, C.deleted_at, C.restored_at
        FROM categories C LEFT JOIN categories P ON P._id = C.parent_id WHERE C._id > 0 ORDER BY C.sync_key
        """,
        8,
    ),
    "memberships" to rows(
        "SELECT M.url, C.sync_key FROM mangas_categories MC JOIN mangas M ON M._id = MC.manga_id " +
            "JOIN categories C ON C._id = MC.category_id ORDER BY M.url, C.sync_key",
        2,
    ),
    "exclusions" to rows(
        "SELECT M.url, E.scanlator FROM excluded_scanlators E JOIN mangas M ON M._id = E.manga_id ORDER BY 1, 2",
        2,
    ),
    "priorities" to rows(
        "SELECT M.url, P.scanlator, P.priority FROM scanlator_priority P JOIN mangas M ON M._id = P.manga_id ORDER BY 1, 2",
        3,
    ),
    "tracks" to rows(
        """
        SELECT M.url, T.sync_id, T.remote_id, T.library_id, T.title, T.last_chapter_read, T.total_chapters,
            T.status, T.score, T.remote_url, T.start_date, T.finish_date, T.private
        FROM manga_sync T JOIN mangas M ON M._id = T.manga_id ORDER BY 1, 2
        """,
        13,
    ),
    "history" to rows(
        "SELECT C.url, H.last_read, H.time_read FROM history H JOIN chapters C ON C._id = H.chapter_id ORDER BY 1",
        3,
    ),
    "notes" to rows(
        "SELECT C.url, N.page_index, N.note, N.date_added FROM bookmark_notes N JOIN chapters C ON C._id = N.chapter_id ORDER BY 1, 2",
        4,
    ),
    "merged groups" to rows("SELECT main_source, main_url, show_all, modified_at FROM merged_groups ORDER BY 1, 2", 4),
    "merged members" to rows(
        "SELECT G.main_url, M.member_source, M.member_url, M.position, M.check_updates " +
            "FROM merged_members M JOIN merged_groups G ON G._id = M.group_id ORDER BY 1, 2, 3",
        5,
    ),
)

internal fun SyncTestDevice.latchesRaised(): Long = long(
    """
    SELECT (SELECT count(*) FROM mangas WHERE is_syncing = 1) + (SELECT count(*) FROM chapters WHERE is_syncing = 1) +
        (SELECT count(*) FROM categories WHERE is_syncing = 1) + (SELECT count(*) FROM merged_groups WHERE is_syncing = 1)
    """,
)!!

/** A favourite with a row in every synced table and an owner value on every clock A can move. */
internal fun SyncTestDevice.buildLibrary() {
    addFavourite(1)
    addChapter(10, mangaId = 1)
    addChapter(11, mangaId = 1)
    exec("UPDATE chapters SET read = 1, last_page_read = 5, bookmark = 1 WHERE _id = 10")
    addCategory(1, "Parent")
    addCategory(2, "Reading", parentId = 1)
    exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 2)")
    exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Group A')")
    exec("INSERT INTO scanlator_priority(manga_id, scanlator, priority) VALUES (1, 'Group B', 1)")
    exec(
        """
        INSERT INTO manga_sync(manga_id, sync_id, remote_id, library_id, title, last_chapter_read,
            total_chapters, status, score, remote_url, start_date, finish_date, private)
        VALUES (1, 2, 20, NULL, 'Remote', 1.0, 10, 1, 7.5, '/remote/20', 0, 0, 0)
        """,
    )
    exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, 7000, 60)")
    exec("INSERT INTO bookmark_notes(chapter_id, page_index, note, date_added) VALUES (10, 3, 'note', 8000)")
    exec("UPDATE mangas SET notes = 'owner notes', viewer = 2, manual_position = 4 WHERE _id = 1")
    exec("UPDATE mangas SET title_override = 'Edited title', description_override = 'Edited text' WHERE _id = 1")
    exec("INSERT INTO merged_groups(main_source, main_url, modified_at) VALUES (${SyncTestDevice.SOURCE}, '/series/1', 9000)")
    exec(
        """
        INSERT INTO merged_members(member_source, member_url, group_id, position)
        VALUES (${SyncTestDevice.SOURCE}, '/series/1', 1, 0)
        """,
    )
}

/**
 * A library of [entries] favourites, `/series/<n>` with local id n, each with [chapters] chapters
 * (local id `n * 1000 + c`), the first two read with history and the third half read, in one of two
 * categories. [description] is repeated into every entry's description, to give rows a real size.
 * Written in a few statements, through every trigger.
 */
internal fun SyncTestDevice.bulkLibrary(entries: Int, chapters: Int, description: String = "") {
    addCategory(1, "Reading")
    addCategory(2, "Done")
    exec(
        """
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $entries)
        INSERT INTO mangas(_id, source, url, title, description, status, favorite, initialized, viewer, chapter_flags,
            cover_last_modified, date_added, memo)
        SELECT i, ${SyncTestDevice.SOURCE}, '/series/' || i, 'Title ' || i, '$description' || i, 0, 1, 0, 0, 0, 0,
            1000 + i, CAST('{}' AS BLOB)
        FROM n
        """,
    )
    exec(
        """
        WITH RECURSIVE c(j) AS (SELECT 1 UNION ALL SELECT j + 1 FROM c WHERE j < $chapters)
        INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, chapter_number,
            source_order, date_fetch, date_upload, memo)
        SELECT M._id * 1000 + j, M._id, M.url || '/chapter/' || j, 'Chapter ' || j, 'Group A', j <= 2, j = 1,
            CASE WHEN j = 3 THEN 7 ELSE 0 END, j, $chapters - j, 2000, 3000, CAST('{}' AS BLOB)
        FROM mangas M, c
        """,
    )
    exec("INSERT INTO history(chapter_id, last_read, time_read) SELECT _id, 5000 + _id, 60000 FROM chapters WHERE read = 1")
    exec("INSERT INTO mangas_categories(manga_id, category_id) SELECT _id, 1 + _id % 2 FROM mangas")
}

/** Every path the backend lists under the layout's root, sorted. */
internal fun InMemorySyncBackend.paths(): List<String> =
    runBlocking { list(SyncLayout.ROOT_PREFIX) }.map { it.path }.sorted()

/** The header and rows of the row file at [path]. */
internal fun InMemorySyncBackend.rowFile(path: String): Pair<SyncHeader, List<SyncRow>> {
    val bytes = bytes(path) ?: error("No file at $path")
    return SegmentReader(ByteArrayInputStream(bytes)).use { reader ->
        reader.header to generateSequence { reader.next() }.toList()
    }
}

/** A gzip JSON Lines file of the given lines as they are, as a hand-made or foreign file would be. */
internal fun rawFile(vararg lines: String): ByteArray {
    val bytes = ByteArrayOutputStream()
    GZIPOutputStream(bytes).bufferedWriter().use { writer ->
        lines.forEach {
            writer.write(it)
            writer.write("\n")
        }
    }
    return bytes.toByteArray()
}
