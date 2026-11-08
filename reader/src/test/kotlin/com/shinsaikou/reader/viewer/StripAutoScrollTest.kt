package com.shinsaikou.reader.viewer

import android.content.res.Resources
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/**
 * The Experimental long strip auto-scroll. The recycler and the choreographer are mocks (the unit
 * tests have no Android runtime), so a test calls [StripAutoScroll.doFrame] itself with the frame
 * times it wants. What a real choreographer and a real view tree do on a device is not covered here.
 */
class StripAutoScrollTest {

    private val choreographer = mockk<Choreographer>(relaxed = true)
    private val touchListener = slot<RecyclerView.OnItemTouchListener>()
    private val scrolls = mutableListOf<Int>()
    private var canScroll = true
    private var more = false
    private var moreAsked = 0

    private val metrics = DisplayMetrics().apply { density = 1f }
    private val resources = mockk<Resources> { every { displayMetrics } returns metrics }
    private val recycler = mockk<RecyclerView>(relaxed = true) {
        every { resources } returns this@StripAutoScrollTest.resources
        every { canScrollVertically(1) } answers { canScroll }
        every { scrollBy(0, any()) } answers { scrolls += secondArg<Int>() }
        every { addOnItemTouchListener(capture(touchListener)) } returns Unit
    }

    private val scroll = StripAutoScroll(recycler) { moreAsked++; more }

    @BeforeEach
    fun setUp() {
        mockkStatic(Choreographer::class)
        every { Choreographer.getInstance() } returns choreographer
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Choreographer::class)
    }

    // The speed of a level.

    @Test
    fun `level n is n times 25 dp per second, in pixels through the density`() {
        autoScrollPixelsPerSecond(1, 1f) shouldBe 25f
        autoScrollPixelsPerSecond(4, 1f) shouldBe 100f
        autoScrollPixelsPerSecond(4, 2f) shouldBe 200f
        autoScrollPixelsPerSecond(10, 3f) shouldBe 750f
        autoScrollPixelsPerSecond(7, 2.625f) shouldBe 7 * 25f * 2.625f
        autoScrollPixelsPerSecond(1, 0.75f) shouldBe 18.75f
    }

    @Test
    fun `a level below 1 counts as 1 and a level above 10 counts as 10`() {
        autoScrollPixelsPerSecond(0, 2f) shouldBe autoScrollPixelsPerSecond(1, 2f)
        autoScrollPixelsPerSecond(-5, 2f) shouldBe autoScrollPixelsPerSecond(1, 2f)
        autoScrollPixelsPerSecond(Int.MIN_VALUE, 2f) shouldBe autoScrollPixelsPerSecond(1, 2f)
        autoScrollPixelsPerSecond(11, 2f) shouldBe autoScrollPixelsPerSecond(10, 2f)
        autoScrollPixelsPerSecond(Int.MAX_VALUE, 2f) shouldBe autoScrollPixelsPerSecond(10, 2f)
    }

    @Test
    fun `start reads the density of the strip's display`() {
        metrics.density = 2f
        scroll.start(4)
        frames(0, 100) // the first frame only sets the clock
        scrolls shouldBe listOf(20)
    }

    // The frame logic.

    @Test
    fun `the first frame only sets the clock and scrolls nothing`() {
        scroll.start(10)
        scroll.doFrame(5 * SECOND)
        scrolls shouldBe emptyList()
        scroll.isRunning shouldBe true
    }

    @Test
    fun `a frame scrolls the speed times the time since the last one`() {
        scroll.start(4) // 100 px per second at density 1
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 50 * MS)
        scrolls shouldBe listOf(5)
    }

    @Test
    fun `the fraction of a pixel a frame leaves is carried to the next, so the slowest speed moves`() {
        scroll.start(1) // 25 px per second: 0.4 px per 16 ms frame
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 16 * MS)
        scroll.doFrame(SECOND + 32 * MS)
        scrolls shouldBe emptyList()
        scroll.doFrame(SECOND + 48 * MS) // 1.2 px banked
        scrolls shouldBe listOf(1)
    }

    @Test
    fun `a thousand frames add up to the speed times the time, with no drift`() {
        scroll.start(4) // 100 px per second, 1.6 px per 16 ms frame
        var now = SECOND
        scroll.doFrame(now)
        repeat(1000) {
            now += 16 * MS
            scroll.doFrame(now)
        }
        // 16 seconds at 100 px per second is 1600 px; only the pixel still in the remainder is short.
        val total = scrolls.sum()
        (total in 1599..1600) shouldBe true
        scrolls.all { it == 1 || it == 2 } shouldBe true
    }

    @Test
    fun `a dropped frame does not slow the strip, the time it took is counted`() {
        scroll.start(4)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 16 * MS) // 1 px, 0.6 left
        scroll.doFrame(SECOND + 16 * MS + 80 * MS) // 8 px plus the 0.6
        scrolls shouldBe listOf(1, 8)
    }

    @Test
    fun `a stall counts as at most 100 ms, so the strip goes on and does not leap`() {
        scroll.start(4)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 30 * SECOND) // half a minute asleep
        scrolls shouldBe listOf(10)
    }

    @Test
    fun `a gap of exactly 100 ms counts in full`() {
        scroll.start(4)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 100 * MS)
        scrolls shouldBe listOf(10)
    }

    @Test
    fun `a clock that runs backwards scrolls nothing`() {
        scroll.start(10)
        scroll.doFrame(2 * SECOND)
        scroll.doFrame(SECOND)
        scrolls shouldBe emptyList()
        scroll.isRunning shouldBe true
    }

    @Test
    fun `a start while running changes the speed and does not queue a second callback`() {
        scroll.start(1)
        scroll.start(10)
        verify(exactly = 1) { choreographer.postFrameCallback(scroll) }
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 40 * MS)
        scrolls shouldBe listOf(10) // 250 px per second
    }

    @Test
    fun `a start after a stop begins a fresh run, with no scroll for the time spent stopped`() {
        scroll.start(10)
        scroll.doFrame(SECOND)
        scroll.stop()
        scroll.start(10)
        scroll.doFrame(60 * SECOND)
        scrolls shouldBe emptyList()
    }

    @Test
    fun `a start after a stop forgets the remainder of the run before`() {
        scroll.start(1)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 100 * MS) // 2.5 px: 2 scrolled, 0.5 left
        scroll.stop()
        scroll.start(1)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 100 * MS)
        scrolls shouldBe listOf(2, 2)
    }

    @Test
    fun `a run asks for the next frame after each one`() {
        scroll.start(4)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 16 * MS)
        // One from start and one per frame.
        verify(exactly = 3) { choreographer.postFrameCallback(scroll) }
    }

    @Test
    fun `a frame that arrives after a stop does nothing and asks for no more`() {
        scroll.start(10)
        scroll.doFrame(SECOND)
        scroll.stop()
        scroll.doFrame(SECOND + 50 * MS)
        scrolls shouldBe emptyList()
        verify(exactly = 2) { choreographer.postFrameCallback(scroll) } // start and the first frame
    }

    @Test
    fun `a scroll that stops the run through a listener is not followed by another request`() {
        every { recycler.scrollBy(0, any()) } answers {
            scrolls += secondArg<Int>()
            scroll.stop()
        }
        scroll.start(10)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 50 * MS)
        scrolls.size shouldBe 1
        scroll.isRunning shouldBe false
        verify(exactly = 2) { choreographer.postFrameCallback(scroll) }
    }

    // The end of the strip.

    @Test
    fun `at the end with nothing more coming the run stops`() {
        canScroll = false
        more = false
        scroll.start(10)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 50 * MS)
        scroll.isRunning shouldBe false
        scrolls shouldBe emptyList()
        verify { choreographer.removeFrameCallback(scroll) }
        verify { recycler.keepScreenOn = false }
        verify(exactly = 2) { choreographer.postFrameCallback(scroll) } // no request after the stop
    }

    @Test
    fun `at the end with the next chapter coming the run keeps waiting`() {
        canScroll = false
        more = true
        scroll.start(10)
        scroll.doFrame(SECOND)
        repeat(20) { scroll.doFrame(SECOND + (it + 1) * 50 * MS) }
        scroll.isRunning shouldBe true
        scrolls shouldBe emptyList()
        verify(exactly = 0) { recycler.keepScreenOn = false }
    }

    @Test
    fun `it carries on when the pages arrive after a wait`() {
        canScroll = false
        more = true
        scroll.start(4)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 50 * MS)
        canScroll = true
        scroll.doFrame(SECOND + 100 * MS)
        scrolls shouldBe listOf(5)
    }

    @Test
    fun `the distance of the frames spent waiting at the end is dropped, not banked`() {
        canScroll = false
        more = true
        scroll.start(1) // 25 px per second: a 100 ms frame is 2.5 px
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 100 * MS) // 2 px due, nowhere to go: the 0.5 left must not be kept
        canScroll = true
        scroll.doFrame(SECOND + 200 * MS) // 2.5 px; with the 0.5 banked it would be 3
        scrolls shouldBe listOf(2)
    }

    @Test
    fun `the end is only asked about when there is a whole pixel to scroll`() {
        canScroll = false
        scroll.start(1)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 16 * MS) // 0.4 px
        scroll.isRunning shouldBe true
        moreAsked shouldBe 0
    }

    @Test
    fun `the next chapter is not asked about while the strip can still scroll`() {
        scroll.start(10)
        scroll.doFrame(SECOND)
        scroll.doFrame(SECOND + 50 * MS)
        moreAsked shouldBe 0
    }

    // The stops.

    @Test
    fun `a run keeps the screen on and a stop lets it go`() {
        scroll.start(4)
        verify { recycler.keepScreenOn = true }
        scroll.stop()
        verify { recycler.keepScreenOn = false }
        verify { choreographer.removeFrameCallback(scroll) }
        scroll.isRunning shouldBe false
    }

    @Test
    fun `a stop that is not running touches nothing`() {
        scroll.stop()
        verify(exactly = 0) { recycler.keepScreenOn = any() }
        verify(exactly = 0) { choreographer.removeFrameCallback(any()) }
    }

    @Test
    fun `a stop twice lets the screen go once`() {
        scroll.start(4)
        scroll.stop()
        scroll.stop()
        verify(exactly = 1) { recycler.keepScreenOn = false }
        verify(exactly = 1) { choreographer.removeFrameCallback(scroll) }
    }

    @Test
    fun `the strip gets a touch watcher on construction`() {
        verify(exactly = 1) { recycler.addOnItemTouchListener(any()) }
        touchListener.isCaptured shouldBe true
    }

    @Test
    fun `a touch down stops the run and the touch is not taken`() {
        scroll.start(4)
        val taken = touchListener.captured.onInterceptTouchEvent(recycler, event(MotionEvent.ACTION_DOWN))
        taken shouldBe false
        scroll.isRunning shouldBe false
        verify { choreographer.removeFrameCallback(scroll) }
        verify { recycler.keepScreenOn = false }
    }

    @Test
    fun `a pointer down is not a touch down and the run goes on`() {
        scroll.start(4)
        val taken = touchListener.captured.onInterceptTouchEvent(recycler, event(MotionEvent.ACTION_POINTER_DOWN))
        taken shouldBe false
        scroll.isRunning shouldBe true
    }

    @Test
    fun `a move, an up and a cancel do not stop the run and are not taken`() {
        scroll.start(4)
        listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL).forEach { action ->
            touchListener.captured.onInterceptTouchEvent(recycler, event(action)) shouldBe false
        }
        scroll.isRunning shouldBe true
    }

    @Test
    fun `a touch down while idle does nothing`() {
        touchListener.captured.onInterceptTouchEvent(recycler, event(MotionEvent.ACTION_DOWN)) shouldBe false
        verify(exactly = 0) { choreographer.removeFrameCallback(any()) }
    }

    @Test
    fun `release stops the run, removes the frame callback and lets go of the strip`() {
        scroll.start(4)
        scroll.release()
        scroll.isRunning shouldBe false
        verify { choreographer.removeFrameCallback(scroll) }
        verify { recycler.keepScreenOn = false }
        verify { recycler.removeOnItemTouchListener(touchListener.captured) }
    }

    @Test
    fun `release while idle still lets go of the touch watcher`() {
        scroll.release()
        verify { recycler.removeOnItemTouchListener(touchListener.captured) }
        verify(exactly = 0) { choreographer.removeFrameCallback(any()) }
    }

    // What is coming after the strip's last item.

    @Test
    fun `a next chapter that is waiting, loading or loaded and not yet added is coming`() {
        listOf(ReaderChapter.State.Wait, ReaderChapter.State.Loading, ReaderChapter.State.Loaded(emptyList()))
            .forEach { state -> stripHasMoreComing(listOf(page(), next(state))) shouldBe true }
    }

    @Test
    fun `a next chapter that failed is not coming, its retry is for a tap`() {
        stripHasMoreComing(listOf(page(), next(ReaderChapter.State.Error(RuntimeException())))) shouldBe false
    }

    @Test
    fun `a transition to no chapter is the end of the entry`() {
        stripHasMoreComing(listOf(page(), ChapterTransition.Next(chapter(1), null))) shouldBe false
    }

    @Test
    fun `an empty strip has nothing coming`() {
        stripHasMoreComing(emptyList()) shouldBe false
    }

    @Test
    fun `a strip that ends on a page has nothing coming`() {
        stripHasMoreComing(listOf(next(ReaderChapter.State.Wait), page())) shouldBe false
    }

    @Test
    fun `a transition to the previous chapter is not a way forward`() {
        val prev = ChapterTransition.Prev(chapter(1), chapter(0).also { it.state = ReaderChapter.State.Wait })
        stripHasMoreComing(listOf(prev)) shouldBe false
    }

    @Test
    fun `only the last item counts, a next transition earlier in the list does not`() {
        stripHasMoreComing(listOf(next(ReaderChapter.State.Wait), page(), page())) shouldBe false
    }

    private fun frames(vararg millis: Long) = millis.forEach { scroll.doFrame(SECOND + it * MS) }

    private fun event(action: Int) = mockk<MotionEvent> { every { actionMasked } returns action }

    private fun chapter(id: Long) = ReaderChapter(Chapter.create().copy(id = id, mangaId = 1))

    private fun page() = ReaderPage(0)

    private fun next(state: ReaderChapter.State): ChapterTransition.Next =
        ChapterTransition.Next(chapter(1), chapter(2).also { it.state = state })

    private companion object {
        const val MS = 1_000_000L
        const val SECOND = 1_000_000_000L
    }
}
