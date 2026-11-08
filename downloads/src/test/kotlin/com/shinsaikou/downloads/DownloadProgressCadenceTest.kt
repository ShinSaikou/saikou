package com.shinsaikou.downloads

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** The engine reports every page; the notification gets a chapter's first report, then one a second. */
class DownloadProgressCadenceTest {

    private val time = TestTimeSource()
    private val cadence = DownloadProgressCadence(time)

    private fun progress(chapterId: Long, done: Int) =
        DownloadEngine.Progress(chapterId, "Title", "Chapter $chapterId", done, pagesTotal = 20, chaptersLeft = 2)

    @Test
    fun `a chapter's first report goes out, then one a second at most`() {
        cadence.shouldSend(progress(1, 0)) shouldBe true
        time += 100.milliseconds
        cadence.shouldSend(progress(1, 1)) shouldBe false
        time += 899.milliseconds
        cadence.shouldSend(progress(1, 2)) shouldBe false
        time += 1.milliseconds
        cadence.shouldSend(progress(1, 3)) shouldBe true
        cadence.shouldSend(progress(1, 4)) shouldBe false
    }

    @Test
    fun `the next chapter's first report goes out at once`() {
        cadence.shouldSend(progress(1, 0)) shouldBe true
        time += 10.milliseconds
        cadence.shouldSend(progress(2, 0)) shouldBe true
        cadence.shouldSend(progress(2, 1)) shouldBe false
    }

    private fun together(chapterId: Long, done: Int, running: Int) = DownloadEngine.Progress(
        chapterId, "Title", "Chapter $chapterId", done, pagesTotal = 20, chaptersLeft = 2, chaptersRunning = running,
    )

    @Test
    fun `a change in the number of chapters running goes out at once`() {
        cadence.shouldSend(together(1, 0, running = 1)) shouldBe true
        time += 10.milliseconds
        cadence.shouldSend(together(1, 4, running = 2)) shouldBe true
        time += 10.milliseconds
        cadence.shouldSend(together(1, 5, running = 2)) shouldBe false
        time += 10.milliseconds
        cadence.shouldSend(together(1, 5, running = 3)) shouldBe true
    }

    @Test
    fun `the number running dropping back to one goes out at once`() {
        cadence.shouldSend(together(1, 0, running = 2)) shouldBe true
        time += 10.milliseconds
        cadence.shouldSend(together(1, 3, running = 2)) shouldBe false
        time += 10.milliseconds
        cadence.shouldSend(together(1, 3, running = 1)) shouldBe true
        cadence.shouldSend(together(1, 4, running = 1)) shouldBe false
    }

    @Test
    fun `with the same number running, reports are still held to one a second`() {
        cadence.shouldSend(together(1, 0, running = 2)) shouldBe true
        time += 999.milliseconds
        cadence.shouldSend(together(1, 1, running = 2)) shouldBe false
        time += 1.milliseconds
        cadence.shouldSend(together(1, 2, running = 2)) shouldBe true
    }
}
