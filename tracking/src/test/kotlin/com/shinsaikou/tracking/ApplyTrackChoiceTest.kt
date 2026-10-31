package com.shinsaikou.tracking

import com.shinsaikou.tracking.DelayedTrackingStore.DelayedTrackingItem
import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ACCESS_2
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.ONE_MONTH
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.REFRESH_2
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.form
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.json
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.statusBody
import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.tokenBody
import com.shinsaikou.tracking.myanimelist.getStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.track.model.Track
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Apply of "Compare with MyAnimeList" (phase 4 unit TC, the design's tests 21 to 39), on the
 * fixture: the real MyAnimeList over the real client against a MockWebServer that keeps the owner's
 * list, with the app's library in [FakeLibrary] and a recording transaction runner. "Mark as read"
 * marks forward only among the chapters shown, `read` alone, in one transaction after one read of
 * the service; every send is a compare and set on the entry as the service holds it; each row holds
 * the sending lock; a cancel stops a read on the wire; and the stop rule of the design's 8.8.
 */
class ApplyTrackChoiceTest {

    @TempDir
    lateinit var cacheDir: File

    private val harnesses = mutableListOf<Harness>()

    @AfterEach
    fun close() {
        harnesses.forEach { it.close() }
    }

    /** An entry on the owner's list, as the fake service keeps it. */
    private data class Remote(
        val chapters: Int,
        val status: String = "reading",
        val score: Int = 0,
        val start: String? = null,
        val rereading: Boolean = false,
    )

    /** One app and one service: every test's world, and each case of test 39's. */
    private inner class Harness : AutoCloseable {
        val transactions = RecordingTransactionRunner()
        val calls: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())
        val rows = FakeTrackRepository()
        val repository = RecordingTrackRepository(rows, transactions, calls)
        val fixture = MyAnimeListFixture(cacheDir, repository = rows, trackRepository = repository)
        val library = FakeLibrary(transactions, calls)
        val store = DelayedTrackingStore(fixture.preferences, fixture.preferences::write)
        val apply = ApplyTrackChoice(
            repository,
            library.chapterRepository,
            library.mangaRepository,
            transactions,
            store,
            RemoveTrack(repository, store),
        )

        /** The owner's list by remote id; an id absent is off the list, one in [gone] answers 404. */
        val service = ConcurrentHashMap<Long, Remote>()
        val gone: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        /** Every request as "METHOD /path", and each PUT's form by remote id. */
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val puts: MutableList<Pair<Long, Map<String, String>>> = Collections.synchronizedList(mutableListOf())

        /** An answer that replaces the service's own for a request, when it gives one. */
        @Volatile var intercept: (RecordedRequest) -> MockResponse? = { null }

        /** The number a PUT's answer keeps, when the service keeps another one than it was sent. */
        @Volatile var keepAt: Int? = null

        init {
            fixture.signedIn()
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.url.encodedPath}"
                    intercept(request)?.let { return it }
                    val id = request.url.pathSegments.getOrNull(2)?.toLongOrNull() ?: return json(500, "{}")
                    return when (request.method) {
                        "GET" -> if (id in gone) json(404, """{"error":"not_found"}""") else json(200, entryBody(id, service[id]))
                        "PUT" -> {
                            val form = request.form()
                            puts += id to form
                            val remote = Remote(
                                chapters = keepAt ?: form.getValue("num_chapters_read").toInt(),
                                status = form.getValue("status"),
                                score = form.getValue("score").toDouble().toInt(),
                                start = form["start_date"]?.ifBlank { null },
                                rereading = form["is_rereading"] == "true",
                            )
                            service[id] = remote
                            json(200, remote.body())
                        }
                        else -> json(500, "{}")
                    }
                }
            }
            harnesses += this
        }

        /** Library entry [mangaId] with [chapters] read through [readThrough], bound to [remoteId]. */
        fun entry(
            mangaId: Long,
            chapters: Iterable<Number> = 1..50,
            readThrough: Double = 50.0,
            remote: Remote? = Remote(chapters = 45),
            stored: (Track) -> Track = { it },
            remoteId: Long = 1000 + mangaId,
        ) {
            library.addManga(mangaId)
            library.addChapters(mangaId, chapters, readThrough)
            val row = MyAnimeListFixture.track(id = mangaId, remoteId = remoteId, lastChapterRead = remote?.chapters?.toDouble() ?: 0.0)
                .copy(mangaId = mangaId)
            stored(row).let { rows.rows[it.id] = it }
            remote?.let { service[remoteId] = it }
        }

        /** What a comparison shows for [mangaId] now: the service's state and this device's queue. */
        fun shown(mangaId: Long): ComparedEntry {
            val track = rows.rows.values.single { it.mangaId == mangaId }
            val remote = service[track.remoteId]
            val answer = when {
                track.remoteId in gone -> RemoteAnswer.Gone
                remote == null -> RemoteAnswer.OffList(totalChapters = 0)
                else -> RemoteAnswer.Listed(ListEntry(track.remoteId, remote.chapters, 0, remote.statusId()))
            }
            val waiting = store.current(mangaId, track.trackerId)?.takeIf { it.remoteId == track.remoteId }
            return CompareRules.entry(
                CompareLink(library.manga.getValue(mangaId), track),
                answer,
                library.chapters.filter { it.mangaId == mangaId },
                waiting,
                emptySet(),
                MyAnimeList.REREADING,
            )
        }

        fun run(vararg rows: ApplyRow): ApplyRun = runBlocking { apply.run(fixture.tracker, rows.toList()) }

        fun stored(mangaId: Long): Track? = rows.rows.values.singleOrNull { it.mangaId == mangaId }

        fun readNumbers(mangaId: Long): List<Double> =
            library.chapters.filter { it.mangaId == mangaId && it.read }.map { it.chapterNumber }.sorted()

        override fun close() {
            fixture.close()
        }
    }

    private fun Remote.statusId(): Long = if (rereading) MyAnimeList.REREADING else getStatus(status)

    private fun Remote.body(): String = statusBody(status = status, chapters = chapters, score = score, rereading = rereading, start = start)

    private fun entryBody(id: Long, remote: Remote?): String =
        """{"id":$id,"title":"Fixture Title $id","num_chapters":0""" + (remote?.let { ""","my_list_status":${it.body()}""" } ?: "") + "}"

    private fun date(text: String): Long = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(text)!!.time

    @Test
    fun `mark as read marks every copy of the chapters above the app's highest read up to the service's, and nothing else`() {
        val h = Harness()
        h.library.addManga(1)
        (1..45).filter { it != 30 }.forEach { h.library.addChapter(1, it.toDouble(), read = true, scanlator = "A") }
        h.library.addChapter(1, 30.0, scanlator = "A")
        (46..50).forEach { number ->
            h.library.addChapter(1, number.toDouble(), scanlator = "A")
            h.library.addChapter(1, number.toDouble(), scanlator = "B")
        }
        h.library.addChapter(1, 50.5, scanlator = "A")
        h.rows.rows[1] = MyAnimeListFixture.track(id = 1, remoteId = 1001, lastChapterRead = 50.0).copy(mangaId = 1)
        h.service[1001] = Remote(chapters = 50)
        val entry = h.shown(1)
        entry.toMark.size shouldBe 10
        // Fetched after the comparison: never listed, so never marked.
        h.library.addChapter(1, 49.5, scanlator = "C")

        val run = h.run(ApplyRow(entry, CompareChoice.MARK_READ))

        run.results shouldBe listOf(ApplyResult.Done(marked = 10, listed = 10))
        h.library.chapters.filter { !it.read }.map { it.chapterNumber }.sorted() shouldBe listOf(30.0, 49.5, 50.5)
        h.puts.shouldBeEmpty()
    }

    @Test
    fun `mark as read writes read alone, so a page and a bookmark stay`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = Remote(chapters = 50))
        val page = h.library.chapters.single { it.mangaId == 1L && it.chapterNumber == 47.0 }
        h.library.chapters[h.library.chapters.indexOf(page)] = page.copy(lastPageRead = 4, bookmark = true)

        h.run(ApplyRow(h.shown(1), CompareChoice.MARK_READ)).results shouldBe listOf(ApplyResult.Done(5, 5))

        h.library.chapterUpdates.flatten().forEach { update ->
            update.read shouldBe true
            update.lastPageRead.shouldBeNull()
            update.bookmark.shouldBeNull()
        }
        h.library.chapter(page.id).lastPageRead shouldBe 4L
        h.library.chapter(page.id).bookmark shouldBe true
    }

    @Test
    fun `a hand edit on the service after the comparison marks nothing, stores what the service holds and reads changed since`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = Remote(chapters = 50))
        val entry = h.shown(1)
        h.service[1001] = Remote(chapters = 47)

        h.run(ApplyRow(entry, CompareChoice.MARK_READ)).results shouldBe listOf(ApplyResult.ChangedSince)

        h.library.chapterUpdates.shouldBeEmpty()
        h.stored(1)?.lastChapterRead shouldBe 47.0
        h.requests shouldBe listOf("GET /v2/manga/1001")
    }

    @Test
    fun `a rebind, an entry out of the library, or a chapter read since each mark nothing`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = Remote(chapters = 50))
        h.entry(2, readThrough = 45.0, remote = Remote(chapters = 50))
        h.entry(3, chapters = (1..45) + listOf(45.5, 45.7) + (46..50), readThrough = 45.5, remote = Remote(chapters = 50))
        val rebound = h.shown(1)
        val unfavourited = h.shown(2)
        val readSince = h.shown(3)
        rebound.maxRead shouldBe 45.0
        readSince.maxRead shouldBe 45.5

        runBlocking { h.rows.rebind(h.stored(1)!!.copy(remoteId = 2002)) }
        h.library.manga[2] = h.library.manga.getValue(2).copy(favorite = false)
        val chapter = h.library.chapters.single { it.mangaId == 3L && it.chapterNumber == 45.7 }
        h.library.chapters[h.library.chapters.indexOf(chapter)] = chapter.copy(read = true)

        val run = h.run(
            ApplyRow(rebound, CompareChoice.MARK_READ),
            ApplyRow(unfavourited, CompareChoice.MARK_READ),
            ApplyRow(readSince, CompareChoice.MARK_READ),
        )

        run.results shouldBe List(3) { ApplyResult.ChangedSince }
        h.library.chapterUpdates.shouldBeEmpty()
    }

    @Test
    fun `a listed chapter renumbered since stays unread and the row reports how many of the listed it marked`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = Remote(chapters = 50))
        val entry = h.shown(1)
        val renumbered = h.library.chapters.single { it.mangaId == 1L && it.chapterNumber == 47.0 }
        h.library.chapters[h.library.chapters.indexOf(renumbered)] = renumbered.copy(chapterNumber = 147.0)

        h.run(ApplyRow(entry, CompareChoice.MARK_READ)).results shouldBe listOf(ApplyResult.Done(marked = 4, listed = 5))

        h.library.chapter(renumbered.id).read shouldBe false
    }

    @Test
    fun `mark as read reads the pair, the entry and its chapters and writes everything in one transaction`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = Remote(chapters = 50))
        val entry = h.shown(1)
        h.calls.clear()

        h.run(ApplyRow(entry, CompareChoice.MARK_READ)).results shouldBe listOf(ApplyResult.Done(5, 5))

        h.calls.toList() shouldBe listOf(
            "read" to true,
            "manga" to true,
            "chapters" to true,
            "updateAll" to true,
            "insertIfBoundTo" to true,
        )
        h.transactions.opened shouldBe 1
    }

    @Test
    fun `mark as read stores the service's answer, not the copy the comparison read`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = Remote(chapters = 50, score = 9), stored = { it.copy(score = 7.0) })

        h.run(ApplyRow(h.shown(1), CompareChoice.MARK_READ)).results shouldBe listOf(ApplyResult.Done(5, 5))

        h.stored(1)?.score shouldBe 9.0
        h.stored(1)?.lastChapterRead shouldBe 50.0
    }

    @Test
    fun `no row sends or writes while another holder has the sending lock`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = Remote(chapters = 50))
        h.entry(2, readThrough = 50.0, remote = Remote(chapters = 45))
        val rows = listOf(ApplyRow(h.shown(1), CompareChoice.MARK_READ), ApplyRow(h.shown(2), CompareChoice.UPDATE_REMOTE))
        runBlocking {
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.Default) {
                h.store.whileSending {
                    held.complete(Unit)
                    release.await()
                }
            }
            held.await()
            val run = async(Dispatchers.Default) { h.apply.run(h.fixture.tracker, rows) }
            val wroteMeanwhile = withContext(Dispatchers.Default) {
                withTimeoutOrNull(LOCK_WAIT_MILLIS) {
                    while (h.requests.isEmpty() && h.library.chapterUpdates.isEmpty()) delay(10)
                }
            }
            wroteMeanwhile.shouldBeNull()

            release.complete(Unit)
            holder.join()
            run.await().results shouldBe listOf(ApplyResult.Done(5, 5), ApplyResult.Done())
        }
    }

    @Test
    fun `a send finds the service moved since the comparison, sends nothing, stores what it holds and reads changed since`() {
        val h = Harness()
        h.entry(1, readThrough = 50.0, remote = Remote(chapters = 45))
        val entry = h.shown(1)
        h.service[1001] = Remote(chapters = 47)

        h.run(ApplyRow(entry, CompareChoice.UPDATE_REMOTE)).results shouldBe listOf(ApplyResult.ChangedSince)

        h.puts.shouldBeEmpty()
        h.stored(1)?.lastChapterRead shouldBe 47.0
    }

    @Test
    fun `a service already at the number to send is done with no send, and the queued push goes`() {
        val h = Harness()
        h.entry(1, readThrough = 50.0, remote = Remote(chapters = 45))
        h.store.add(1, TRACKER, 1001, 50.0)
        val entry = h.shown(1)
        h.service[1001] = Remote(chapters = 50)

        h.run(ApplyRow(entry, CompareChoice.UPDATE_REMOTE)).results shouldBe listOf(ApplyResult.Done())

        h.puts.shouldBeEmpty()
        h.store.getItems().shouldBeEmpty()
        h.stored(1)?.lastChapterRead shouldBe 50.0
    }

    @Test
    fun `a send carries the entry as the service holds it now, with the service's dates only`() {
        val h = Harness()
        h.entry(
            1,
            readThrough = 50.0,
            remote = Remote(chapters = 45, status = "on_hold", score = 9),
            stored = { it.copy(status = MyAnimeList.READING, score = 7.0, startDate = date("2026-01-02")) },
        )

        h.run(ApplyRow(h.shown(1), CompareChoice.UPDATE_REMOTE)).results shouldBe listOf(ApplyResult.Done())

        h.puts.single() shouldBe (
            1001L to mapOf(
                "status" to "on_hold",
                "is_rereading" to "false",
                "score" to "9.0",
                "num_chapters_read" to "50",
                "start_date" to "",
                "finish_date" to "",
            )
            )
    }

    @Test
    fun `a service that keeps another number than the one sent is kept, and its answer stored`() {
        val h = Harness()
        h.entry(1, readThrough = 50.0, remote = Remote(chapters = 45))
        h.keepAt = 48

        h.run(ApplyRow(h.shown(1), CompareChoice.UPDATE_REMOTE)).results shouldBe listOf(ApplyResult.Kept(48))

        h.stored(1)?.lastChapterRead shouldBe 48.0
    }

    @Test
    fun `after a send only the queued push at or below the row's highest read for that entry goes`() {
        val h = Harness()
        val chapters = (1..45) + listOf(45.5)
        (1L..3L).forEach { h.entry(it, chapters = chapters, readThrough = 45.5, remote = Remote(chapters = 40)) }
        h.store.add(1, TRACKER, 1001, 45.5)
        h.store.add(2, TRACKER, 9999, 45.5)
        h.store.add(3, TRACKER, 1003, 46.0)
        val rows = (1L..3L).map { ApplyRow(h.shown(it), CompareChoice.UPDATE_REMOTE) }

        h.run(*rows.toTypedArray()).results shouldBe List(3) { ApplyResult.Done() }

        h.puts.map { (id, form) -> id to form["num_chapters_read"] } shouldBe listOf(1001L to "45", 1002L to "45", 1003L to "45")
        h.store.getItems() shouldBe listOf(
            DelayedTrackingItem(2, TRACKER, 9999, 45.5),
            DelayedTrackingItem(3, TRACKER, 1003, 46.0),
        )
    }

    @Test
    fun `a send whose answer never came stops the run, and a second apply finds the service there and is done with no send`() {
        val h = Harness()
        h.entry(1, readThrough = 50.0, remote = Remote(chapters = 45))
        h.entry(2, readThrough = 50.0, remote = Remote(chapters = 45))
        val rows = listOf(ApplyRow(h.shown(1), CompareChoice.UPDATE_REMOTE), ApplyRow(h.shown(2), CompareChoice.UPDATE_REMOTE))
        h.intercept = { request ->
            if (request.method == "PUT") {
                // The service took it; the answer is lost on the way back.
                h.service[1001] = Remote(chapters = 50)
                MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build()
            } else {
                null
            }
        }

        val first = runBlocking { h.apply.run(h.fixture.tracker, rows) }

        val failed = first.results[0].shouldBeInstanceOf<ApplyResult.Failed>()
        failed.failure.shouldBeInstanceOf<CompareFailure.Stop>().sendUnanswered shouldBe true
        first.results[1] shouldBe ApplyResult.NotReached
        first.stop shouldBe failed.failure

        h.intercept = { null }
        h.requests.clear()
        val second = runBlocking { h.apply.run(h.fixture.tracker, rows.take(1)) }

        second.results shouldBe listOf(ApplyResult.Done())
        h.requests shouldBe listOf("GET /v2/manga/1001")
    }

    @Test
    fun `a cancel while a row's read is on the wire cancels the read and sends nothing`() {
        val h = Harness()
        h.entry(1, readThrough = 50.0, remote = Remote(chapters = 45))
        val onTheWire = CountDownLatch(1)
        val answer = CountDownLatch(1)
        h.intercept = { request ->
            if (request.method == "GET") {
                onTheWire.countDown()
                answer.await(ANSWER_HOLD_SECONDS, TimeUnit.SECONDS)
            }
            null
        }
        val cancelled = MutableStateFlow(false)
        try {
            runBlocking {
                val run = async(Dispatchers.Default) {
                    h.apply.run(h.fixture.tracker, listOf(ApplyRow(h.shown(1), CompareChoice.UPDATE_REMOTE)), cancelled)
                }
                withContext(Dispatchers.IO) { onTheWire.await(ANSWER_HOLD_SECONDS, TimeUnit.SECONDS) } shouldBe true
                cancelled.value = true
                val ended = withContext(Dispatchers.Default) { withTimeoutOrNull(CANCEL_WAIT_MILLIS) { run.await() } }
                answer.countDown()
                val result = ended ?: run.await()

                result.cancelled shouldBe true
                result.results shouldBe listOf(ApplyResult.NotReached)
                h.puts.shouldBeEmpty()
                ended.shouldBeInstanceOf<ApplyRun>()
            }
        } finally {
            answer.countDown()
        }
    }

    @Test
    fun `add it back sends nothing once the entry is on the list again, and otherwise sends the stored status, score and dates`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = null)
        h.entry(
            2,
            readThrough = 45.0,
            remote = null,
            stored = { it.copy(status = MyAnimeList.ON_HOLD, score = 7.0, startDate = date("2026-01-02"), lastChapterRead = 40.0) },
        )
        val backSince = h.shown(1)
        val offList = h.shown(2)
        h.service[1001] = Remote(chapters = 45)

        val run = h.run(ApplyRow(backSince, CompareChoice.ADD_BACK), ApplyRow(offList, CompareChoice.ADD_BACK))

        run.results shouldBe listOf(ApplyResult.ChangedSince, ApplyResult.Done())
        h.puts.single() shouldBe (
            1002L to mapOf(
                "status" to "on_hold",
                "is_rereading" to "false",
                "score" to "7.0",
                "num_chapters_read" to "45",
                "start_date" to "2026-01-02",
                "finish_date" to "",
            )
            )
    }

    @Test
    fun `remove tracking deletes the pair's row only while the entry is still off the list and the pair still names it`() {
        val h = Harness()
        h.entry(1, readThrough = 45.0, remote = null)
        h.entry(2, readThrough = 45.0, remote = null)
        h.entry(3, readThrough = 45.0, remote = null)
        h.gone += 1002
        h.gone += 1003
        h.store.add(3, TRACKER, 1003, 46.0)
        val backOnList = h.shown(1)
        val rebound = h.shown(2)
        val gone = h.shown(3)
        h.service[1001] = Remote(chapters = 45)
        runBlocking { h.rows.rebind(h.stored(2)!!.copy(remoteId = 2002)) }

        val run = h.run(
            ApplyRow(backOnList, CompareChoice.REMOVE_TRACKING),
            ApplyRow(rebound, CompareChoice.REMOVE_TRACKING),
            ApplyRow(gone, CompareChoice.REMOVE_TRACKING),
        )

        run.results shouldBe listOf(ApplyResult.ChangedSince, ApplyResult.ChangedSince, ApplyResult.Done())
        h.stored(1)?.remoteId shouldBe 1001L
        h.stored(2)?.remoteId shouldBe 2002L
        h.stored(3).shouldBeNull()
        h.store.getItems().shouldBeEmpty()
        h.puts.shouldBeEmpty()
    }

    @Test
    fun `keep cancels the waiting update the owner saw with no call, and leaves one raised since`() {
        val h = Harness()
        h.entry(1, readThrough = 50.0, remote = Remote(chapters = 45))
        h.entry(2, readThrough = 50.0, remote = Remote(chapters = 45))
        h.store.add(1, TRACKER, 1001, 50.0)
        h.store.add(2, TRACKER, 1002, 48.0)
        val waiting = h.shown(1)
        val raised = h.shown(2)
        waiting.choices shouldBe listOf(CompareChoice.DONT_CHANGE, CompareChoice.UPDATE_REMOTE, CompareChoice.KEEP_REMOTE)
        h.store.add(2, TRACKER, 1002, 50.0)

        val run = h.run(ApplyRow(waiting, CompareChoice.KEEP_REMOTE), ApplyRow(raised, CompareChoice.KEEP_REMOTE))

        run.results shouldBe listOf(ApplyResult.Done(), ApplyResult.ChangedSince)
        h.store.getItems() shouldBe listOf(DelayedTrackingItem(2, TRACKER, 1002, 50.0))
        h.requests.shouldBeEmpty()
    }

    @Test
    fun `a failure that is not about one entry stops the run at row 2 of 4, and one that is fails row 2 alone`() {
        val stops = listOf<Triple<String, StopKind, (Harness) -> Unit>>(
            Triple("a refused sign-in", StopKind.SIGN_IN_REFUSED) { h -> h.expireAfterFirstPut(json(400, """{"error":"invalid_grant"}""")) },
            Triple("an API 401, again after the forced refresh", StopKind.UNAUTHORIZED) { h ->
                h.answerSecondGet(json(401, """{"error":"invalid_token"}"""), tokenEndpoint = json(200, tokenBody(ACCESS_2, REFRESH_2)))
            },
            Triple("an API 401 whose forced refresh is refused", StopKind.SIGN_IN_REFUSED) { h ->
                h.answerSecondGet(json(401, """{"error":"invalid_token"}"""), tokenEndpoint = json(400, """{"error":"invalid_grant"}"""))
            },
            Triple("a 403", StopKind.FORBIDDEN) { h -> h.answerSecondGet(json(403, "{}")) },
            Triple("an API 429", StopKind.THROTTLED) { h -> h.answerSecondGet(json(429, "{}")) },
            Triple("a token endpoint 429", StopKind.THROTTLED) { h -> h.expireAfterFirstPut(json(429, "{}")) },
            Triple("a 503", StopKind.OTHER) { h -> h.answerSecondGet(json(503, "{}")) },
            Triple("a dropped connection", StopKind.OTHER) { h ->
                h.answerSecondGet(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
            },
        )
        for ((case, kind, inject) in stops) {
            val h = fourRows()
            inject(h)

            val run = h.run(*h.fourApplyRows())

            withClue(case) {
                run.results[0] shouldBe ApplyResult.Done()
                run.results[1].shouldBeInstanceOf<ApplyResult.Failed>().failure.shouldBeInstanceOf<CompareFailure.Stop>().kind shouldBe kind
                run.results.drop(2) shouldBe List(2) { ApplyResult.NotReached }
                run.stop?.kind shouldBe kind
            }
        }

        val rowOnly = listOf<Pair<String, MockResponse>>(
            "a 404 on the send" to json(404, """{"error":"not_found"}"""),
            "a 400 invalid_content on the send" to json(400, """{"message":"Invalid content","error":"invalid_content"}"""),
        )
        for ((case, response) in rowOnly) {
            val h = fourRows()
            h.intercept = { request -> response.takeIf { request.method == "PUT" && request.url.encodedPath.contains("/1002/") } }

            val run = h.run(*h.fourApplyRows())

            withClue(case) {
                run.results[1].shouldBeInstanceOf<ApplyResult.Failed>().failure.shouldBeInstanceOf<CompareFailure.RowOnly>()
                run.results[2] shouldBe ApplyResult.Done()
                run.results[3] shouldBe ApplyResult.Done()
                run.stop.shouldBeNull()
            }
        }
    }

    /** Four entries, the app at 50 and the service at 45 on each. */
    private fun fourRows(): Harness = Harness().apply {
        (1L..4L).forEach { entry(it, readThrough = 50.0, remote = Remote(chapters = 45)) }
    }

    private fun Harness.fourApplyRows(): Array<ApplyRow> =
        (1L..4L).map { ApplyRow(shown(it), CompareChoice.UPDATE_REMOTE) }.toTypedArray()

    /** Row 2's read of the service is answered with [response], and a refresh with [tokenEndpoint]. */
    private fun Harness.answerSecondGet(response: MockResponse, tokenEndpoint: MockResponse? = null) {
        intercept = { request ->
            when {
                request.url.encodedPath == "/v1/oauth2/token" -> tokenEndpoint
                request.method == "GET" && request.url.encodedPath == "/v2/manga/1002" -> response
                else -> null
            }
        }
    }

    /** The token expires as row 1's send is answered, and the token endpoint answers [refusal]. */
    private fun Harness.expireAfterFirstPut(refusal: MockResponse) {
        intercept = { request ->
            when {
                request.url.encodedPath == "/v1/oauth2/token" -> refusal
                request.method == "PUT" && request.url.encodedPath.contains("/1001/") -> {
                    fixture.now = fixture.now + (ONE_MONTH + 3600).seconds
                    null
                }
                else -> null
            }
        }
    }

    private companion object {
        const val TRACKER = 1L
        const val LOCK_WAIT_MILLIS = 500L
        const val CANCEL_WAIT_MILLIS = 2_000L
        const val ANSWER_HOLD_SECONDS = 10L
    }
}
