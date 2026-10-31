package com.shinsaikou.extensions.adapter

import androidx.preference.PreferenceScreen
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.FilterList
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.MangaPage
import com.shinsaikou.source.model.MangaUpdate
import com.shinsaikou.source.model.PageInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import com.shinsaikou.source.CatalogueSource as ContractCatalogueSource
import com.shinsaikou.source.ConfigurableSource as ContractConfigurableSource
import com.shinsaikou.source.UnmeteredSource as ContractUnmeteredSource
import eu.kanade.tachiyomi.source.ConfigurableSource as AbiConfigurableSource
import eu.kanade.tachiyomi.source.Source as AbiSource

/**
 * Wraps one extension source object as a [com.shinsaikou.source.CatalogueSource] for as long as the
 * extension stays loaded.
 *
 * Only the ABI's suspend members are ever called. For a lib-1.4 extension, which overrides only the
 * deprecated `fetchXxx(): Observable<...>` members, the vendored `CatalogueSource` and `HttpSource`
 * defaults route each suspend call to `fetchXxx(...).awaitSingle()`, so both ABI generations reach
 * this one code path. The adapter never calls a `fetch*` method itself.
 */
open class ExtensionSourceAdapter(
    internal val delegate: AbiSource,
    /** Package name of the extension that produced [delegate]; for logs and the settings screen. */
    val packageName: String,
    /** 1.4 or 1.6, from the extension manifest. Informational; the call path is the same for both. */
    val libVersion: Double,
) : ContractCatalogueSource {

    private val filterCache = AbiFilterCache()

    override val id: Long get() = delegate.id
    override val name: String get() = delegate.name
    override val lang: String get() = delegate.lang
    override val supportsLatest: Boolean get() = delegate.supportsLatest
    override val homeUrl: String? get() = (delegate as? HttpSource)?.getHomeUrl()
    override val baseUrl: String? get() = (delegate as? HttpSource)?.baseUrl

    // OkHttp's multimap lowercases the names; a repeated header keeps its first value, as upstream's
    // WebView does.
    override val requestHeaders: Map<String, String>
        get() = (delegate as? HttpSource)?.headers?.toMultimap()
            ?.mapValues { (_, values) -> values.firstOrNull().orEmpty() }
            .orEmpty()

    override suspend fun getPopularManga(page: Int): MangaPage = abiCall {
        delegate.getPopularManga(page).toContract()
    }

    override suspend fun getLatestUpdates(page: Int): MangaPage = abiCall {
        delegate.getLatestUpdates(page).toContract()
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangaPage {
        val abiFilters = filters.toAbi(delegate, filterCache)
        return abiCall { delegate.getSearchManga(page, query, abiFilters).toContract() }
    }

    /**
     * Not suspend, so it may run on the main thread: the ABI contract is that an extension only
     * builds objects here. A throwing extension costs an empty filter tree, not a crash.
     */
    override fun getFilterList(): FilterList = runCatching {
        val abiFilters = delegate.getFilterList()
        filterCache.lastAbiFilters = abiFilters
        abiFilters.toContract()
    }.getOrElse {
        logcat(LogPriority.WARN, it) { "getFilterList failed for source $id" }
        FilterList.EMPTY
    }

    /**
     * The single calls hand the extension no stored chapters, since they are given none. The
     * library update and the manga screen's refresh, which have the entry's chapters, call
     * [getMangaUpdate] instead.
     */
    override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = abiCall {
        val update = delegate.getMangaUpdate(manga.toAbi(), emptyList(), fetchDetails = true, fetchChapters = false)
        manga.mergeDetails(update.manga.detailsToContract()).copy(initialized = true)
    }

    override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = abiCall {
        val update = delegate.getMangaUpdate(manga.toAbi(), emptyList(), fetchDetails = false, fetchChapters = true)
        update.chapters.map { it.toContract() }
    }

    /**
     * One ABI call for both halves. The ABI's own default composes the two deprecated Observables
     * inside a `supervisorScope`, and a lib-1.6 extension may override it with a single request, so
     * the combined method serves both with one code path.
     *
     * [chapters] go to the extension as its `chapters` argument, the entry's existing chapters. The
     * ABI default and several catalog extensions return that list as the chapters of an update
     * that did not fetch them, which is why a half that was not asked for is dropped here. A few
     * return it whatever was asked when their page cannot be read, so that update finds the stored
     * chapters unchanged, as in upstream, where an empty list used to end it as no chapters found.
     */
    override suspend fun getMangaUpdate(
        manga: MangaInfo,
        chapters: List<ChapterInfo>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): MangaUpdate = abiCall {
        val update = delegate.getMangaUpdate(manga.toAbi(), chapters.map { it.toAbi() }, fetchDetails, fetchChapters)
        MangaUpdate(
            manga = if (fetchDetails) {
                manga.mergeDetails(update.manga.detailsToContract()).copy(initialized = true)
            } else {
                null
            },
            chapters = if (fetchChapters) update.chapters.map { it.toContract() } else null,
        )
    }

    override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = abiCall {
        // Reading order is list order. The source's own Page.index is not trusted and not carried;
        // see toContract, which takes the position so this cannot quietly go back to a pass-through.
        delegate.getPageList(chapter.toAbi()).mapIndexed { index, page -> page.toContract(index) }
    }

    override suspend fun getImage(page: PageInfo): ImageResult = abiCall {
        val http = delegate as? HttpSource
            ?: throw UnsupportedOperationException("$name has no image pipeline")
        val abiPage = BridgedPage(page, page.imageUrl)
        if (abiPage.imageUrl == null) {
            abiPage.imageUrl = http.getImageUrl(abiPage)
        }
        val response = http.getImage(abiPage)
        val body = response.body
        ImageResult.ofSource(
            source = body.source(),
            contentType = response.header("Content-Type"),
            contentLength = body.contentLength(),
            imageUrl = abiPage.imageUrl,
            onClose = response::close,
        )
    }

    /**
     * Upstream's cover fetcher asks the source for its client and headers; this is that request. The
     * headers carry the Referer that hotlink-protected image hosts check (they answer 403 without
     * it), and the client carries the source's own interceptors.
     */
    override suspend fun getCoverImage(url: String): ImageResult? {
        val http = delegate as? HttpSource ?: return null
        return abiCall {
            val response = http.client.newCall(GET(url, http.headers)).awaitSuccess()
            val body = response.body
            ImageResult.ofSource(
                source = body.source(),
                contentType = response.header("Content-Type"),
                contentLength = body.contentLength(),
                imageUrl = url,
                onClose = response::close,
            )
        }
    }

    /** Builds a Request and reads its URL; no I/O, so it is safe on the main thread. */
    override fun getMangaUrl(manga: MangaInfo): String? = (delegate as? HttpSource)?.getMangaUrl(manga.toAbi())

    override fun getChapterUrl(chapter: ChapterInfo): String? = (delegate as? HttpSource)?.getChapterUrl(chapter.toAbi())

    override fun toString(): String = delegate.toString()
}

/** [delegate] also implements the ABI's `ConfigurableSource`. */
class ConfigurableExtensionSourceAdapter(
    delegate: AbiSource,
    packageName: String,
    libVersion: Double,
) : ExtensionSourceAdapter(delegate, packageName, libVersion), ContractConfigurableSource {
    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        (delegate as AbiConfigurableSource).setupPreferenceScreen(screen)
}

/** [delegate] also implements the ABI's `UnmeteredSource`. */
class UnmeteredExtensionSourceAdapter(
    delegate: AbiSource,
    packageName: String,
    libVersion: Double,
) : ExtensionSourceAdapter(delegate, packageName, libVersion), ContractUnmeteredSource

/** [delegate] implements both ABI markers. */
class ConfigurableUnmeteredExtensionSourceAdapter(
    delegate: AbiSource,
    packageName: String,
    libVersion: Double,
) : ExtensionSourceAdapter(delegate, packageName, libVersion), ContractConfigurableSource, ContractUnmeteredSource {
    override fun setupPreferenceScreen(screen: PreferenceScreen) =
        (delegate as AbiConfigurableSource).setupPreferenceScreen(screen)
}
