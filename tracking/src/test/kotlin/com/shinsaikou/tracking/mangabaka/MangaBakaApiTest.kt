package com.shinsaikou.tracking.mangabaka

import com.shinsaikou.tracking.TrackDates
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.inZone
import com.shinsaikou.tracking.localMidnight
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.CREATED_BODY
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.NOT_FOUND_BODY
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.NOW_SECONDS
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.WRITTEN_BODY
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.day
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.entryBody
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.json
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.jsonBody
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.searchBody
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.series
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.seriesBody
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.track
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * MangaBaka's API v1 calls against a fake: what each sends (its path, its query or JSON body, and
 * the stored token as the bearer where the call needs one) and how each answer, written as MangaBaka
 * writes it, is read into a search result or a track row: the best title, the cover, the rating
 * rounded, the chapter count from text, and dates as days on the device.
 */
class MangaBakaApiTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { MangaBakaFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    private val first = TrackSearchResult(
        trackerId = TrackerIds.MANGABAKA,
        remoteId = 1001L,
        title = "Fixture Title One",
        trackingUrl = "https://mangabaka.org/manga/1001/Fixture-Title-One",
        coverUrl = "https://img.example.invalid/1001.jpg",
        summary = "A fixture story.",
        publishingStatus = "releasing",
        publishingType = "Manga",
        startDate = "2020-04-15",
        totalChapters = 40L,
        authors = listOf("Fixture Author"),
        artists = listOf("Fixture Artist"),
        score = 81.23,
    )

    @Test
    fun `search asks for 50 series that are not novels, carries no token, and reads every hit`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(
                json(
                    200,
                    searchBody(
                        series(),
                        series(
                            id = 1002,
                            titles = """[{"language":"ko","traits":["native"],"title":"Fixture Korean","is_primary":null}]""",
                            totalChapters = null,
                            rating = null,
                            type = "manhwa",
                            canonicalUrl = null,
                        ),
                        series(id = 1003, titles = "null", totalChapters = "12.5"),
                    ),
                ),
            )

            val results = fixture.tracker.search("  fixture title  ")

            val request = fixture.server.takeRequest()
            request.method shouldBe "GET"
            request.url.encodedPath shouldBe "/v1/series/search"
            request.url.queryParameter("q") shouldBe "fixture title"
            request.url.queryParameter("type_not") shouldBe "novel"
            request.url.queryParameter("limit") shouldBe "50"
            request.headers["Authorization"] shouldBe null
            results shouldContainExactly listOf(
                first,
                first.copy(
                    remoteId = 1002L,
                    title = "Fixture Korean",
                    trackingUrl = "https://mangabaka.org/manga/1002",
                    coverUrl = "https://img.example.invalid/1002.jpg",
                    publishingType = "Manhwa",
                    totalChapters = 0L,
                    score = -1.0,
                ),
                first.copy(
                    remoteId = 1003L,
                    title = "Fixture Legacy Title",
                    trackingUrl = "https://mangabaka.org/manga/1003/Fixture-Title-One",
                    coverUrl = "https://img.example.invalid/1003.jpg",
                    totalChapters = 12L,
                ),
            )
        }
    }

    @Test
    fun `the title is the best one by language, primary, official, then native`() {
        runBlocking {
            val titles = """[{"language":"ja","traits":["native"],"title":"Native JA","is_primary":true},""" +
                """{"language":"ja-Latn","traits":["romanized"],"title":"Romaji","is_primary":false},""" +
                """{"language":"en","traits":["unofficial"],"title":"Fan EN","is_primary":false},""" +
                """{"language":"en","traits":["official"],"title":"Official EN","is_primary":false}]"""
            val noEnglish = """[{"language":"ja","traits":["native"],"title":"Native JA","is_primary":false},""" +
                """{"language":"ja-Latn","traits":["romanized"],"title":"Romaji","is_primary":true}]"""
            fixture.server.enqueue(json(200, searchBody(series(titles = titles), series(id = 1002, titles = noEnglish))))

            fixture.tracker.search("fixture").map { it.title } shouldContainExactly listOf("Official EN", "Romaji")
        }
    }

    @Test
    fun `id and a series' address on the website read that one series, and a missing one is no result`() {
        runBlocking {
            for (query in listOf("id: 1001", "https://mangabaka.org/manga/1001/Fixture-Title-One", "mangabaka.org/1001")) {
                fixture.server.enqueue(json(200, seriesBody()))

                fixture.tracker.search(query) shouldContainExactly listOf(first)

                fixture.server.takeRequest().url.encodedPath shouldBe "/v1/series/1001"
            }
            fixture.server.enqueue(json(404, NOT_FOUND_BODY))

            fixture.tracker.search("id:99999").shouldBeEmpty()
        }
    }

    @Test
    fun `binding an entry the library lacks adds it as planned, or as reading once a chapter is read, with no score`() {
        for ((hasRead, state) in listOf(false to "plan_to_read", true to "reading")) {
            MangaBakaFixture(cacheDir).use { local ->
                runBlocking {
                    local.signedIn()
                    local.server.enqueue(json(404, NOT_FOUND_BODY))
                    local.server.enqueue(json(201, CREATED_BODY))

                    val bound = local.tracker.bind(track(status = 0, score = 40.0, private = true), hasReadChapters = hasRead)

                    local.server.takeRequest().url.encodedPath shouldBe "/v1/my/library/1001"
                    val added = local.server.takeRequest()
                    added.method shouldBe "POST"
                    added.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
                    added.jsonBody() shouldBe buildJsonObject {
                        put("is_private", true)
                        put("state", state)
                    }
                    bound.status shouldBe if (hasRead) MangaBaka.READING else MangaBaka.PLAN_TO_READ
                    bound.score shouldBe 0.0
                }
            }
        }
    }

    @Test
    fun `binding an entry already in the library takes its progress, score, dates and title, keeps the sheet's privacy, and writes it back`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, entryBody(state = "paused", progress = "7", rating = "70", isPrivate = true)))
            fixture.server.enqueue(json(200, seriesBody()))
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            val bound = fixture.tracker.bind(track(status = 0, private = false), hasReadChapters = true)

            bound shouldBe track().copy(
                title = "Fixture Title One",
                totalChapters = 40L,
                status = MangaBaka.READING,
                lastChapterRead = 7.0,
                score = 70.0,
                startDate = day(2026, 1, 2),
                private = false,
            )
            fixture.server.takeRequest()
            fixture.server.takeRequest()
            val written = fixture.server.takeRequest()
            written.method shouldBe "PUT"
            written.url.encodedPath shouldBe "/v1/my/library/1001"
            written.jsonBody() shouldBe buildJsonObject {
                put("state", "reading")
                put("is_private", false)
                put("progress_chapter", 7.0)
                put("rating", 70)
                put("start_date", "2026-01-02")
                put("finish_date", JsonNull)
            }
        }
    }

    @Test
    fun `a rereading or completed entry keeps its status when bound with chapters read`() {
        for (state in listOf("rereading" to MangaBaka.REREADING, "completed" to MangaBaka.COMPLETED)) {
            MangaBakaFixture(cacheDir).use { local ->
                runBlocking {
                    local.signedIn()
                    local.server.enqueue(json(200, entryBody(state = state.first)))
                    local.server.enqueue(json(200, seriesBody()))
                    local.server.enqueue(json(200, WRITTEN_BODY))

                    local.tracker.bind(track(status = 0), hasReadChapters = true).status shouldBe state.second
                }
            }
        }
    }

    @Test
    fun `an update writes every field, a cleared one as null`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            val row = track(status = MangaBaka.CONSIDERING, lastChapterRead = 0.0, score = 0.0, private = true)
            fixture.tracker.update(row) shouldBe row

            fixture.server.takeRequest().jsonBody() shouldBe buildJsonObject {
                put("state", "considering")
                put("is_private", true)
                put("progress_chapter", JsonNull)
                put("rating", JsonNull)
                put("start_date", JsonNull)
                put("finish_date", JsonNull)
            }
        }
    }

    @Test
    fun `a chapter read starts a planned entry today, and the last chapter completes it today`() {
        runBlocking {
            val today = TrackDates.startOfDay(NOW_SECONDS * 1000)
            fixture.signedIn()
            repeat(2) { fixture.server.enqueue(json(200, WRITTEN_BODY)) }

            val started = fixture.tracker.update(
                track(status = MangaBaka.PLAN_TO_READ, lastChapterRead = 3.0, totalChapters = 40),
                didReadChapter = true,
            )
            val finished = fixture.tracker.update(
                started.copy(lastChapterRead = 40.0),
                didReadChapter = true,
            )

            started.status shouldBe MangaBaka.READING
            started.startDate shouldBe today
            finished.status shouldBe MangaBaka.COMPLETED
            finished.finishDate shouldBe today
            fixture.server.takeRequest().jsonBody()["start_date"] shouldBe JsonPrimitive(MangaBakaApi.formatDate(today))
            fixture.server.takeRequest().jsonBody()["state"] shouldBe JsonPrimitive("completed")
        }
    }

    @Test
    fun `an update to an entry removed on the website adds it back`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(404, NOT_FOUND_BODY))
            fixture.server.enqueue(json(201, CREATED_BODY))

            fixture.tracker.update(track(lastChapterRead = 5.0))

            fixture.server.takeRequest().method shouldBe "PUT"
            val added = fixture.server.takeRequest()
            added.method shouldBe "POST"
            added.jsonBody()["progress_chapter"] shouldBe JsonPrimitive(5.0)
        }
    }

    @Test
    fun `an update MangaBaka refuses otherwise throws its status`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(400, """{"status":400,"message":"Validation error"}"""))

            shouldThrow<HttpException> { fixture.tracker.update(track()) }.code shouldBe 400
            fixture.server.requestCount shouldBe 1
        }
    }

    @Test
    fun `delete removes the entry, and one already gone counts as removed`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, WRITTEN_BODY))
            fixture.server.enqueue(json(404, NOT_FOUND_BODY))

            fixture.tracker.delete(track())
            fixture.tracker.delete(track())

            fixture.server.takeRequest().method shouldBe "DELETE"
            fixture.server.takeRequest().url.encodedPath shouldBe "/v1/my/library/1001"
        }
    }

    @Test
    fun `dates are the calendar day on the device, east or west of UTC`() {
        for (offset in listOf("+09:00", "-05:00")) {
            inZone(offset) {
                val stored = localMidnight("2026-03-04", offset)
                MangaBakaApi.formatDate(stored) shouldBe "2026-03-04"
                MangaBakaApi.parseDate("2026-03-04T00:00:00.000Z") shouldBe stored
                MangaBakaApi.parseDate("2026-03-04") shouldBe stored
            }
        }
        MangaBakaApi.formatDate(0L) shouldBe null
        MangaBakaApi.parseDate(null) shouldBe null
        MangaBakaApi.parseDate("not a date") shouldBe null
    }
}
