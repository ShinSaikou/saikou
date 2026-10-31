package com.shinsaikou.reader.memory

/**
 * What the reader knows about the heap. Contract 07 section 3.5, plus [usedHeapBytes], which part
 * 1b section 4.10 adds for the foreground watermark (decision D45).
 *
 * An interface so `PreloadPolicy`, `AnimatedImagePolicy` and [ReaderMemoryGuard] can all be pinned
 * on the JVM without a device. The implementation reads `ActivityManager` and `Runtime`.
 */
interface MemoryInfoProvider {
    val maxHeapBytes: Long
    val isLowRamDevice: Boolean

    /**
     * `Runtime.totalMemory() - Runtime.freeMemory()`. Includes garbage not yet collected, which is
     * the conservative bias a watermark wants; [ReaderMemoryGuard] never calls `System.gc()` to
     * sharpen it.
     */
    val usedHeapBytes: Long
}
