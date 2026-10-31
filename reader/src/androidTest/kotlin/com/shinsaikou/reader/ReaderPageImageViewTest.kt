package com.shinsaikou.reader

import android.graphics.Bitmap
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_SMART_FIT
import com.github.chrisbanes.photoview.PhotoView
import com.shinsaikou.reader.pipeline.ImageFormat
import com.shinsaikou.reader.pipeline.PageRoute
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Instrumentation, not a unit test, for three reasons. [ReaderPageImageView] is a `FrameLayout` that
 * builds real child views; the static branch builds the image view fork, whose scale type validation
 * and whose `recycle` live in a binary with no source in this repository, so only a real class load
 * on a device says whether `SCALE_TYPE_SMART_FIT` is accepted; and the animated branch enqueues a
 * Coil request, which needs a live `SingletonImageLoader`.
 *
 * Contract 07 part 2 section 9.3 sets the ceiling for what may be claimed here: this asserts that
 * the API is called correctly, not that the result looks right. Nothing below touches tiling, scale,
 * or a rendered pixel. What is observable from outside the class is the child view the fork produced
 * for a route, that child's visibility, and whether the fork still holds an image.
 *
 * The fixture is a PNG generated in process, so this test never skips.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPageImageViewTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var pagePng: ByteArray
    private lateinit var view: ReaderPageImageView

    @Before
    fun setUp() {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        pagePng = ByteArrayOutputStream()
            .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
        bitmap.recycle()

        onMain { view = ReaderPageImageView(context) }
    }

    @Test
    fun regionRouteBuildsTheForkedImageView() {
        onMain { view.setImage(pageSource(), regionRoute(), config()) }

        val child = view.singleChild()
        assertTrue("a Region route built ${child.javaClass.name}", child is SubsamplingScaleImageView)
        assertTrue((child as SubsamplingScaleImageView).hasImage())
        assertEquals(View.VISIBLE, child.visibility)
    }

    /**
     * The bytes handed over are the same static PNG the Region test uses. That is the point: since
     * `setImage` takes a decided [PageRoute] instead of sniffing the header a second time, the route
     * alone picks the branch.
     */
    @Test
    fun animatedRouteBuildsTheCoilImageView() {
        onMain { view.setImage(pageSource(), animatedRoute(), config()) }

        val child = view.singleChild()
        assertTrue("an Animated route built ${child.javaClass.name}", child is PhotoView)
    }

    @Test
    fun recycleReleasesTheForkedImage() {
        onMain {
            view.setImage(pageSource(), regionRoute(), config())
            view.recycle()
        }

        val child = view.singleChild() as SubsamplingScaleImageView
        assertFalse("recycle left the fork holding its image", child.hasImage())
        assertEquals(View.GONE, child.visibility)
    }

    /**
     * The Coil branch has no `hasImage` to interrogate: `dispose` cancels the request and returns
     * nothing observable, so visibility is the whole of what can be asserted. The load itself is
     * asynchronous and is deliberately not awaited.
     */
    @Test
    fun recycleReleasesTheCoilImage() {
        onMain {
            view.setImage(pageSource(), animatedRoute(), config())
            view.recycle()
        }

        val child = view.singleChild()
        assertTrue(child is PhotoView)
        assertEquals(View.GONE, child.visibility)
    }

    @Test
    fun switchingRouteReplacesTheChildView() {
        onMain { view.setImage(pageSource(), regionRoute(), config()) }
        assertTrue(view.singleChild() is SubsamplingScaleImageView)

        onMain { view.setImage(pageSource(), animatedRoute(), config()) }
        assertTrue(view.singleChild() is PhotoView)

        onMain { view.setImage(pageSource(), regionRoute(), config()) }
        assertTrue(view.singleChild() is SubsamplingScaleImageView)
    }

    @Test
    fun webtoonRoutesBuildTheWebtoonViews() {
        lateinit var webtoon: ReaderPageImageView
        onMain {
            webtoon = ReaderPageImageView(context, isWebtoon = true)
            webtoon.setImage(pageSource(), regionRoute(), config())
        }
        assertTrue(webtoon.singleChild() is WebtoonSubsamplingImageView)

        onMain { webtoon.setImage(pageSource(), animatedRoute(), config()) }
        val animated = webtoon.singleChild()
        assertTrue(animated is AppCompatImageView)
        assertFalse("the webtoon animated branch must not zoom on its own", animated is PhotoView)
    }

    /**
     * `setMinimumScaleType` is the one call on the static path that can reject its argument, and the
     * `imageScaleType` preference integer reaches it unvalidated. Reaching `hasImage` proves the
     * call returned rather than threw, because `setNonAnimatedImage` makes it before it hands the
     * source over.
     */
    @Test
    fun smartFitConfigReachesTheForkWithoutThrowing() {
        onMain { view.setImage(pageSource(), regionRoute(), config(SCALE_TYPE_SMART_FIT)) }

        val child = view.singleChild() as SubsamplingScaleImageView
        assertTrue(child.hasImage())
    }

    /**
     * The guard for the test above: the fork's setter really does validate, so accepting
     * `SCALE_TYPE_SMART_FIT` is a property of this binary and not of a setter that takes anything.
     */
    @Test
    fun forkRejectsAScaleTypeItDoesNotKnow() {
        var smartFit: Throwable? = null
        var unknown: Throwable? = null
        onMain {
            val fork = SubsamplingScaleImageView(context)
            smartFit = runCatching { fork.setMinimumScaleType(SCALE_TYPE_SMART_FIT) }.exceptionOrNull()
            unknown = runCatching { fork.setMinimumScaleType(UNKNOWN_SCALE_TYPE) }.exceptionOrNull()
        }

        assertNull(smartFit)
        assertTrue("an unknown scale type was accepted: $unknown", unknown is IllegalArgumentException)
    }

    private fun pageSource(): BufferedSource = Buffer().write(pagePng)

    private fun regionRoute() = PageRoute.Region(ImageFormat.PNG, PAGE_WIDTH, PAGE_HEIGHT)

    private fun animatedRoute() = PageRoute.Animated(ImageFormat.GIF, PAGE_WIDTH, PAGE_HEIGHT)

    private fun config(minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE) = ReaderPageImageView.Config(
        zoomDuration = 1,
        minimumScaleType = minimumScaleType,
    )

    private fun ReaderPageImageView.singleChild(): View {
        assertEquals("the fork stacked views instead of replacing its child", 1, childCount)
        return getChildAt(0)
    }

    /**
     * A throw inside `runOnMainSync` never reaches the caller: it goes to the main looper and leaves
     * the waiting thread blocked forever, so the block swallows it and it is rethrown here where
     * JUnit can see it. Every assertion is made after the block, off the main thread, on state the
     * `runOnMainSync` handoff has already published.
     */
    private fun onMain(block: () -> Unit) {
        var failure: Throwable? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private companion object {
        const val PAGE_WIDTH = 64
        const val PAGE_HEIGHT = 96

        /** Outside the fork's valid range whatever scale types it gains later. */
        const val UNKNOWN_SCALE_TYPE = -1
    }
}
