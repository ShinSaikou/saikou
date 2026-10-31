package com.shinsaikou.ui.appupdate

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.appupdate.AppUpdate
import com.shinsaikou.i18n.R
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The update prompt over the main screen, and the trip to Android's "Install unknown apps" page that
 * Update makes first when the owner has not allowed this app to install apps yet. Composed by the
 * navigation host outside its `NavHost`, so the view model is the activity's and the prompt shows
 * over whichever screen is open, Settings included after a manual check.
 */
@Composable
internal fun AppUpdatePrompt() {
    val viewModel: AppUpdateViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onInstallPermissionResult()
    }

    LaunchedEffect(state.askInstallPermission) {
        if (!state.askInstallPermission) return@LaunchedEffect
        viewModel.installPermissionAsked()
        val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
        try {
            installPermission.launch(page)
        } catch (_: ActivityNotFoundException) {
            viewModel.onInstallPermissionResult(pageOpened = false)
        }
    }

    val appName = stringResource(R.string.app_name)
    val downloading = (state.notice as? AppUpdateViewModel.Notice.Downloading)?.let {
        stringResource(R.string.app_update_downloading_toast, appName, it.version)
    }
    val notAllowed = stringResource(R.string.app_update_install_not_allowed, appName)
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        context.toast(if (notice is AppUpdateViewModel.Notice.Downloading) downloading else notAllowed)
        viewModel.noticeShown()
    }

    state.offer?.let { update ->
        AppUpdateDialog(
            update = update,
            onUpdate = viewModel::update,
            onNotNow = viewModel::notNow,
            onSkip = viewModel::skip,
        )
    }
}

/**
 * "Saikou 0.0.2 is available", the release notes as plain text in a scrolling body, and the three
 * answers. Dismissing it (back, a tap outside) is "Not now".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppUpdateDialog(
    update: AppUpdate,
    onUpdate: () -> Unit,
    onNotNow: () -> Unit,
    onSkip: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onNotNow) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.app_update_available, stringResource(R.string.app_name), update.version)) },
                text = {
                    Text(
                        text = update.notes.ifEmpty { stringResource(R.string.app_update_no_notes) },
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                buttons = {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall, Alignment.End),
                    ) {
                        TextButton(onClick = onSkip) { Text(stringResource(R.string.app_update_skip)) }
                        TextButton(onClick = onNotNow) { Text(stringResource(R.string.app_update_not_now)) }
                        TextButton(onClick = onUpdate) { Text(stringResource(R.string.app_update_update)) }
                    }
                },
            )
        }
    }
}
