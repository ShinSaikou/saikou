package com.shinsaikou.reader.state

import com.shinsaikou.reader.memory.DisplayInfoProvider
import com.shinsaikou.reader.memory.MemoryInfoProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * One preload window for the whole reader, sized by a memory budget. Contract 07 section 3.5.
 *
 * Upstream keeps three unreconciled constants: five pages before a chapter preload in each viewer and
 * four pages of image fetch ahead in its online loader, none of them aware of the heap or of the
 * page size. Here the window is computed once per session and again after a trim.
 *
 * [compute] is pure over the two providers, which is why they are interfaces: the three worked
 * rows of section 3.5 are pinned on the JVM.
 */
@SingleIn(AppScope::class)
@Inject
class PreloadPolicy(
    private val memory: MemoryInfoProvider,
    private val display: DisplayInfoProvider,
) {

    /**
     * @param decodeAhead pages after the current one whose views may stay bound (tiles resident).
     * @param decodeBehind pages before the current one kept bound.
     * @param fetchAhead pages after the current one whose bytes are fetched into the disk cache.
     * @param chapterPreloadAt when the pages remaining in the chapter drop below this, the next
     *   chapter's page list loads.
     */
    data class Window(
        val decodeAhead: Int,
        val decodeBehind: Int,
        val fetchAhead: Int,
        val chapterPreloadAt: Int,
    )

    fun compute(): Window {
        val cap = if (memory.isLowRamDevice) LOW_RAM_CAP_BYTES else CAP_BYTES
        val budgetBytes = minOf(memory.maxHeapBytes / HEAP_SHARE, cap)
        // One screen of ARGB_8888 tiles.
        val pageBytes = display.widthPx.toLong() * display.heightPx * BYTES_PER_PIXEL
        if (pageBytes <= 0) return DEFAULT
        val decodeAhead = (budgetBytes / pageBytes).toInt().coerceIn(MIN_DECODE_AHEAD, MAX_DECODE_AHEAD)
        val fetchAhead = (decodeAhead * 2).coerceIn(MIN_FETCH_AHEAD, MAX_FETCH_AHEAD)
        return Window(
            decodeAhead = decodeAhead,
            decodeBehind = 1,
            fetchAhead = fetchAhead,
            chapterPreloadAt = fetchAhead,
        )
    }

    companion object {
        const val MIB = 1024L * 1024L

        /** The window before a session has computed one, and the floor a test can compare against. */
        val DEFAULT = Window(decodeAhead = 2, decodeBehind = 1, fetchAhead = 4, chapterPreloadAt = 4)

        /** In force from the first trim until the next chapter load (section 3.3). */
        val DEGRADED = Window(decodeAhead = 1, decodeBehind = 0, fetchAhead = 2, chapterPreloadAt = 2)

        private const val HEAP_SHARE = 4L
        private const val BYTES_PER_PIXEL = 4L
        private val LOW_RAM_CAP_BYTES = 32L * MIB
        private val CAP_BYTES = 96L * MIB
        private const val MIN_DECODE_AHEAD = 1
        private const val MAX_DECODE_AHEAD = 4
        private const val MIN_FETCH_AHEAD = 2
        private const val MAX_FETCH_AHEAD = 8
    }
}
