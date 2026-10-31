// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/util/lang/DateExtensions.kt (modified: yes) only toLocalDate and toRelativeString; strings from com.shinsaikou.i18n; zone and today are parameters
package eu.kanade.tachiyomi.util.lang

import android.content.Context
import com.shinsaikou.i18n.R
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.absoluteValue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The day an epoch time falls on, in the device's zone as upstream reads it. The zone is a parameter
 * (phase 4 unit UG) so a test can fix it; every caller in the app passes none.
 */
fun Long.toLocalDate(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate {
    return Instant.fromEpochMilliseconds(this).toLocalDateTime(zone).date
}

/**
 * Upstream's relative date: within a week either side of today it is a word ("Today", "Yesterday",
 * "3 days ago", "Tomorrow", "In 2 days"), further out or with [relative] off it is the date in
 * [dateFormat]. [today] is a parameter so a test can fix it; the app passes none.
 */
fun LocalDate.toRelativeString(
    context: Context,
    relative: Boolean = true,
    dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT),
    today: LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date,
): String {
    if (!relative) {
        return dateFormat.format(this.toJavaLocalDate())
    }
    val difference = this.daysUntil(today)
    return when {
        difference < -7 -> dateFormat.format(this.toJavaLocalDate())
        difference < 0 -> context.resources.getQuantityString(
            R.plurals.upcoming_relative_time,
            difference.absoluteValue,
            difference.absoluteValue,
        )

        difference < 1 -> context.getString(R.string.relative_time_today)
        difference < 7 -> context.resources.getQuantityString(
            R.plurals.relative_time,
            difference,
            difference,
        )

        else -> dateFormat.format(this.toJavaLocalDate())
    }
}
