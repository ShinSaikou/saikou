package com.shinsaikou.reader

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.decode.Decoder
import com.shinsaikou.domain.preferences.AppTheme
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.state.PreloadPolicy
import com.shinsaikou.reader.viewer.ViewerHost
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewerAdapter
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonAdapter
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * A transition reached before the chapter it leads to has loaded, in a real viewer laid out in a
 * window: the case a source with one page per chapter hits on every page turn. With "always show
 * chapter transition" off, the transition goes once that chapter loads, and the pager lands where
 * turning past it would have in all three paged viewers; with the setting on, it stays.
 *
 * Instrumentation, because the pager only creates its page views, and only follows an item across
 * an update, once it is attached to a window and laid out.
 */
@RunWith(AndroidJUnit4::class)
class ChapterTransitionViewerTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = MainScope()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        scope.cancel()
    }

    @Test
    fun aTransitionToTheNextChapterGivesWayToItsFirstPageOnceItLoads() = VIEWERS.forEach { (name, build) ->
        val current = loaded(id = 1, pages = 1)
        val next = waiting(id = 2)
        val viewer = show(build, ViewerChapters(current, prevChapter = null, nextChapter = next), alwaysShow = false)

        onMain { viewer.moveToNext() }
        assertTrue("$name: the transition is not shown", shown(viewer) is ChapterTransition.Next)

        next.load(pages = 3)
        onMain { viewer.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next)) }

        assertSame("$name: not on the next chapter's first page", next.pages!!.first(), shown(viewer))
        assertTrue("$name: a transition is left", items(viewer).none { it is ChapterTransition.Next })
    }

    @Test
    fun aTransitionToThePreviousChapterGivesWayToItsLastPageOnceItLoads() = VIEWERS.forEach { (name, build) ->
        val current = loaded(id = 2, pages = 1)
        val previous = waiting(id = 1)
        val viewer = show(build, ViewerChapters(current, prevChapter = previous, nextChapter = null), alwaysShow = false)

        onMain { viewer.moveToPrevious() }
        assertTrue("$name: the transition is not shown", shown(viewer) is ChapterTransition.Prev)

        previous.load(pages = 3)
        onMain { viewer.setChapters(ViewerChapters(current, prevChapter = previous, nextChapter = null)) }

        assertSame("$name: not on the previous chapter's last page", previous.pages!!.last(), shown(viewer))
        assertTrue("$name: a transition is left", items(viewer).none { it is ChapterTransition.Prev })
    }

    @Test
    fun withTransitionsAlwaysShownTheTransitionStays() = VIEWERS.forEach { (name, build) ->
        val current = loaded(id = 1, pages = 1)
        val next = waiting(id = 2)
        val viewer = show(build, ViewerChapters(current, prevChapter = null, nextChapter = next), alwaysShow = true)

        onMain { viewer.moveToNext() }
        val transition = shown(viewer)
        assertTrue("$name: the transition is not shown", transition is ChapterTransition.Next)

        next.load(pages = 3)
        onMain { viewer.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next)) }

        assertEquals("$name: the transition did not stay", transition, shown(viewer))
    }

    @Test
    fun inTheLongStripATransitionReachedGoesOnceTheNextChapterLoads() {
        val current = loaded(id = 1, pages = 1)
        val next = waiting(id = 2)
        lateinit var viewer: WebtoonViewer
        scenario.onActivity { activity ->
            viewer = WebtoonViewer(FakeHost(activity, ReaderPreferences(InMemoryPreferenceStore())))
            viewer.config.alwaysShowChapterTransition = false
            activity.setContentView(viewer.getView())
        }
        onMain { viewer.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next)) }
        val items = { (viewer.recycler.adapter as WebtoonAdapter).items }

        // Scrolled down to the transition, which is what makes it the strip's current item.
        onMain { viewer.onScrolled(pos = items().indexOfFirst { it is ChapterTransition.Next }) }
        next.load(pages = 3)
        onMain { viewer.setChapters(ViewerChapters(current, prevChapter = null, nextChapter = next)) }

        assertTrue("a transition is left", items().none { it is ChapterTransition.Next })
        assertEquals(current.pages!! + next.pages!!, items().filterIsInstance<ReaderPage>())
    }

    /** A viewer in the activity's window, on [chapters]' first page, with the pager laid out. */
    private fun show(
        build: (ViewerHost) -> PagerViewer,
        chapters: ViewerChapters,
        alwaysShow: Boolean,
    ): PagerViewer {
        lateinit var viewer: PagerViewer
        scenario.onActivity { activity ->
            viewer = build(FakeHost(activity, ReaderPreferences(InMemoryPreferenceStore())))
            // Set on the config itself: an in-memory preference emits changes only, never its
            // current value, so the config's own collectors would leave both at their defaults.
            viewer.config.alwaysShowChapterTransition = alwaysShow
            // Page turns without the scroll animation, so a turn is done when the looper is idle.
            viewer.config.usePageTransitions = false
            activity.setContentView(viewer.getView())
        }
        onMain { viewer.setChapters(chapters) }
        assertSame("the viewer did not open on the first page", chapters.currChapter.pages!!.first(), shown(viewer))
        return viewer
    }

    /** Runs [block] on the main thread, then waits for the layout pass it caused. */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
        instrumentation.waitForIdleSync()
    }

    private fun items(viewer: PagerViewer): List<Any> = (viewer.pager.adapter as PagerViewerAdapter).items

    private fun shown(viewer: PagerViewer): Any? {
        var item: Any? = null
        instrumentation.runOnMainSync { item = items(viewer).getOrNull(viewer.pager.currentItem) }
        return item
    }

    private fun waiting(id: Long) = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1))

    private fun loaded(id: Long, pages: Int) = waiting(id).apply { load(pages) }

    private fun ReaderChapter.load(pages: Int) {
        state = ReaderChapter.State.Loaded(List(pages) { ReaderPage(it).also { page -> page.chapter = this } })
    }

    private inner class FakeHost(
        override val context: Context,
        override val readerPreferences: ReaderPreferences,
    ) : ViewerHost {
        override val scope: CoroutineScope = this@ChapterTransitionViewerTest.scope
        override val menuVisible = false
        override val isScrollingThroughPages = false
        override val appTheme = AppTheme.DEFAULT
        override val isDark = true
        override val isAmoled = false
        override val manga: Manga? = null

        override fun preloadWindow() = PreloadPolicy.DEFAULT
        override fun onPageSelected(page: ReaderPage) = Unit
        override fun onTransitionSelected(transition: ChapterTransition) = Unit
        override fun requestPreloadChapter(chapter: ReaderChapter) = Unit
        override fun onPageLongTap(page: ReaderPage) = Unit
        override fun toggleMenu() = Unit
        override fun showMenu() = Unit
        override fun hideMenu() = Unit
        override fun setNavigationOverlay(navigation: ViewerNavigation, showOnStart: Boolean) = Unit
        override fun onReaderThemeChanged() = Unit
        override fun animatedDecoderFactory(): Decoder.Factory? = null
    }

    private companion object {
        val VIEWERS: List<Pair<String, (ViewerHost) -> PagerViewer>> = listOf(
            "left to right" to ::L2RPagerViewer,
            "right to left" to ::R2LPagerViewer,
            "vertical" to ::VerticalPagerViewer,
        )
    }
}
