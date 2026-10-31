package com.shinsaikou.reader.memory

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import coil3.SingletonImageLoader
import com.shinsaikou.reader.bench.ReaderBenchHooks

/**
 * Owns everything the reader does under memory pressure that the reducer cannot express. Contract
 * 07 part 1b section 4.10.
 *
 * Fresh; upstream v0.20.4 has no trim handling anywhere. `onTrimMemory`, `onLowMemory`,
 * `TRIM_MEMORY_*` and `ComponentCallbacks2` appear nowhere in it.
 *
 * The fact that shapes the design: five of the seven trim levels are deprecated as of API 34, and
 * they are exactly the ones that used to signal foreground pressure. The two that survive fire
 * only once the reader is already hidden, which is after the moment that matters. So [sample]
 * synthesizes the foreground levels from a heap watermark, and the level table below is a table of
 * rows that actually fire.
 *
 * Registered by the view model on the application context, once per reader session, so exactly one
 * guard exists however many times the activity is recreated. The activity must not also send a
 * trim event: the framework delivers to both, and a doubled trim during a fling is a jank source.
 *
 * @param onTrimLevel where a level goes. Part 1b writes this as `send: (ReaderEvent) -> Unit`, but
 *   `ReaderEvent` belongs to `com.shinsaikou.reader.state` and is not built yet, and a memory-package
 *   object has no business importing the reducer's vocabulary. The view model wires it as
 *   `{ level -> send(ReaderEvent.TrimMemory(level)) }`.
 * @param clock injected so the rate limit is testable.
 * @param dropImageMemoryCache injected for the same reason: the default reaches the Coil singleton
 *   through the application context, which a JVM test cannot build.
 */
@Suppress("DEPRECATION") // The five foreground levels are deprecated; naming them is the point.
class ReaderMemoryGuard(
    private val context: Context,
    private val memory: MemoryInfoProvider,
    private val onTrimLevel: (Int) -> Unit,
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val dropImageMemoryCache: () -> Unit = {
        SingletonImageLoader.get(context).memoryCache?.clear()
    },
) : ComponentCallbacks2 {

    private var lastSample: Long? = null

    /**
     * The whole level table. Below `RUNNING_CRITICAL` the reader only reports the level, which the
     * session turns into a degraded preload window and a viewer trim. From `RUNNING_CRITICAL` up
     * it also drops Coil's memory cache.
     *
     * The memory cache is the app's, not the reader's: reader requests disable it, so what is in
     * there is covers. That makes it the largest droppable heap consumer the reader can reach and
     * the cheapest to lose, because a cover re-decodes from disk. Dropping it at `RUNNING_LOW` as
     * well would give up the library grid on a spike the viewer trim alone usually settles.
     *
     * The `reader.trim level=<n>` marker (part 2 section 8.4) is emitted here and is the only
     * observable this class has: it keeps no counter by design. `LowRamReproTest` in `:app` reads
     * the levels back from logcat and records them in the `low_ram_repro_pass` fragment's method
     * note, but does not assert on them: under ruling R61 the row passes on one process id, no
     * `OutOfMemoryError`, a cover cache within its share of the memory class and no per-pass
     * growth in live allocation, because a device with room to spare need not trim.
     */
    override fun onTrimMemory(level: Int) {
        ReaderBenchHooks.mark("reader.trim level=$level")
        onTrimLevel(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            dropImageMemoryCache()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

    /**
     * The foreground pressure signal the platform no longer sends. Cheap: two `Runtime` reads,
     * rate limited to one in [SAMPLE_INTERVAL_MS]. Called once per page turn and once per chapter
     * load, never from a holder bind and never per frame.
     *
     * The watermarks are where they are because the gap between 85 percent and an
     * `OutOfMemoryError` on a 512 MB heap is about 77 MB, which is room for a trim to take effect,
     * and because below 75 percent the preload window would flap on ordinary allocation sawtooth.
     */
    fun sample() {
        val now = clock()
        val last = lastSample
        if (last != null && now - last < SAMPLE_INTERVAL_MS) return
        lastSample = now

        val used = memory.usedHeapBytes.toDouble() / memory.maxHeapBytes
        when {
            used >= CRITICAL_FRACTION -> onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            used >= LOW_FRACTION -> onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        }
    }

    companion object {
        const val LOW_FRACTION = 0.75
        const val CRITICAL_FRACTION = 0.85
        const val SAMPLE_INTERVAL_MS = 2_000L
    }
}
