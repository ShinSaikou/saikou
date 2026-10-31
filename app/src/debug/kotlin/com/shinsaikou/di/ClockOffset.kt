package com.shinsaikou.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * How many days the debug and androidTest variants move the graph's wall clock ahead (phase 3 unit
 * H, Q12): `sync_14_day_no_reconsent` dates the Drive token's expiry 14 days on without waiting 14
 * days. An instrumentation test sets it from the runner argument
 * [ClockOffsetSetting.EXTRA_OFFSET_DAYS]; nothing else sets it, and nothing persists it, so a new
 * process starts at 0.
 *
 * It moves only the `kotlin.time.Clock` the graph provides, which `GoogleOAuthClient` and
 * `DriveAccount` read; code that reads `Clock.System` itself is unchanged. Only a debug build has
 * it: it replaces [NoClockOffset] there, and no other build compiles it (phase 4 unit GH, R94
 * carry-over 5), on `GoogleTestBase`'s pattern.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoClockOffset::class])
@Inject
class ClockOffset : ClockOffsetSetting {

    @Volatile
    override var days: Int = 0
}
