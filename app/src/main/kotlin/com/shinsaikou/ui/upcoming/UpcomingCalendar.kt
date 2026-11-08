package com.shinsaikou.ui.upcoming

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinsaikou.i18n.R
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.onDay
import kotlinx.datetime.toJavaDayOfWeek
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaYearMonth
import kotlinx.datetime.toKotlinDayOfWeek
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private const val DAYS_IN_WEEK = 7

/** Upstream's sizes: 16sp text, and a dot per entry up to three, each a shade darker than the one before. */
private val FONT_SIZE = 16.sp
private const val MAX_DOTS = 3
private const val DOT_ALPHA_STEP = 0.3f
private val DOT_SIZE = 56.dp / 12
private const val MONTH_CHANGE_MILLIS = 200

/**
 * Upstream narrows the grid to 360dp in a medium width window (600 to 839dp) and lets it fill any
 * other. A phone in portrait is narrower than either cap, so its days fill the width as upstream's
 * do; past 839dp upstream's days grow until a landscape phone cannot hold a week, so Saikou caps
 * those at seven days of 64dp.
 */
private val MEDIUM_WIDTH_MAX = 360.dp
private val WIDE_MAX = 448.dp

/**
 * The weeks of [month] as rows of seven, the first starting on [firstDayOfWeek]. A null is a day of
 * the month before or after, which the grid leaves blank, so every row is a whole week.
 */
fun calendarWeeks(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate?>> {
    val blanks = (month.firstDay.dayOfWeek.isoDayNumber - firstDayOfWeek.isoDayNumber + DAYS_IN_WEEK) % DAYS_IN_WEEK
    val cells = List(blanks) { null } + (1..month.numberOfDays).map { month.onDay(it) }
    return cells.chunked(DAYS_IN_WEEK) { week -> week + List(DAYS_IN_WEEK - week.size) { null } }
}

/**
 * Upstream's Upcoming calendar: the month's name with an arrow either side of it, the weekday
 * initials from the locale's first day of the week, then the days. A day with releases in [days]
 * carries a dot per entry up to three, and a tap on it is [onDay]; today is ringed and the days
 * before it are faded, as nothing is predicted for them.
 */
@Composable
fun UpcomingCalendar(
    month: YearMonth,
    days: Map<LocalDate, UpcomingDay>,
    today: LocalDate,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val maxWidth = if (windowWidth >= 600.dp && windowWidth < 840.dp) MEDIUM_WIDTH_MAX else WIDE_MAX
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek.toKotlinDayOfWeek() }
    val weeks = remember(month, firstDayOfWeek) { calendarWeeks(month, firstDayOfWeek) }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CalendarHeader(
            month = month,
            locale = locale,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth,
        )
        Column(modifier = Modifier.widthIn(max = maxWidth)) {
            Row {
                (0 until DAYS_IN_WEEK).forEach { offset ->
                    val weekday = DayOfWeek((firstDayOfWeek.isoDayNumber - 1 + offset) % DAYS_IN_WEEK + 1)
                    Text(
                        text = weekday.toJavaDayOfWeek().getDisplayName(TextStyle.NARROW, locale),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = FONT_SIZE,
                    )
                }
            }
            val dateFormatter = remember(locale) {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
            }
            weeks.forEach { week ->
                Row {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            CalendarDay(
                                date = date,
                                count = days[date]?.count ?: 0,
                                today = today,
                                description = dateFormatter.format(date.toJavaLocalDate()),
                                onClick = { onDay(date) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Upstream's header: the month's name, sliding up for a later month and down for an earlier one, and
 * the two arrows. Upstream formats the name as "MMMM yyyy", which reads right in English only; this
 * asks the locale for its own order and case ("2026年11月", "ноябрь 2026").
 */
@Composable
private fun CalendarHeader(
    month: YearMonth,
    locale: Locale,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val formatter = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMyyyy"), locale)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.small)
            .padding(start = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val later = targetState > initialState
                val spec = tween<IntOffset>(MONTH_CHANGE_MILLIS)
                val fade = tween<Float>(MONTH_CHANGE_MILLIS)
                (slideInVertically(spec) { height -> if (later) height else -height } + fadeIn(fade))
                    .togetherWith(slideOutVertically(spec) { height -> if (later) -height else height } + fadeOut(fade))
                    .using(SizeTransform(clip = false))
            },
            label = "upcomingMonth",
        ) { shown ->
            Text(text = formatter.format(shown.toJavaYearMonth()), style = MaterialTheme.typography.titleLarge)
        }
        Row {
            IconButton(onClick = onPreviousMonth) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.upcoming_previous_month),
                )
            }
            IconButton(onClick = onNextMonth) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.upcoming_next_month),
                )
            }
        }
    }
}

/**
 * One day, a circle as wide as its seventh of the row, as upstream's `circleLayout` makes it. Only a
 * day with releases takes a tap, where upstream's every day ripples and an empty one then does
 * nothing; a screen reader hears the whole date and the count.
 */
@Composable
private fun CalendarDay(
    date: LocalDate,
    count: Int,
    today: LocalDate,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onBackground = MaterialTheme.colorScheme.onBackground
    val label = if (count > 0) {
        "$description, ${pluralStringResource(R.plurals.upcoming_entry_count, count, count)}"
    } else {
        description
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(if (date == today) Modifier.border(BorderStroke(1.dp, onBackground), CircleShape) else Modifier)
            .clip(CircleShape)
            .clickable(enabled = count > 0, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = date.day.toString(),
            color = if (date < today) onBackground.copy(alpha = DISABLED_ALPHA) else onBackground,
            textAlign = TextAlign.Center,
            fontSize = FONT_SIZE,
            fontWeight = FontWeight.SemiBold,
        )
        if (count > 0) {
            Row(modifier = Modifier.offset(y = 12.dp)) {
                repeat(count.coerceAtMost(MAX_DOTS)) { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .size(DOT_SIZE)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = (index + 1) * DOT_ALPHA_STEP)),
                    )
                }
            }
        }
    }
}
