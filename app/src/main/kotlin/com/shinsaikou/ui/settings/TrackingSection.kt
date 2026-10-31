package com.shinsaikou.ui.settings

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.AutoTrackState
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.navigation.Route
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.SectionLabel
import com.shinsaikou.ui.components.SettingsColumn
import com.shinsaikou.ui.settings.TrackingSettingsViewModel.CompareRow
import com.shinsaikou.ui.settings.TrackingSettingsViewModel.Status
import com.shinsaikou.ui.settings.TrackingSettingsViewModel.TrackerRow
import com.shinsaikou.ui.util.ErrorText
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The Tracking section (phase 3 unit T1), a plain screen since upstream's `TrackerPreference` was not
 * vendored (contract 08): a row per configured tracker, signed out with Sign in, waiting for the
 * browser with Cancel, signed in as its account with Sign out, or Sign in again when the service
 * refused the stored sign-in; the last sign-in's failure under its row through `ErrorText` (R67);
 * then, for a tracker that can compare its list with the app, its "Compare with" row (phase 4 unit
 * TC); then the reader's and the chapter list's progress rows.
 *
 * The settings list hides the section while no configured tracker is in the graph
 * ([shownSettingsSections]).
 */
@Composable
internal fun TrackingSection() {
    val viewModel: TrackingSettingsViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    var confirmingSignOut by remember { mutableStateOf<TrackerRow?>(null) }

    SettingsColumn {
        InlineNotice(stringResource(R.string.tracking_note))
        if (state.trackers.isNotEmpty()) SectionLabel(stringResource(R.string.tracking_trackers))
        state.trackers.forEach { row ->
            TrackerRowItem(row = row, viewModel = viewModel, onSignOut = { confirmingSignOut = row })
        }
        state.trackers.filter { it.compare != CompareRow.HIDDEN }.forEach { row ->
            val enabled = row.compare == CompareRow.ENABLED
            ListRow(
                title = stringResource(R.string.tracking_compare_title_format, row.name),
                detail = if (enabled) {
                    stringResource(R.string.tracking_compare_detail_format, row.name)
                } else {
                    stringResource(R.string.tracking_compare_sign_in_again)
                },
                modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA).testTag(trackerCompareRowTag(row.id)),
                onClick = if (enabled) {
                    { navigator.open(Route.TrackerCompare(row.id)) }
                } else {
                    null
                },
            )
        }

        SectionLabel(stringResource(R.string.tracking_behaviour))
        Column {
            CheckboxItem(
                label = stringResource(R.string.pref_auto_update_track),
                checked = state.autoUpdateTrack,
                onClick = { viewModel.setAutoUpdateTrack(!state.autoUpdateTrack) },
            )
            Text(
                text = stringResource(R.string.pref_auto_update_track_detail),
                modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsChipRow(R.string.pref_auto_update_track_on_mark_read) {
            AutoTrackState.entries.forEach { value ->
                FilterChip(
                    selected = state.autoUpdateTrackOnMarkRead == value,
                    onClick = { viewModel.setAutoUpdateTrackOnMarkRead(value) },
                    label = { Text(stringResource(value.labelRes)) },
                )
            }
        }
    }

    confirmingSignOut?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmingSignOut = null },
            title = { Text(stringResource(R.string.tracking_sign_out_title_format, row.name)) },
            text = { Text(stringResource(R.string.tracking_sign_out_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingSignOut = null
                        viewModel.signOut(row.id)
                    },
                ) { Text(stringResource(R.string.tracking_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * One tracker: its name, its state in a line, and the actions that state offers. Sign in and Sign in
 * again start the browser sign-in from the visible activity, whose notifications bring the owner
 * back to it; Cancel ends a sign-in waiting on the browser.
 */
@Composable
private fun TrackerRowItem(row: TrackerRow, viewModel: TrackingSettingsViewModel, onSignOut: () -> Unit) {
    val activity = LocalActivity.current
    val resources = LocalResources.current
    val signIn: () -> Unit = {
        activity?.let {
            val returnIntent = Intent(it, it.javaClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            viewModel.signIn(row.id, returnIntent)
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = row.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (row.status) {
                Status.SIGNED_OUT -> stringResource(R.string.tracking_signed_out)
                Status.WAITING_FOR_BROWSER -> stringResource(R.string.tracking_waiting_for_browser)
                Status.SIGNED_IN -> if (row.displayName.isBlank()) {
                    stringResource(R.string.tracking_signed_in)
                } else {
                    stringResource(R.string.tracking_signed_in_as_format, row.displayName)
                }
                Status.SIGN_IN_AGAIN -> ErrorText.of(resources, ErrorKey.TRACKER_AUTH_EXPIRED, null)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.status == Status.SIGN_IN_AGAIN) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (row.status == Status.WAITING_FOR_BROWSER) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (row.status) {
                Status.SIGNED_OUT -> Button(onClick = signIn, enabled = activity != null && row.canSignIn) {
                    Text(stringResource(R.string.tracking_sign_in))
                }
                Status.WAITING_FOR_BROWSER -> OutlinedButton(onClick = { viewModel.cancelSignIn(row.id) }) {
                    Text(stringResource(R.string.action_cancel))
                }
                Status.SIGNED_IN -> OutlinedButton(onClick = onSignOut) {
                    Text(stringResource(R.string.tracking_sign_out))
                }
                Status.SIGN_IN_AGAIN -> {
                    Button(onClick = signIn, enabled = activity != null && row.canSignIn) {
                        Text(stringResource(R.string.tracking_sign_in_again))
                    }
                    OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.tracking_sign_out)) }
                }
            }
        }
        row.problem?.let { problem ->
            Text(
                text = ErrorText.of(resources, problem.key, problem.detail),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The tag on a tracker's "Compare with" row, for a device test to tap it. */
internal fun trackerCompareRowTag(trackerId: Long): String = "tracker_compare_$trackerId"

private val AutoTrackState.labelRes: Int
    get() = when (this) {
        AutoTrackState.ALWAYS -> R.string.auto_track_always
        AutoTrackState.ASK -> R.string.auto_track_ask
        AutoTrackState.NEVER -> R.string.auto_track_never
    }
