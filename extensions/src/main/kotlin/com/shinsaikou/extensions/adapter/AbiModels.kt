package com.shinsaikou.extensions.adapter

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaPage
import com.shinsaikou.source.model.MangaStatus
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import com.shinsaikou.source.model.UpdateStrategy as ContractUpdateStrategy
import eu.kanade.tachiyomi.source.model.UpdateStrategy as AbiUpdateStrategy

/**
 * Model mapping between the extension ABI and the source contract. Field for field in both
 * directions; a status code outside the ABI's 0..6 becomes [MangaStatus.UNKNOWN], the `-1f`
 * chapter-number sentinel survives, and `memo` is copied by reference because `JsonObject` is
 * immutable.
 */
internal fun SManga.toContract(): MangaInfo =
    // url and title are lateinit on SMangaImpl; abiCall turns an unset one into IllegalStateException.
    toContract(url = url, title = title)

/**
 * The details half of an update. Extensions commonly build it with `SManga.create()` and set
 * neither url nor title, since upstream never reads the url there and reads an unset title as ""
 * (`UpdateMangaFromRemote`). So an unset one becomes "" here, and [MangaInfo.mergeDetails] keeps
 * the entry's own.
 */
internal fun SManga.detailsToContract(): MangaInfo =
    toContract(url = orEmpty { url }, title = orEmpty { title })

private inline fun orEmpty(read: () -> String): String =
    try {
        read()
    } catch (_: UninitializedPropertyAccessException) {
        ""
    }

private fun SManga.toContract(url: String, title: String): MangaInfo = MangaInfo(
    url = url,
    title = title,
    thumbnailUrl = thumbnail_url,
    artist = artist,
    author = author,
    status = MangaStatus.fromCode(status),
    description = description,
    genre = genre,
    updateStrategy = update_strategy.toContract(),
    initialized = initialized,
    memo = memo,
)

internal fun MangaInfo.toAbi(): SManga = SManga.create().also {
    it.url = url
    it.title = title
    it.thumbnail_url = thumbnailUrl
    it.artist = artist
    it.author = author
    it.status = status.code
    it.description = description
    it.genre = genre
    it.update_strategy = updateStrategy.toAbi()
    it.initialized = initialized
    it.memo = memo
}

internal fun SChapter.toContract(): ChapterInfo = ChapterInfo(
    url = url,
    name = name,
    chapterNumber = chapter_number,
    scanlator = scanlator,
    dateUpload = date_upload,
    memo = memo,
)

internal fun ChapterInfo.toAbi(): SChapter = SChapter.create().also {
    it.url = url
    it.name = name
    it.chapter_number = chapterNumber
    it.scanlator = scanlator
    it.date_upload = dateUpload
    it.memo = memo
}

/**
 * [index] is the page's position in the list `getPageList` returned, never the value the extension
 * put in [Page.index]. The ABI promises nothing about that value, while `PageInfo.index` is
 * documented 0-based and `PageInfo.number` is `index + 1`, so a source numbering its pages from 1
 * made the contract's promise false and every display number off by one. A real catalog extension
 * does exactly that.
 *
 * Taking the index as a parameter rather than reading it off the receiver is the point: it is what
 * stops the pass-through being restored by someone who reads [Page.index] and assumes it means
 * position.
 *
 * Nothing an extension can observe changes. Upstream re-indexes at both of its page boundaries and
 * says why in the code: `HttpPageLoader.kt:79` ("Don't trust sources and use our own indexing")
 * and `Downloader.kt:359` ("Don't trust index from source"), both as of v0.20.4. Each builds a new
 * object, and since `ReaderPage` extends `Page` that re-indexed object is what the extension is
 * handed for the image fetch, so no extension has ever been given its own index back. Ours already
 * behaves the same: `ReaderPage.kt:32` builds the [PageInfo] passed to `Source.getImage` from its
 * own position index.
 *
 * [Page.uri] is deprecated in the ABI and never populated here.
 */
internal fun Page.toContract(index: Int): PageInfo = PageInfo(index = index, url = url, imageUrl = imageUrl)

internal fun MangasPage.toContract(): MangaPage =
    MangaPage(mangas = mangas.map { it.toContract() }, hasNextPage = hasNextPage)

internal fun AbiUpdateStrategy.toContract(): ContractUpdateStrategy = when (this) {
    AbiUpdateStrategy.ALWAYS_UPDATE -> ContractUpdateStrategy.ALWAYS_UPDATE
    AbiUpdateStrategy.ONLY_FETCH_ONCE -> ContractUpdateStrategy.ONLY_FETCH_ONCE
}

internal fun ContractUpdateStrategy.toAbi(): AbiUpdateStrategy = when (this) {
    ContractUpdateStrategy.ALWAYS_UPDATE -> AbiUpdateStrategy.ALWAYS_UPDATE
    ContractUpdateStrategy.ONLY_FETCH_ONCE -> AbiUpdateStrategy.ONLY_FETCH_ONCE
}
