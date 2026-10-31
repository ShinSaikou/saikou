package com.shinsaikou.ui.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Asked once, on the first launch that finds the app still restricted. Dismissing it counts as an
 * answer, so there is no way to leave the question open; Settings keeps a row that asks again.
 */
@Composable
internal fun BatteryExemptionDialog(onAllow: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(R.string.battery_dialog_title)) },
        text = { Text(stringResource(R.string.battery_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onAllow) { Text(stringResource(R.string.battery_dialog_allow)) }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.battery_dialog_skip)) }
        },
    )
}
