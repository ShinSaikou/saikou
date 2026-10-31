package com.shinsaikou.ui.migration.list

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.DialogProperties
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/*
 * Upstream's three migration list dialogs (phase 4 unit MB), from `tachiyomi/feature/migration/list/components`:
 * the confirm, the progress and the exit dialog, with upstream's words. Upstream's Cancel, Stop, Migrate and
 * Copy labels are Saikou's `action_cancel`, `action_stop`, `migrate` and `copy`, the same English.
 */

@Composable
internal fun MigrationMangaDialog(
    onDismissRequest: () -> Unit,
    copy: Boolean,
    totalCount: Int,
    skippedCount: Int,
    onMigrate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = pluralStringResource(
                    if (copy) {
                        R.plurals.migrationListScreen_migrateDialog_copyTitle
                    } else {
                        R.plurals.migrationListScreen_migrateDialog_migrateTitle
                    },
                    totalCount,
                    totalCount,
                ),
            )
        },
        text = {
            if (skippedCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.migrationListScreen_migrateDialog_skipText,
                        skippedCount,
                        skippedCount,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onMigrate) {
                Text(text = stringResource(if (copy) R.string.copy else R.string.migrate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Not dismissed by back or a tap outside: only its Cancel stops the loop. */
@Composable
internal fun MigrationProgressDialog(
    progress: Float,
    exitMigration: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            TextButton(onClick = exitMigration) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
        text = {
            if (!progress.isNaN()) {
                val progressAnimated by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    label = "migration_progress",
                )
                LinearProgressIndicator(
                    progress = { progressAnimated },
                )
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

@Composable
internal fun MigrationExitDialog(
    onDismissRequest: () -> Unit,
    exitMigration: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(R.string.migrationListScreen_exitDialogTitle))
        },
        confirmButton = {
            TextButton(onClick = exitMigration) {
                Text(text = stringResource(R.string.action_stop))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(R.string.action_cancel))
            }
        },
    )
}
