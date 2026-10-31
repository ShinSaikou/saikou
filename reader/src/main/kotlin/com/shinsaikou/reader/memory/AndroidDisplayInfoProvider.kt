package com.shinsaikou.reader.memory

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * [DisplayInfoProvider] over the display metrics. Decision D28, same reasoning as
 * [AndroidMemoryInfoProvider]: a `Context` is all it needs, so it stays in `:reader`.
 *
 * Read live rather than cached, so a rotation is reflected without anyone having to invalidate
 * anything. `PreloadPolicy` and `AnimatedImagePolicy` multiply the two dimensions, which a rotation
 * leaves alone; `SplitPlanner` reads [maxDimensionPx], which is rotation-independent by
 * construction. That is the whole reason this class holds no policy: the numbers it reports change
 * under the callers' feet, and only pure functions of them are safe to recompute.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AndroidDisplayInfoProvider(private val context: Context) : DisplayInfoProvider {

    override val widthPx: Int
        get() = context.resources.displayMetrics.widthPixels

    override val heightPx: Int
        get() = context.resources.displayMetrics.heightPixels

    override val maxDimensionPx: Int
        get() = maxOf(widthPx, heightPx)
}
