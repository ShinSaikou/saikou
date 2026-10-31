package com.shinsaikou.reader

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonFrame
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation, not a unit test: the zoom geometry lives in a `RecyclerView`'s own measure pass,
 * its layout params, its scale and its translation, and taps in a platform gesture detector, none
 * of which a JVM stub keeps. The view is measured by hand at fixed sizes, never laid out or
 * attached, so the numbers do not depend on the device's screen.
 */
@RunWith(AndroidJUnit4::class)
class WebtoonRecyclerViewTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Rotation and split screen reach the long strip without a new viewer (`configChanges`). */
    @Test
    fun zoomingOutAfterTheWindowShrankUsesTheNewHeight() = onMain {
        val view = recycler()
        view.measureAt(WIDTH, 2000)
        view.measureAt(WIDTH, 1200)
        assertEquals(1200, view.originalHeight)

        view.onScale(0.5f)
        assertEquals(2400, view.layoutParams.height)

        // While zoomed out the height is the zoom's, so a measure at it is not the window's.
        view.measureAt(WIDTH, 2400)
        assertEquals(1200, view.originalHeight)
    }

    /** Zoomed out, the strip's own height is the zoom's, so the window's reaches it through the frame. */
    @Test
    fun aResizeWhileZoomedOutKeepsTheZoomedOutStripFillingTheWindow() = onMain {
        val view = recycler()
        val frame = WebtoonFrame(context).apply { addView(view) }
        frame.measureAt(WIDTH, 2000)
        view.onScale(0.5f)
        assertEquals(4000, view.layoutParams.height)

        frame.measureAt(WIDTH, 1200)
        assertEquals(1200, view.originalHeight)
        assertEquals(2400, view.measuredHeight)
        // Scaled by half about its centre, the strip's top is at the window's top.
        assertEquals(-600f, view.y, 0.5f)

        view.onScale(1.25f)
        assertEquals(1920, view.layoutParams.height)
    }

    @Test
    fun zoomingBackInHandsTheHeightBackToTheWindow() = onMain {
        val view = recycler()
        view.measureAt(WIDTH, 2000)
        view.onScale(0.5f)
        view.onScale(2f)
        assertEquals(MATCH_PARENT, view.layoutParams.height)

        view.measureAt(WIDTH, 1500)
        assertEquals(1500, view.originalHeight)
    }

    /** Side padding is empty margin: zoomed in, the strip pans to the page's edge, not the margin's. */
    @Test
    fun zoomedInPanningStopsAtThePageEdgeRatherThanThePaddingEdge() = onMain {
        val view = recycler()
        view.sidePaddingPercent = 10
        view.measureAt(WIDTH, 2000)
        view.onScale(2f)
        view.x = WIDTH.toFloat()
        view.onScale(1f)

        val halfWidth = WIDTH / 2
        assertEquals((halfWidth - view.sidePaddingPixels) * 2f - halfWidth, view.x, 0.5f)
    }

    /** A split-screen or freeform window is narrower than the display the padding was taken from. */
    @Test
    fun sidePaddingIsAShareOfTheStripsWidthRatherThanTheDisplays() = onMain {
        val view = recycler()
        view.sidePaddingPercent = 10
        view.measureAt(WIDTH, 2000)
        assertEquals(WIDTH / 10, view.sidePaddingPixels)

        view.measureAt(WIDTH / 2, 2000)
        assertEquals(WIDTH / 2 / 10, view.sidePaddingPixels)
    }

    /** The keyboard's page step: one screenful of the strip at any zoom, in its unscaled pixels. */
    @Test
    fun visibleHeightIsTheWindowsHeightAtTheCurrentZoom() = onMain {
        val view = recycler()
        view.measureAt(WIDTH, 1200)
        assertEquals(1200, view.visibleHeight)

        view.onScale(2f)
        assertEquals(600, view.visibleHeight)

        view.onScale(0.25f)
        assertEquals(2400, view.visibleHeight)
    }

    /** Without double-tap zoom there is no second tap to wait for, and two quick taps are two taps. */
    @Test
    fun aTapGoesOutWhenTheFingerLiftsWhileDoubleTapZoomIsOff() = onMain {
        val view = recycler()
        val taps = mutableListOf<Float>()
        view.tapListener = { taps += it.x }
        view.doubleTapZoom = false

        view.tap(x = 10f, at = 0)
        assertEquals(listOf(10f), taps)

        view.tap(x = 20f, at = 100)
        assertEquals(listOf(10f, 20f), taps)
    }

    @Test
    fun aTapWaitsForASecondOneWhileDoubleTapZoomIsOn() = onMain {
        val view = recycler()
        val taps = mutableListOf<Float>()
        view.tapListener = { taps += it.x }

        view.tap(x = 10f, at = 0)
        assertEquals(emptyList<Float>(), taps)
    }

    /** The confirmed tap comes a double-tap timeout later, when the reader may have let go of the strip. */
    @Test
    fun aTapConfirmedAfterTheStripLeftItsWindowIsDropped() {
        val taps = mutableListOf<Float>()
        onMain {
            // Never attached, as a strip the closing reader has already released.
            val view = recycler()
            view.tapListener = { taps += it.x }
            view.tap(x = 10f, at = SystemClock.uptimeMillis())
        }

        Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 200L)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertEquals(emptyList<Float>(), taps)
    }

    private fun WebtoonRecyclerView.tap(x: Float, at: Long) {
        val down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, 10f, 0)
        val up = MotionEvent.obtain(at, at + 50, MotionEvent.ACTION_UP, x, 10f, 0)
        onTouchEvent(down)
        onTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private fun recycler() = WebtoonRecyclerView(context).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

    private fun View.measureAt(width: Int, height: Int) = measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
    )

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
        /** An odd width no screen has, so a value taken from the display instead would show. */
        const val WIDTH = 731
    }
}
