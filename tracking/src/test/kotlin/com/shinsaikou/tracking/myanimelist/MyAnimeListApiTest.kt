package com.shinsaikou.tracking.myanimelist

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.inZone
import com.shinsaikou.tracking.localMidnight
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ONE_MONTH
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.REFRESH_1
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.REFRESH_2
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.USER_BODY
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.form
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.json
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.statusBody
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.tokenBody
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.track
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.LogcatLogger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Instant

/**
 * MyAnimeList's calls against recorded response shapes (phase 3 unit T2): search with upstream's `id:`
 * and `my:` prefixes, its 64-character limit and its novel filter; the list page's pagination; bind
 * adding or adopting the list entry; update with upstream's status and date rules and its form; the
 * title waiting for approval; delete. Then the headers: no User-Agent of MyAnimeList's own, and no
 * token, code or verifier in any logged line with verbose logging on.
 *
 * The bodies follow API v2's shapes as upstream's DTOs read them, with fields the app ignores left in;
 * every title is a fixture's.
 */
class MyAnimeListApiTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { MyAnimeListFixture(cacheDir).apply { signedIn() } }

    @AfterEach
    fun close() {
        fixture.close()
    }

    private fun format(pattern: String, date: String): Long = SimpleDateFormat(pattern, Locale.US).parse(date)!!.time

    @Test
    fun `search sends upstream's query, truncated to 64 characters, and drops novels`() {
        runBlocking {
            fixture.server.enqueue(json(200, SEARCH_BODY))
            val query = "q".repeat(80)

            val results = fixture.tracker.search(query)

            val request = fixture.server.takeRequest()
            request.method shouldBe "GET"
            request.url.pathSegments shouldContainExactly listOf("v2", "manga")
            request.url.queryParameter("q") shouldBe "q".repeat(64)
            request.url.queryParameter("nsfw") shouldBe "true"
            request.url.queryParameter("fields") shouldBe SEARCH_FIELDS
            request.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            results shouldContainExactly listOf(FIXTURE_ONE)
        }
    }

    @Test
    fun `an id prefix reads the one entry, and a non-number falls back to a search`() {
        runBlocking {
            fixture.server.enqueue(json(200, MANGA_BODY))
            fixture.tracker.search("id:1001") shouldContainExactly listOf(FIXTURE_ONE)
            val details = fixture.server.takeRequest()
            details.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001")
            details.url.queryParameter("fields") shouldBe SEARCH_FIELDS

            fixture.server.enqueue(json(200, """{"data":[],"paging":{}}"""))
            fixture.tracker.search("id:one")
            fixture.server.takeRequest().url.queryParameter("q") shouldBe "id:one"
        }
    }

    /** E33: an id pasted with spaces around it, before or after the prefix, still reads the entry. */
    @Test
    fun `an id prefix with spaces around the id or the query reads the one entry`() {
        runBlocking {
            for (query in listOf("id: 1001", "id:1001 ", " id:\t1001\n")) {
                fixture.server.enqueue(json(200, MANGA_BODY))
                fixture.tracker.search(query) shouldContainExactly listOf(FIXTURE_ONE)
                fixture.server.takeRequest().url.pathSegments shouldContainExactly listOf("v2", "manga", "1001")
            }
        }
    }

    @Test
    fun `an entry's address on the website reads that one entry, and any other address is a title search`() {
        runBlocking {
            val addresses = listOf(
                "https://myanimelist.net/manga/1001/Fixture_Title_One",
                "myanimelist.net/manga/1001",
                " https://www.MyAnimeList.net/manga/1001?q=x ",
                "http://myanimelist.net/manga/1001/",
            )
            for (address in addresses) {
                fixture.server.enqueue(json(200, MANGA_BODY))
                fixture.tracker.search(address) shouldContainExactly listOf(FIXTURE_ONE)
                val details = fixture.server.takeRequest()
                details.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001")
                details.url.queryParameter("fields") shouldBe SEARCH_FIELDS
            }

            for (other in listOf("https://myanimelist.net/anime/1001", "https://tracker.example.invalid/manga/1001")) {
                fixture.server.enqueue(json(200, """{"data":[],"paging":{}}"""))
                fixture.tracker.search(other)
                fixture.server.takeRequest().url.queryParameter("q") shouldBe other
            }
        }
    }

    @Test
    fun `a my prefix walks the owner's list 250 at a time and matches titles`() {
        runBlocking {
            fixture.server.enqueue(json(200, listPage(next = true)))
            fixture.server.enqueue(json(200, listPage(next = false)))

            val results = fixture.tracker.search("my:title one")

            val first = fixture.server.takeRequest()
            first.url.pathSegments shouldContainExactly listOf("v2", "users", "@me", "mangalist")
            first.url.queryParameter("limit") shouldBe "250"
            first.url.queryParameter("offset") shouldBe null
            first.url.queryParameter("fields") shouldBe SEARCH_FIELDS
            fixture.server.takeRequest().url.queryParameter("offset") shouldBe "250"
            results shouldContainExactly listOf(FIXTURE_ONE, FIXTURE_ONE)
        }
    }

    @Test
    fun `bind adds an entry the owner's list lacks, reading with chapters read, else planned`() {
        runBlocking {
            for ((hasRead, status) in listOf(true to "reading", false to "plan_to_read")) {
                fixture.server.enqueue(json(200, """{"id":1001,"title":"Fixture Title One","num_chapters":40}"""))
                fixture.server.enqueue(json(200, statusBody(status = status, chapters = 0)))

                val bound = fixture.tracker.bind(track(lastChapterRead = 0.0), hasReadChapters = hasRead)

                val lookup = fixture.server.takeRequest()
                lookup.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001")
                lookup.url.queryParameter("fields") shouldBe "num_chapters,my_list_status{start_date,finish_date}"
                val put = fixture.server.takeRequest()
                put.method shouldBe "PUT"
                put.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001", "my_list_status")
                put.form() shouldBe mapOf(
                    "status" to status,
                    "is_rereading" to "false",
                    "score" to "0.0",
                    "num_chapters_read" to "0",
                    "start_date" to "",
                    "finish_date" to "",
                )
                bound.totalChapters shouldBe 40L
                bound.status shouldBe if (hasRead) MyAnimeList.READING else MyAnimeList.PLAN_TO_READ
            }
        }
    }

    @Test
    fun `bind adopts the owner's list entry, its chapters, score and dates, and moves it to reading`() {
        runBlocking {
            fixture.server.enqueue(
                json(
                    200,
                    """{"id":1001,"title":"Fixture Title One","num_chapters":40,"my_list_status":""" +
                        """{"status":"plan_to_read","score":8,"num_volumes_read":0,"num_chapters_read":12,""" +
                        """"is_rereading":false,"start_date":"2026-01-02","updated_at":"2026-09-01T10:00:00+00:00"}}""",
                ),
            )
            fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 12, score = 8, start = "2026-01-02")))

            val bound = fixture.tracker.bind(track(), hasReadChapters = true)

            fixture.server.takeRequest()
            fixture.server.takeRequest().form() shouldBe mapOf(
                "status" to "reading",
                "is_rereading" to "false",
                "score" to "8.0",
                "num_chapters_read" to "12",
                "start_date" to "2026-01-02",
                "finish_date" to "",
            )
            bound.lastChapterRead shouldBe 12.0
            bound.score shouldBe 8.0
            bound.totalChapters shouldBe 40L
            bound.startDate shouldBe format("yyyy-MM-dd", "2026-01-02")
        }
    }

    @Test
    fun `a read chapter update marks the first chapter started and the last one completed, from the clock`() {
        runBlocking {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(NOW_SECONDS * 1000)
            fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 1, start = today)))
            val started = fixture.tracker.update(track(lastChapterRead = 1.0, totalChapters = 40, status = MyAnimeList.PLAN_TO_READ), didReadChapter = true)
            fixture.server.takeRequest().form().let {
                it["status"] shouldBe "reading"
                it["num_chapters_read"] shouldBe "1"
                it["start_date"] shouldBe today
            }
            started.status shouldBe MyAnimeList.READING

            fixture.server.enqueue(json(200, statusBody(status = "completed", chapters = 40, finish = today)))
            val finished = fixture.tracker.update(track(lastChapterRead = 40.0, totalChapters = 40), didReadChapter = true)
            fixture.server.takeRequest().form().let {
                it["status"] shouldBe "completed"
                it["finish_date"] shouldBe today
            }
            finished.status shouldBe MyAnimeList.COMPLETED
            finished.finishDate shouldBe format("yyyy-MM-dd", today)

            // A reread stays a reread, sent as reading with is_rereading.
            fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 3, rereading = true)))
            val reread = fixture.tracker.update(track(lastChapterRead = 3.0, status = MyAnimeList.REREADING), didReadChapter = true)
            fixture.server.takeRequest().form().let {
                it["status"] shouldBe "reading"
                it["is_rereading"] shouldBe "true"
            }
            reread.status shouldBe MyAnimeList.REREADING
        }
    }

    /**
     * Any chapter read starts an entry that has no start date, not chapter 1 alone: a first read of
     * chapter 3, or a mark-read jump, is sent with today. A start date the entry has stays, chapter
     * 1 read again included. Today is the device's: 03:00 UTC is still the day before at -05:00.
     */
    @Test
    fun `a read chapter starts an entry with no start date, and keeps one it has, east and west of UTC`() {
        runBlocking {
            fixture.now = Instant.parse("2026-09-22T03:00:00Z")
            for ((offset, today) in listOf("+09:00" to "2026-09-22", "-05:00" to "2026-09-21")) {
                inZone(offset) {
                    fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 3, start = today)))
                    val started = fixture.tracker.update(
                        track(lastChapterRead = 3.0, totalChapters = 40, status = MyAnimeList.PLAN_TO_READ),
                        didReadChapter = true,
                    )
                    fixture.server.takeRequest().form()["start_date"] shouldBe today
                    started.startDate shouldBe localMidnight(today, offset)

                    fixture.server.enqueue(json(200, statusBody(status = "reading", chapters = 1, start = "2026-01-02")))
                    fixture.tracker.update(
                        track(lastChapterRead = 1.0, totalChapters = 40, startDate = localMidnight("2026-01-02", offset)),
                        didReadChapter = true,
                    )
                    fixture.server.takeRequest().form()["start_date"] shouldBe "2026-01-02"
                }
            }
        }
    }

    @Test
    fun `a title waiting for approval is its own failure, and any other refusal is its status`() {
        runBlocking {
            fixture.server.enqueue(json(400, """{"message":"Invalid content","error":"invalid_content"}"""))
            shouldThrow<MALTitleNotApproved> { fixture.tracker.update(track(lastChapterRead = 2.0)) }

            fixture.server.enqueue(json(404, """{"message":"","error":"not_found"}"""))
            shouldThrow<HttpException> { fixture.tracker.update(track(lastChapterRead = 2.0)) }.code shouldBe 404
        }
    }

    @Test
    fun `delete removes the list entry`() {
        runBlocking {
            fixture.server.enqueue(json(200, "[]"))

            fixture.tracker.delete(track(remoteId = 1001L))

            val request = fixture.server.takeRequest()
            request.method shouldBe "DELETE"
            request.url.pathSegments shouldContainExactly listOf("v2", "manga", "1001", "my_list_status")
            request.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
        }
    }

    @Test
    fun `MyAnimeList sets no User-Agent of its own, and no logged line names a token, code or verifier`() {
        val logged = mutableListOf<String>()
        val capture = object : LogcatLogger {
            override fun isLoggable(priority: LogPriority, tag: String): Boolean = true
            override fun log(priority: LogPriority, tag: String, message: String) {
                synchronized(logged) { logged += "$tag: $message" }
            }
        }
        if (!LogcatLogger.isInstalled) LogcatLogger.install()
        LogcatLogger.loggers += capture
        try {
            MyAnimeListFixture(cacheDir).use { local ->
                runBlocking {
                    val pkce = Pkce.generate()
                    local.server.enqueue(json(200, tokenBody(ACCESS_1, REFRESH_1, expiresIn = 3600)))
                    local.server.enqueue(json(200, USER_BODY))
                    local.tracker.completeSignIn("the-secret-code", "http://127.0.0.1:18796/callback", pkce)
                    // Two hours on, the token has expired: a refresh, then the search.
                    local.now = local.now.plus(kotlin.time.Duration.parse("2h"))
                    local.server.enqueue(json(200, tokenBody(ACCESS_2, REFRESH_2, expiresIn = ONE_MONTH)))
                    local.server.enqueue(json(200, SEARCH_BODY))
                    local.tracker.search("fixture")
                    // A refused update is logged by BaseTracker with its exception.
                    local.server.enqueue(json(500, "{}"))
                    runCatching { local.tracker.setRemoteLastChapterRead(track(), 3) }

                    val requests = List(local.server.requestCount) { local.server.takeRequest() }
                    val defaultUserAgent = local.networkPreferences.defaultUserAgent.get().trim()
                    // The API calls carry the shared client's user agent, the one every other call
                    // carries; the refresh, sent from inside the interceptor, carries OkHttp's own.
                    // None names the app.
                    requests.filter { it.url.encodedPath.startsWith("/v2") }.map { it.headers["User-Agent"] }.toSet() shouldBe
                        setOf(defaultUserAgent)
                    for (request in requests) {
                        val agent = request.headers["User-Agent"].orEmpty()
                        agent shouldNotContain "Saikou"
                        agent shouldNotContain "com.shinsaikou"
                    }

                    val lines = synchronized(logged) { logged.toList() }
                    lines.filter { "TrackerHttp" in it }.shouldNotBeEmpty()
                    for (line in lines) {
                        for (secret in listOf(ACCESS_1, ACCESS_2, REFRESH_1, REFRESH_2, "the-secret-code", pkce.verifier)) {
                            line shouldNotContain secret
                        }
                    }
                }
            }
        } finally {
            LogcatLogger.loggers -= capture
        }
    }

    @Test
    fun `the tracker's labels and scores are upstream's`() {
        fixture.tracker.name shouldBe "MyAnimeList"
        fixture.tracker.id shouldBe TrackerIds.MYANIMELIST
        fixture.tracker.supportsReadingDates shouldBe true
        fixture.tracker.getStatusList() shouldContainExactly listOf(1L, 2L, 3L, 4L, 6L, 7L)
        fixture.tracker.getStatusList().forEach { (fixture.tracker.getStatus(it) != null) shouldBe true }
        fixture.tracker.getStatus(5L) shouldBe null
        fixture.tracker.getScoreList() shouldContainExactly (0..10).map(Int::toString)
        fixture.tracker.displayScore(track(score = 7.0)) shouldBe "7"
        fixture.tracker.getReadingStatus() shouldBe MyAnimeList.READING
        fixture.tracker.getRereadingStatus() shouldBe MyAnimeList.REREADING
        fixture.tracker.getCompletionStatus() shouldBe MyAnimeList.COMPLETED
    }

    private companion object {
        const val SEARCH_FIELDS =
            "id,title,synopsis,num_chapters,mean,main_picture,status,media_type,start_date,authors{first_name,last_name}"

        const val NODE_ONE = """{"id":1001,"title":"Fixture Title One","main_picture":{"medium":"https://cdn.myanimelist.net/images/manga/1/1001.jpg","large":"https://cdn.myanimelist.net/images/manga/1/1001l.jpg"},""" +
            """"synopsis":"A fixture.","mean":8.1,"num_chapters":40,"status":"currently_publishing","media_type":"manga",""" +
            """"start_date":"2020-04-01","authors":[{"node":{"id":1,"first_name":"Writer","last_name":"One"},"role":"Story"},""" +
            """{"node":{"id":2,"first_name":"Artist","last_name":"Two"},"role":"Art"},{"node":{"id":3,"first_name":"","last_name":"Both"},"role":"Story & Art"}]}"""

        const val NODE_NOVEL = """{"id":1002,"title":"Fixture Title Novel","synopsis":"","num_chapters":0,"status":"finished","media_type":"light_novel","authors":[]}"""

        const val SEARCH_BODY = """{"data":[{"node":$NODE_ONE},{"node":$NODE_NOVEL}],"paging":{"next":"https://api.myanimelist.net/v2/manga?offset=2"}}"""

        const val MANGA_BODY = NODE_ONE

        fun listPage(next: Boolean): String {
            val paging = if (next) """{"next":"https://api.myanimelist.net/v2/users/@me/mangalist?offset=250"}""" else "{}"
            return """{"data":[{"node":$NODE_ONE,"list_status":{"status":"reading"}},{"node":$NODE_NOVEL}],"paging":$paging}"""
        }

        val FIXTURE_ONE = TrackSearchResult(
            trackerId = TrackerIds.MYANIMELIST,
            remoteId = 1001L,
            title = "Fixture Title One",
            trackingUrl = "https://myanimelist.net/manga/1001",
            coverUrl = "https://cdn.myanimelist.net/images/manga/1/1001l.jpg",
            summary = "A fixture.",
            publishingStatus = "currently publishing",
            publishingType = "manga",
            startDate = "2020-04-01",
            totalChapters = 40L,
            authors = listOf("Writer One", "Both"),
            artists = listOf("Artist Two"),
            score = 8.1,
        )
    }
}
