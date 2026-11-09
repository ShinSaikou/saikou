package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncDigest
import com.shinsaikou.sync.format.BookmarkNotesRow
import com.shinsaikou.sync.format.CategoryRow
import com.shinsaikou.sync.format.ChapterKey
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.ExcludedScanlatorsRow
import com.shinsaikou.sync.format.HistoryRow
import com.shinsaikou.sync.format.MangaCategoriesRow
import com.shinsaikou.sync.format.MangaKey
import com.shinsaikou.sync.format.MangaRow
import com.shinsaikou.sync.format.MergedGroupRow
import com.shinsaikou.sync.format.ScanlatorPriorityRow
import com.shinsaikou.sync.format.SyncFormat
import com.shinsaikou.sync.format.SyncRow
import com.shinsaikou.sync.format.TracksRow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * [SyncDigest] (phase 3 unit I, fix rounds 1 and 2): two devices that hold the same library under
 * different local ids give one digest, and a change to any one value a wire row carries gives
 * another. Each value case rewrites one value of the planned rows, through the digest's own seam,
 * and checks that value alone moved on the wire; [wirePaths] lists every value the row types carry,
 * read from their serial descriptors, so a value added to a wire row fails the completeness test
 * until it has a case here.
 */
class SyncDigestTest {

    private val backend = InMemorySyncBackend()
    private val devices = mutableListOf<SyncTestDevice>()

    @AfterEach
    fun close() {
        devices.forEach(SyncTestDevice::close)
    }

    /** Hands over what the real store read, passed through [change]. */
    private class ChangingStore(private val real: SyncStore) : SyncStore by real {
        var change: (SyncPushRows) -> SyncPushRows = { it }

        override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows = change(real.readLibrary(mangaIds))
    }

    private fun device(): Pair<SyncTestDevice, ChangingStore> {
        lateinit var store: ChangingStore
        val device = SyncTestDevice(backend, storeWrapper = { ChangingStore(it).also { wrapped -> store = wrapped } })
        devices += device
        return device to store
    }

    private fun SyncStore.digest(): SyncDigest.Result = runBlocking { SyncDigest.of(this@digest) }

    /** The digest with [change] applied to every planned row, and the wire paths it moved. */
    private fun SyncStore.digest(change: (SyncRow) -> SyncRow): Pair<SyncDigest.Result, Set<String>> {
        val moved = mutableSetOf<String>()
        val result = runBlocking { SyncDigest.of(this@digest) { row -> change(row).also { moved += diff(row, it) } } }
        return result to moved
    }

    @Test
    fun `two libraries equal in content under different local ids give one digest`() {
        val (a, storeA) = device()
        val (b, storeB) = device()
        a.buildLibrary()
        // B holds rows of its own first, outside the library, so every row the sync brings takes
        // another local id there.
        b.exec(
            """
            INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
                cover_last_modified, date_added, memo)
            VALUES (1, ${SyncTestDevice.SOURCE}, '/elsewhere', 'Not in the library', 0, 0, 0, 0, 0, 0, 0, CAST('{}' AS BLOB))
            """,
        )
        b.addChapter(10, mangaId = 1, url = "/elsewhere/10")
        b.addChapter(11, mangaId = 1, url = "/elsewhere/11")
        b.addCategory(5, "Parent")
        b.addCategory(6, "Reading", parentId = 5)

        a.sync()
        b.sync()
        a.sync()

        // B's own entry is outside the library, so it is in neither the digest nor what syncs.
        storeA.digest() shouldBe storeB.digest()
        b.mangaId(SyncTestDevice.SOURCE, "/series/1") shouldNotBe a.mangaId(SyncTestDevice.SOURCE, "/series/1")
        b.long("SELECT _id FROM chapters WHERE url = '/series/1/chapter/10'") shouldNotBe 10L
        b.long("SELECT _id FROM categories WHERE name = 'Reading'") shouldNotBe
            a.long("SELECT _id FROM categories WHERE name = 'Reading'")

        // Without it the two databases hold the same rows, every value by natural key.
        b.exec("DELETE FROM chapters WHERE manga_id = 1")
        b.exec("DELETE FROM mangas WHERE _id = 1")
        a.library() shouldBe b.library()
    }

    @Test
    fun `the order a device holds a set in does not count`() {
        val (a, store) = device()
        a.buildLibrary()
        a.addCategory(3, "Later")
        a.exec("INSERT INTO mangas_categories(manga_id, category_id) VALUES (1, 3)")
        a.exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Group C')")
        val digest = store.digest()

        store.change = { rows ->
            rows.copy(
                categoryKeys = rows.categoryKeys.mapValues { it.value.sortedDescending().toSet() },
                excludedScanlators = rows.excludedScanlators.mapValues { it.value.sortedDescending().toSet() },
            )
        }
        store.digest() shouldBe digest
    }

    @Test
    fun `two libraries differing only in the chapters' source order give one digest`() {
        val (a, store) = device()
        a.buildLibrary()
        val digest = store.digest()

        // The wire carries it, but no merge rule takes it, so it is not a synced value.
        store.change = { rows -> rows.copy(chapters = rows.chapters.mapValues { it.value.copy(sourceOrder = it.value.sourceOrder + 7) }) }
        store.digest() shouldBe digest
    }

    /** The rows [SyncDigest] planned from [this], in the order it planned them, and its result. */
    private fun SyncStore.planned(): Pair<List<SyncRow>, SyncDigest.Result> {
        val rows = mutableListOf<SyncRow>()
        val result = runBlocking { SyncDigest.of(this@planned) { row -> row.also { rows += it } } }
        return rows to result
    }

    /** The digest of [rows], in their order, whatever [this] plans: the k-th row planned is replaced by `rows[k]`. */
    private fun SyncStore.digestOf(rows: List<SyncRow>): SyncDigest.Result {
        var next = 0
        val result = runBlocking { SyncDigest.of(this@digestOf) { rows[next++] } }
        next shouldBe rows.size
        return result
    }

    @Test
    fun `the same rows in another order give one digest`() {
        val (a, store) = device()
        a.buildLibrary()
        val (rows, digest) = store.planned()
        rows.size shouldBe digest.rows
        // Every row is distinct (each carries its key), so a reordering is not the same list.
        rows.toSet().size shouldBe rows.size

        store.digestOf(rows) shouldBe digest
        store.digestOf(rows.reversed()) shouldBe digest
        store.digestOf(rows.shuffled(Random(7))) shouldBe digest
        store.digestOf(rows.drop(1) + rows.first()) shouldBe digest
    }

    @Test
    fun `one changed byte in one row changes the digest`() {
        val (a, store) = device()
        a.buildLibrary()
        val (rows, digest) = store.planned()
        val index = rows.indexOfFirst { it is MangaRow }
        val before = rows[index] as MangaRow
        val after = before.copy(title = before.title.dropLast(1) + if (before.title.last() == 'x') 'y' else 'x')
        val beforeLine = SyncFormat.json.encodeToString(SyncRow.serializer(), before).toByteArray()
        val afterLine = SyncFormat.json.encodeToString(SyncRow.serializer(), after).toByteArray()
        afterLine.size shouldBe beforeLine.size
        beforeLine.indices.count { beforeLine[it] != afterLine[it] } shouldBe 1

        val changed = store.digestOf(rows.toMutableList().also { it[index] = after })
        changed.sha256 shouldNotBe digest.sha256
        changed.rows shouldBe digest.rows
    }

    @Test
    fun `two different sets of a few rows give two digests`() {
        val (a, store) = device()
        a.buildLibrary()
        val (rows, digest) = store.planned()
        val (first, second) = rows.take(2)
        val rest = rows.drop(2)

        // The first row twice in place of the second, and the second twice in place of the first:
        // as many rows each time, and different sets.
        val firstTwice = store.digestOf(listOf(first, first) + rest)
        val secondTwice = store.digestOf(listOf(second, second) + rest)
        listOf(digest, firstTwice, secondTwice).map { it.sha256 }.toSet().size shouldBe 3
        // An exclusive or of the lines' hashes would give these two one value: a pair cancels out.
        firstTwice.sha256 shouldNotBe secondTwice.sha256
        // The same rows swapped are the same set.
        store.digestOf(listOf(second, first) + rest) shouldBe digest
    }

    @Test
    fun `a change to any one value a wire row carries changes the digest`() {
        val (a, store) = device()
        a.buildLibrary()
        val digest = store.digest()

        for ((path, change) in valueCases) {
            val (changed, moved) = store.digest(change)
            withClue(path) {
                moved shouldBe setOf(path)
                changed.sha256 shouldNotBe digest.sha256
            }
        }
        for ((path, change) in unmergedCases) {
            val (changed, moved) = store.digest(change)
            withClue(path) {
                moved shouldBe setOf(path)
                changed shouldBe digest
            }
        }
        store.digest() shouldBe digest
    }

    @Test
    fun `every value a wire row carries has a case`() {
        val paths = wirePaths().filterNot { it.substringBefore('.') in notInDigest }.toSet()
        val cases = valueCases.keys + unmergedCases.keys

        withClue("wire values with no case") { (paths - cases) shouldBe emptySet() }
        withClue("cases for no wire value") { (cases - paths) shouldBe emptySet() }
    }

    /** Row types the digest never reads, by their wire name, and why. */
    private val notInDigest = mapOf(
        "chapter_removed" to "a deletion event only a segment carries; the export plans state, so the digest never sees one",
    )

    /** Wire values that are carried but merged by no rule, so the digest leaves them out ([SyncDigest]'s encode). */
    private val unmergedCases: Map<String, (SyncRow) -> SyncRow> = mapOf(
        "chapter.sourceOrder" to row<ChapterRow> { copy(sourceOrder = sourceOrder + 7) },
    )

    /**
     * One case per value path of [wirePaths]: each changes that value in every planned row of its
     * type, and buildLibrary's library has a row of every type with one entry in every list.
     */
    private val valueCases: Map<String, (SyncRow) -> SyncRow> = mapOf(
        "manga.key.source" to row<MangaRow> { copy(key = key.onOtherSource()) },
        "manga.key.url" to row<MangaRow> { copy(key = key.moved()) },
        "manga.favorite" to row<MangaRow> { copy(favorite = !favorite) },
        "manga.dateAdded" to row<MangaRow> { copy(dateAdded = dateAdded + 1) },
        "manga.favoriteModifiedAt" to row<MangaRow> { copy(favoriteModifiedAt = (favoriteModifiedAt ?: 0) + 1) },
        "manga.deletedAt" to row<MangaRow> { copy(deletedAt = (deletedAt ?: 0) + 1) },
        "manga.restoredAt" to row<MangaRow> { copy(restoredAt = (restoredAt ?: 0) + 1) },
        "manga.version" to row<MangaRow> { copy(version = version + 1) },
        "manga.title" to row<MangaRow> { copy(title = "$title changed") },
        "manga.author" to row<MangaRow> { copy(author = "$author changed") },
        "manga.artist" to row<MangaRow> { copy(artist = "$artist changed") },
        "manga.description" to row<MangaRow> { copy(description = "$description changed") },
        "manga.genre" to row<MangaRow> { copy(genre = genre.orEmpty() + "Changed") },
        "manga.status" to row<MangaRow> { copy(status = status + 1) },
        "manga.thumbnailUrl" to row<MangaRow> { copy(thumbnailUrl = "$thumbnailUrl/changed") },
        "manga.updateStrategy" to row<MangaRow> { copy(updateStrategy = updateStrategy + 1) },
        "manga.memo" to row<MangaRow> { copy(memo = JsonObject(memo + ("changed" to JsonPrimitive(1)))) },
        "manga.viewer" to row<MangaRow> { copy(viewer = viewer + 1) },
        "manga.chapterFlags" to row<MangaRow> { copy(chapterFlags = chapterFlags + 1) },
        "manga.displayModifiedAt" to row<MangaRow> { copy(displayModifiedAt = (displayModifiedAt ?: 0) + 1) },
        "manga.notes" to row<MangaRow> { copy(notes = "$notes changed") },
        "manga.notesModifiedAt" to row<MangaRow> { copy(notesModifiedAt = (notesModifiedAt ?: 0) + 1) },
        "manga.manualPosition" to row<MangaRow> { copy(manualPosition = manualPosition + 1) },
        "manga.positionModifiedAt" to row<MangaRow> { copy(positionModifiedAt = (positionModifiedAt ?: 0) + 1) },
        "manga.titleOverride" to row<MangaRow> { copy(titleOverride = "$titleOverride changed") },
        "manga.descriptionOverride" to row<MangaRow> { copy(descriptionOverride = "$descriptionOverride changed") },
        "manga.detailsModifiedAt" to row<MangaRow> { copy(detailsModifiedAt = (detailsModifiedAt ?: 0) + 1) },
        "manga.authorOverride" to row<MangaRow> { copy(authorOverride = "$authorOverride changed") },
        "manga.artistOverride" to row<MangaRow> { copy(artistOverride = "$artistOverride changed") },
        "manga.statusOverride" to row<MangaRow> { copy(statusOverride = (statusOverride ?: 0) + 1) },
        "manga.genreOverride" to row<MangaRow> { copy(genreOverride = genreOverride.orEmpty() + "Changed") },
        "chapter.key.manga.source" to row<ChapterRow> { copy(key = key.copy(manga = key.manga.onOtherSource())) },
        "chapter.key.manga.url" to row<ChapterRow> { copy(key = key.copy(manga = key.manga.moved())) },
        "chapter.key.url" to row<ChapterRow> { copy(key = key.moved()) },
        "chapter.read" to row<ChapterRow> { copy(read = !read) },
        "chapter.bookmark" to row<ChapterRow> { copy(bookmark = !bookmark) },
        "chapter.lastPageRead" to row<ChapterRow> { copy(lastPageRead = lastPageRead + 1) },
        "chapter.dateFetch" to row<ChapterRow> { copy(dateFetch = dateFetch + 1) },
        "chapter.version" to row<ChapterRow> { copy(version = version + 1) },
        "chapter.name" to row<ChapterRow> { copy(name = "$name changed") },
        "chapter.scanlator" to row<ChapterRow> { copy(scanlator = "$scanlator changed") },
        "chapter.chapterNumber" to row<ChapterRow> { copy(chapterNumber = chapterNumber + 1) },
        "chapter.dateUpload" to row<ChapterRow> { copy(dateUpload = dateUpload + 1) },
        "chapter.volume" to row<ChapterRow> { copy(volume = (volume ?: 0.0) + 1) },
        "chapter.deletedAt" to row<ChapterRow> { copy(deletedAt = (deletedAt ?: 0) + 1) },
        "chapter.restoredAt" to row<ChapterRow> { copy(restoredAt = (restoredAt ?: 0) + 1) },
        "chapter.readModifiedAt" to row<ChapterRow> { copy(readModifiedAt = readModifiedAt + 1) },
        "chapter.bookmarkModifiedAt" to row<ChapterRow> { copy(bookmarkModifiedAt = bookmarkModifiedAt + 1) },
        "category.key" to row<CategoryRow> { copy(key = "$key-2") },
        "category.name" to row<CategoryRow> { copy(name = "$name changed") },
        "category.order" to row<CategoryRow> { copy(order = order + 1) },
        "category.flags" to row<CategoryRow> { copy(flags = flags + 1) },
        "category.parent" to row<CategoryRow> { copy(parent = "$parent-2") },
        "category.modifiedAt" to row<CategoryRow> { copy(modifiedAt = (modifiedAt ?: 0) + 1) },
        "category.deletedAt" to row<CategoryRow> { copy(deletedAt = (deletedAt ?: 0) + 1) },
        "category.restoredAt" to row<CategoryRow> { copy(restoredAt = (restoredAt ?: 0) + 1) },
        "manga_categories.manga.source" to row<MangaCategoriesRow> { copy(manga = manga.onOtherSource()) },
        "manga_categories.manga.url" to row<MangaCategoriesRow> { copy(manga = manga.moved()) },
        "manga_categories.categories" to row<MangaCategoriesRow> { copy(categories = categories + "other") },
        "manga_categories.categoriesModifiedAt" to row<MangaCategoriesRow> {
            copy(categoriesModifiedAt = (categoriesModifiedAt ?: 0) + 1)
        },
        "history.chapter.manga.source" to row<HistoryRow> { copy(chapter = chapter.copy(manga = chapter.manga.onOtherSource())) },
        "history.chapter.manga.url" to row<HistoryRow> { copy(chapter = chapter.copy(manga = chapter.manga.moved())) },
        "history.chapter.url" to row<HistoryRow> { copy(chapter = chapter.moved()) },
        "history.lastRead" to row<HistoryRow> { copy(lastRead = (lastRead ?: 0) + 1) },
        "history.timeRead" to row<HistoryRow> { copy(timeRead = timeRead + 1) },
        "tracks.manga.source" to row<TracksRow> { copy(manga = manga.onOtherSource()) },
        "tracks.manga.url" to row<TracksRow> { copy(manga = manga.moved()) },
        "tracks.tracks.trackerId" to row<TracksRow> { copy(tracks = tracks.map { it.copy(trackerId = it.trackerId + 1) }) },
        "tracks.tracks.remoteId" to row<TracksRow> { copy(tracks = tracks.map { it.copy(remoteId = it.remoteId + 1) }) },
        "tracks.tracks.libraryId" to row<TracksRow> { copy(tracks = tracks.map { it.copy(libraryId = (it.libraryId ?: 0) + 1) }) },
        "tracks.tracks.title" to row<TracksRow> { copy(tracks = tracks.map { it.copy(title = "${it.title} changed") }) },
        "tracks.tracks.lastChapterRead" to row<TracksRow> {
            copy(tracks = tracks.map { it.copy(lastChapterRead = it.lastChapterRead + 1) })
        },
        "tracks.tracks.totalChapters" to row<TracksRow> { copy(tracks = tracks.map { it.copy(totalChapters = it.totalChapters + 1) }) },
        "tracks.tracks.status" to row<TracksRow> { copy(tracks = tracks.map { it.copy(status = it.status + 1) }) },
        "tracks.tracks.score" to row<TracksRow> { copy(tracks = tracks.map { it.copy(score = it.score + 1) }) },
        "tracks.tracks.remoteUrl" to row<TracksRow> { copy(tracks = tracks.map { it.copy(remoteUrl = "${it.remoteUrl}/changed") }) },
        "tracks.tracks.startDate" to row<TracksRow> { copy(tracks = tracks.map { it.copy(startDate = it.startDate + 1) }) },
        "tracks.tracks.finishDate" to row<TracksRow> { copy(tracks = tracks.map { it.copy(finishDate = it.finishDate + 1) }) },
        "tracks.tracks.private" to row<TracksRow> { copy(tracks = tracks.map { it.copy(private = !it.private) }) },
        "tracks.trackersModifiedAt" to row<TracksRow> { copy(trackersModifiedAt = (trackersModifiedAt ?: 0) + 1) },
        "excluded_scanlators.manga.source" to row<ExcludedScanlatorsRow> { copy(manga = manga.onOtherSource()) },
        "excluded_scanlators.manga.url" to row<ExcludedScanlatorsRow> { copy(manga = manga.moved()) },
        "excluded_scanlators.scanlators" to row<ExcludedScanlatorsRow> { copy(scanlators = scanlators + "Group Z") },
        "excluded_scanlators.exclusionsModifiedAt" to row<ExcludedScanlatorsRow> {
            copy(exclusionsModifiedAt = (exclusionsModifiedAt ?: 0) + 1)
        },
        "scanlator_priority.manga.source" to row<ScanlatorPriorityRow> { copy(manga = manga.onOtherSource()) },
        "scanlator_priority.manga.url" to row<ScanlatorPriorityRow> { copy(manga = manga.moved()) },
        "scanlator_priority.priorities.scanlator" to row<ScanlatorPriorityRow> {
            copy(priorities = priorities.map { it.copy(scanlator = "${it.scanlator} changed") })
        },
        "scanlator_priority.priorities.priority" to row<ScanlatorPriorityRow> {
            copy(priorities = priorities.map { it.copy(priority = it.priority + 1) })
        },
        "scanlator_priority.priorityModifiedAt" to row<ScanlatorPriorityRow> {
            copy(priorityModifiedAt = (priorityModifiedAt ?: 0) + 1)
        },
        "bookmark_notes.chapter.manga.source" to row<BookmarkNotesRow> {
            copy(chapter = chapter.copy(manga = chapter.manga.onOtherSource()))
        },
        "bookmark_notes.chapter.manga.url" to row<BookmarkNotesRow> { copy(chapter = chapter.copy(manga = chapter.manga.moved())) },
        "bookmark_notes.chapter.url" to row<BookmarkNotesRow> { copy(chapter = chapter.moved()) },
        "bookmark_notes.notes.pageIndex" to row<BookmarkNotesRow> { copy(notes = notes.map { it.copy(pageIndex = it.pageIndex + 1) }) },
        "bookmark_notes.notes.note" to row<BookmarkNotesRow> { copy(notes = notes.map { it.copy(note = "${it.note} changed") }) },
        "bookmark_notes.notes.dateAdded" to row<BookmarkNotesRow> { copy(notes = notes.map { it.copy(dateAdded = it.dateAdded + 1) }) },
        "bookmark_notes.bookmarkNotesModifiedAt" to row<BookmarkNotesRow> {
            copy(bookmarkNotesModifiedAt = (bookmarkNotesModifiedAt ?: 0) + 1)
        },
        "merged_group.main.source" to row<MergedGroupRow> { copy(main = main.onOtherSource()) },
        "merged_group.main.url" to row<MergedGroupRow> { copy(main = main.moved()) },
        "merged_group.members.manga.source" to row<MergedGroupRow> {
            copy(members = members.map { it.copy(manga = it.manga.onOtherSource()) })
        },
        "merged_group.members.manga.url" to row<MergedGroupRow> {
            copy(members = members.map { it.copy(manga = it.manga.moved()) })
        },
        "merged_group.members.position" to row<MergedGroupRow> {
            copy(members = members.map { it.copy(position = it.position + 1) })
        },
        "merged_group.members.checkUpdates" to row<MergedGroupRow> {
            copy(members = members.map { it.copy(checkUpdates = !it.checkUpdates) })
        },
        "merged_group.showAll" to row<MergedGroupRow> { copy(showAll = !showAll) },
        "merged_group.modifiedAt" to row<MergedGroupRow> { copy(modifiedAt = modifiedAt + 1) },
    )

    private inline fun <reified R : SyncRow> row(crossinline change: R.() -> R): (SyncRow) -> SyncRow =
        { if (it is R) it.change() else it }

    private fun MangaKey.onOtherSource() = copy(source = source + 1)

    private fun MangaKey.moved() = copy(url = "$url/moved")

    private fun ChapterKey.moved() = copy(url = "$url/moved")

    /** Each wire row type's descriptor by its wire name, `manga` to `bookmark_notes`. */
    private val rowDescriptors: Map<String, SerialDescriptor> =
        SyncRow.serializer().descriptor.getElementDescriptor(1).elementDescriptors.associateBy { it.serialName }

    /**
     * Every value path the wire row types carry, as `row.property`, descending through keys and the
     * entries of a list (`tracks.tracks.score`); a list of plain values and the memo are one value.
     */
    private fun wirePaths(): List<String> = rowDescriptors.flatMap { (name, descriptor) -> paths(descriptor, name) }

    private fun paths(descriptor: SerialDescriptor, path: String): List<String> = when {
        descriptor.kind == StructureKind.CLASS ->
            (0 until descriptor.elementsCount).flatMap {
                paths(descriptor.getElementDescriptor(it), "$path.${descriptor.getElementName(it)}")
            }
        descriptor.isListOfEntries() -> paths(descriptor.getElementDescriptor(0), path)
        else -> listOf(path)
    }

    /** The value paths at which [before] and [after], one row before and after a case, differ on the wire. */
    private fun diff(before: SyncRow, after: SyncRow): Set<String> {
        val a = SyncFormat.json.encodeToJsonElement(SyncRow.serializer(), before).jsonObject
        val b = SyncFormat.json.encodeToJsonElement(SyncRow.serializer(), after).jsonObject
        val name = a.getValue("entity").jsonPrimitive.content
        if (name != b.getValue("entity").jsonPrimitive.content) return setOf("entity")
        return diff(rowDescriptors.getValue(name), a, b, name)
    }

    private fun diff(descriptor: SerialDescriptor, a: JsonElement?, b: JsonElement?, path: String): Set<String> = when {
        descriptor.kind == StructureKind.CLASS && a is JsonObject && b is JsonObject ->
            (0 until descriptor.elementsCount).flatMapTo(mutableSetOf()) {
                val name = descriptor.getElementName(it)
                diff(descriptor.getElementDescriptor(it), a[name], b[name], "$path.$name")
            }
        descriptor.isListOfEntries() && a is JsonArray && b is JsonArray && a.size == b.size ->
            a.indices.flatMapTo(mutableSetOf()) { diff(descriptor.getElementDescriptor(0), a[it], b[it], path) }
        a == b -> emptySet()
        else -> setOf(path)
    }

    private fun SerialDescriptor.isListOfEntries() =
        kind == StructureKind.LIST && getElementDescriptor(0).kind == StructureKind.CLASS
}
