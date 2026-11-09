package com.shinsaikou.data.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.data.backfillCategorySyncKeys
import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.model.StoredChapter
import com.shinsaikou.domain.sync.model.StoredHistory
import com.shinsaikou.domain.sync.model.StoredManga
import com.shinsaikou.domain.sync.model.StoredMergedGroup
import com.shinsaikou.domain.sync.model.StoredMergedMember
import com.shinsaikou.domain.sync.model.StoredNote
import com.shinsaikou.domain.sync.model.StoredTrack
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.domain.sync.model.SyncPushRequest
import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonObject
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.History
import tachiyomi.data.Manga_sync
import tachiyomi.data.Mangas
import tachiyomi.data.Merged_groups
import tachiyomi.data.Merged_members
import tachiyomi.data.SyncGetAll
import java.util.Date

/**
 * The sync engine's reads and writes over the generated queries (phase 3 unit E2). Rows are read
 * into the generated types and mapped by column name, never by position, because a manga row has
 * thirty-six columns.
 *
 * The change log's counter is the one thing read and written through [driver] directly: it lives in
 * SQLite's own `sqlite_sequence` table, which SQLDelight's compiler does not know ("No table found
 * with name sqlite_sequence"), so no `.sq` statement can name it.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncStoreImpl(
    private val handler: DatabaseHandler,
    private val driver: SqlDriver,
) : SyncStore {

    override suspend fun readForPush(request: SyncPushRequest): SyncPushRows = handler.await(inTransaction = true) {
        val chapters = mutableMapOf<Long, StoredChapter>()
        val chapterIds = request.chapterIds + request.noteChapterIds + request.historyChapterIds
        chapterIds.forEach { id ->
            chaptersQueries.getChapterById(id).awaitAsOneOrNull()?.let { chapters[id] = it.toStored() }
        }
        val notes = request.noteChapterIds.filter { it in chapters }.associateWith { notesOf(it) }
        val history = request.historyChapterIds.mapNotNull { id ->
            historyQueries.syncGetByChapterId(id).awaitAsOneOrNull()?.toStored()
        }.associateBy { it.chapterId }

        val mangas = mutableMapOf<Long, StoredManga>()
        val mangaIds = request.mangaIds + request.categoryMembershipMangaIds + request.exclusionMangaIds +
            request.priorityMangaIds + request.trackMangaIds + chapters.values.map { it.mangaId }
        mangaIds.forEach { id ->
            mangasQueries.getMangaById(id).awaitAsOneOrNull()?.let { mangas[id] = it.toStored() }
        }
        request.removedChapters.forEach { key ->
            mangasQueries.getMangaByUrlAndSource(key.mangaUrl, key.source).awaitAsOneOrNull()
                ?.let { mangas[it._id] = it.toStored() }
        }

        SyncPushRows(
            mangas = mangas,
            categoryKeys = request.categoryMembershipMangaIds.filter { it in mangas }.associateWith { categoryKeysOf(it) },
            excludedScanlators = request.exclusionMangaIds.filter { it in mangas }.associateWith { excludedOf(it) },
            scanlatorPriority = request.priorityMangaIds.filter { it in mangas }.associateWith { priorityOf(it) },
            tracks = request.trackMangaIds.filter { it in mangas }.associateWith { tracksOf(it) },
            chapters = chapters,
            notes = notes,
            history = history,
            categories = categoriesQueries.syncGetAll().awaitAsList().mapNotNull { it.toStored() }.associateBy { it.id },
            mergedGroups = request.mergedGroupIds.mapNotNull { id ->
                merged_groupsQueries.getGroupById(id).awaitAsOneOrNull()?.let { it.toStored(membersOf(it._id)) }
            }.associateBy { it.id },
        )
    }

    override suspend fun chapterMangaIds(chapterIds: Collection<Long>): Map<Long, Long> =
        handler.await(inTransaction = true) {
            // Bound parameters per statement are capped (32766 in the bundled SQLite).
            chapterIds.chunked(CHAPTER_ID_BATCH).flatMap { ids ->
                chaptersQueries.syncGetMangaIdsByIds(ids).awaitAsList()
            }.associate { it._id to it.manga_id }
        }

    override suspend fun libraryMangaIds(afterId: Long, limit: Long): List<Long> {
        val ids = mutableListOf<Long>()
        var after = afterId
        while (ids.size < limit) {
            val page = handler.awaitList { mangasQueries.syncGetPage(after, LIBRARY_PAGE) }
            page.filter { it.favorite || it.deleted_at != null || it.favorite_modified_at != null }
                .take((limit - ids.size).toInt())
                .mapTo(ids) { it._id }
            if (page.size < LIBRARY_PAGE) break
            after = page.last()._id
        }
        return ids
    }

    override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows = handler.await(inTransaction = true) {
        val mangas = mangaIds.mapNotNull { id -> mangasQueries.getMangaById(id).awaitAsOneOrNull()?.toStored() }
            .associateBy { it.id }
        val chapters = mutableMapOf<Long, StoredChapter>()
        val notes = mutableMapOf<Long, List<StoredNote>>()
        val history = mutableMapOf<Long, StoredHistory>()
        mangas.keys.forEach { mangaId ->
            chaptersQueries.syncGetByMangaId(mangaId).awaitAsList().forEach { chapters[it._id] = it.toStored() }
            bookmark_notesQueries.getByMangaId(mangaId).awaitAsList().groupBy { it.chapter_id }.forEach { (chapterId, rows) ->
                notes[chapterId] = rows.map { StoredNote(pageIndex = it.page_index, note = it.note, dateAdded = it.date_added) }
            }
            historyQueries.getHistoryByMangaId(mangaId).awaitAsList().forEach { history[it.chapter_id] = it.toStored() }
        }
        SyncPushRows(
            mangas = mangas,
            categoryKeys = mangas.keys.associateWith { categoryKeysOf(it) },
            excludedScanlators = mangas.keys.associateWith { excludedOf(it) },
            scanlatorPriority = mangas.keys.associateWith { priorityOf(it) },
            tracks = mangas.keys.associateWith { tracksOf(it) },
            chapters = chapters,
            notes = notes,
            history = history,
            categories = categoriesQueries.syncGetAll().awaitAsList().mapNotNull { it.toStored() }.associateBy { it.id },
        )
    }

    override suspend fun mergedGroups(): List<StoredMergedGroup> = handler.await(inTransaction = true) {
        val members = merged_groupsQueries.getAllMembers().awaitAsList().groupBy { it.group_id }
        merged_groupsQueries.getAllGroups().awaitAsList().map { group ->
            group.toStored(members[group._id].orEmpty().map { it.toStored() })
        }
    }

    override suspend fun beginExport(pushedSeq: Long): Long = handler.await(inTransaction = true) {
        // The counter's row appears with the log's first insert.
        val counter = driver.executeQuery(
            identifier = null,
            sql = "SELECT seq FROM sqlite_sequence WHERE name = 'sync_change_log'",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            parameters = 0,
        ).await()
        val highest = maxOf(counter ?: 0L, sync_change_logQueries.getLatestSeq().awaitAsOne())
        // Nothing logged since the last push: an export at that push's seq would read as already
        // applied to every device that has applied the push, so it ends one past it.
        val lastSeq = if (highest > pushedSeq) highest else pushedSeq + 1
        if (lastSeq > (counter ?: 0L)) {
            val sql = if (counter == null) {
                "INSERT INTO sqlite_sequence(name, seq) VALUES ('sync_change_log', ?)"
            } else {
                "UPDATE sqlite_sequence SET seq = ? WHERE name = 'sync_change_log'"
            }
            driver.execute(identifier = null, sql = sql, parameters = 1) { bindLong(0, lastSeq) }.await()
        }
        sync_change_logQueries.pruneUpTo(lastSeq)
        lastSeq
    }

    override suspend fun libraryCounts(): SyncLibraryCounts = handler.awaitOne {
        mangasQueries.syncCountLibrary(::SyncLibraryCounts)
    }

    override suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T = handler.await(inTransaction = true) {
        backfillCategorySyncKeys()
        val scope = ApplyScope(this)
        val result = scope.block()
        scope.resolveCategoryParents()
        scope.lowerLatches()
        result
    }

    /**
     * One apply's reads and writes. Each write raises the latch of the row whose triggers it fires
     * and remembers it; [lowerLatches] lowers every one before the transaction commits. The manga's
     * latch covers its history, tracks and collections, the chapter's its notes (R85 item 1), and a
     * merged group's its members.
     */
    private class ApplyScope(private val db: Database) : SyncApplyScope {

        private val raisedMangas = mutableSetOf<Long>()
        private val raisedChapters = mutableSetOf<Long>()
        private val raisedCategories = mutableSetOf<Long>()
        private val raisedGroups = mutableSetOf<Long>()

        /** Every category this apply wrote, by id, with the parent key its line carries. */
        private val categoryParentKeys = linkedMapOf<Long, String?>()

        /** The categories this apply turned from live to deleted. */
        private val deletedCategories = mutableSetOf<Long>()

        override suspend fun manga(source: Long, url: String): StoredManga? =
            db.mangasQueries.getMangaByUrlAndSource(url, source).awaitAsOneOrNull()?.toStored()

        override suspend fun chapter(mangaId: Long, url: String): StoredChapter? =
            db.chaptersQueries.getChapterByUrlAndMangaId(url, mangaId).awaitAsOneOrNull()?.toStored()

        override suspend fun category(syncKey: String): StoredCategory? =
            db.categoriesQueries.syncGetBySyncKey(syncKey).awaitAsOneOrNull()?.let {
                StoredCategory(
                    id = it._id,
                    syncKey = it.sync_key!!,
                    name = it.name,
                    order = it.sort,
                    flags = it.flags,
                    parentKey = it.parent_key,
                    modifiedAt = it.modified_at,
                    deletedAt = it.deleted_at,
                    restoredAt = it.restored_at,
                )
            }

        override suspend fun categoryKeys(mangaId: Long): Set<String> = db.categoryKeysOf(mangaId)

        override suspend fun excludedScanlators(mangaId: Long): Set<String> = db.excludedOf(mangaId)

        override suspend fun scanlatorPriority(mangaId: Long): Map<String, Long> = db.priorityOf(mangaId)

        override suspend fun tracks(mangaId: Long): List<StoredTrack> = db.tracksOf(mangaId)

        override suspend fun notes(chapterId: Long): List<StoredNote> = db.notesOf(chapterId)

        override suspend fun history(chapterId: Long): StoredHistory? =
            db.historyQueries.syncGetByChapterId(chapterId).awaitAsOneOrNull()?.toStored()

        override suspend fun mergedGroup(mainSource: Long, mainUrl: String): StoredMergedGroup? =
            db.merged_groupsQueries.getGroupByMain(mainSource, mainUrl).awaitAsOneOrNull()
                ?.let { it.toStored(db.membersOf(it._id)) }

        override suspend fun mergedGroupOfMember(source: Long, url: String): StoredMergedGroup? =
            db.merged_groupsQueries.getMember(source, url).awaitAsOneOrNull()?.let { member ->
                db.merged_groupsQueries.getGroupById(member.group_id).awaitAsOne().toStored(db.membersOf(member.group_id))
            }

        override suspend fun insertManga(manga: StoredManga): Long {
            db.mangasQueries.syncInsert(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre,
                title = manga.title,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                favorite = manga.favorite,
                // Fetched on the other device when any of its details came with it (mangas.sq).
                initialized = listOf(manga.description, manga.author, manga.artist, manga.genre).any { it != null },
                viewer = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                dateAdded = manga.dateAdded,
                updateStrategy = manga.updateStrategy,
                version = manga.version,
                notes = manga.notes,
                memo = manga.memo,
                manualPosition = manga.manualPosition,
                deletedAt = manga.deletedAt,
                favoriteModifiedAt = manga.favoriteModifiedAt,
                restoredAt = manga.restoredAt,
                displayModifiedAt = manga.displayModifiedAt,
                notesModifiedAt = manga.notesModifiedAt,
                positionModifiedAt = manga.positionModifiedAt,
                titleOverride = manga.titleOverride,
                descriptionOverride = manga.descriptionOverride,
                authorOverride = manga.authorOverride,
                artistOverride = manga.artistOverride,
                statusOverride = manga.statusOverride,
                genreOverride = manga.genreOverride,
                detailsModifiedAt = manga.detailsModifiedAt,
                categoriesModifiedAt = manga.categoriesModifiedAt,
                exclusionsModifiedAt = manga.exclusionsModifiedAt,
                priorityModifiedAt = manga.priorityModifiedAt,
                trackersModifiedAt = manga.trackersModifiedAt,
            )
            // Inserted with the latch raised, so the insert trigger stayed quiet.
            return db.mangasQueries.selectLastInsertedRowId().awaitAsOne().also { raisedMangas += it }
        }

        override suspend fun updateManga(manga: StoredManga) {
            raiseManga(manga.id)
            db.mangasQueries.syncUpdate(
                favorite = manga.favorite,
                dateAdded = manga.dateAdded,
                deletedAt = manga.deletedAt,
                restoredAt = manga.restoredAt,
                version = manga.version,
                title = manga.title,
                author = manga.author,
                artist = manga.artist,
                description = manga.description,
                genre = manga.genre,
                status = manga.status,
                thumbnailUrl = manga.thumbnailUrl,
                updateStrategy = manga.updateStrategy,
                memo = manga.memo,
                viewer = manga.viewerFlags,
                chapterFlags = manga.chapterFlags,
                displayModifiedAt = manga.displayModifiedAt,
                notes = manga.notes,
                notesModifiedAt = manga.notesModifiedAt,
                manualPosition = manga.manualPosition,
                positionModifiedAt = manga.positionModifiedAt,
                titleOverride = manga.titleOverride,
                descriptionOverride = manga.descriptionOverride,
                authorOverride = manga.authorOverride,
                artistOverride = manga.artistOverride,
                statusOverride = manga.statusOverride,
                genreOverride = manga.genreOverride,
                detailsModifiedAt = manga.detailsModifiedAt,
                categoriesModifiedAt = manga.categoriesModifiedAt,
                exclusionsModifiedAt = manga.exclusionsModifiedAt,
                priorityModifiedAt = manga.priorityModifiedAt,
                trackersModifiedAt = manga.trackersModifiedAt,
                mangaId = manga.id,
            )
            // After the favourite, whose unguarded trigger stamps the write's own time.
            db.mangasQueries.syncSetFavoriteModifiedAt(favoriteModifiedAt = manga.favoriteModifiedAt, mangaId = manga.id)
        }

        override suspend fun insertChapter(chapter: StoredChapter): Long {
            db.chaptersQueries.syncInsert(
                mangaId = chapter.mangaId,
                url = chapter.url,
                name = chapter.name,
                scanlator = chapter.scanlator,
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastPageRead = chapter.lastPageRead,
                chapterNumber = chapter.chapterNumber,
                sourceOrder = chapter.sourceOrder,
                dateFetch = chapter.dateFetch,
                dateUpload = chapter.dateUpload,
                version = chapter.version,
                memo = JsonObject(emptyMap()),
                volume = chapter.volume,
                deletedAt = chapter.deletedAt,
                restoredAt = chapter.restoredAt,
                bookmarkNotesModifiedAt = chapter.bookmarkNotesModifiedAt,
                readModifiedAt = chapter.readModifiedAt,
                bookmarkModifiedAt = chapter.bookmarkModifiedAt,
            )
            return db.chaptersQueries.selectLastInsertedRowId().awaitAsOne().also { raisedChapters += it }
        }

        override suspend fun updateChapter(chapter: StoredChapter) {
            raiseChapter(chapter.id)
            db.chaptersQueries.syncUpdate(
                read = chapter.read,
                bookmark = chapter.bookmark,
                lastPageRead = chapter.lastPageRead,
                dateFetch = chapter.dateFetch,
                version = chapter.version,
                name = chapter.name,
                scanlator = chapter.scanlator,
                chapterNumber = chapter.chapterNumber,
                dateUpload = chapter.dateUpload,
                volume = chapter.volume,
                deletedAt = chapter.deletedAt,
                restoredAt = chapter.restoredAt,
                bookmarkNotesModifiedAt = chapter.bookmarkNotesModifiedAt,
                readModifiedAt = chapter.readModifiedAt,
                bookmarkModifiedAt = chapter.bookmarkModifiedAt,
                chapterId = chapter.id,
            )
        }

        override suspend fun chapterSourceOrders(mangaId: Long): Map<Long, Long> =
            db.chaptersQueries.syncGetSourceOrders(mangaId).awaitAsList().associate { it._id to it.source_order }

        // No latch: none of the chapter triggers that log or bump a version reads source_order.
        override suspend fun setSourceOrders(orders: Map<Long, Long>) {
            orders.forEach { (chapterId, order) -> db.chaptersQueries.syncSetSourceOrder(order, chapterId) }
        }

        override suspend fun insertCategory(category: StoredCategory): Long {
            db.categoriesQueries.syncInsert(
                name = category.name,
                sort = category.order,
                flags = category.flags,
                parentId = parentIdOf(category.parentKey),
                deletedAt = category.deletedAt,
                modifiedAt = category.modifiedAt,
                restoredAt = category.restoredAt,
                syncKey = category.syncKey,
            )
            val id = db.categoriesQueries.selectLastInsertedRowId().awaitAsOne().also { raisedCategories += it }
            categoryParentKeys[id] = category.parentKey
            return id
        }

        override suspend fun updateCategory(category: StoredCategory) {
            raiseCategory(category.id)
            val wasLive = db.categoriesQueries.getCategory(category.id).awaitAsOneOrNull()?.deleted_at == null
            if (wasLive && category.deletedAt != null) deletedCategories += category.id
            categoryParentKeys[category.id] = category.parentKey
            db.categoriesQueries.syncUpdate(
                name = category.name,
                sort = category.order,
                flags = category.flags,
                parentId = parentIdOf(category.parentKey),
                modifiedAt = category.modifiedAt,
                deletedAt = category.deletedAt,
                restoredAt = category.restoredAt,
                categoryId = category.id,
            )
        }

        override suspend fun dropMemberships(categoryId: Long) {
            db.mangas_categoriesQueries.getMangaIdsByCategoryId(categoryId).awaitAsList().forEach { raiseManga(it) }
            db.mangas_categoriesQueries.deleteByCategoryId(categoryId)
        }

        override suspend fun setCategoryKeys(mangaId: Long, keys: Set<String>, modifiedAt: Long?) {
            raiseManga(mangaId)
            val current = db.mangas_categoriesQueries.syncGetCategoriesByMangaId(mangaId).awaitAsList()
                .associate { it._id to it.sync_key }
            val wanted = keys.mapNotNull { key ->
                db.categoriesQueries.syncGetBySyncKey(key).awaitAsOneOrNull()?.takeIf { it.deleted_at == null }?._id
            }.toSet()
            (current.keys - wanted).forEach { db.mangas_categoriesQueries.syncDelete(mangaId = mangaId, categoryId = it) }
            (wanted - current.keys).forEach { db.mangas_categoriesQueries.insert(mangaId = mangaId, categoryId = it) }
            db.mangasQueries.syncSetCategoriesModifiedAt(modifiedAt = modifiedAt, mangaId = mangaId)
        }

        override suspend fun setExcludedScanlators(mangaId: Long, scanlators: Set<String>, modifiedAt: Long?) {
            raiseManga(mangaId)
            val current = db.excludedOf(mangaId)
            val removed = current - scanlators
            if (removed.isNotEmpty()) db.excluded_scanlatorsQueries.remove(mangaId, removed)
            (scanlators - current).forEach { db.excluded_scanlatorsQueries.insert(mangaId, it) }
            db.mangasQueries.syncSetExclusionsModifiedAt(modifiedAt = modifiedAt, mangaId = mangaId)
        }

        override suspend fun setScanlatorPriority(mangaId: Long, priorities: Map<String, Long>, modifiedAt: Long?) {
            raiseManga(mangaId)
            db.scanlator_priorityQueries.deleteByMangaId(mangaId)
            priorities.forEach { (scanlator, priority) -> db.scanlator_priorityQueries.insert(mangaId, scanlator, priority) }
            db.mangasQueries.syncSetPriorityModifiedAt(modifiedAt = modifiedAt, mangaId = mangaId)
        }

        override suspend fun setTracks(mangaId: Long, tracks: List<StoredTrack>, modifiedAt: Long?) {
            raiseManga(mangaId)
            val current = db.tracksOf(mangaId).associateBy { it.trackerId }
            val wanted = tracks.associateBy { it.trackerId }
            (current.keys - wanted.keys).forEach { db.manga_syncQueries.delete(mangaId = mangaId, syncId = it) }
            // An insert of a bound pair replaces the row; one whose fields already match is left alone.
            wanted.values.filter { current[it.trackerId] != it }.forEach { track ->
                db.manga_syncQueries.insert(
                    mangaId = mangaId,
                    syncId = track.trackerId,
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    title = track.title,
                    lastChapterRead = track.lastChapterRead,
                    totalChapters = track.totalChapters,
                    status = track.status,
                    score = track.score,
                    remoteUrl = track.remoteUrl,
                    startDate = track.startDate,
                    finishDate = track.finishDate,
                    `private` = track.private,
                )
            }
            db.mangasQueries.syncSetTrackersModifiedAt(modifiedAt = modifiedAt, mangaId = mangaId)
        }

        override suspend fun setNotes(chapterId: Long, notes: List<StoredNote>, modifiedAt: Long?) {
            raiseChapter(chapterId)
            val current = db.notesOf(chapterId).associateBy { it.pageIndex }
            val wanted = notes.associateBy { it.pageIndex }
            (current.keys - wanted.keys).forEach { db.bookmark_notesQueries.delete(chapterId, it) }
            wanted.values.filter { current[it.pageIndex] != it }.forEach { note ->
                db.bookmark_notesQueries.syncUpsert(
                    chapterId = chapterId,
                    pageIndex = note.pageIndex,
                    note = note.note,
                    dateAdded = note.dateAdded,
                )
            }
            db.chaptersQueries.syncSetNotesModifiedAt(modifiedAt = modifiedAt, chapterId = chapterId)
        }

        override suspend fun setHistory(history: StoredHistory) {
            // The history triggers read the latch of the chapter's manga.
            val chapter = db.chaptersQueries.getChapterById(history.chapterId).awaitAsOne()
            raiseManga(chapter.manga_id)
            db.historyQueries.syncUpsert(
                chapterId = history.chapterId,
                lastRead = history.lastRead?.let(::Date),
                timeRead = history.timeRead,
            )
        }

        override suspend fun insertMergedGroup(group: StoredMergedGroup): Long {
            db.merged_groupsQueries.syncInsertGroup(
                mainSource = group.mainSource,
                mainUrl = group.mainUrl,
                showAll = group.showAll,
                modifiedAt = group.modifiedAt,
            )
            // Inserted with the latch raised, so the insert trigger stayed quiet.
            val id = db.merged_groupsQueries.getGroupByMain(group.mainSource, group.mainUrl).awaitAsOne()._id
            raisedGroups += id
            insertMembers(id, group.members)
            return id
        }

        override suspend fun updateMergedGroup(group: StoredMergedGroup) {
            raiseGroup(group.id)
            db.merged_groupsQueries.syncUpdateGroup(
                showAll = group.showAll,
                modifiedAt = group.modifiedAt,
                groupId = group.id,
            )
            db.merged_groupsQueries.deleteMembersByGroupId(group.id)
            insertMembers(group.id, group.members)
        }

        override suspend fun removeMergedMember(source: Long, url: String) {
            val member = db.merged_groupsQueries.getMember(source, url).awaitAsOneOrNull() ?: return
            raiseGroup(member.group_id)
            db.merged_groupsQueries.deleteMember(source, url)
        }

        private suspend fun insertMembers(groupId: Long, members: List<StoredMergedMember>) {
            members.forEach { member ->
                db.merged_groupsQueries.insertMember(
                    memberSource = member.source,
                    memberUrl = member.url,
                    groupId = groupId,
                    position = member.position,
                    checkUpdates = member.checkUpdates,
                )
            }
        }

        /**
         * The apply's last pass over its categories (phase 5 unit CT, challenge c2 g3 C point 3),
         * once every category line it holds is stored. A line's parent may come after it: a cycle
         * of parents, which the merge can make and the file's parent-first order breaks where it
         * closes, puts one member first. So each category written is given its parent again here,
         * by key, and the stored tree is the merged one on every device, cycles included.
         *
         * A parent this device holds deleted is stored as none, and the categories under one this
         * apply deleted lose their parent, as the local delete's `reparentChildren` leaves no
         * category under a deleted one; the tree reads such a parent as absent either way, and two
         * devices then store the same row whichever of them saw the delete first.
         *
         * Every write here is to a row whose latch is raised: the rows this apply wrote are raised
         * already, and a child is raised before its reparent. `update_category_modified_at` and
         * `update_category_version` both fire only `WHEN new.is_syncing = 0`, so the pass moves no
         * clock, bumps no version and logs no change of its own.
         */
        suspend fun resolveCategoryParents() {
            for (categoryId in deletedCategories) {
                db.categoriesQueries.syncGetChildIds(categoryId).awaitAsList().forEach { raiseCategory(it) }
                db.categoriesQueries.reparentChildren(categoryId = categoryId)
            }
            for ((categoryId, parentKey) in categoryParentKeys) {
                val parentId = parentIdOf(parentKey)
                if (db.categoriesQueries.getCategory(categoryId).awaitAsOne().parent_id != parentId) {
                    db.categoriesQueries.updateParent(parentId = parentId, categoryId = categoryId)
                }
            }
        }

        suspend fun lowerLatches() {
            raisedMangas.forEach { db.mangasQueries.syncSetIsSyncing(isSyncing = 0, mangaId = it) }
            raisedChapters.forEach { db.chaptersQueries.syncSetIsSyncing(isSyncing = 0, chapterId = it) }
            raisedCategories.forEach { db.categoriesQueries.syncSetIsSyncing(isSyncing = 0, categoryId = it) }
            raisedGroups.forEach { db.merged_groupsQueries.syncSetIsSyncing(isSyncing = 0, groupId = it) }
        }

        private suspend fun raiseManga(id: Long) {
            if (raisedMangas.add(id)) db.mangasQueries.syncSetIsSyncing(isSyncing = 1, mangaId = id)
        }

        private suspend fun raiseChapter(id: Long) {
            if (raisedChapters.add(id)) db.chaptersQueries.syncSetIsSyncing(isSyncing = 1, chapterId = id)
        }

        private suspend fun raiseCategory(id: Long) {
            if (raisedCategories.add(id)) db.categoriesQueries.syncSetIsSyncing(isSyncing = 1, categoryId = id)
        }

        private suspend fun raiseGroup(id: Long) {
            if (raisedGroups.add(id)) db.merged_groupsQueries.syncSetIsSyncing(isSyncing = 1, groupId = id)
        }

        /**
         * The live category that holds [parentKey]. A parent this device does not hold, or holds
         * deleted, is none; one the apply writes later is resolved by [resolveCategoryParents].
         */
        private suspend fun parentIdOf(parentKey: String?): Long? =
            parentKey?.let { key ->
                db.categoriesQueries.syncGetBySyncKey(key).awaitAsOneOrNull()?.takeIf { it.deleted_at == null }?._id
            }
    }
}

/** Manga rows read per page while collecting the ones a full export carries. */
private const val LIBRARY_PAGE = 500L

/** Chapter ids per statement of [SyncStoreImpl.chapterMangaIds], as `MangaRepositoryImpl` batches ids. */
private const val CHAPTER_ID_BATCH = 1_000

private suspend fun Database.categoryKeysOf(mangaId: Long): Set<String> =
    mangas_categoriesQueries.syncGetCategoriesByMangaId(mangaId).awaitAsList().mapNotNull { it.sync_key }.toSet()

private suspend fun Database.excludedOf(mangaId: Long): Set<String> =
    excluded_scanlatorsQueries.getExcludedScanlatorsByMangaId(mangaId).awaitAsList().toSet()

private suspend fun Database.priorityOf(mangaId: Long): Map<String, Long> =
    scanlator_priorityQueries.getByMangaId(mangaId).awaitAsList().associate { it.scanlator to it.priority }

private suspend fun Database.tracksOf(mangaId: Long): List<StoredTrack> =
    manga_syncQueries.getTracksByMangaId(mangaId).awaitAsList().map { it.toStored() }

private suspend fun Database.notesOf(chapterId: Long): List<StoredNote> =
    bookmark_notesQueries.getByChapterId(chapterId).awaitAsList().map {
        StoredNote(pageIndex = it.page_index, note = it.note, dateAdded = it.date_added)
    }

private suspend fun Database.membersOf(groupId: Long): List<StoredMergedMember> =
    merged_groupsQueries.getMembersByGroupId(groupId).awaitAsList().map { it.toStored() }

private fun Merged_groups.toStored(members: List<StoredMergedMember>) = StoredMergedGroup(
    id = _id,
    mainSource = main_source,
    mainUrl = main_url,
    showAll = show_all,
    modifiedAt = modified_at,
    members = members,
)

private fun Merged_members.toStored() = StoredMergedMember(
    source = member_source,
    url = member_url,
    position = position,
    checkUpdates = check_updates,
)

private fun Mangas.toStored() = StoredManga(
    id = _id,
    source = source,
    url = url,
    favorite = favorite,
    dateAdded = date_added,
    favoriteModifiedAt = favorite_modified_at,
    deletedAt = deleted_at,
    restoredAt = restored_at,
    version = version,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genre = genre,
    status = status,
    thumbnailUrl = thumbnail_url,
    updateStrategy = update_strategy,
    memo = memo,
    viewerFlags = viewer,
    chapterFlags = chapter_flags,
    displayModifiedAt = display_modified_at,
    notes = notes,
    notesModifiedAt = notes_modified_at,
    manualPosition = manual_position,
    positionModifiedAt = position_modified_at,
    titleOverride = title_override,
    descriptionOverride = description_override,
    authorOverride = author_override,
    artistOverride = artist_override,
    statusOverride = status_override,
    genreOverride = genre_override,
    detailsModifiedAt = details_modified_at,
    categoriesModifiedAt = categories_modified_at,
    exclusionsModifiedAt = exclusions_modified_at,
    priorityModifiedAt = priority_modified_at,
    trackersModifiedAt = trackers_modified_at,
)

private fun Chapters.toStored() = StoredChapter(
    id = _id,
    mangaId = manga_id,
    url = url,
    read = read,
    bookmark = bookmark,
    lastPageRead = last_page_read,
    dateFetch = date_fetch,
    version = version,
    name = name,
    scanlator = scanlator,
    chapterNumber = chapter_number,
    dateUpload = date_upload,
    volume = volume,
    sourceOrder = source_order,
    deletedAt = deleted_at,
    restoredAt = restored_at,
    bookmarkNotesModifiedAt = bookmark_notes_modified_at,
    readModifiedAt = read_modified_at,
    bookmarkModifiedAt = bookmark_modified_at,
)

private fun History.toStored() = StoredHistory(chapterId = chapter_id, lastRead = last_read?.time, timeRead = time_read)

private fun Manga_sync.toStored() = StoredTrack(
    trackerId = sync_id,
    remoteId = remote_id,
    libraryId = library_id,
    title = title,
    lastChapterRead = last_chapter_read,
    totalChapters = total_chapters,
    status = status,
    score = score,
    remoteUrl = remote_url,
    startDate = start_date,
    finishDate = finish_date,
    private = private_,
)

/** Null for a row with no key yet, which the start-up backfill has still to key. */
private fun SyncGetAll.toStored(): StoredCategory? = sync_key?.let { syncKey ->
    StoredCategory(
        id = _id,
        syncKey = syncKey,
        name = name,
        order = sort,
        flags = flags,
        parentKey = parent_key,
        modifiedAt = modified_at,
        deletedAt = deleted_at,
        restoredAt = restored_at,
    )
}
