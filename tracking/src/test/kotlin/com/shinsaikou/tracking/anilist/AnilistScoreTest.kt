package com.shinsaikou.tracking.anilist

import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.graphql
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.json
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.savedBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.track
import com.shinsaikou.i18n.R
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
 * AniList's statuses and its five score formats, upstream's mappings: a row's score is stored on
 * AniList's 100-point scale whatever the account's format, the sheet's picker lists the format's
 * choices, a choice becomes the stored score, and the stored score reads back in the format. The
 * format is the account's, stored at sign-in; one this code does not know reads as `POINT_10`.
 */
class AnilistScoreTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { AnilistFixture(cacheDir) }

    @AfterEach
    fun close() {
        fixture.close()
    }

    private fun format(type: String) = fixture.trackPreferences.anilistScoreType.set(type)

    @Test
    fun `the statuses are upstream's ids with Saikou's words, Repeating for rereading`() {
        val tracker = fixture.tracker
        tracker.getStatusList() shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L, 6L)
        tracker.getStatusList().map(tracker::getStatus) shouldContainExactly listOf(
            R.string.track_status_reading,
            R.string.track_status_completed,
            R.string.track_status_on_hold,
            R.string.track_status_dropped,
            R.string.track_status_plan_to_read,
            R.string.track_status_repeating,
        )
        tracker.getStatus(7L) shouldBe null
        tracker.getReadingStatus() shouldBe Anilist.READING
        tracker.getRereadingStatus() shouldBe Anilist.REREADING
        tracker.getCompletionStatus() shouldBe Anilist.COMPLETED
        tracker.supportsReadingDates shouldBe true
        tracker.supportsPrivateTracking shouldBe true
    }

    @Test
    fun `POINT_10, the default, picks 0 to 10 and stores tens`() {
        val tracker = fixture.tracker
        tracker.getScoreList() shouldBe (0..10).map(Int::toString)
        tracker.indexToScore(tracker.getScoreList().indexOf("7")) shouldBe 70.0
        tracker.displayScore(track(score = 70.0)) shouldBe "7"
        tracker.displayScore(track(score = 75.0)) shouldBe "7"
        tracker.get10PointScore(track(score = 70.0)) shouldBe 7.0
    }

    @Test
    fun `POINT_100 picks 0 to 100 and stores them as they are`() {
        format(Anilist.POINT_100)
        val tracker = fixture.tracker
        tracker.getScoreList() shouldHaveSize 101
        tracker.indexToScore(tracker.getScoreList().indexOf("85")) shouldBe 85.0
        tracker.displayScore(track(score = 85.0)) shouldBe "85"
        tracker.get10PointScore(track(score = 85.0)) shouldBe 8.5
    }

    @Test
    fun `POINT_10_DECIMAL picks 0,0 to 10,0 in tenths and stores them in hundredths`() {
        format(Anilist.POINT_10_DECIMAL)
        val tracker = fixture.tracker
        tracker.getScoreList().first() shouldBe "0.0"
        tracker.getScoreList().last() shouldBe "10.0"
        tracker.indexToScore(tracker.getScoreList().indexOf("7.5")) shouldBe 75.0
        tracker.displayScore(track(score = 75.0)) shouldBe "7.5"
    }

    @Test
    fun `POINT_5 picks stars and stores their middles`() {
        format(Anilist.POINT_5)
        val tracker = fixture.tracker
        tracker.getScoreList() shouldBe listOf("0 ★", "1 ★", "2 ★", "3 ★", "4 ★", "5 ★")
        (0..5).map(tracker::indexToScore) shouldBe listOf(0.0, 10.0, 30.0, 50.0, 70.0, 90.0)
        listOf(0.0, 10.0, 30.0, 50.0, 70.0, 90.0, 100.0).map { tracker.displayScore(track(score = it)) } shouldBe
            listOf("0 ★", "1 ★", "2 ★", "3 ★", "4 ★", "5 ★", "5 ★")
    }

    @Test
    fun `POINT_3 picks a face and stores its point`() {
        format(Anilist.POINT_3)
        val tracker = fixture.tracker
        tracker.getScoreList() shouldBe listOf("-", "😦", "😐", "😊")
        (0..3).map(tracker::indexToScore) shouldBe listOf(0.0, 35.0, 60.0, 85.0)
        listOf(0.0, 35.0, 60.0, 85.0).map { tracker.displayScore(track(score = it)) } shouldBe listOf("0", "😦", "😐", "😊")
    }

    @Test
    fun `a score format this code does not know reads as POINT_10 instead of failing the sheet`() {
        format("POINT_7")
        val tracker = fixture.tracker
        tracker.scoreType() shouldBe Anilist.POINT_10
        tracker.getScoreList() shouldBe (0..10).map(Int::toString)
        tracker.indexToScore(4) shouldBe 40.0
        tracker.displayScore(track(score = 40.0)) shouldBe "4"
    }

    @Test
    fun `a score picked in the sheet is sent raw, on the 100-point scale`() {
        runBlocking {
            format(Anilist.POINT_5)
            fixture.signedIn()
            fixture.repository.insert(track())
            fixture.server.enqueue(json(200, savedBody()))

            val stored = fixture.tracker.setRemoteScore(track(), "4 ★")

            stored.score shouldBe 70.0
            fixture.server.takeRequest().graphql().second["score"] shouldBe JsonPrimitive(70)
            fixture.repository.rows.getValue(10L).score shouldBe 70.0
        }
    }
}
