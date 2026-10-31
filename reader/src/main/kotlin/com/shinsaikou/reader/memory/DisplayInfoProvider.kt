package com.shinsaikou.reader.memory

/**
 * What the reader knows about the display. Contract 07 section 3.5.
 *
 * An interface for the same reason as [MemoryInfoProvider]: the split plan and the animated cap
 * are pure functions of these three numbers, so their tables are pinned on the JVM.
 */
interface DisplayInfoProvider {
    val widthPx: Int
    val heightPx: Int

    /** The larger of the two, independent of the current rotation. */
    val maxDimensionPx: Int
}
