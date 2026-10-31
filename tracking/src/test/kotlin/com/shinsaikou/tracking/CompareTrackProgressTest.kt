package com.shinsaikou.tracking

import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.json
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.statusBody
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * The comparison of "Compare with MyAnimeList" (phase 4 unit TC, the design's tests 11 to 20): the
 * app's number, the groups of the design's table 6.2 with their choices, every row starting on
 * "Don't change", the warnings, the queue, the library filter, the confirmations of entries the
 * list lacks, and a chapter read that fails.
 */
class CompareTrackProgressTest {

    @TempDir
    lateinit var cacheDir: File

    private val library = FakeLibrary()
    private val preferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(preferences, preferences::write)
    private val trackRepository = FakeTrackRepository()
    private val compare = CompareTrackProgress(
        library.getFavorites,
        trackRepository,
        library.chapterRepository,
        library.historyRepository,
        store,
    )
    private val tracker = FakeTracker(TRACKER, valRereadingStatus = REREADING)

    /** The link of [mangaId] to the service's entry [remoteId], its manga added to the library if new. */
    private fun link(mangaId: Long, remoteId: Long = 1000 + mangaId): CompareLink {
        val manga = library.manga[mangaId] ?: library.addManga(mangaId)
        return CompareLink(manga, track(id = mangaId, trackerId = TRACKER, mangaId = mangaId).copy(remoteId = remoteId))
    }

    private fun listed(chapters: Int, count: Long = 0, status: Long = MyAnimeList.READING, remoteId: Long = 0) =
        RemoteAnswer.Listed(ListEntry(remoteId, chapters, count, status))

    /** [CompareRules.entry] for [mangaId] over the library's chapters and history. */
    private fun entry(mangaId: Long, remote: RemoteAnswer, waiting: DelayedTrackingStore.DelayedTrackingItem? = null): ComparedEntry {
        val chapters = library.chapters.filter { it.mangaId == mangaId }
        val history = library.history.map { it.chapterId }.toSet()
        return CompareRules.entry(link(mangaId), remote, chapters, waiting, history, REREADING)
    }

    @Test
    fun `the app's number is the highest read recognised chapter rounded down, and none read marks from chapter 0`() {
        library.addChapters(1, 1..44, readThrough = 44.0)
        library.addChapter(1, 45.0)
        library.addChapter(1, 45.5, read = true)
        library.addChapter(1, 46.0)
        library.addChapter(1, -1.0, read = true)

        CompareRules.numbers(library.chapters.filter { it.mangaId == 1L }) shouldBe CompareRules.Numbers(maxRead = 45.5, app = 45, last = 46.0)

        library.addChapters(2, 0..5)
        val none = entry(2, listed(3))
        none.maxRead shouldBe -1.0
        none.app shouldBe 0
        none.section shouldBe CompareSection.REMOTE_AHEAD
        none.toMark.map { it.number } shouldBe listOf(0.0, 1.0, 2.0, 3.0)
    }

    @Test
    fun `each row of the table lands in its group with its choices, and every row starts on don't change`() {
        val dont = CompareChoice.DONT_CHANGE
        // 1: no live chapter.
        val noChapters = entry(1, listed(0))
        val noChaptersAhead = entry(2, listed(5, count = 12))
        // 2: chapters, none recognised.
        library.addChapter(3, -1.0, read = true)
        library.addChapter(4, -1.0)
        val noNumbers = entry(3, listed(0))
        val noNumbersAhead = entry(4, listed(5, count = 12))
        // 3, 4, 5: absent from the list.
        library.addChapters(5, 1..10, readThrough = 4.0)
        library.addChapters(6, 1..10, readThrough = 4.0)
        library.addChapters(7, 1..10, readThrough = 4.0)
        val offList = entry(5, RemoteAnswer.OffList(totalChapters = 0))
        val gone = entry(6, RemoteAnswer.Gone)
        val failed = entry(7, RemoteAnswer.Failed(HttpException(400)))
        // 6 to 9.
        library.addChapters(8, 1..12, readThrough = 10.0)
        library.addChapters(9, 1..12, readThrough = 12.0)
        library.addChapters(10, 1..12, readThrough = 10.0)
        library.addChapters(11, 1..10, readThrough = 10.0)
        val agree = entry(8, listed(10, count = 0))
        val appAhead = entry(9, listed(10, count = 0))
        val remoteAhead = entry(10, listed(12, count = 20))
        val nothingToMark = entry(11, listed(12, count = 20))

        listOf(noChapters, noNumbers, agree).forEach { it.section.shouldBeNull() }
        noChaptersAhead.section shouldBe CompareSection.CANT_COMPARE
        noChaptersAhead.cantCompare shouldBe CantCompareReason.NO_CHAPTERS
        noNumbersAhead.section shouldBe CompareSection.CANT_COMPARE
        noNumbersAhead.cantCompare shouldBe CantCompareReason.NO_NUMBERS
        failed.section shouldBe CompareSection.CANT_COMPARE
        failed.cantCompare shouldBe CantCompareReason.COULD_NOT_CHECK
        listOf(noChaptersAhead, noNumbersAhead, failed).forEach {
            it.choices.shouldBeEmpty()
            it.defaultChoice.shouldBeNull()
        }

        offList.section shouldBe CompareSection.NOT_ON_LIST
        offList.choices shouldBe listOf(dont, CompareChoice.ADD_BACK, CompareChoice.REMOVE_TRACKING)
        gone.section shouldBe CompareSection.NOT_ON_LIST
        gone.choices shouldBe listOf(dont, CompareChoice.REMOVE_TRACKING)
        appAhead.section shouldBe CompareSection.APP_AHEAD
        appAhead.target shouldBe 12
        appAhead.choices shouldBe listOf(dont, CompareChoice.UPDATE_REMOTE)
        remoteAhead.section shouldBe CompareSection.REMOTE_AHEAD
        remoteAhead.choices shouldBe listOf(dont, CompareChoice.MARK_READ, CompareChoice.SET_REMOTE_BACK)
        nothingToMark.section shouldBe CompareSection.REMOTE_AHEAD
        nothingToMark.choices shouldBe listOf(dont, CompareChoice.SET_REMOTE_BACK)
        listOf(offList, gone, appAhead, remoteAhead, nothingToMark).forEach { it.defaultChoice shouldBe dont }
    }

    @Test
    fun `an app past the service's count sends the count with the numbering warning, and agrees once the service is there`() {
        library.addChapters(1, 1..125, readThrough = 125.0)

        val ahead = entry(1, listed(100, count = 120))
        val there = entry(1, listed(120, count = 120))

        ahead.section shouldBe CompareSection.APP_AHEAD
        ahead.app shouldBe 125
        ahead.target shouldBe 120
        ahead.warnings shouldBe setOf(CompareWarning.NUMBERING, CompareWarning.NOT_OPENED)
        there.section.shouldBeNull()
    }

    @Test
    fun `a reread, a count the chapters do not reach and a top chapter never opened in the reader are warned`() {
        library.addChapters(1, 1..12, readThrough = 12.0)
        library.addHistory(library.chapters.single { it.mangaId == 1L && it.chapterNumber == 12.0 }.id)
        library.addChapters(2, 1..60, readThrough = 50.0)
        library.addChapters(3, 1..12, readThrough = 12.0)

        entry(1, listed(3, status = REREADING)).warnings shouldBe setOf(CompareWarning.REREAD)
        entry(2, listed(55, count = 120)).warnings shouldBe setOf(CompareWarning.NUMBERING)
        entry(3, listed(3)).warnings shouldBe setOf(CompareWarning.NOT_OPENED)
        entry(1, listed(3)).warnings.shouldBeEmpty()
    }

    @Test
    fun `nothing below the app's own highest read is marked, so a volume restart or a service past the last chapter offers set back only`() {
        // Volume 1's 1 to 20 read, volume 2's 1 to 20 unread.
        library.addChapters(1, 1..20, readThrough = 20.0)
        library.addChapters(1, 1..20)
        library.addChapters(2, 1..100, readThrough = 100.0)

        val restart = entry(1, listed(30))
        val past = entry(2, listed(120, count = 0))

        restart.section shouldBe CompareSection.REMOTE_AHEAD
        restart.toMark.shouldBeEmpty()
        restart.choices shouldBe listOf(CompareChoice.DONT_CHANGE, CompareChoice.SET_REMOTE_BACK)
        restart.app shouldBe 20
        past.section shouldBe CompareSection.REMOTE_AHEAD
        past.toMark.shouldBeEmpty()
        past.choices shouldBe listOf(CompareChoice.DONT_CHANGE, CompareChoice.SET_REMOTE_BACK)
        // The note's range: from the app's number plus one to the service's.
        past.app + 1 shouldBe 101
        past.listed?.chaptersRead shouldBe 120
    }

    @Test
    fun `only library entries tracked on the compared tracker are compared`() {
        runBlocking {
            library.addManga(1)
            library.addManga(2, favorite = false)
            trackRepository.insert(track(id = 1, trackerId = TRACKER, mangaId = 1))
            trackRepository.insert(track(id = 2, trackerId = TRACKER, mangaId = 2))
            trackRepository.insert(track(id = 3, trackerId = TRACKER + 1, mangaId = 1))

            compare.links(TRACKER).map { it.manga.id to it.track.id } shouldBe listOf(1L to 1L)
        }
    }

    @Test
    fun `an entry with no chapters or none numbered agrees with a service at 0 and cannot be compared otherwise`() {
        library.addChapter(3, -1.0)
        library.addChapter(4, -1.0)

        entry(1, listed(0)).section.shouldBeNull()
        entry(2, listed(1)).cantCompare shouldBe CantCompareReason.NO_CHAPTERS
        entry(3, listed(0)).section.shouldBeNull()
        entry(4, listed(1)).cantCompare shouldBe CantCompareReason.NO_NUMBERS
    }

    @Test
    fun `an update waiting above the service's number adds keep, and one at or below it adds nothing`() {
        runBlocking {
            library.addChapters(1, 1..12, readThrough = 12.0)
            library.addChapters(2, 1..12, readThrough = 12.0)
            val links = listOf(link(1), link(2))
            store.add(1, TRACKER, 1001, 12.0)
            store.add(2, TRACKER, 1002, 10.0)
            val remote = RemoteRead(mapOf(1L to listed(10, remoteId = 1001), 2L to listed(10, remoteId = 1002)))

            val comparison = compare.compare(tracker, links, remote)

            val (waiting, stale) = comparison.rows
            waiting.section shouldBe CompareSection.APP_AHEAD
            waiting.waitingUpdate shouldBe DelayedTrackingStore.DelayedTrackingItem(1, TRACKER, 1001, 12.0)
            waiting.choices shouldBe listOf(CompareChoice.DONT_CHANGE, CompareChoice.UPDATE_REMOTE, CompareChoice.KEEP_REMOTE)
            stale.section shouldBe CompareSection.APP_AHEAD
            stale.waitingUpdate.shouldBeNull()
            stale.choices shouldBe listOf(CompareChoice.DONT_CHANGE, CompareChoice.UPDATE_REMOTE)
        }
    }

    @Test
    fun `each entry the list lacks is read on its own before it is called missing, and a server error stops the comparison`() {
        MyAnimeListFixture(cacheDir).use { fixture ->
            runBlocking {
                fixture.signedIn()
                val paths = Collections.synchronizedList(mutableListOf<String>())
                val entry1002 = AtomicReference(json(200, entryBody(1002, statusBody(status = "reading", chapters = 4))))
                fixture.server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        paths += "${request.method} ${request.url.encodedPath}"
                        return when (request.url.encodedPath) {
                            "/v2/users/@me/mangalist" ->
                                json(200, """{"data":[{"node":{"id":1001,"title":"One","num_chapters":0},"list_status":${statusBody("reading", 4)}}],"paging":{}}""")
                            "/v2/manga/1002" -> entry1002.get()
                            "/v2/manga/1003" -> json(200, entryBody(1003, listStatus = null))
                            "/v2/manga/1004" -> json(404, """{"error":"not_found"}""")
                            "/v2/manga/1005" -> json(400, """{"error":"bad_request"}""")
                            else -> json(500, "{}")
                        }
                    }
                }
                (1L..5L).forEach { library.addChapters(it, 1..10, readThrough = 4.0) }
                val links = (1L..5L).map { link(it) }

                val remote = compare.readRemote(fixture.tracker, links)
                val rows = compare.compare(fixture.tracker, links, remote).entries.associateBy { it.manga.id }

                paths shouldBe listOf(
                    "GET /v2/users/@me/mangalist",
                    "GET /v2/manga/1002",
                    "GET /v2/manga/1003",
                    "GET /v2/manga/1004",
                    "GET /v2/manga/1005",
                )
                rows.getValue(1).section.shouldBeNull()
                rows.getValue(2).section.shouldBeNull()
                rows.getValue(2).listed?.chaptersRead shouldBe 4
                rows.getValue(3).section shouldBe CompareSection.NOT_ON_LIST
                rows.getValue(3).choices shouldBe listOf(CompareChoice.DONT_CHANGE, CompareChoice.ADD_BACK, CompareChoice.REMOVE_TRACKING)
                rows.getValue(4).section shouldBe CompareSection.NOT_ON_LIST
                rows.getValue(4).choices shouldBe listOf(CompareChoice.DONT_CHANGE, CompareChoice.REMOVE_TRACKING)
                rows.getValue(5).section shouldBe CompareSection.CANT_COMPARE
                rows.getValue(5).cantCompare shouldBe CantCompareReason.COULD_NOT_CHECK

                entry1002.set(json(503, """{"error":"unavailable"}"""))
                val stopped = shouldThrow<HttpException> { compare.readRemote(fixture.tracker, links) }
                stopped.code shouldBe 503
            }
        }
    }

    @Test
    fun `a chapter read that fails fails the comparison instead of reading as no chapters`() {
        runBlocking {
            library.chapterFailure = IllegalStateException("fixture: the database refused the read")
            val links = listOf(link(1))

            shouldThrow<IllegalStateException> {
                compare.compare(tracker, links, RemoteRead(mapOf(1L to listed(5, count = 12))))
            }
        }
    }

    /** `GET manga/{id}` with the list fields. */
    private fun entryBody(id: Long, listStatus: String?): String =
        """{"id":$id,"title":"Fixture Title $id","num_chapters":0""" + (listStatus?.let { ""","my_list_status":$it""" } ?: "") + "}"

    private companion object {
        const val TRACKER = 1L
        const val REREADING = MyAnimeList.REREADING
    }
}
