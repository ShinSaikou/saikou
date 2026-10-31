package com.shinsaikou.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * How many days the graph's wall clock is moved ahead, as every variant sees it (phase 3 unit H,
 * Q12). A debug build binds `ClockOffset`, the holder in `src/debug` that an instrumentation test
 * sets; every other build binds [NoClockOffset], so the holder is not in a release APK (phase 4 unit
 * GH, R94 carry-over 5). The runner argument's name stays here, where the androidTest source set,
 * which R90 also compiles against release, can read it.
 */
interface ClockOffsetSetting {

    var days: Int

    companion object {
        /** The instrumentation runner argument that carries it. */
        const val EXTRA_OFFSET_DAYS = "saikou.clock.offsetDays"
    }
}

/**
 * The binding of every build but debug: always 0, and setting it keeps nothing. A release build's
 * clock never asks for it anyway (`PlatformBindings.clockFor(debug = false, ...)`).
 */
@ContributesBinding(AppScope::class)
@Inject
class NoClockOffset : ClockOffsetSetting {

    override var days: Int
        get() = 0
        set(_) = Unit
}
