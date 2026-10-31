package com.shinsaikou.reader.state

import com.shinsaikou.reader.memory.DisplayInfoProvider
import com.shinsaikou.reader.memory.MemoryInfoProvider
import com.shinsaikou.reader.pipeline.PageLoader
import com.shinsaikou.reader.pipeline.PageSplit
import com.shinsaikou.reader.pipeline.PageState
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import com.shinsaikou.source.model.UpdateStrategy

/** The fakes every test in this package shares. Nothing here touches Android or a coroutine. */

const val MIB = 1024L * 1024L

fun chapter(
    id: Long,
    number: Double = id.toDouble(),
    read: Boolean = false,
    bookmark: Boolean = false,
    lastPageRead: Long = 0,
    scanlator: String? = null,
    deletedAt: Long? = null,
    sourceOrder: Long = id,
): Chapter = Chapter.create().copy(
    id = id,
    mangaId = MANGA_ID,
    chapterNumber = number,
    read = read,
    bookmark = bookmark,
    lastPageRead = lastPageRead,
    scanlator = scanlator,
    deletedAt = deletedAt,
    sourceOrder = sourceOrder,
    name = "chapter $id",
    url = "/$id",
)

const val MANGA_ID = 7L

/**
 * Sorted by chapter number by default, not by source order. `getChapterSort`'s source-order branch
 * inverts its flag (source order is newest first), so a fixture on the default flags would come
 * back reversed and every expectation in this package would be written backwards for no reason.
 */
fun manga(
    chapterFlags: Long = Manga.CHAPTER_SORTING_NUMBER,
    viewerFlags: Long = 0L,
): Manga = Manga(
    id = MANGA_ID,
    source = 1L,
    favorite = true,
    lastUpdate = 0,
    nextUpdate = 0,
    fetchInterval = 0,
    dateAdded = 0,
    viewerFlags = viewerFlags,
    chapterFlags = chapterFlags,
    coverLastModified = 0,
    url = "/manga",
    title = "title",
    artist = null,
    author = null,
    description = null,
    genre = null,
    status = 0,
    thumbnailUrl = null,
    updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    initialized = true,
    lastModifiedAt = 0,
    favoriteModifiedAt = null,
    version = 1,
    notes = "",
    memo = JsonObject(emptyMap()),
    manualPosition = 0,
    deletedAt = null,
    titleOverride = null,
    descriptionOverride = null,
)

/** A chapter already in [ReaderChapter.State.Loaded] with [pageCount] queued pages. */
fun loadedChapter(id: Long, pageCount: Int = 3, bookmark: Boolean = false): ReaderChapter =
    ReaderChapter(chapter(id, bookmark = bookmark)).also { readerChapter ->
        val pages = List(pageCount) { index -> ReaderPage(index).apply { chapter = readerChapter } }
        readerChapter.state = ReaderChapter.State.Loaded(pages)
    }

fun waitingChapter(id: Long): ReaderChapter = ReaderChapter(chapter(id))

fun session(vararg chapters: ReaderChapter): ReaderSession = ReaderSession(manga(), chapters.toList())

/** A state that is already Ready over [session], centred on [current]. */
fun readyState(
    session: ReaderSession,
    current: ReaderChapter,
    currentPage: Int = 0,
    incognito: Boolean = false,
    preload: PreloadPolicy.Window = PreloadPolicy.DEFAULT,
): ReaderState {
    val window = ChapterWindow.around(session, current)
    return ReaderState(
        phase = ReaderPhase.Ready,
        manga = session.manga,
        session = session,
        window = window,
        currentPage = currentPage,
        totalPages = current.pages?.count { it !is InsertPage } ?: -1,
        bookmarked = current.chapter.bookmark,
        incognito = incognito,
        preload = preload,
    )
}

fun machine(
    longTapEnabled: Boolean = true,
    preload: PreloadPolicy.Window = PreloadPolicy.DEFAULT,
    showReadingMode: Boolean = false,
): ReaderStateMachine = ReaderStateMachine({ longTapEnabled }, { preload }, { showReadingMode })

/**
 * Page [index] of [chapter], Ready so the progress rules apply to it. Looked up by its index rather
 * than its position, so in a chapter with split pages it is the page itself, not one of its parts.
 */
fun readyPage(chapter: ReaderChapter, index: Int): ReaderPage =
    chapter.pages!!.first { it.index == index && it !is InsertPage }.also { it.status = PageState.Ready }

/** Splices [parts] tall-strip parts into a loaded [chapter] right after page [index], as a split does. */
fun splitPage(chapter: ReaderChapter, index: Int, parts: Int): List<InsertPage> {
    val pages = chapter.pages!!
    val parent = pages.first { it.index == index && it !is InsertPage }
    val inserts = (1..parts).map { InsertPage(parent, partIndex = it) }
    val at = pages.indexOf(parent)
    chapter.replacePages(pages.take(at + 1) + inserts + pages.drop(at + 1))
    return inserts
}

class FakeMemory(
    override val maxHeapBytes: Long,
    override val isLowRamDevice: Boolean,
) : MemoryInfoProvider {
    override val usedHeapBytes: Long get() = 0
}

class FakeDisplay(
    override val widthPx: Int,
    override val heightPx: Int,
) : DisplayInfoProvider {
    override val maxDimensionPx: Int get() = maxOf(widthPx, heightPx)
}

/** Counts [recycle] so the window's reference rules can be asserted. */
class CountingLoader : PageLoader {
    var recycled = 0
        private set

    override val isLocal = true
    override val isRecycled: Boolean get() = recycled > 0
    override val splits: Flow<PageSplit> = emptyFlow()

    override suspend fun getPages(): List<ReaderPage> = emptyList()
    override suspend fun loadPage(page: ReaderPage) = Unit
    override fun retryPage(page: ReaderPage) = Unit

    override fun recycle() {
        recycled++
    }
}
