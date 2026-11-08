package com.shinsaikou.reader.viewer

import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.TwoPageLayout
import com.shinsaikou.reader.pipeline.ImageFormat
import com.shinsaikou.reader.pipeline.PageRoute
import com.shinsaikou.reader.pipeline.TargetSize
import com.shinsaikou.reader.state.chapter
import com.shinsaikou.reader.viewer.SpreadShape.NARROW
import com.shinsaikou.reader.viewer.SpreadShape.UNKNOWN
import com.shinsaikou.reader.viewer.SpreadShape.WIDE
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The Experimental two-page view's pairing (ruling R165 item 2), apart from the views. */
class SpreadsTest {

    // ---- Pairing ----

    @Test
    fun `the first page stands alone and the rest pair in order, an odd count ending on a pair`() {
        SpreadPlanner.plan(narrow(5), shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1, 2), Spread(3, 4))
    }

    @Test
    fun `an even count leaves the last page alone`() {
        SpreadPlanner.plan(narrow(6), shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1, 2), Spread(3, 4), Spread(5))
    }

    @Test
    fun `one page and no page`() {
        SpreadPlanner.plan(narrow(1), shift = false) shouldContainExactly listOf(Spread(0))
        SpreadPlanner.plan(narrow(1), shift = true) shouldContainExactly listOf(Spread(0))
        SpreadPlanner.plan(emptyList(), shift = false) shouldContainExactly emptyList()
    }

    @Test
    fun `shift pairs the first page with the second`() {
        SpreadPlanner.plan(narrow(5), shift = true) shouldContainExactly
            listOf(Spread(0, 1), Spread(2, 3), Spread(4))
        SpreadPlanner.plan(narrow(6), shift = true) shouldContainExactly
            listOf(Spread(0, 1), Spread(2, 3), Spread(4, 5))
    }

    @Test
    fun `a wide page in the middle stands alone and the pairing starts again after it`() {
        val shapes = listOf(NARROW, NARROW, NARROW, NARROW, WIDE, NARROW, NARROW, NARROW)
        SpreadPlanner.plan(shapes, shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1, 2), Spread(3), Spread(4), Spread(5, 6), Spread(7))
        // Shift moves the chapter's first run only: after the wide page the pairing is the same.
        SpreadPlanner.plan(shapes, shift = true) shouldContainExactly
            listOf(Spread(0, 1), Spread(2, 3), Spread(4), Spread(5, 6), Spread(7))
    }

    @Test
    fun `a chapter that opens on a wide page pairs from the page after it, and shifted that page is alone`() {
        val shapes = listOf(WIDE, NARROW, NARROW, NARROW, NARROW)
        SpreadPlanner.plan(shapes, shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1, 2), Spread(3, 4))
        SpreadPlanner.plan(shapes, shift = true) shouldContainExactly
            listOf(Spread(0), Spread(1), Spread(2, 3), Spread(4))
    }

    @Test
    fun `a wide last page stands alone and leaves the page before it alone too`() {
        SpreadPlanner.plan(listOf(NARROW, NARROW, NARROW, NARROW, WIDE), shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1, 2), Spread(3), Spread(4))
        SpreadPlanner.plan(listOf(NARROW, NARROW, NARROW, WIDE), shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1, 2), Spread(3))
    }

    @Test
    fun `two wide pages in a row each stand alone`() {
        SpreadPlanner.plan(listOf(NARROW, WIDE, WIDE, NARROW, NARROW), shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1), Spread(2), Spread(3, 4))
    }

    @Test
    fun `an unknown size pairs as narrow, and the spreads after it change once it turns out wide`() {
        val unknown = listOf(NARROW, NARROW, NARROW, UNKNOWN, NARROW, NARROW, NARROW)
        SpreadPlanner.plan(unknown, shift = false) shouldContainExactly
            SpreadPlanner.plan(narrow(7), shift = false)
        SpreadPlanner.plan(unknown, shift = false) shouldContainExactly
            listOf(Spread(0), Spread(1, 2), Spread(3, 4), Spread(5, 6))

        val known = unknown.toMutableList().apply { this[3] = WIDE }
        val after = SpreadPlanner.plan(known, shift = false)
        after shouldContainExactly listOf(Spread(0), Spread(1, 2), Spread(3), Spread(4, 5), Spread(6))
        // The spreads before the wide page are the same.
        after.take(2) shouldContainExactly SpreadPlanner.plan(unknown, shift = false).take(2)
    }

    @Test
    fun `in right to left the earlier page sits on the right, in left to right on the left`() {
        Spread(3, 4).onScreen(rightToLeft = true) shouldContainExactly listOf(4, 3)
        Spread(3, 4).onScreen(rightToLeft = false) shouldContainExactly listOf(3, 4)
        Spread(0).onScreen(rightToLeft = true) shouldContainExactly listOf(0)

        val pages = pages(2)
        PageSpread(pages[0], pages[1]).onScreen(rightToLeft = true) shouldContainExactly listOf(pages[1], pages[0])
        PageSpread(pages[0], pages[1]).onScreen(rightToLeft = false) shouldContainExactly listOf(pages[0], pages[1])
    }

    // ---- Shapes ----

    @Test
    fun `a page's shape comes from its route, and a wide one is wider than it is tall`() {
        spreadShape(ReaderPage(0)) shouldBe UNKNOWN
        spreadShape(page(0, 800, 1200)) shouldBe NARROW
        spreadShape(page(0, 1000, 1000)) shouldBe NARROW
        spreadShape(page(0, 1600, 1200)) shouldBe WIDE
        spreadShape(ReaderPage(0).apply { route = PageRoute.Animated(ImageFormat.GIF, 400, 600) }) shouldBe WIDE
        spreadShape(ReaderPage(0).apply { route = PageRoute.Unsupported }) shouldBe WIDE
    }

    @Test
    fun `a page found animated after it was paired goes alone before anything decodes it`() {
        val animated = ReaderPage(1).apply {
            route = PageRoute.Animated(ImageFormat.GIF, 400, 600)
            stream = { error("an animated page is never decoded for a spread") }
        }

        SpreadImage.draw(listOf(animated, page(2, 800, 1200)), cropBorders = false, TargetSize(1080, 1920, 4_000_000L)) shouldBe
            SpreadImage.Result.Alone(animated)
    }

    @Test
    fun `what a spread measured wins over an unknown route`() {
        spreadShape(ReaderPage(0), measured = WIDE) shouldBe WIDE
    }

    @Test
    fun `a tall page cut into parts and each part stand alone`() {
        val tall = page(0, 800, 6000).apply { partCount = 2 }
        spreadShape(tall) shouldBe WIDE
        spreadShape(InsertPage(tall, partIndex = 1)) shouldBe WIDE
    }

    // ---- Items and index mapping ----

    @Test
    fun `a chapter's items are its pages alone and its pairs as spreads`() {
        val pages = pages(5)
        spreadItems(pages, shift = false) shouldContainExactly listOf(
            pages[0],
            PageSpread(pages[1], pages[2]),
            PageSpread(pages[3], pages[4]),
        )
    }

    @Test
    fun `a spread built again for the same two pages is equal, so its view is kept`() {
        val pages = pages(5)
        spreadItems(pages, shift = false) shouldBe spreadItems(pages, shift = false)
    }

    @Test
    fun `a slider page lands on the spread that holds it, and resume opens the spread of the saved page`() {
        val pages = pages(7)
        val items = spreadItems(pages, shift = false)
        // 0 | 1-2 | 3-4 | 5-6
        items.positionShowing(pages[0]) shouldBe 0
        items.positionShowing(pages[1]) shouldBe 1
        items.positionShowing(pages[2]) shouldBe 1
        items.positionShowing(pages[5]) shouldBe 3
        items.positionShowing(pages[6]) shouldBe 3
        // Progress is saved as a spread's earlier page; reopening on it shows the same spread.
        val saved = (items[2] as PageSpread).first
        items[items.positionShowing(saved)] shouldBe items[2]
        items.positionShowing(ReaderPage(9)) shouldBe -1
    }

    @Test
    fun `split parts stay in the list as pages alone, and the page after them pairs from there`() {
        val pages = pages(5).toMutableList()
        val tall = pages[2].apply { partCount = 2 }
        val part = InsertPage(tall, partIndex = 1)
        pages.add(3, part)
        // 0 | 1 | 2 | 2' | 3-4: page 1 is left over before the tall page.
        spreadItems(pages, shift = false) shouldContainExactly listOf(
            pages[0],
            pages[1],
            tall,
            part,
            PageSpread(pages[4], pages[5]),
        )
        spreadItems(pages, shift = false).positionShowing(part) shouldBe 3
    }

    @Test
    fun `the page on screen is found again when the mode toggles`() {
        val pages = pages(5)
        val single: List<Any> = pages
        val double = spreadItems(pages, shift = false)

        // One page to two: the spread that holds it.
        double.positionShowing(leadPage(single[3])!!) shouldBe 2
        // Two pages to one: the spread's earlier page.
        single.positionShowing(leadPage(double[2])!!) shouldBe 3
        // A half of a page split in two stands for its page; a tall part stands for itself.
        leadPage(InsertPage(pages[2], partIndex = 0)) shouldBe pages[2]
        val part = InsertPage(pages[2], partIndex = 1)
        leadPage(part) shouldBe part
        leadPage("a transition") shouldBe null
    }

    @Test
    fun `a page measured wide splits its spread and the pairing after it`() {
        val pages = pages(5)
        spreadItems(pages, shift = false) { if (it === pages[2]) WIDE else null } shouldContainExactly listOf(
            pages[0],
            pages[1],
            pages[2],
            PageSpread(pages[3], pages[4]),
        )
    }

    @Test
    fun `holdsPage knows a page and the spreads it is part of`() {
        val pages = pages(3)
        holdsPage(pages[0], pages[0]) shouldBe true
        holdsPage(PageSpread(pages[1], pages[2]), pages[2]) shouldBe true
        holdsPage(PageSpread(pages[1], pages[2]), pages[0]) shouldBe false
        holdsPage(null, pages[0]) shouldBe false
    }

    // ---- When two pages show ----

    @Test
    fun `two pages show in the horizontal paged modes, as the layout says`() {
        listOf(ReadingMode.LEFT_TO_RIGHT, ReadingMode.RIGHT_TO_LEFT).forEach { mode ->
            twoPagesShown(true, TwoPageLayout.ALWAYS, mode, landscape = false) shouldBe true
            twoPagesShown(true, TwoPageLayout.IN_LANDSCAPE, mode, landscape = true) shouldBe true
            twoPagesShown(true, TwoPageLayout.IN_LANDSCAPE, mode, landscape = false) shouldBe false
            twoPagesShown(true, TwoPageLayout.NEVER, mode, landscape = true) shouldBe false
        }
    }

    @Test
    fun `never with the switch off, and never in vertical paged or the long strip`() {
        TwoPageLayout.entries.forEach { layout ->
            twoPagesShown(false, layout, ReadingMode.RIGHT_TO_LEFT, landscape = true) shouldBe false
        }
        listOf(ReadingMode.VERTICAL, ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL).forEach { mode ->
            twoPagesShown(true, TwoPageLayout.ALWAYS, mode, landscape = true) shouldBe false
        }
    }

    @Test
    fun `shift is kept per entry and toggles`() {
        val shifted = emptySet<String>().withShiftToggled(7)
        shifted shouldBe setOf("7")
        shifted.withShiftToggled(8) shouldBe setOf("7", "8")
        shifted.withShiftToggled(7) shouldBe emptySet()
    }

    private val readerChapter = ReaderChapter(chapter(1))

    private fun narrow(count: Int): List<SpreadShape> = List(count) { NARROW }

    private fun page(index: Int, width: Int, height: Int): ReaderPage = ReaderPage(index).apply {
        chapter = readerChapter
        route = PageRoute.Region(ImageFormat.JPEG, width, height)
    }

    /** [count] portrait pages with known sizes. */
    private fun pages(count: Int): List<ReaderPage> = List(count) { page(it, 800, 1200) }
}
