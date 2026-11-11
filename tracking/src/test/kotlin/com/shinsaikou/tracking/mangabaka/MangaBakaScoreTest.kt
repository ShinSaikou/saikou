package com.shinsaikou.tracking.mangabaka

import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.WRITTEN_BODY
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.json
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.jsonBody
import com.shinsaikou.tracking.mangabaka.MangaBakaFixture.Companion.track
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * MangaBaka's statuses and its five score steps, upstream's: a row's score is stored on MangaBaka's
 * 0 to 100 scale whatever the account's step, the sheet's picker lists the step's choices, a choice
 * becomes the stored score as it is, and the score reads back as its number. The step is the
 * account's, stored at sign-in; one this code does not know reads as `STEP_1`.
 */
class MangaBakaScoreTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { MangaBakaFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    private fun step(type: String) = fixture.trackPreferences.mangabakaScoreType.set(type)

    @Test
    fun `the statuses are upstream's ids, rereading and considering included, with Saikou's words`() {
        val tracker = fixture.tracker
        tracker.getStatusList() shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        tracker.getStatusList().map(tracker::getStatus) shouldContainExactly listOf(
            R.string.track_status_reading,
            R.string.track_status_completed,
            R.string.track_status_paused,
            R.string.track_status_dropped,
            R.string.track_status_plan_to_read,
            R.string.track_status_rereading,
            R.string.track_status_considering,
        )
        tracker.getStatus(8L) shouldBe null
        tracker.getReadingStatus() shouldBe MangaBaka.READING
        tracker.getRereadingStatus() shouldBe MangaBaka.REREADING
        tracker.getCompletionStatus() shouldBe MangaBaka.COMPLETED
        tracker.supportsReadingDates shouldBe true
        tracker.supportsPrivateTracking shouldBe true
    }

    @Test
    fun `each status goes to MangaBaka as its state, and each state comes back as its status`() {
        val states = listOf("reading", "completed", "paused", "dropped", "plan_to_read", "rereading", "considering")
        fixture.tracker.getStatusList().map { track(status = it).toApiStatus() } shouldContainExactly states
        states.map(::toMangaBakaStatus) shouldContainExactly fixture.tracker.getStatusList()
        // One neither side knows is reading, never a failure the retry worker cannot catch.
        track(status = 99L).toApiStatus() shouldBe "reading"
        toMangaBakaStatus("abandoned") shouldBe MangaBaka.READING
    }

    @Test
    fun `STEP_1, the default, picks 0 to 100 and stores them as they are`() {
        val tracker = fixture.tracker
        tracker.getScoreList() shouldHaveSize 101
        tracker.indexToScore(tracker.getScoreList().indexOf("73")) shouldBe 73.0
        tracker.displayScore(track(score = 73.0)) shouldBe "73"
        tracker.get10PointScore(track(score = 73.0)) shouldBe 7.3
    }

    @Test
    fun `each coarser step lists its stride on 0 to 100 and stores the choice as it is`() {
        val tracker = fixture.tracker
        for ((type, stride) in listOf(MangaBaka.STEP_5 to 5, MangaBaka.STEP_10 to 10, MangaBaka.STEP_20 to 20, MangaBaka.STEP_25 to 25)) {
            step(type)
            tracker.getScoreList() shouldBe (0..100 step stride).map(Int::toString)
            tracker.indexToScore(tracker.getScoreList().lastIndex) shouldBe 100.0
            tracker.indexToScore(1) shouldBe stride.toDouble()
        }
        step(MangaBaka.STEP_25)
        tracker.getScoreList() shouldBe listOf("0", "25", "50", "75", "100")
        tracker.displayScore(track(score = 75.0)) shouldBe "75"
    }

    @Test
    fun `a step this code does not know reads as STEP_1, and a pick the list lacks is no score`() {
        step("STEP_3")
        val tracker = fixture.tracker
        tracker.scoreType() shouldBe MangaBaka.STEP_1
        tracker.getScoreList() shouldHaveSize 101
        tracker.indexToScore(-1) shouldBe 0.0
        tracker.indexToScore(500) shouldBe 0.0
    }

    @Test
    fun `the profile's rating step is stored as its step, and an unknown or absent one as STEP_1`() {
        MangaBaka.scoreTypeOf(1) shouldBe MangaBaka.STEP_1
        MangaBaka.scoreTypeOf(5) shouldBe MangaBaka.STEP_5
        MangaBaka.scoreTypeOf(10) shouldBe MangaBaka.STEP_10
        MangaBaka.scoreTypeOf(20) shouldBe MangaBaka.STEP_20
        MangaBaka.scoreTypeOf(25) shouldBe MangaBaka.STEP_25
        MangaBaka.scoreTypeOf(null) shouldBe MangaBaka.STEP_1
        MangaBaka.scoreTypeOf(3) shouldBe MangaBaka.STEP_1
    }

    @Test
    fun `a score picked in the sheet is sent as MangaBaka's rating and stored`() {
        runBlocking {
            step(MangaBaka.STEP_10)
            fixture.signedIn()
            fixture.repository.insert(track())
            fixture.server.enqueue(json(200, WRITTEN_BODY))

            val stored = fixture.tracker.setRemoteScore(track(), "80")

            stored.score shouldBe 80.0
            fixture.server.takeRequest().jsonBody()["rating"] shouldBe JsonPrimitive(80)
            fixture.repository.rows.getValue(10L).score shouldBe 80.0
        }
    }
}
