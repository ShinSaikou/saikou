package com.shinsaikou.reader.viewer

import android.view.Choreographer
import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import com.shinsaikou.reader.settings.ReaderSettingsOptions
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter

/**
 * Experimental auto-scroll of the long strip: moves [recycler] down by itself, a little on every
 * frame, until something stops it. Upstream has no auto-scroll.
 *
 * It scrolls with `scrollBy`, as a finger's drag does, so the strip's own scroll listener selects
 * the pages, preloads the next chapter and flows into it exactly as it does for a drag. The
 * distance comes from each frame's time, not from a count of frames, so a dropped frame does not
 * slow it down, and the fraction of a pixel a frame leaves over is carried to the next one, so the
 * slowest speed still moves.
 *
 * A touch down on the strip stops it, and the touch then does what it always does: the listener
 * only watches and never takes the stream. The reader shell stops it for the rest (the menu, a
 * dialog, the activity pausing), and the viewer when it moves to another page or is destroyed.
 * Every stop is final; only the bottom bar's button starts it again.
 *
 * While it runs the strip keeps the screen on through [android.view.View.setKeepScreenOn], which
 * lasts only while the strip is in a window, so it never outlives the reader.
 */
class StripAutoScroll(
    private val recycler: RecyclerView,
    private val moreComing: () -> Boolean,
) : Choreographer.FrameCallback {

    /**
     * Read on use, never at construction: a viewer can be built on a thread with no looper (a test,
     * for one), where there is no choreographer, and only a run on the main thread needs one.
     */
    private val choreographer: Choreographer get() = Choreographer.getInstance()

    private val touchListener = object : RecyclerView.SimpleOnItemTouchListener() {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) stop()
            return false
        }
    }

    private var pixelsPerSecond = 0f
    private var lastFrameNanos = 0L
    private var remainder = 0f

    var isRunning = false
        private set

    init {
        recycler.addOnItemTouchListener(touchListener)
    }

    /** Starts scrolling at [level], 1 to 10; a start while running only changes the speed. */
    fun start(level: Int) {
        pixelsPerSecond = autoScrollPixelsPerSecond(level, recycler.resources.displayMetrics.density)
        if (isRunning) return
        isRunning = true
        lastFrameNanos = 0L
        remainder = 0f
        recycler.keepScreenOn = true
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        choreographer.removeFrameCallback(this)
        recycler.keepScreenOn = false
    }

    /** Stops and lets go of the strip, for the viewer's destroy. */
    fun release() {
        stop()
        recycler.removeOnItemTouchListener(touchListener)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        // The first frame only sets the clock: the time before it was spent starting, not scrolling.
        if (lastFrameNanos != 0L) {
            val elapsed = (frameTimeNanos - lastFrameNanos).coerceIn(0L, MAX_FRAME_NANOS)
            val distance = pixelsPerSecond * elapsed / NANOS_PER_SECOND + remainder
            val dy = distance.toInt()
            remainder = distance - dy
            if (dy > 0) {
                if (recycler.canScrollVertically(1)) {
                    recycler.scrollBy(0, dy)
                } else if (moreComing()) {
                    // At the end while the next chapter loads: wait there rather than bank the
                    // distance, so the strip does not jump once the pages arrive.
                    remainder = 0f
                } else {
                    stop()
                    return
                }
            }
        }
        lastFrameNanos = frameTimeNanos
        // A scroll can reach a listener that stops this, so only a run still going asks again.
        if (isRunning) choreographer.postFrameCallback(this)
    }

    companion object {
        /** The speed of level 1, so level n is n times it. */
        const val DP_PER_SECOND_PER_LEVEL = 25f

        private const val NANOS_PER_SECOND = 1_000_000_000f

        /** The most one frame may count: after a long stall the strip goes on, it does not leap. */
        private const val MAX_FRAME_NANOS = 100_000_000L
    }
}

/**
 * Screen pixels per second for a speed [level], clamped to 1 to 10: [level] times 25 dp, through
 * the display's [density], so a level reads the same on every screen. The strip's zoom is not
 * counted, so a strip zoomed to twice its size passes twice as fast on screen.
 */
fun autoScrollPixelsPerSecond(level: Int, density: Float): Float =
    level.coerceIn(ReaderSettingsOptions.AUTO_SCROLL_SPEED_MIN, ReaderSettingsOptions.AUTO_SCROLL_SPEED_MAX) *
        StripAutoScroll.DP_PER_SECOND_PER_LEVEL * density

/**
 * True when a strip that cannot scroll further is waiting for more: its last item is the way into
 * a next chapter that is still to load, is loading, or has loaded and is about to be added. A next
 * chapter that failed has a retry button to tap, and no next chapter means the end of the entry.
 */
fun stripHasMoreComing(items: List<Any>): Boolean {
    val last = items.lastOrNull() as? ChapterTransition.Next ?: return false
    val to = last.to ?: return false
    return to.state !is ReaderChapter.State.Error
}
