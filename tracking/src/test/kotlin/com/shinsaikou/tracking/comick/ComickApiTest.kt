package com.shinsaikou.tracking.comick

import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.comick.ComickFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.comick.ComickFixture.Companion.HID
import com.shinsaikou.tracking.comick.ComickFixture.Companion.LIBRARY_PATH
import com.shinsaikou.tracking.comick.ComickFixture.Companion.NOT_FOUND_BODY
import com.shinsaikou.tracking.comick.ComickFixture.Companion.REMOTE_ID
import com.shinsaikou.tracking.comick.ComickFixture.Companion.WRITTEN_BODY
import com.shinsaikou.tracking.comick.ComickFixture.Companion.entryBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.json
import com.shinsaikou.tracking.comick.ComickFixture.Companion.jsonBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.searchBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.title
import com.shinsaikou.tracking.comick.ComickFixture.Companion.titleBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.track
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Comick's integrations API calls against a fake: what each sends (its path under
 * `/integrations/v1/`, its query or JSON body, and the stored token as the bearer) and how each
 * answer, written as Comick writes it, is read into a search result or a track row. Remote ids are
 * Comick's HIDs decoded to a Long and encoded back on every library call.
 */
class ComickApiTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { ComickFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    private val first = TrackSearchResult(
        trackerId = TrackerIds.COMICK,
        remoteId = REMOTE_ID,
        title = "Fixture Title One",
        trackingUrl = "https://comick.dev/comic/fixture-title-one",
        coverUrl = "https://img.example.invalid/$HID.jpg",
        summary = "A fixture story.",
        publishingStatus = "Ongoing",
        publishingType = "Manga",
        startDate = "2020",
        totalChapters = 0L,
        authors = emptyList(),
        artists = emptyList(),
        score = 8.42,
    )

    @Test
    fun `a HID and its remote id round trip, at both ends of the 48-bit range`() {
        HID.asHidToLong() shouldBe REMOTE_ID
        REMOTE_ID.toHid() shouldBe HID
        for (hid in listOf("AAAAAAAA", "________", "Ab-_09Yz", HID)) {
            hid.asHidToLong().toHid() shouldBe hid
        }
        "AAAAAAAA".asHidToLong() shouldBe 0L
        "________".asHidToLong() shouldBe (1L shl 48) - 1
        "Ab-_09Yz".asHidToLong() shouldBe 1_923_068_712_499L
        for (id in listOf(0L, 1L, 255L, 1L shl 40, (1L shl 48) - 1)) {
            id.toHid().asHidToLong() shouldBe id
        }
    }

    @Test
    fun `a string that is not a HID, and a remote id outside 48 bits, are refused`() {
        for (notHid in listOf("", "short", "toolongHID", "ab+/cdef", "abc de f")) {
            notHid.hidToLongOrNull() shouldBe null
        }
        shouldThrow<IllegalArgumentException> { "short".asHidToLong() }
        shouldThrow<IllegalArgumentException> { (-1L).toHid() }
        shouldThrow<IllegalArgumentException> { (1L shl 48).toHid() }
    }

    @Test
    fun `search asks for 20 manga with the token, cuts the query to 100 characters, and reads every hit`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(
                json(
                    200,
                    searchBody(
                        title(),
                        title(hid = "Ab-_09Yz", title = "Fixture Korean", status = 4, country = "kr", rating = null),
                        title(hid = "AAAAAAAA", title = "", status = 9, country = "cn"),
                    ),
                ),
            )

            val results = fixture.tracker.search("  " + "f".repeat(120) + "  ")

            val request = fixture.server.takeRequest()
            request.method shouldBe "GET"
            request.url.encodedPath shouldBe "/integrations/v1/titles"
            request.url.queryParameter("q") shouldBe "f".repeat(100)
            request.url.queryParameter("media_type") shouldBe "manga"
            request.url.queryParameter("limit") shouldBe "20"
            request.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            request.headers["Accept"] shouldBe "application/json; charset=utf-8"
            results shouldContainExactly listOf(
                first,
                first.copy(
                    remoteId = 1_923_068_712_499L,
                    title = "Fixture Korean",
                    coverUrl = "https://img.example.invalid/Ab-_09Yz.jpg",
                    publishingStatus = "Hiatus",
                    publishingType = "Manhwa",
                    score = -1.0,
                ),
                // A title with none falls back to its HID, and a status this code does not know is none.
                first.copy(
                    remoteId = 0L,
                    title = "AAAAAAAA",
                    coverUrl = "https://img.example.invalid/AAAAAAAA.jpg",
                    publishingStatus = "",
                    publishingType = "Manhua",
                ),
            )
        }
    }

    @Test
    fun `a hit whose HID does not decode is skipped, not the whole search`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, searchBody(title(hid = "not-a-hid"), title())))

            fixture.tracker.search("fixture") shouldContainExactly listOf(first)
        }
    }

    @Test
    fun `id reads that one title, and a missing one, a blank one or an answer that does not decode is no result`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, titleBody()))

            fixture.tracker.search(" id: $HID ") shouldContainExactly listOf(first)

            val lookup = fixture.server.takeRequest()
            lookup.url.encodedPath shouldBe "/integrations/v1/titles/$HID"
            lookup.headers["Authorization"] shouldBe "Bearer $ACCESS_1"

            fixture.server.enqueue(json(404, NOT_FOUND_BODY))
            fixture.tracker.search("id:zzzzzzzz").shouldBeEmpty()

            fixture.server.enqueue(json(200, titleBody(title(hid = "nine-chars"))))
            fixture.tracker.search("id:nine-chars").shouldBeEmpty()

            fixture.tracker.search("id:  ").shouldBeEmpty()
            fixture.server.requestCount shouldBe 3
        }
    }

    @Test
    fun `binding an entry the library lacks adds it as planned, or as reading once a chapter is read, with no score`() {
        for ((hasRead, status) in listOf(false to 5, true to 1)) {
            ComickFixture(cacheDir).use { local ->
                runBlocking {
                    local.signedIn()
                    local.server.enqueue(json(404, NOT_FOUND_BODY))
                    local.server.enqueue(json(200, WRITTEN_BODY))

                    val bound = local.tracker.bind(track(status = 0, score = 4.0), hasReadChapters = hasRead)

                    local.server.takeRequest().url.encodedPath shouldBe LIBRARY_PATH
                    val added = local.server.takeRequest()
                    added.method shouldBe "PUT"
                    added.url.encodedPath shouldBe LIBRARY_PATH
                    added.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
                    added.jsonBody() shouldBe buildJsonObject {
                        put("status", status)
                        put("progress", JsonNull)
                    }
                    bound.status shouldBe if (hasRead) Comick.READING else Comick.PLAN_TO_READ
                    bound.score shouldBe 0.0
                }
            }
        }
    }

    @Test
    fun `binding an entry already in the library takes its status, progress, score and title, and writes it back`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, entryBody(status = 3, progress = "\"7\"", rating = "7")))
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            val bound = fixture.tracker.bind(track(status = 0), hasReadChapters = true)

            bound shouldBe track().copy(
                title = "Fixture Title One",
                status = Comick.ON_HOLD,
                lastChapterRead = 7.0,
                score = 7.0,
            )
            fixture.server.takeRequest()
            val written = fixture.server.takeRequest()
            written.method shouldBe "PATCH"
            written.url.encodedPath shouldBe LIBRARY_PATH
            written.jsonBody() shouldBe buildJsonObject {
                put("rating", 7)
                put("status", 3)
                putJsonObject("progress") { put("number", "7") }
            }
        }
    }

    @Test
    fun `an update writes the status, the progress with at most two decimals, and a cleared rating as null`() {
        runBlocking {
            fixture.signedIn()
            repeat(2) { fixture.server.enqueue(json(200, WRITTEN_BODY)) }

            val row = track(status = Comick.DROPPED, lastChapterRead = 12.505, score = 0.0)
            fixture.tracker.update(row) shouldBe row
            fixture.tracker.update(track(status = Comick.COMPLETED, lastChapterRead = 0.0, score = 10.0))

            fixture.server.takeRequest().jsonBody() shouldBe buildJsonObject {
                put("rating", JsonNull)
                put("status", 4)
                putJsonObject("progress") { put("number", "12.51") }
            }
            fixture.server.takeRequest().jsonBody() shouldBe buildJsonObject {
                put("rating", 10)
                put("status", 2)
                putJsonObject("progress") { put("number", "0") }
            }
        }
    }

    @Test
    fun `a chapter read makes a planned or paused entry reading, and leaves a completed one completed`() {
        runBlocking {
            fixture.signedIn()
            repeat(4) { fixture.server.enqueue(json(200, WRITTEN_BODY)) }

            fixture.tracker.update(track(status = Comick.PLAN_TO_READ, lastChapterRead = 3.0), didReadChapter = true)
                .status shouldBe Comick.READING
            fixture.tracker.update(track(status = Comick.ON_HOLD, lastChapterRead = 3.0), didReadChapter = true)
                .status shouldBe Comick.READING
            fixture.tracker.update(track(status = Comick.COMPLETED, lastChapterRead = 3.0), didReadChapter = true)
                .status shouldBe Comick.COMPLETED
            // Comick has no dates: nothing stamps one.
            fixture.tracker.update(track(status = Comick.PLAN_TO_READ, lastChapterRead = 1.0), didReadChapter = true)
                .startDate shouldBe 0L
        }
    }

    @Test
    fun `an update to an entry removed on the website adds it back`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(404, NOT_FOUND_BODY))
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            fixture.tracker.update(track(lastChapterRead = 5.0))

            fixture.server.takeRequest().method shouldBe "PATCH"
            val added = fixture.server.takeRequest()
            added.method shouldBe "PUT"
            added.jsonBody() shouldBe buildJsonObject {
                put("status", 1)
                putJsonObject("progress") { put("number", "5") }
            }
        }
    }

    @Test
    fun `an update Comick refuses otherwise throws its status`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(400, """{"statusCode":400,"message":"Validation error"}"""))

            shouldThrow<HttpException> { fixture.tracker.update(track()) }.code shouldBe 400
            fixture.server.requestCount shouldBe 1
        }
    }

    @Test
    fun `delete removes the entry by its HID, and one already gone counts as removed`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, WRITTEN_BODY))
            fixture.server.enqueue(json(404, NOT_FOUND_BODY))

            fixture.tracker.delete(track())
            fixture.tracker.delete(track())

            val deleted = fixture.server.takeRequest()
            deleted.method shouldBe "DELETE"
            deleted.url.encodedPath shouldBe LIBRARY_PATH
            deleted.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
            fixture.server.takeRequest().method shouldBe "DELETE"
        }
    }

    @Test
    fun `a chapter goes to Comick with at most two decimals and none when whole`() {
        ComickApi.chapterNumber(0.0) shouldBe "0"
        ComickApi.chapterNumber(12.0) shouldBe "12"
        ComickApi.chapterNumber(12.5) shouldBe "12.5"
        ComickApi.chapterNumber(12.505) shouldBe "12.51"
        ComickApi.chapterNumber(100.0) shouldBe "100"
    }
}
