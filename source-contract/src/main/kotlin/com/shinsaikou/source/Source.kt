package com.shinsaikou.source

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A provider of manga. Implemented by the Local source directly and, for every loaded extension,
 * by the adapter in :extensions. Every method is a suspend function and may be called from any
 * dispatcher; implementations must move blocking work to Dispatchers.IO themselves. Implementations
 * must propagate CancellationException untouched.
 *
 * Identity rule: [id] is owned by the implementation. Nothing in the host derives it; see [SourceIds].
 */
interface Source {
    /** Stable identifier. For extension sources this is the extension's own id, unchanged. */
    val id: Long

    /** Display name, as reported by the source. */
    val name: String

    /** ISO 639-1 language code ("en"), "all" for multi-language sources, "other" or "" when unknown. */
    val lang: String

    /**
     * Fetches details for [manga]. The returned value replaces the fields the source knows about;
     * fields the source does not fill keep their input value (the adapter and the Local source
     * both copy the input first). The result has [MangaInfo.initialized] set to true.
     */
    suspend fun getMangaDetails(manga: MangaInfo): MangaInfo

    /** Fetches the chapter list for [manga], in the source's own order (usually newest first). */
    suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo>

    /** Fetches the page list for [chapter]. Pages carry [PageInfo.index] in reading order. */
    suspend fun getPageList(chapter: ChapterInfo): List<PageInfo>

    /**
     * Fetches the image bytes for [page]. If [PageInfo.imageUrl] is null the source resolves it
     * first (the extension ABI's getImageUrl step). Progress is reported through
     * [PageInfo.reportProgress] while the body streams. The caller owns the returned
     * [ImageResult] and must close it.
     */
    suspend fun getImage(page: PageInfo): ImageResult

    /**
     * Fetches the cover at [url] the way the source's site expects: through the source's own client
     * and with its request headers, most often a Referer that the image host checks before it serves
     * a cover. Returns null when the source has no network of its own (the default), and the caller
     * then fetches [url] itself. A response that is not a success throws, as [getImage] does. The
     * caller owns the returned [ImageResult] and must close it.
     */
    suspend fun getCoverImage(url: String): ImageResult? = null

    /**
     * Combined refresh used by the library update pass and the manga screen's refresh. The default
     * composes the two single calls concurrently; the extension adapter overrides it to make one
     * ABI call (getMangaUpdate) so sources that serve details and chapters from one page pay one
     * request. Either flag false means the corresponding field of the result is null.
     *
     * [chapters] are the entry's chapters as stored, in source order, the way upstream's
     * `UpdateMangaFromRemote` reads them: the extension ABI documents its `chapters` argument as the
     * entry's existing chapters, and an extension may answer with them unchanged, for a half it was
     * not asked to fetch or when it cannot read its page. Empty for an entry with none stored. The
     * default ignores them.
     */
    suspend fun getMangaUpdate(
        manga: MangaInfo,
        chapters: List<ChapterInfo>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): MangaUpdate = coroutineScope {
        val details = if (fetchDetails) async { getMangaDetails(manga) } else null
        val chapters = if (fetchChapters) async { getChapterList(manga) } else null
        MangaUpdate(manga = details?.await(), chapters = chapters?.await())
    }

    /** Absolute URL of the source's landing page, or null when the source has no web presence (Local). */
    val homeUrl: String? get() = null

    /**
     * The address the source's requests are built on (the extension's `baseUrl`), or null when the
     * source asks no website (Local). Unlike [homeUrl], which an extension may point anywhere, this
     * names the site the source loads. The library update gives every source whose base URL names
     * the same host one shared request budget, so it may throw: an extension can compute it from
     * its own preferences.
     */
    val baseUrl: String? get() = null

    /**
     * The headers the source's own requests carry (the extension's `headers`), keyed by lowercased
     * name, or empty when the source asks no website (Local). The in-app WebView loads a source's
     * page with them, its user agent above all, because a clearance earned under another agent is
     * refused on the source's next request. It may throw: an extension builds them itself.
     */
    val requestHeaders: Map<String, String> get() = emptyMap()

    /** Absolute URL to open [manga] in a browser, or null when not applicable. */
    fun getMangaUrl(manga: MangaInfo): String? = null

    /** Absolute URL to open [chapter] in a browser, or null when not applicable. */
    fun getChapterUrl(chapter: ChapterInfo): String? = null
}
