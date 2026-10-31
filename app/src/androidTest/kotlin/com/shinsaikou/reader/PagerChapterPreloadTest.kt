package com.shinsaikou.reader

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.state.ReaderPhase
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A short right-to-left chapter, opened repeatedly while its next chapter is fast enough to finish
 * loading before the pager's first layout pass. Guards a race the Phase 2 full gate run hit once in
 * two full-suite runs and diagnosed from logcat:
 *
 * ```
 * reader.chapter id=83590 pages=3 / reader.ready page=1 of=3 / reader.viewer mode=RIGHT_TO_LEFT
 * R2LPagerViewer: Pager first layout
 * R2LPagerViewer: onReaderPageSelected: 1/3
 * R2LPagerViewer: Request preload next chapter because we're at page 1 of 3
 * R2LPagerViewer: onReaderPageSelected: 1/4      <- 4 ms later: page 1 of the next chapter
 * R2LPagerViewer: onReaderPageSelected: 2/4
 * ```
 *
 * `PagerViewer.setChaptersInternal` runs a second time, for the preloaded next chapter, before the
 * pager has laid out for the first time. `PagerViewerAdapter.setChapters` reverses the item list for
 * a right-to-left reader, so the next chapter's pages are inserted in front of the current one's, and
 * with no page view bound yet `getItemPosition` cannot re-map the current item; `pager.currentItem`
 * keeps its old index, which the reversed list now holds the next chapter's first page at, and the
 * page-change callback selects it. This is a race between the preload finishing and the first layout
 * pass, so it does not reproduce on every run: this test opens the chapter [REPEATS] times to give it
 * room to show up.
 *
 * The fixture is built with the longer chapter first and the shorter, tested chapter second, because
 * [ReaderFixture.create] inserts both rows through `chapterRepository.addAll` directly, bypassing the
 * source sync that would give them distinct `sourceOrder` values. With every row sharing one
 * `sourceOrder`, the reader's chapter sort (`ReaderSession`, ascending, ties broken by insertion order)
 * follows the order the rows were inserted in, which is the order the local source listed them in, and
 * the local source lists chapter names descending. So `fixture.chapters[1]` ("Chapter 2", the shorter
 * one this test opens) is the reader's current chapter and `fixture.chapters[0]` ("Chapter 1", the
 * longer one) is its next, the opposite of the fixture's own list order. This is a fixture artifact of
 * the direct insert, not a product defect; a real sync sets `sourceOrder` and the two chapters would
 * sort by name. `shortChapter`'s few pages are fewer than the chapter-preload window computes for a
 * fresh session, so the reader asks to preload `nextChapter` as soon as it reports page 1; `nextChapter`
 * has more pages of the same fixture kind, so its load has nothing slow to wait on. Both chapters are
 * synthetic, built by [ReaderFixture] on the device.
 */
@RunWith(AndroidJUnit4::class)
class PagerChapterPreloadTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private val context = instrumentation.targetContext

    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    private lateinit var fixture: ReaderFixture

    /**
     * Whether `always_show_chapter_transition` was set before this test pinned it, and to what. It
     * decides whether `PagerViewer.setChaptersInternal` forces a chapter-transition item into the
     * adapter's item list, which changes the indices the pager's items sit at and so bears directly
     * on the race this test guards. `BackupImportTest` restores the owner's real backup earlier in
     * the suite, and that backup could leave the preference at either value, so it is pinned to its
     * own default for the run and put back exactly as found afterward.
     */
    private var alwaysShowChapterTransitionWasSet = false
    private var alwaysShowChapterTransitionBefore = true

    @Before
    fun buildTheFixture() {
        val alwaysShowChapterTransition = graph.readerPreferences.alwaysShowChapterTransition
        alwaysShowChapterTransitionWasSet = alwaysShowChapterTransition.isSet()
        alwaysShowChapterTransitionBefore = alwaysShowChapterTransition.get()
        alwaysShowChapterTransition.set(DEFAULT_ALWAYS_SHOW_CHAPTER_TRANSITION)
        // The chapter-preload window itself is not a preference: PreloadPolicy.compute() sizes it
        // from the device's memory class, so there is nothing of it to pin here. shortChapter's few
        // pages are fewer than any window that policy can compute, which is what triggers the
        // preload this test relies on regardless of the device it runs on.
        //
        // Chapter 1 (index 0) gets the longer page count and Chapter 2 (index 1) the shorter one on
        // purpose: the fixture's rows share one source order, the reader breaks that tie by insertion
        // order, and the local source lists chapter names descending, so Chapter 2 is inserted first
        // and ends up as the reader's current chapter with Chapter 1 as its next. See the class KDoc.
        fixture = ReaderFixture.create(context, graph, FIXTURE_PREFIX, listOf(NEXT_CHAPTER_PAGES, SHORT_CHAPTER_PAGES))
        fixture.writeReadingMode(ReadingMode.RIGHT_TO_LEFT)
    }

    @After
    fun removeTheFixture() {
        if (::fixture.isInitialized) fixture.remove()
        val alwaysShowChapterTransition = graph.readerPreferences.alwaysShowChapterTransition
        if (alwaysShowChapterTransitionWasSet) {
            alwaysShowChapterTransition.set(alwaysShowChapterTransitionBefore)
        } else {
            alwaysShowChapterTransition.delete()
        }
    }

    @Test
    fun aShortChapterWhoseNextChapterLoadsAtOnceStaysOpenOnItsFirstPage() {
        // fixture.chapters[1] is "Chapter 2", the shorter chapter; the reader orders it first with
        // fixture.chapters[0] ("Chapter 1") as its next. See the class KDoc for why.
        val shortChapter = fixture.chapters[1]
        val nextChapter = fixture.chapters[0]

        repeat(REPEATS) { iteration ->
            val scenario = ActivityScenario.launch<ReaderActivity>(
                ReaderIntents.newIntent(context, fixture.mangaId, shortChapter.id, page = 0),
            )
            try {
                val viewModel = viewModelOf(scenario)

                val ready = await(OPEN_TIMEOUT_MILLIS, "iteration $iteration: the session never became ready") {
                    viewModel.state.first { it.phase is ReaderPhase.Ready && it.totalPages > 0 }
                }
                // This is the first assertion on purpose: the defect this test guards can already
                // have landed the reader on the next chapter by the time Ready is observed (the jump
                // happens within a few milliseconds of the short chapter's own page 1 selection), so
                // every assertion after this one would otherwise fail on the next chapter's own state
                // rather than name the jump itself.
                val actualId = ready.currentChapter?.chapter?.id
                assertEquals(
                    "iteration $iteration: the reader opened on chapter $actualId, not the short chapter " +
                        "${shortChapter.id} it was asked for: the right to left pager selected the next " +
                        "chapter's page when that chapter loaded",
                    shortChapter.id,
                    actualId,
                )
                assertEquals(
                    "iteration $iteration: the reader did not open in right-to-left mode",
                    ReadingMode.RIGHT_TO_LEFT,
                    ready.readingMode,
                )
                assertEquals(
                    "iteration $iteration: the fixture's short chapter has a different page count",
                    SHORT_CHAPTER_PAGES,
                    ready.totalPages,
                )
                assertEquals(
                    "iteration $iteration: the reader's next chapter is not the fixture's other chapter " +
                        "(the fixture's rows share one source order and the reader keeps their insertion " +
                        "order, in which the local source lists Chapter 2 first)",
                    nextChapter.id,
                    ready.window?.nextChapter?.chapter?.id,
                )

                awaitTrue(
                    NEXT_CHAPTER_TIMEOUT_MILLIS,
                    "iteration $iteration: the next chapter never finished loading",
                ) {
                    viewModel.state.value.window?.nextChapter?.state is ReaderChapter.State.Loaded
                }

                // The window in which the race above can land the reader on the next chapter's page:
                // the pager's own first layout pass and the page callbacks it drives.
                Thread.sleep(SETTLE_MILLIS)

                val settled = viewModel.state.value
                assertEquals(
                    "iteration $iteration: the reader is showing the next chapter instead of the short one",
                    shortChapter.id,
                    settled.currentChapter?.chapter?.id,
                )
                assertEquals(
                    "iteration $iteration: the reader moved off the short chapter's first page",
                    0,
                    settled.currentPage,
                )
            } finally {
                scenario.close()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun viewModelOf(scenario: ActivityScenario<ReaderActivity>): ReaderViewModel {
        lateinit var viewModel: ReaderViewModel
        scenario.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java]
        }
        return viewModel
    }

    private fun <T> await(timeoutMillis: Long, message: String, block: suspend () -> T): T =
        runBlocking {
            try {
                withTimeout(timeoutMillis) { block() }
            } catch (e: TimeoutCancellationException) {
                throw AssertionError("$message (after $timeoutMillis ms)", e)
            }
        }

    private fun awaitTrue(timeoutMillis: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("$message (after $timeoutMillis ms)")
            }
            Thread.sleep(POLL_MILLIS)
        }
    }

    private companion object {
        const val FIXTURE_PREFIX = "saikou-reader-pager-preload-"

        /** Fewer pages than any chapter-preload window `PreloadPolicy.compute()` can size. */
        const val SHORT_CHAPTER_PAGES = 3

        /** Enough pages to be a distinct fixture from the short chapter; nothing about its count matters. */
        const val NEXT_CHAPTER_PAGES = 4

        /** `ReaderPreferences.alwaysShowChapterTransition`'s own default. */
        const val DEFAULT_ALWAYS_SHOW_CHAPTER_TRANSITION = true

        /** Chosen once in two full-suite runs; ten repeats give the race room to show up again. */
        const val REPEATS = 10

        const val OPEN_TIMEOUT_MILLIS = 30_000L

        /** A page list fetch and decode for the longer fixture chapter. */
        const val NEXT_CHAPTER_TIMEOUT_MILLIS = 20_000L

        /** The pager's first layout pass and the page-change callbacks it drives, per the logcat above. */
        const val SETTLE_MILLIS = 500L

        const val POLL_MILLIS = 100L
    }
}
