package com.shinsaikou.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import eu.kanade.presentation.components.relativeDateText
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock

/** Upstream's hourglass button, in the primary colour while the owner chose the interval. */
@Composable
internal fun UpdateIntervalButton(cadence: UpdateCadence, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (cadence is UpdateCadence.Custom) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Icon(
            imageVector = Icons.Outlined.HourglassEmpty,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(updateCadenceLabel(cadence))
    }
}

/**
 * Why and when the entry is checked. While [offerChoice] is on, chips set the number of days between
 * checks, [chosenDays] being the stored choice: a value restored from elsewhere that is not a chip
 * selects none. A chip applies at once and the dialog stays, so the new next check shows here.
 */
@Composable
internal fun UpdateIntervalDialog(
    cadence: UpdateCadence,
    chosenDays: Int?,
    offerChoice: Boolean,
    relativeTime: Boolean,
    dateFormat: String,
    onChoose: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.update_interval_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(updateCadenceBody(cadence))
                val today = rememberToday()
                when (cadence) {
                    is UpdateCadence.Custom -> Text(
                        stringResource(
                            R.string.update_interval_next_check,
                            relativeDateText(maxOf(cadence.nextCheck, today), relativeTime, dateFormat),
                        ),
                    )
                    is UpdateCadence.Estimated -> Text(
                        stringResource(
                            R.string.update_interval_next_check,
                            relativeDateText(maxOf(cadence.nextCheck, today), relativeTime, dateFormat),
                        ),
                    )
                    is UpdateCadence.Predicted -> {
                        Text(
                            stringResource(
                                R.string.update_interval_next_release,
                                relativeDateText(maxOf(cadence.nextRelease, today), relativeTime, dateFormat),
                            ),
                        )
                        Text(stringResource(R.string.update_interval_automatic_off))
                    }
                    UpdateCadence.CompletedSkipped,
                    UpdateCadence.UnreadSkipped,
                    UpdateCadence.NotStartedSkipped,
                    -> Text(stringResource(R.string.update_interval_refresh_note))
                    else -> Unit
                }
                if (offerChoice) IntervalChoices(chosenDays = chosenDays, onChoose = onChoose)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun IntervalChoices(chosenDays: Int?, onChoose: (Int) -> Unit) {
    Text(
        text = stringResource(R.string.update_interval_choice_heading),
        style = MaterialTheme.typography.titleSmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = chosenDays == null,
            onClick = { onChoose(0) },
            label = { Text(stringResource(R.string.update_interval_choice_automatic)) },
        )
        INTERVAL_CHOICES.forEach { days ->
            FilterChip(
                selected = chosenDays == days,
                onClick = { onChoose(days) },
                label = { Text(pluralStringResource(R.plurals.update_interval_days, days, days)) },
            )
        }
    }
}

@Composable
private fun updateCadenceLabel(cadence: UpdateCadence): String {
    val today = rememberToday()
    return when (cadence) {
        UpdateCadence.FetchOnce -> stringResource(R.string.update_interval_label_fetch_once)
        UpdateCadence.CompletedSkipped -> stringResource(R.string.update_interval_label_completed)
        UpdateCadence.UnreadSkipped -> stringResource(R.string.update_interval_label_unread)
        UpdateCadence.NotStartedSkipped -> stringResource(R.string.update_interval_label_not_started)
        UpdateCadence.EveryUpdate -> stringResource(R.string.update_interval_label_every_update)
        UpdateCadence.Completed, UpdateCadence.NoPrediction -> stringResource(R.string.update_interval_label_none)
        is UpdateCadence.Custom -> pluralStringResource(
            R.plurals.update_interval_label_custom,
            cadence.days,
            cadence.days,
        )
        is UpdateCadence.Estimated -> dayLabel(
            days = today.daysUntil(cadence.nextCheck),
            todayRes = R.string.update_interval_label_next_today,
            laterRes = R.plurals.update_interval_label_next,
        )
        is UpdateCadence.Predicted -> dayLabel(
            days = today.daysUntil(cadence.nextRelease),
            todayRes = R.string.update_interval_label_release_today,
            laterRes = R.plurals.update_interval_label_release,
        )
    }
}

/** A date that has passed reads as today: the next update is the first that can act on it. */
@Composable
private fun dayLabel(days: Int, todayRes: Int, laterRes: Int): String =
    if (days <= 0) stringResource(todayRes) else pluralStringResource(laterRes, days, days)

@Composable
private fun updateCadenceBody(cadence: UpdateCadence): String = when (cadence) {
    UpdateCadence.FetchOnce -> stringResource(R.string.update_interval_body_fetch_once)
    UpdateCadence.CompletedSkipped -> stringResource(R.string.update_interval_body_completed_skipped)
    UpdateCadence.UnreadSkipped -> stringResource(R.string.update_interval_body_unread)
    UpdateCadence.NotStartedSkipped -> stringResource(R.string.update_interval_body_not_started)
    UpdateCadence.EveryUpdate -> stringResource(R.string.update_interval_body_every_update)
    UpdateCadence.Completed -> stringResource(R.string.update_interval_body_completed)
    UpdateCadence.NoPrediction -> stringResource(R.string.update_interval_body_none)
    is UpdateCadence.Custom -> stringResource(R.string.update_interval_body_custom, daysText(cadence.days))
    is UpdateCadence.Estimated -> stringResource(R.string.update_interval_body_estimated, daysText(cadence.days))
    is UpdateCadence.Predicted -> stringResource(R.string.update_interval_body_estimated, daysText(cadence.days))
}

@Composable
private fun daysText(days: Int): String = pluralStringResource(R.plurals.update_interval_days, days, days)

@Composable
private fun rememberToday(): LocalDate =
    remember { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date }

/** The days a chip offers, up to the estimate's own longest interval. */
private val INTERVAL_CHOICES = listOf(1, 2, 3, 5, 7, 10, 14, 21, 28)
