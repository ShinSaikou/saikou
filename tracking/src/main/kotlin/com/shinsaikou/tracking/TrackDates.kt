package com.shinsaikou.tracking

import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A row's start and finish dates are calendar days: the epoch millis of the day's midnight in the
 * device's zone. That is what the track sheet's date picker stores, what MyAnimeList's dates parse
 * to, and what its API is sent back as `yyyy-MM-dd` in the same zone. A date stamped by the app goes
 * through here too, so it names the day the owner saw on the device: upstream stamped the instant,
 * and shifted a bind's first read into UTC, which sent the next or the previous day.
 */
object TrackDates {

    /** The midnight, in [zone], of the day [epochMillis] falls on there. */
    fun startOfDay(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date.atStartOfDayIn(zone).toEpochMilliseconds()

    /** Today by [clock], as a stored date. */
    fun today(clock: Clock, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
        startOfDay(clock.now().toEpochMilliseconds(), zone)
}
