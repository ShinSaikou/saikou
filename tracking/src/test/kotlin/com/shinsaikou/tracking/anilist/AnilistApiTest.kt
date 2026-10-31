package com.shinsaikou.tracking.anilist

import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerIds
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.ACCESS_1
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.DELETED_BODY
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.USER_ID
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.date
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.day
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.graphql
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.json
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.listBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.listEntry
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.media
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.savedBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.searchBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.track
import com.shinsaikou.tracking.inZone
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
 * AniList's GraphQL calls against a fake: what each sends (its query and variables, and the stored
 * token as the bearer) and how each answer, written as AniList writes it, is read into a search
 * result or a track row: formats and countries, staff, the description as text, fuzzy dates as days
 * on the device, the list's status, score, progress, privacy and list id.
 */
class AnilistApiTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { AnilistFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `search sends upstream's query with the trimmed text and reads every hit`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(
                json(
                    200,
                    searchBody(
                        media(description = "First line.<br><br>Second &amp; <i>last</i> line &quot;quoted&quot;.<br />"),
                        media(id = 1002, title = "Fixture Title Two", countryOfOrigin = "KR", chapters = null, averageScore = null),
                        media(id = 1003, title = "Fixture Title Three", format = "ONE_SHOT", status = null, description = null),
                    ),
                ),
            )

            val results = fixture.tracker.search("  fixture title  ")

            val (query, variables) = fixture.server.takeRequest().also {
                it.headers["Authorization"] shouldBe "Bearer $ACCESS_1"
                it.headers["Content-Type"].orEmpty() shouldContain "application/json"
            }.graphql()
            query shouldContain "media(search: \$query, type: MANGA, format_not_in: [NOVEL])"
            variables shouldBe buildJsonObject { put("query", "fixture title") }
            results shouldContainExactly listOf(
                TrackSearchResult(
                    trackerId = TrackerIds.ANILIST,
                    remoteId = 1001L,
                    title = "Fixture Title One",
                    trackingUrl = "https://anilist.co/manga/1001",
                    coverUrl = "https://img.example.invalid/1001.jpg",
                    summary = "First line.\n\nSecond & last line \"quoted\".",
                    publishingStatus = "RELEASING",
                    publishingType = "Manga",
                    startDate = "2020-04-15",
                    totalChapters = 40L,
                    authors = listOf("Fixture Author"),
                    artists = listOf("Fixture Author", "Fixture Artist"),
                    score = 81.0,
                ),
                TrackSearchResult(
                    trackerId = TrackerIds.ANILIST,
                    remoteId = 1002L,
                    title = "Fixture Title Two",
                    trackingUrl = "https://anilist.co/manga/1002",
                    coverUrl = "https://img.example.invalid/1002.jpg",
                    summary = "A fixture story.",
                    publishingStatus = "RELEASING",
                    publishingType = "Manhwa",
                    startDate = "2020-04-15",
                    totalChapters = 0L,
                    authors = listOf("Fixture Author"),
                    artists = listOf("Fixture Author", "Fixture Artist"),
                    score = -1.0,
                ),
                TrackSearchResult(
                    trackerId = TrackerIds.ANILIST,
                    remoteId = 1003L,
                    title = "Fixture Title Three",
                    trackingUrl = "https://anilist.co/manga/1003",
                    coverUrl = "https://img.example.invalid/1003.jpg",
                    summary = "",
                    publishingStatus = "",
                    publishingType = "ONE-SHOT",
                    startDate = "2020-04-15",
                    totalChapters = 40L,
                    authors = listOf("Fixture Author"),
                    artists = listOf("Fixture Author", "Fixture Artist"),
                    score = 81.0,
                ),
            )
        }
    }

    @Test
    fun `a country of origin of CN or TW reads as Manhua, and a start date with no day as none`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(
                json(
                    200,
                    searchBody(
                        media(countryOfOrigin = "CN", startDate = date(2021, 3, null)),
                        media(id = 1002, countryOfOrigin = "TW", startDate = date(null, null, null)),
                        media(id = 1003, countryOfOrigin = null),
                    ),
                ),
            )

            val results = fixture.tracker.search("fixture")

            results.map { it.publishingType } shouldContainExactly listOf("Manhua", "Manhua", "Manga")
            results.map { it.startDate } shouldContainExactly listOf("", "", "2020-04-15")
        }
    }

    @Test
    fun `id and a pasted address read one entry by id, and an id AniList lacks finds nothing`() {
        runBlocking {
            fixture.signedIn()
            for (query in listOf("id: 1001", " id:1001 ", "https://anilist.co/manga/1001/Fixture-Title-One/", "anilist.co/manga/1001")) {
                fixture.server.enqueue(json(200, searchBody(media())))

                fixture.tracker.search(query).map { it.remoteId } shouldContainExactly listOf(1001L)

                val (body, variables) = fixture.server.takeRequest().graphql()
                body shouldContain "media(id: \$manga_id, type: MANGA, format_not_in: [NOVEL])"
                variables shouldBe buildJsonObject { put("manga_id", 1001) }
            }

            fixture.server.enqueue(json(200, searchBody()))
            fixture.tracker.search("id:999").shouldBeEmpty()

            // Text after the prefix that is not a number is searched as it is.
            fixture.server.enqueue(json(200, searchBody()))
            fixture.tracker.search("id:fixture")
            fixture.server.takeRequest()
            fixture.server.takeRequest().graphql().second shouldBe buildJsonObject { put("query", "id:fixture") }
        }
    }

    @Test
    fun `a list entry is read over the row with its status, score, progress, dates, privacy, list id and count`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(
                json(
                    200,
                    listBody(
                        listEntry(
                            libraryId = 5009L,
                            status = "REPEATING",
                            scoreRaw = 85,
                            progress = 12,
                            private = true,
                            startedAt = date(2026, 1, 2),
                            completedAt = date(2026, 2, 28),
                        ),
                    ),
                ),
            )
            val row = track(libraryId = null, status = Anilist.PLAN_TO_READ)

            val refreshed = fixture.tracker.refresh(row)

            val (query, variables) = fixture.server.takeRequest().graphql()
            query shouldContain "mediaList(userId: \$id, type: MANGA, mediaId: \$manga_id)"
            query shouldContain "scoreRaw: score(format: POINT_100)"
            variables shouldBe buildJsonObject {
                put("id", USER_ID)
                put("manga_id", 1001)
            }
            refreshed shouldBe row.copy(
                title = "Fixture Title One",
                status = Anilist.REREADING,
                score = 85.0,
                lastChapterRead = 12.0,
                private = true,
                libraryId = 5009L,
                totalChapters = 40L,
                startDate = day(2026, 1, 2),
                finishDate = day(2026, 2, 28),
            )
        }
    }

    @Test
    fun `every list status reads as its own, and one this code does not know as Reading`() {
        val statuses = mapOf(
            "CURRENT" to Anilist.READING,
            "COMPLETED" to Anilist.COMPLETED,
            "PAUSED" to Anilist.ON_HOLD,
            "DROPPED" to Anilist.DROPPED,
            "PLANNING" to Anilist.PLAN_TO_READ,
            "REPEATING" to Anilist.REREADING,
            "SOMETHING_NEW" to Anilist.READING,
        )
        runBlocking {
            fixture.signedIn()
            statuses.forEach { (status, expected) ->
                fixture.server.enqueue(json(200, listBody(listEntry(status = status))))
                fixture.tracker.refresh(track()).status shouldBe expected
            }
        }
    }

    @Test
    fun `the update sends upstream's mutation with the list id, status, raw score, fuzzy dates and privacy`() {
        inZone("+09:00") {
            runBlocking {
                fixture.signedIn()
                fixture.server.enqueue(json(200, savedBody()))
                val row = track(
                    lastChapterRead = 7.5,
                    status = Anilist.ON_HOLD,
                    score = 85.0,
                    startDate = day(2026, 1, 2),
                    private = true,
                )

                fixture.tracker.update(row) shouldBe row

                val (query, variables) = fixture.server.takeRequest().graphql()
                query shouldContain "SaveMediaListEntry("
                query shouldContain "scoreRaw: \$score"
                variables shouldBe buildJsonObject {
                    put("listId", 5001)
                    put("progress", 7)
                    put("status", "PAUSED")
                    put("score", 85)
                    put("startedAt", buildJsonObject { put("year", 2026); put("month", 1); put("day", 2) })
                    put("completedAt", buildJsonObject { put("year", JsonNull); put("month", JsonNull); put("day", JsonNull) })
                    put("private", true)
                }
            }
        }
    }

    @Test
    fun `every status is sent as AniList names it`() {
        val statuses = mapOf(
            Anilist.READING to "CURRENT",
            Anilist.COMPLETED to "COMPLETED",
            Anilist.ON_HOLD to "PAUSED",
            Anilist.DROPPED to "DROPPED",
            Anilist.PLAN_TO_READ to "PLANNING",
            Anilist.REREADING to "REPEATING",
            0L to "CURRENT",
        )
        runBlocking {
            fixture.signedIn()
            statuses.forEach { (status, expected) ->
                fixture.server.enqueue(json(200, savedBody()))
                fixture.tracker.update(track(status = status))
                fixture.server.takeRequest().graphql().second["status"] shouldBe JsonPrimitive(expected)
            }
        }
    }

    @Test
    fun `a bind of an entry off the owner's list adds it with upstream's fields and keeps the new list id`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, listBody()))
            fixture.server.enqueue(json(200, savedBody(libraryId = 5077L, status = "PLANNING")))
            val row = track(libraryId = null, status = 0L, score = 50.0, private = true)

            val bound = fixture.tracker.bind(row, hasReadChapters = false)

            fixture.server.takeRequest().graphql().first shouldContain "mediaList("
            val (query, variables) = fixture.server.takeRequest().graphql()
            query shouldContain "SaveMediaListEntry (mediaId: \$mangaId"
            variables shouldBe buildJsonObject {
                put("mangaId", 1001)
                put("progress", 0)
                put("status", "PLANNING")
                put("private", true)
            }
            bound shouldBe row.copy(status = Anilist.PLAN_TO_READ, score = 0.0, libraryId = 5077L)
        }
    }

    @Test
    fun `a bind of an entry on the owner's list takes the list's progress, keeps the sheet's privacy, and reads as Reading once chapters are read`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, listBody(listEntry(status = "PLANNING", progress = 3, scoreRaw = 60, private = true))))
            fixture.server.enqueue(json(200, savedBody()))
            val row = track(libraryId = null, status = 0L, private = false)

            val bound = fixture.tracker.bind(row, hasReadChapters = true)

            fixture.server.takeRequest()
            val variables = fixture.server.takeRequest().graphql().second
            variables["listId"] shouldBe JsonPrimitive(5001)
            variables["status"] shouldBe JsonPrimitive("CURRENT")
            variables["progress"] shouldBe JsonPrimitive(3)
            variables["score"] shouldBe JsonPrimitive(60)
            variables["private"] shouldBe JsonPrimitive(false)
            bound.libraryId shouldBe 5001L
            bound.status shouldBe Anilist.READING
            bound.private shouldBe false
            bound.startDate shouldBe day(2026, 1, 2)
        }
    }

    @Test
    fun `an update of a row with no list id reads it from the list, or adds the entry when the list lacks it`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, listBody(listEntry(libraryId = 5011L))))
            fixture.server.enqueue(json(200, savedBody()))

            fixture.tracker.update(track(libraryId = null)).libraryId shouldBe 5011L
            fixture.server.takeRequest()
            fixture.server.takeRequest().graphql().second["listId"] shouldBe JsonPrimitive(5011)

            fixture.server.enqueue(json(200, listBody()))
            fixture.server.enqueue(json(200, savedBody(libraryId = 5022L)))
            fixture.server.enqueue(json(200, savedBody()))

            fixture.tracker.update(track(libraryId = 0L, lastChapterRead = 2.0)).libraryId shouldBe 5022L
            fixture.server.takeRequest()
            fixture.server.takeRequest().graphql().second["mangaId"] shouldBe JsonPrimitive(1001)
            fixture.server.takeRequest().graphql().second["listId"] shouldBe JsonPrimitive(5022)
        }
    }

    @Test
    fun `delete removes the list entry by its id, reading the id when the row lacks it, and does nothing when the list lacks the entry`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(200, DELETED_BODY))
            fixture.tracker.delete(track(libraryId = 5001L))
            val (query, variables) = fixture.server.takeRequest().graphql()
            query shouldContain "DeleteMediaListEntry(id: \$listId)"
            variables shouldBe buildJsonObject { put("listId", 5001) }

            fixture.server.enqueue(json(200, listBody(listEntry(libraryId = 5033L))))
            fixture.server.enqueue(json(200, DELETED_BODY))
            fixture.tracker.delete(track(libraryId = null))
            fixture.server.takeRequest()
            fixture.server.takeRequest().graphql().second shouldBe buildJsonObject { put("listId", 5033) }

            fixture.server.enqueue(json(200, listBody()))
            fixture.tracker.delete(track(libraryId = null))
            fixture.server.takeRequest()
            fixture.server.requestCount shouldBe 4
        }
    }

    @Test
    fun `an answer outside 2xx is an HttpException with its status`() {
        runBlocking {
            fixture.signedIn()
            fixture.server.enqueue(json(404, """{"errors":[{"message":"Not Found.","status":404}],"data":{"Page":null}}"""))

            shouldThrow<HttpException> { fixture.tracker.refresh(track()) }.code shouldBe 404
        }
    }
}
