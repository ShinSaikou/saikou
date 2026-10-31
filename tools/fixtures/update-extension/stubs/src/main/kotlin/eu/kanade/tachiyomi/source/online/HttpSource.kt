// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Suppress("UNUSED_PARAMETER")
abstract class HttpSource : CatalogueSource {
    protected val network: NetworkHelper get() = throw RuntimeException("Stub!")
    abstract val baseUrl: String
    override val id: Long get() = throw RuntimeException("Stub!")
    val headers: Headers get() = throw RuntimeException("Stub!")
    open val client: OkHttpClient get() = throw RuntimeException("Stub!")

    protected open fun popularMangaRequest(page: Int): Request = throw RuntimeException("Stub!")
    protected open fun popularMangaParse(response: Response): MangasPage = throw RuntimeException("Stub!")
    protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        throw RuntimeException("Stub!")
    protected open fun searchMangaParse(response: Response): MangasPage = throw RuntimeException("Stub!")
    protected open fun latestUpdatesRequest(page: Int): Request = throw RuntimeException("Stub!")
    protected open fun latestUpdatesParse(response: Response): MangasPage = throw RuntimeException("Stub!")
    open fun mangaDetailsRequest(manga: SManga): Request = throw RuntimeException("Stub!")
    protected open fun mangaDetailsParse(response: Response): SManga = throw RuntimeException("Stub!")
    protected open fun chapterListRequest(manga: SManga): Request = throw RuntimeException("Stub!")
    protected open fun chapterListParse(response: Response): List<SChapter> = throw RuntimeException("Stub!")
    protected open fun pageListRequest(chapter: SChapter): Request = throw RuntimeException("Stub!")
    protected open fun pageListParse(response: Response): List<Page> = throw RuntimeException("Stub!")
    protected open fun imageUrlParse(response: Response): String = throw RuntimeException("Stub!")
}
