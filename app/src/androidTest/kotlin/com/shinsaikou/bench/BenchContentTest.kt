package com.shinsaikou.bench

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.ReaderActivity
import com.shinsaikou.reader.ReaderIntents
import com.shinsaikou.reader.ReaderViewModel
import com.shinsaikou.reader.pipeline.PageState
import com.shinsaikou.reader.state.ReaderPhase
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds the benchmark content, and opens the reader on it for the benchmark scripts.
 *
 * ### Why the benchmark scripts cannot open the reader themselves
 *
 * `ReaderActivity` is declared `android:exported="false"` and carries no intent filter, so
 * `adb shell am start` is refused: `SecurityException: Permission Denial ... not exported from uid
 * 10233`. Exporting it, or adding a launcher route that takes a manga id, would put a new intent
 * surface into the shipped application to serve a benchmark. Instrumentation already runs in the
 * application's own uid and can start the activity with no change to what ships, so that is what
 * opens it (carry-over 11).
 *
 * The cost of that route is the reason [holdThePagedReader] and [holdTheWebtoonReader] sleep.
 * Finishing an instrumentation run kills the process it ran in, reader and all, so a test that
 * started the activity and returned would leave the benchmark measuring the launcher. Each hold
 * therefore starts the activity, proves the session actually opened, and then holds the process
 * alive for `saikou.bench.holdSeconds` while the bench script performs its gestures.
 *
 * ### What each method asserts
 *
 * A hold that opened nothing is the failure this class exists to prevent, because the benchmark
 * downstream cannot tell a jank figure for a chapter from a jank figure for the launcher. So the
 * holds assert the session reached [ReaderPhase.Ready] with the page count the seeder wrote, and
 * the script asserts the activity is on screen before it measures and still on screen afterwards.
 *
 * With no `saikou.bench.holdSeconds` argument the hold is zero and the methods are ordinary tests:
 * they seed, open, assert and return in a few seconds. That is what they do inside an ordinary
 * `connectedAndroidTest` run, so this class is never a test that passes by sleeping.
 */
@RunWith(AndroidJUnit4::class)
class BenchContentTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = instrumentation.targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    /**
     * The step Phase 0 runs once per benchmark device before anything is measured. Image
     * generation is minutes of work on the first run and nothing at all afterwards, and keeping it
     * out of the benchmarks means neither the cost nor a failure in it lands inside a measured
     * window.
     */
    @Test
    fun seedTheBenchmarkContent() {
        val seed = runBlocking { BenchFixtureSeeder.seed(context, graph) }
        Log.i(
            LOG_TAG,
            "paged manga ${seed.paged.mangaId} chapter ${seed.paged.chapterId}, " +
                "strip manga ${seed.strip.mangaId} chapter ${seed.strip.chapterId}",
        )
    }

    @Test
    fun holdThePagedReader() {
        hold(BenchFixtureSeeder.PAGED_MODE)
    }

    @Test
    fun holdTheWebtoonReader() {
        hold(BenchFixtureSeeder.STRIP_MODE)
    }

    /**
     * The mode is the one [BenchFixtureSeeder] wrote onto the entry. The reading-mode intent extra
     * contract 07 part 2 section 8.5 used to reserve for exactly this opened a reader that reached
     * [ReaderPhase.Ready] with the right mode and the right page count and then drew nothing at
     * all: the page area stayed black and no tap reached the viewer (carry-over 12). It is retired
     * (ruling R39), and asserting the mode here is a real assertion either way, because the only
     * thing that can have set it is the seeder's write.
     */
    private fun hold(mode: ReadingMode) {
        val seed = runBlocking { BenchFixtureSeeder.seed(context, graph) }
        val entry = if (mode == BenchFixtureSeeder.STRIP_MODE) seed.strip else seed.paged

        // ActivityScenario is deliberately not used: it closes the activity when the block ends,
        // and this activity has to outlive the test body.
        val monitor = instrumentation.addMonitor(ReaderActivity::class.java.name, null, false)
        // Page 0 by name: with no page the reader resumes where the chapter was left, and the
        // bench's own page turns leave the seeded chapter part read for the next hold.
        val intent = ReaderIntents.newIntent(context, entry.mangaId, entry.chapterId, page = 0)
            .putExtra(ReaderIntents.EXTRA_BENCH_TAG, BENCH_TAG)
            // The activity is started from the application context, which owns no task of its own.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        val activity = instrumentation.waitForMonitorWithTimeout(monitor, LAUNCH_TIMEOUT_MILLIS)
        assertNotNull("$mode: the reader activity never started", activity)
        instrumentation.removeMonitor(monitor)

        val ready = await("$mode: the session never became ready") {
            viewModelOf(activity!!).state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 }
        }
        assertEquals("$mode: the entry did not carry the reading mode the seeder wrote", mode, ready.readingMode)
        assertEquals("$mode: the reader opened another chapter", entry.chapterId, ready.currentChapter?.chapter?.id)
        assertEquals("$mode: the chapter is not the seeded one", entry.pages, ready.totalPages)

        val first = checkNotNull(ready.currentChapter?.pages).first()
        val status = await("$mode: page 1 never left the queue") {
            first.statusFlow.first { it is PageState.Ready || it is PageState.Error }
        }
        assertTrue("$mode: page 1 did not decode: $status", status is PageState.Ready)

        // A local page is Ready the moment the page list exists, because its bytes are already on
        // disk; the probe and the split run when the viewer's own holder first loads it. So Ready
        // says nothing here, and what the benchmark needs is that the viewer has been through the
        // page: gestures measured while the first page is still being probed are measuring the
        // pipeline warming up.
        if (mode.isWebtoon) {
            // A strip the splitter declined would leave the webtoon bench measuring the whole-image
            // path over a tall page, which is not what the reader does with a real strip, and
            // nothing downstream of here could tell those two numbers apart.
            awaitTrue("$mode: the splitter never banded the strip") { first.partCount > 1 }
        } else {
            awaitTrue("$mode: the viewer never loaded page 1") { first.route != null }
            assertEquals("$mode: a page-shaped image was split", 1, first.partCount)
        }

        Log.i(
            LOG_TAG,
            "$mode: holding ${ready.totalPages} pages, page 1 in ${first.partCount} part(s), " +
                "for $holdMillis ms",
        )

        // The benchmark's gestures happen here, in another process. When holdMillis is 0 this is a
        // plain test that has already asserted everything it came to assert.
        if (holdMillis > 0) Thread.sleep(holdMillis)
    }

    /** Seconds the process is held after the reader opens; 0, and an ordinary test, when absent. */
    private val holdMillis: Long
        get() = (InstrumentationRegistry.getArguments().getString(HOLD_ARGUMENT)?.toLongOrNull() ?: 0L) * 1000L

    /**
     * The activity's own view model, not a second one. The instance is already in the activity's
     * store under the default key, so the provider returns it and never calls a factory; a factory
     * built here would construct a second reader session against the same rows.
     */
    @Suppress("DEPRECATION")
    private fun viewModelOf(activity: Activity): ReaderViewModel {
        lateinit var viewModel: ReaderViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(activity as ReaderActivity)[ReaderViewModel::class.java]
        }
        return viewModel
    }

    /** A bounded wait with a message: an unbounded one reports only that the runner gave up. */
    private fun <T> await(message: String, block: suspend () -> T): T = runBlocking {
        try {
            withTimeout(OPEN_TIMEOUT_MILLIS) { block() }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("$message (after $OPEN_TIMEOUT_MILLIS ms)", e)
        }
    }

    /**
     * The same wait for state the pipeline mutates on a page rather than publishes in a flow. The
     * split is one of those: it sets `partCount` and `route` on the page the viewer is holding.
     * The longest single IO operation in the reader is behind this, so it is given its own budget.
     */
    private fun awaitTrue(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + SPLIT_TIMEOUT_MILLIS
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("$message (after $SPLIT_TIMEOUT_MILLIS ms)")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val LOG_TAG = "SaikouBenchContent"

        const val BENCH_TAG = "phase0"

        const val HOLD_ARGUMENT = "saikou.bench.holdSeconds"

        const val LAUNCH_TIMEOUT_MILLIS = 30_000L

        /** A cold activity, a session build, and a page list off the file system. */
        const val OPEN_TIMEOUT_MILLIS = 60_000L

        /** Region-decoding and re-encoding every band of a 16 megapixel strip, on an emulator. */
        const val SPLIT_TIMEOUT_MILLIS = 120_000L

        const val POLL_MILLIS = 100L
    }
}
