package com.shinsaikou.reader.memory

import android.app.ActivityManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * [MemoryInfoProvider] over `ActivityManager` and `Runtime`. Decision D28: the implementation is
 * `:reader`'s, because it needs nothing but a `Context`.
 *
 * Deliberately without a policy of its own. Every threshold, budget and cap lives in
 * `PreloadPolicy`, `AnimatedImagePolicy` and `ReaderMemoryGuard`, which are pure over this
 * interface and are pinned on the JVM because of it.
 *
 * Every member is a `get()` rather than a stored value, and the service lookup is lazy, because the
 * `cold_start_ms` gate row forbids `:reader` doing any work at process start.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidMemoryInfoProvider(private val context: Context) : MemoryInfoProvider {

    private val activityManager by lazy { context.getSystemService<ActivityManager>() }

    override val maxHeapBytes: Long
        get() = Runtime.getRuntime().maxMemory()

    override val isLowRamDevice: Boolean
        get() = activityManager?.isLowRamDevice == true

    override val usedHeapBytes: Long
        get() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
