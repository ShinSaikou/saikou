package com.shinsaikou.tracking.comick

import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.comick.ComickFixture.Companion.WRITTEN_BODY
import com.shinsaikou.tracking.comick.ComickFixture.Companion.json
import com.shinsaikou.tracking.comick.ComickFixture.Companion.jsonBody
import com.shinsaikou.tracking.comick.ComickFixture.Companion.track
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Comick's statuses and scores, upstream's: five statuses with no rereading one, scores 0 to 10
 * stored and sent as they are, and no dates or private entries.
 */
class ComickScoreTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { ComickFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `the statuses are upstream's ids with Saikou's words, and Comick has no rereading, dates or private entries`() {
        val tracker = fixture.tracker
        tracker.getStatusList() shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L)
        tracker.getStatusList().map(tracker::getStatus) shouldContainExactly listOf(
            R.string.track_status_reading,
            R.string.track_status_completed,
            R.string.track_status_on_hold,
            R.string.track_status_dropped,
            R.string.track_status_plan_to_read,
        )
        tracker.getStatus(6L) shouldBe null
        tracker.getReadingStatus() shouldBe Comick.READING
        tracker.getRereadingStatus() shouldBe -1L
        tracker.getCompletionStatus() shouldBe Comick.COMPLETED
        tracker.supportsReadingDates shouldBe false
        tracker.supportsPrivateTracking shouldBe false
    }

    @Test
    fun `each status goes to Comick as its number, and each number comes back as its status`() {
        val numbers = listOf(1, 2, 3, 4, 5)
        fixture.tracker.getStatusList().map { it.toApiListStatus() } shouldContainExactly numbers
        numbers.map { it.toLong().fromApiListStatus() } shouldContainExactly fixture.tracker.getStatusList()
        // One neither side knows is reading, never a failure the retry worker cannot catch.
        99L.toApiListStatus() shouldBe 1
        6L.fromApiListStatus() shouldBe Comick.READING
    }

    @Test
    fun `scores are 0 to 10, stored as picked and shown as whole numbers`() {
        val tracker = fixture.tracker
        tracker.getScoreList() shouldBe (0..10).map(Int::toString)
        tracker.indexToScore(tracker.getScoreList().indexOf("7")) shouldBe 7.0
        tracker.displayScore(track(score = 7.0)) shouldBe "7"
        tracker.get10PointScore(track(score = 7.0)) shouldBe 7.0
    }

    @Test
    fun `a score picked in the sheet is sent as Comick's rating and stored`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track())
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            val stored = fixture.tracker.setRemoteScore(track(), "8")

            stored.score shouldBe 8.0
            fixture.server.takeRequest().jsonBody()["rating"] shouldBe JsonPrimitive(8)
            fixture.repository.rows.getValue(10L).score shouldBe 8.0
        }
    }
}
