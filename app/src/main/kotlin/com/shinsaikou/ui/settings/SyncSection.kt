package com.shinsaikou.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import com.shinsaikou.backup.export.BackupFileNames
import com.shinsaikou.ui.backup.KeptBackupCopyViewModel
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.i18n.R
import com.shinsaikou.sync.SyncOutcome
import com.shinsaikou.sync.SyncWorkPreferences
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.SectionLabel
import com.shinsaikou.ui.components.SettingsColumn
import com.shinsaikou.ui.settings.SyncSettingsViewModel.Fingerprint
import com.shinsaikou.ui.settings.SyncSettingsViewModel.ReportRows
import com.shinsaikou.ui.settings.SyncSettingsViewModel.Service
import com.shinsaikou.ui.settings.SyncSettingsViewModel.SignInProblem
import com.shinsaikou.ui.util.ErrorText
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.text.NumberFormat

/**
 * The Sync section (phase 3 unit G). While no backend is configured: the service choice, None,
 * Google Drive when this build offers it (unit H) and WebDAV, with Google's sign-in button or
 * WebDAV's form. While one is: the account, the last sync, Sync now, the interval, a battery row
 * while Saikou is not exempt, the safety backup, the library fingerprint (unit SP) and sign-out; or,
 * when its sign-in was refused or is gone, Sign in again in place of Sync now and the interval. Either way: the newest run's report,
 * the earlier runs behind a dialog, and one line on what sync does and does not carry (Q20).
 *
 * The form's password is held by the view model's in-memory state only, never by `rememberSaveable`;
 * whether it is shown is a plain `remember`.
 */
@Composable
internal fun SyncSection() {
    val viewModel: SyncSettingsViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmingSignOut by remember { mutableStateOf(false) }
    var showingEarlier by remember { mutableStateOf(false) }
    // The exemption is answered in a system screen; coming back from it is a resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshBatteryExemption() }

    SettingsColumn {
        // A newer format stopped the pull: nothing below matters until the app is updated.
        if (state.newerFormat) {
            Text(
                text = stringResource(R.string.error_sync_newer_format),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        val signInAgain = state.signInAgain
        when {
            !state.configured -> SignedOut(state = state, viewModel = viewModel)
            signInAgain != null -> SignInAgain(
                state = state,
                reason = signInAgain,
                viewModel = viewModel,
                onSignOut = { confirmingSignOut = true },
            )
            else -> SignedIn(state = state, viewModel = viewModel, onSignOut = { confirmingSignOut = true })
        }

        SettingsSearchTarget(R.string.sync_report_last) {
            SectionLabel(stringResource(R.string.sync_report_last))
        }
        val latest = state.reports.firstOrNull()
        if (latest == null) {
            InlineNotice(stringResource(R.string.sync_report_none))
        } else {
            RunReport(latest, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val earlier = state.reports.size - 1
        if (earlier > 0) {
            ListRow(
                title = stringResource(R.string.sync_reports_earlier),
                detail = pluralStringResource(R.plurals.sync_reports_earlier_format, earlier, earlier),
                onClick = { showingEarlier = true },
            )
        }
        InlineNotice(stringResource(R.string.sync_carries_note))
    }

    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            title = { Text(stringResource(R.string.sync_sign_out_title)) },
            text = { Text(stringResource(syncSignOutMessage(state.configuredService))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingSignOut = false
                        viewModel.signOut()
                    },
                ) { Text(stringResource(R.string.sync_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOut = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showingEarlier && state.reports.size > 1) {
        EarlierRunsDialog(reports = state.reports.drop(1), onDismiss = { showingEarlier = false })
    }
}

/**
 * The service choice, None, Google Drive and WebDAV in that order (Q2), Google Drive only while this
 * build offers it; then WebDAV's form or Google's sign-in button for the one chosen. A sign-in in
 * flight holds the choice where it is.
 */
@Composable
private fun SignedOut(state: SyncSettingsViewModel.State, viewModel: SyncSettingsViewModel) {
    SettingsSearchTarget(R.string.sync_backend) {
        SettingsChipRow(R.string.sync_backend) {
            FilterChip(
                selected = state.chosen == Service.NONE,
                onClick = { viewModel.choose(Service.NONE) },
                label = { Text(stringResource(R.string.sync_backend_none)) },
                enabled = !state.signingIn,
            )
            if (state.driveOffered) {
                FilterChip(
                    selected = state.chosen == Service.GOOGLE_DRIVE,
                    onClick = { viewModel.choose(Service.GOOGLE_DRIVE) },
                    label = { Text(stringResource(R.string.sync_backend_drive)) },
                    enabled = !state.signingIn,
                )
            }
            FilterChip(
                selected = state.chosen == Service.WEBDAV,
                onClick = { viewModel.choose(Service.WEBDAV) },
                label = { Text(stringResource(R.string.sync_backend_webdav)) },
                enabled = !state.signingIn || state.chosen == Service.WEBDAV,
            )
        }
    }
    when (state.chosen) {
        Service.WEBDAV -> WebDavForm(state = state, viewModel = viewModel)
        Service.GOOGLE_DRIVE -> {
            InlineNotice(stringResource(R.string.sync_drive_note))
            GoogleSignInButton(state = state, viewModel = viewModel, again = false)
        }
        Service.NONE -> InlineNotice(stringResource(R.string.sync_off_note))
    }
}

/**
 * "Sign in with Google", or "Sign in with Google again", from the visible activity: the owner's
 * browser opens Google's page, and the hold's notifications bring the owner back to this activity.
 * A sign-in's problem shows below it through `ErrorText`; a cancelled one shows nothing.
 */
@Composable
private fun GoogleSignInButton(state: SyncSettingsViewModel.State, viewModel: SyncSettingsViewModel, again: Boolean) {
    val activity = LocalActivity.current
    val resources = LocalResources.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                activity?.let {
                    val returnIntent = Intent(it, it.javaClass)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    viewModel.signInWithGoogle(returnIntent)
                }
            },
            enabled = activity != null && state.canSignInWithGoogle,
        ) {
            Text(
                stringResource(
                    when {
                        state.signingIn -> R.string.sync_signing_in
                        again -> R.string.sync_drive_sign_in_again
                        else -> R.string.sync_drive_sign_in
                    },
                ),
            )
        }
        if (state.signingIn) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        (state.signInProblem as? SignInProblem.Failed)?.let { problem ->
            Text(
                text = ErrorText.of(resources, problem.error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * WebDAV's sign-in form. [passwordOnly] leaves out the address and user name, for Sign in again
 * with the account known, where the caller shows them as fixed rows.
 */
@Composable
private fun WebDavForm(state: SyncSettingsViewModel.State, viewModel: SyncSettingsViewModel, passwordOnly: Boolean = false) {
    val resources = LocalResources.current
    var passwordVisible by remember { mutableStateOf(false) }
    val form = state.form
    val editable = !state.signingIn

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!passwordOnly) {
            OutlinedTextField(
                value = form.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.sync_webdav_url)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = editable,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
            )
            OutlinedTextField(
                value = form.user,
                onValueChange = viewModel::setUser,
                label = { Text(stringResource(R.string.sync_webdav_user)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = editable,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
            )
        }
        OutlinedTextField(
            value = form.password,
            onValueChange = viewModel::setPassword,
            label = { Text(stringResource(R.string.sync_webdav_password)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (passwordVisible) R.string.sync_password_hide else R.string.sync_password_show,
                        ),
                    )
                }
            },
        )
        Button(onClick = viewModel::signIn, enabled = editable && state.signInForm.canSignIn) {
            Text(stringResource(if (state.signingIn) R.string.sync_signing_in else R.string.sync_sign_in))
        }
        if (state.signingIn) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.signInProblem?.let { problem ->
            val (text, detail) = when (problem) {
                is SignInProblem.Unreachable -> ErrorText.of(resources, ErrorKey.WEBDAV_UNREACHABLE, null) to problem.detail
                is SignInProblem.NotAuthenticated -> stringResource(R.string.sync_sign_in_not_authenticated) to problem.detail
                is SignInProblem.Failed -> ErrorText.of(resources, problem.error) to null
            }
            Text(text = text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (!detail.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.sync_sign_in_detail_format, detail),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SignedIn(state: SyncSettingsViewModel.State, viewModel: SyncSettingsViewModel, onSignOut: () -> Unit) {
    val drive = state.configuredService == Service.GOOGLE_DRIVE
    // Signed in, the service is the account it signed in with, so a search for the service lands here.
    SettingsSearchTarget(R.string.sync_backend) {
        if (drive) {
            ListRow(title = stringResource(R.string.sync_drive_account), detail = state.googleAccount.orEmpty())
        } else {
            ListRow(title = stringResource(R.string.sync_server), detail = state.account?.url.orEmpty())
            ListRow(title = stringResource(R.string.sync_webdav_user), detail = state.account?.user.orEmpty())
        }
    }
    // Google gave the grant an end (a project in testing): say when, and offer to sign in again first.
    if (drive) {
        state.googleExpiresAt?.let { expiresAt ->
            InlineNotice(stringResource(R.string.sync_drive_expires_format, syncDateText(LocalContext.current, expiresAt)))
            GoogleSignInButton(state = state, viewModel = viewModel, again = true)
        }
    }
    LastSyncRow(state)

    SettingsSearchTarget(R.string.sync_now) {
        ListRow(
            title = stringResource(if (state.running) R.string.sync_running else R.string.sync_now),
            detail = stringResource(R.string.sync_now_detail),
            onClick = if (state.running) null else viewModel::syncNow,
        )
    }
    if (state.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))

    IntervalRow(
        labelRes = R.string.sync_interval,
        options = SyncWorkPreferences.INTERVALS,
        selectedHours = state.intervalHours,
        onSelect = viewModel::setInterval,
    )
    SettingsSearchTarget(R.string.sync_wifi_only) {
        Column {
            CheckboxItem(
                label = stringResource(R.string.sync_wifi_only),
                checked = state.wifiOnly,
                onClick = { viewModel.setWifiOnly(!state.wifiOnly) },
            )
            Text(
                text = stringResource(R.string.sync_wifi_only_detail),
                modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!state.batteryExempt) {
        val activity = LocalActivity.current
        ListRow(
            title = stringResource(R.string.sync_battery_exemption),
            detail = stringResource(R.string.sync_battery_exemption_detail),
            onClick = { activity?.let(viewModel::requestBatteryExemption) },
        )
    }
    SafetyBackupNotice(state)
    FingerprintRow(state, viewModel)
    SignOutRow(state, onSignOut)
}

/**
 * A configured backend whose sign-in no longer works: why, and a form to sign in again. With the
 * account known, the saved server and user name stay fixed and only the password is asked for; with
 * it gone, the whole form, and a line on what the same or another server means for this device.
 * Google Drive's is the account as a fixed row when known and "Sign in with Google again", with the
 * same two outcomes said for the Google account. Sync now and the interval wait until the sign-in
 * works; sign-out stays.
 */
@Composable
private fun SignInAgain(
    state: SyncSettingsViewModel.State,
    reason: SyncSettingsViewModel.SignInAgain,
    viewModel: SyncSettingsViewModel,
    onSignOut: () -> Unit,
) {
    if (state.configuredService == Service.GOOGLE_DRIVE) {
        GoogleSignInAgain(state = state, reason = reason, viewModel = viewModel, onSignOut = onSignOut)
        return
    }
    val passwordOnly = reason == SyncSettingsViewModel.SignInAgain.PASSWORD_REFUSED
    Text(
        text = stringResource(if (passwordOnly) R.string.sync_sign_in_again_refused else R.string.sync_sign_in_again_lost),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium,
    )
    if (passwordOnly) {
        ListRow(title = stringResource(R.string.sync_server), detail = state.account?.url.orEmpty())
        ListRow(title = stringResource(R.string.sync_webdav_user), detail = state.account?.user.orEmpty())
    } else {
        InlineNotice(stringResource(R.string.sync_sign_in_again_same_or_new))
    }
    WebDavForm(state = state, viewModel = viewModel, passwordOnly = passwordOnly)
    LastSyncRow(state)
    SafetyBackupNotice(state)
    FingerprintRow(state, viewModel)
    SignOutRow(state, onSignOut)
}

@Composable
private fun GoogleSignInAgain(
    state: SyncSettingsViewModel.State,
    reason: SyncSettingsViewModel.SignInAgain,
    viewModel: SyncSettingsViewModel,
    onSignOut: () -> Unit,
) {
    val refused = reason == SyncSettingsViewModel.SignInAgain.PASSWORD_REFUSED
    Text(
        text = stringResource(if (refused) R.string.sync_drive_sign_in_again_refused else R.string.sync_sign_in_again_lost),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium,
    )
    state.googleAccount?.let { ListRow(title = stringResource(R.string.sync_drive_account), detail = it) }
    InlineNotice(stringResource(R.string.sync_drive_same_account))
    InlineNotice(stringResource(R.string.sync_drive_other_account))
    GoogleSignInButton(state = state, viewModel = viewModel, again = true)
    LastSyncRow(state)
    SafetyBackupNotice(state)
    FingerprintRow(state, viewModel)
    SignOutRow(state, onSignOut)
}

@Composable
private fun LastSyncRow(state: SyncSettingsViewModel.State) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lastSync = when (val outcome = state.outcome) {
        null -> if (state.lastAttemptAt > 0) {
            stringResource(R.string.sync_last_started_format, syncTimeText(context, state.lastAttemptAt))
        } else {
            stringResource(R.string.sync_last_never)
        }
        SyncOutcome.Success -> stringResource(R.string.sync_last_success_format, syncTimeText(context, state.lastSuccessAt))
        is SyncOutcome.Failure -> listOfNotNull(
            stringResource(
                R.string.sync_last_failure_format,
                syncTimeText(context, state.lastAttemptAt),
                ErrorText.of(resources, outcome.key, outcome.detail),
            ),
            state.lastSuccessAt.takeIf { it > 0 }?.let {
                stringResource(R.string.sync_last_success_before_format, syncTimeText(context, it))
            },
        ).joinToString("\n")
    }
    SettingsSearchTarget(R.string.sync_last_sync) {
        ListRow(title = stringResource(R.string.sync_last_sync), detail = lastSync)
    }
}

/**
 * The safety backup's notice, with Save a copy (F11, unit storage-location), which opens the
 * system's save dialog and copies the file there, so "keep it longer" is something the owner can do.
 */
@Composable
private fun SafetyBackupNotice(state: SyncSettingsViewModel.State) {
    if (state.safetyBackupFile.isEmpty()) return
    val copier: KeptBackupCopyViewModel = metroViewModel()
    val copyState by copier.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.safetyBackupFile) { copier.watch(state.safetyBackupFile) }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFileNames.MIME_TYPE)) { uri ->
        if (uri != null) copier.saveCopy(uri)
    }
    InlineNotice(stringResource(R.string.sync_safety_backup_format, state.safetyBackupFile))
    if (copyState.available) {
        TextButton(
            onClick = { saveCopy.launch(state.safetyBackupFile) },
            modifier = Modifier.padding(horizontal = 8.dp).testTag(SAFETY_BACKUP_COPY_TAG),
        ) { Text(stringResource(R.string.action_save_copy)) }
    }
    copyState.result?.let { InlineNotice(stringResource(it)) }
}

/** The tag on the safety backup notice's Save a copy button. */
internal const val SAFETY_BACKUP_COPY_TAG = "sync_safety_backup_copy"


/**
 * The library fingerprint (phase 3 unit SP, R92 item 6): a tap computes it, and it shows the start of
 * [com.shinsaikou.sync.SyncDigest]'s hash and how many synced rows went into it, so the owner's devices
 * can be compared by eye once both have synced. While it computes, a tap does nothing.
 */
@Composable
private fun FingerprintRow(state: SyncSettingsViewModel.State, viewModel: SyncSettingsViewModel) {
    val resources = LocalResources.current
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    val computing = state.fingerprint == Fingerprint.Computing
    val detail = when (val fingerprint = state.fingerprint) {
        Fingerprint.Idle -> stringResource(R.string.sync_fingerprint_detail)
        Fingerprint.Computing -> stringResource(R.string.sync_fingerprint_computing)
        is Fingerprint.Computed -> pluralStringResource(
            R.plurals.sync_fingerprint_result_format,
            fingerprint.rows,
            fingerprint.groups,
            numberFormat.format(fingerprint.rows),
        )
        Fingerprint.SyncRunning -> stringResource(R.string.sync_fingerprint_sync_running)
        Fingerprint.SyncRanMeanwhile -> stringResource(R.string.sync_fingerprint_sync_ran)
        is Fingerprint.Failed -> ErrorText.of(resources, fingerprint.error)
    }
    SettingsSearchTarget(R.string.sync_fingerprint) {
        ListRow(
            title = stringResource(R.string.sync_fingerprint),
            detail = detail,
            onClick = if (computing) null else viewModel::computeFingerprint,
        )
    }
    if (computing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
}

@Composable
private fun SignOutRow(state: SyncSettingsViewModel.State, onSignOut: () -> Unit) {
    SettingsSearchTarget(R.string.sync_sign_out) {
        ListRow(
            title = stringResource(if (state.signingOut) R.string.sync_signing_out else R.string.sync_sign_out),
            detail = stringResource(R.string.sync_sign_out_detail),
            onClick = if (state.signingOut) null else onSignOut,
        )
    }
}

/** One run: when, the counts before and after, the routine merges, the export, and the true conflicts. */
@Composable
private fun RunReport(report: ReportRows, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = syncTimeText(context, report.at),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(
                R.string.sync_report_before_format,
                report.before.libraryEntries,
                report.before.categories,
                report.before.readChapters,
                report.before.bookmarkedChapters,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(
                R.string.sync_report_after_format,
                report.after.libraryEntries,
                report.after.categories,
                report.after.readChapters,
                report.after.bookmarkedChapters,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = pluralStringResource(R.plurals.sync_report_routine_format, report.routineMerges, report.routineMerges),
            style = MaterialTheme.typography.bodySmall,
        )
        if (report.exported) {
            Text(text = stringResource(R.string.sync_report_exported), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = if (report.conflictCount == 0) {
                stringResource(R.string.sync_report_no_conflicts)
            } else {
                pluralStringResource(R.plurals.sync_report_conflicts_format, report.conflictCount, report.conflictCount)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        report.conflicts.forEach { conflict ->
            val fields = conflict.fields.joinToString()
            Text(
                text = conflict.chapter?.let { stringResource(R.string.sync_report_conflict_chapter_format, conflict.title, it, fields) }
                    ?: stringResource(R.string.sync_report_conflict_format, conflict.title, fields),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (report.more > 0) {
            Text(
                text = pluralStringResource(R.plurals.sync_report_more_format, report.more, report.more),
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The runs before the newest, newest first, each shown as the newest is. */
@Composable
private fun EarlierRunsDialog(reports: List<ReportRows>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_reports_earlier)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = MAX_DIALOG_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                reports.forEach { RunReport(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
    )
}

/**
 * The sign-out dialog's text for the configured service: Drive's says the Google account is
 * forgotten and its access revoked; WebDAV's, the server address, user name and password.
 */
@StringRes
internal fun syncSignOutMessage(service: Service): Int =
    if (service == Service.GOOGLE_DRIVE) R.string.sync_drive_sign_out_message else R.string.sync_sign_out_message

/** A date alone, in the device's locale: when a Google sign-in lapses. */
private fun syncDateText(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
)

/** A run's time the way the crash log writes one: the device's locale and its 12 or 24 hour setting. */
private fun syncTimeText(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or
        DateUtils.FORMAT_SHOW_TIME,
)

private val MAX_DIALOG_HEIGHT = 480.dp
