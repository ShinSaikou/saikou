package app.fixture.libraryupdate

import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// The frozen parameters of ruling R66 that live in the extension. tools/bench/update_fixture.py
// holds the same values for the host side, and tools/test_update_fixture.py reads them out of this
// file, so the two cannot drift apart without a failing test.
const val SOURCE_COUNT = 20
const val HOST_SUFFIX = "update-fixture.test"
const val SERVER_PORT = 18794
const val RATE_LIMIT_PERMITS = 5
const val RATE_LIMIT_PERIOD_SECONDS = 1L

/** The emulator's alias for the host's loopback interface, where the fixture server listens. */
private val SERVER_ADDRESS = byteArrayOf(10, 0, 2, 2)

/**
 * Twenty sources through one factory, so one install and one trust step per app give both hosts
 * twenty source ids. Upstream v0.20.4 groups its update by source id, so these are twenty groups to
 * its semaphore exactly as twenty packages would be.
 */
class UpdateFixtureFactory : SourceFactory {
    override fun createSources(): List<Source> = (1..SOURCE_COUNT).map { UpdateFixtureSource(it) }
}

/**
 * One fixture source. Its host name is its own (`sNN.update-fixture.test`), so anything in a host
 * that keys on the host sees twenty hosts, and the name resolves to the fixture server through this
 * source's own client rather than through the device's resolver. The client also carries the rate
 * limit, through the host's RateLimitInterceptor, which is where a published extension sets it.
 */
class UpdateFixtureSource(number: Int) : HttpSource() {
    private val label = number.toString().padStart(2, '0')

    override val name: String = "Update Fixture $label"

    override val lang: String = "en"

    override val supportsLatest: Boolean = false

    override val baseUrl: String = "http://s$label.$HOST_SUFFIX:$SERVER_PORT"

    override val client: OkHttpClient = network.client.newBuilder()
        .dns(FixtureDns)
        .rateLimit(RATE_LIMIT_PERMITS, RATE_LIMIT_PERIOD_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun popularMangaRequest(page: Int): Request = get("$baseUrl/popular?page=$page")

    override fun popularMangaParse(response: Response): MangasPage = entries(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        get("$baseUrl/popular?page=$page&q=${URLEncoder.encode(query, "UTF-8")}")

    override fun searchMangaParse(response: Response): MangasPage = entries(response)

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()

    override fun mangaDetailsRequest(manga: SManga): Request = get(baseUrl + manga.url)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body.string())
        return SManga.create().apply {
            url = json.getString("url")
            title = json.getString("title")
            description = json.getString("description")
            status = SManga.ONGOING
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request = get("$baseUrl${manga.url}/chapters")

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = JSONObject(response.body.string()).getJSONArray("chapters")
        return (0 until chapters.length()).map { i ->
            val json = chapters.getJSONObject(i)
            SChapter.create().apply {
                url = json.getString("url")
                name = json.getString("name")
                chapter_number = json.getDouble("number").toFloat()
                date_upload = json.getLong("uploaded")
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = get("$baseUrl${chapter.url}/pages")

    override fun pageListParse(response: Response): List<Page> {
        val pages = JSONObject(response.body.string()).getJSONArray("pages")
        return (0 until pages.length()).map { i -> Page(i, "", pages.getString(i), null) }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun entries(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        val entries = json.getJSONArray("entries")
        val mangas = (0 until entries.length()).map { i ->
            val entry = entries.getJSONObject(i)
            SManga.create().apply {
                url = entry.getString("url")
                title = entry.getString("title")
            }
        }
        return MangasPage(mangas, json.getBoolean("hasNextPage"))
    }

    private fun get(url: String): Request = Request.Builder().url(url).headers(headers).build()
}

/** Resolves every fixture host name to the host's loopback and refuses every other name. */
private object FixtureDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (!hostname.endsWith(".$HOST_SUFFIX")) throw UnknownHostException(hostname)
        return listOf(InetAddress.getByAddress(hostname, SERVER_ADDRESS))
    }
}
