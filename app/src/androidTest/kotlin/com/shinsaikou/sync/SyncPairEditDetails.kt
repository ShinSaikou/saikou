package com.shinsaikou.sync

import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.manga.model.detailsOverrides
import com.shinsaikou.source.Source
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.PageInfo

/**
 * The `edit_details_race` scenario (phase 5 unit ED, R138 item 2; R165 item 4): the owner's edit of
 * the title, description, author, artist, status and genres on one device against a source refresh
 * on the other, over the pair library's [SyncPairLibrary.SCENARIO_ENTRY].
 *
 * [edit] is the Edit details dialog's Save, through [SetMangaDetailsOverride] as the entry screen's
 * view model calls it; [reset] is its Reset. [refresh] is the entry screen's refresh against a
 * fixture source served from this test APK, whose title for the entry has changed: "Update library
 * manga titles" on, then a manual refresh that finds the new title, then one that finds a new
 * description as well. Two writes, so the refreshing device's version passes the editing device's
 * one edit and the source's new text is what both keep under the edit (an edit and a single refresh
 * tie on the version, and the source values then go by encoding order: the ruling's known limit).
 * The source is handed to [AppGraph.refreshMangaFromSource] directly; no product path can register
 * it (R39), and no request leaves the device.
 *
 * Every step reports what the screens show and what is stored under it: `shownTitle` and
 * `shownDescription` (the edit over the source's), `sourceTitle` and `sourceDescription`,
 * `shownAuthor`, `shownArtist`, `shownStatus` (a `MangaStatus` code) and `shownGenres` (joined by
 * commas), and `edited`, whether any of the six is. A text's spaces are `_` and a missing one is
 * `none`, since a result line's pairs are split on spaces. The seed entry has no author, artist or
 * genres and status 0, and the fixture source keeps them so.
 */
object SyncPairEditDetails {

    const val EDITED_TITLE = "Pair title edited on A"
    const val EDITED_DESCRIPTION = "Pair description edited on A"
    const val RENAMED_TITLE = "Pair title renamed by the source"
    const val NEW_DESCRIPTION = "Pair description from the source"
    const val EDITED_AUTHOR = "Pair author edited on A"
    const val EDITED_ARTIST = "Pair artist edited on A"
    val EDITED_STATUS = MangaStatus.COMPLETED.code.toLong()
    val EDITED_GENRES = listOf("Pair genre one", "Pair genre two")

    /** The fixture source: the entry with [title] and [description], as its details now read. */
    private class DetailsSource(private val title: String, private val description: String?) : Source {
        override val id = SyncPairLibrary.SOURCE_ID
        override val name = "Saikou sync pair details fixture"
        override val lang = "en"
        override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo {
            check(manga.url == SyncPairLibrary.entryUrl(SyncPairLibrary.SCENARIO_ENTRY)) {
                "the fixture source has no details for ${manga.url}"
            }
            return manga.copy(title = title, description = description ?: manga.description, initialized = true)
        }
        override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> =
            error("the fixture source lists no chapters")
        override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> =
            error("the fixture source serves no pages")
        override suspend fun getImage(page: PageInfo): ImageResult = error("the fixture source serves no pages")
    }

    suspend fun edit(graph: AppGraph): Map<String, Any?> {
        val id = SyncPairLibrary.scenarioEntryId(graph)
        val edit = SetMangaDetailsOverride.Edit(
            title = EDITED_TITLE,
            description = EDITED_DESCRIPTION,
            author = EDITED_AUTHOR,
            artist = EDITED_ARTIST,
            status = EDITED_STATUS,
            genres = EDITED_GENRES,
        )
        check(SetMangaDetailsOverride(graph.mangaRepository).await(id, edit)) { "the edit wrote nothing" }
        return shown(graph)
    }

    suspend fun reset(graph: AppGraph): Map<String, Any?> {
        check(SetMangaDetailsOverride(graph.mangaRepository).awaitReset(SyncPairLibrary.scenarioEntryId(graph))) {
            "the reset wrote nothing"
        }
        return shown(graph)
    }

    suspend fun refresh(graph: AppGraph): Map<String, Any?> {
        graph.libraryPreferences.updateMangaTitles.set(true)
        val manga = graph.mangaRepository.getMangaById(SyncPairLibrary.scenarioEntryId(graph))
        val renamed = graph.refreshMangaFromSource(
            DetailsSource(RENAMED_TITLE, description = null),
            manga,
            fetchDetails = true,
            manualFetch = true,
        ).manga
        graph.refreshMangaFromSource(
            DetailsSource(RENAMED_TITLE, NEW_DESCRIPTION),
            renamed,
            fetchDetails = true,
            manualFetch = true,
        )
        return shown(graph)
    }

    suspend fun check(graph: AppGraph): Map<String, Any?> = shown(graph)

    private suspend fun shown(graph: AppGraph): Map<String, Any?> {
        val manga = graph.mangaRepository.getMangaById(SyncPairLibrary.scenarioEntryId(graph))
        return linkedMapOf(
            "shownTitle" to word(manga.displayTitle),
            "shownDescription" to word(manga.displayDescription),
            "sourceTitle" to word(manga.title),
            "sourceDescription" to word(manga.description),
            "shownAuthor" to word(manga.displayAuthor),
            "shownArtist" to word(manga.displayArtist),
            "shownStatus" to manga.displayStatus,
            "shownGenres" to word(manga.displayGenre?.joinToString(",")),
            "edited" to manga.detailsOverrides.any,
        )
    }

    private fun word(text: String?): String = text?.replace(" ", "_") ?: "none"
}
