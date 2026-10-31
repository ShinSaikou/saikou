package com.shinsaikou.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.manga.model.readingMode
import com.shinsaikou.sources.local.LocalSource
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import logcat.logcat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The device half of contract 07 part 2 section 9.2's `ReaderProcessDeathTest`: the reader,
 * killed in the background the way the system kills it under memory pressure, comes back on the
 * chapter and page it was on. The check itself runs from the host, in
 * `tools/ci/reader_process_death.sh`; this class only seeds the entry it reads.
 *
 * ### Why the check cannot live in an instrumentation
 *
 * Established on the API 26 AVD before this was written (unit K's report, section 2). A process
 * with an active instrumentation is held at the foreground adjustment ("instrumentation" in
 * `dumpsys activity oom`) even when its activities are in the background, so `am kill`, which
 * kills only background processes, never selects it. Killing it by other means ends the run with
 * "Process crashed", taking the test with it. And the end of a run, like the start of the next,
 * force-stops the package ("finished inst", "start instr"), which removes its task and the saved
 * instance state with it, so a second run cannot find what a first one left. The state the check
 * needs, a dead process under a task that still holds the reader's activity record, only exists
 * when no instrumentation is attached, so the host opens the reader through the app's own screens,
 * kills it with `am kill` and brings the task back.
 *
 * ### What this seeds
 *
 * One Local source entry, [ReaderFixture], with two chapters of different lengths ([PAGE_COUNTS]),
 * so the host can tell the chapters apart by the page indicator alone as well as by the title.
 * The entry reads left to right, written onto its row as `ReaderSmokeTest` does, so a tap on the
 * right of the page turns forward on every device. It is not a favourite, so no library screen
 * another test reads shows it.
 *
 * The entry has to outlive this run, because the host opens it afterwards, so nothing here removes
 * it. Instead each run first removes what an earlier one seeded (the directory and a soft delete of
 * its row, as [ReaderFixture.remove] does), so one entry at most is ever left on a device.
 *
 * The ids reach the host as one logcat line under [LOG_TAG], carrying the nonce the host passed
 * as `saikou.reader.restore.nonce`, so the host reads this run's line and never an earlier one's.
 * The argument is optional: in a full suite run (steps 5, 5b and 5c of `gate.sh`) the nonce is
 * `none`, the seed still runs and asserts, and the connected run uninstalls the application
 * afterwards. There is no early return.
 */
@RunWith(AndroidJUnit4::class)
class ReaderProcessDeathTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    @Test
    fun anEntryIsSeededForTheHostToKillAndRestore() {
        removeEarlierSeeds()

        val fixture = ReaderFixture.create(context, graph, FIXTURE_PREFIX, PAGE_COUNTS)
        fixture.writeReadingMode(ReadingMode.LEFT_TO_RIGHT)

        val stored = runBlocking { graph.mangaRepository.getMangaById(fixture.mangaId) }
        assertEquals(
            "the seeded entry does not read left to right, so a tap on the right would not turn forward",
            ReadingMode.LEFT_TO_RIGHT,
            stored.readingMode,
        )
        assertTrue("the seeded entry is a favourite, so it would show in the library", !stored.favorite)

        val nonce = InstrumentationRegistry.getArguments().getString(NONCE_ARGUMENT)?.takeIf { it.isNotBlank() } ?: "none"
        logcat(LOG_TAG, LogPriority.INFO) {
            "seeded nonce=$nonce manga=${fixture.mangaId} " +
                fixture.chapters.mapIndexed { index, chapter ->
                    "chapter${index + 1}=${chapter.id} pages${index + 1}=${PAGE_COUNTS[index]}"
                }.joinToString(" ")
        }
    }

    private fun removeEarlierSeeds() {
        val earlier = ReaderFixture.localRoot(context).listFiles { file -> file.name.startsWith(FIXTURE_PREFIX) }
            .orEmpty()
        runBlocking {
            earlier.forEach { directory ->
                graph.mangaRepository.getMangaByUrlAndSourceId(directory.name, LocalSource.ID)
                    ?.let { graph.mangaRepository.softDelete(it.id, System.currentTimeMillis()) }
                directory.deleteRecursively()
            }
        }
        val left = ReaderFixture.localRoot(context).listFiles { file -> file.name.startsWith(FIXTURE_PREFIX) }.orEmpty()
        assertTrue("an earlier seed could not be removed: ${left.map { it.name }}", left.isEmpty())
    }

    private companion object {
        /** Read by `tools/ci/reader_process_death.py`; the two must agree. */
        const val LOG_TAG = "SaikouReaderRestore"

        const val NONCE_ARGUMENT = "saikou.reader.restore.nonce"

        const val FIXTURE_PREFIX = "saikou-reader-restore-"

        /** Different lengths, so "3 / 5" and "3 / 4" name different chapters. */
        val PAGE_COUNTS = listOf(5, 4)
    }
}
