package com.shinsaikou.ui.migration

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.migration.MigrateManga
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.domain.migration.models.MigrationFlag
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Upstream's migrate dialog (phase 4 unit MA): the flags that apply to [current], ticked as the stored
 * `migration_flags` holds them, and Show entry, Copy and Migrate. Copy and Migrate store the ticked
 * set and hand it to [MigrateManga], which moves or copies [current] onto [target].
 *
 * [onComplete] is called only after the migration succeeded, and defaults to [onDismissRequest] as
 * upstream's does. A migration that throws closes the dialog through [onFailed], and the caller says so
 * in a snackbar; the dialog adds no warning of its own. While it runs, a loading screen covers the
 * app and takes the back press, so the screen holding the migration cannot be left mid-fetch.
 *
 * A Migrate that left downloaded chapters of the old entry on this device (unit migrate-downloads)
 * says how many before [onComplete], with Delete them and Keep them: the ones that could not be
 * moved, or with Downloaded chapters unticked the ones that stay (unit migrate-kept-downloads,
 * [leftBehindText]). It is a step of this dialog where the other ends are the caller's snackbar,
 * because two of the three callers leave their screen on [onComplete] and a snackbar there would
 * never be seen.
 */
@Composable
fun MigrateMangaDialog(
    current: Manga,
    target: Manga,
    onClickTitle: () -> Unit,
    onDismissRequest: () -> Unit,
    onFailed: (Throwable) -> Unit,
    onComplete: () -> Unit = onDismissRequest,
) {
    val viewModel: MigrateDialogViewModel = metroViewModel()
    LaunchedEffect(current, target) {
        viewModel.init(current, target)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val outcome = state.outcome
    LaunchedEffect(outcome) {
        when (outcome) {
            null -> Unit
            MigrateDialogViewModel.Outcome.Migrated -> {
                viewModel.consumeOutcome()
                onComplete()
            }
            is MigrateDialogViewModel.Outcome.Failed -> {
                viewModel.consumeOutcome()
                onFailed(outcome.error)
            }
        }
    }

    if (state.isMigrating) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            LoadingScreen(
                modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
            )
        }
        return
    }

    // Nothing is drawn from a state that belongs to another pair, or before the flags are known.
    if (outcome != null || state.current != current || state.target != target) return

    val leftBehind = state.leftBehind.size
    if (leftBehind > 0) {
        val resources = LocalResources.current
        AlertDialog(
            onDismissRequest = { viewModel.resolveLeftBehind(delete = false) },
            text = {
                Text(text = leftBehindText(resources, leftBehind, carried = state.leftBehindCarried, batch = false))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveLeftBehind(delete = true) }) {
                    Text(text = stringResource(R.string.migration_downloads_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveLeftBehind(delete = false) }) {
                    Text(text = stringResource(R.string.migration_downloads_keep))
                }
            },
        )
        return
    }
    val applicableFlags = state.applicableFlags ?: return

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(R.string.migration_dialog_what_to_include))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                applicableFlags.forEach { flag ->
                    LabeledCheckbox(
                        label = stringResource(flag.labelRes()),
                        checked = flag in state.selectedFlags,
                        onCheckedChange = { viewModel.toggleSelection(flag) },
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        onClickTitle()
                    },
                ) {
                    Text(text = stringResource(R.string.action_show_manga))
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(onClick = { viewModel.migrate(replace = false) }) {
                    Text(text = stringResource(R.string.copy))
                }
                TextButton(onClick = { viewModel.migrate(replace = true) }) {
                    Text(text = stringResource(R.string.migrate))
                }
            }
        },
    )
}

/**
 * The question after a Migrate that left [count] downloaded chapters on this device: with
 * [carried] (Downloaded chapters ticked) the ones that could not be moved, without it the ones
 * that stay because nothing was carried. [batch] is the migration list's snackbar, over several
 * entries.
 */
internal fun leftBehindText(resources: Resources, count: Int, carried: Boolean, batch: Boolean): String {
    val plural = when {
        carried && batch -> R.plurals.migration_downloads_not_moved_batch
        carried -> R.plurals.migration_downloads_not_moved
        batch -> R.plurals.migration_downloads_kept_batch
        else -> R.plurals.migration_downloads_kept
    }
    return resources.getQuantityString(plural, count, count)
}

/** Upstream's `MigrationFlag.getLabel`, over Saikou's string resources. */
@StringRes
internal fun MigrationFlag.labelRes(): Int = when (this) {
    MigrationFlag.CHAPTER -> R.string.chapters
    MigrationFlag.CATEGORY -> R.string.categories
    MigrationFlag.CUSTOM_COVER -> R.string.custom_cover
    MigrationFlag.DOWNLOADS -> R.string.downloaded_chapters
    MigrationFlag.NOTES -> R.string.action_notes
    MigrationFlag.REMOVE_DOWNLOAD -> R.string.delete_downloaded
}

/**
 * Upstream's `MigrateDialogViewModel`. The migration runs in [viewModelScope] where upstream's runs in the
 * dialog's own scope, so it outlives a recomposition, and its end is [State.outcome] for the dialog
 * to act on once.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(MigrateDialogViewModel::class)
@Inject
class MigrateDialogViewModel(
    private val sourcePreferences: SourcePreferences,
    private val coverCache: CoverCache,
    private val downloadedChapters: DownloadedChapters,
    private val migrateManga: MigrateManga,
    private val dispatchers: Dispatchers,
) : StateViewModel<MigrateDialogViewModel.State>(State()) {

    /**
     * Upstream's: Chapters and Categories always apply; Custom cover when [current] has one on this
     * device, Notes when its notes are not blank, and Delete downloaded when it has a downloaded
     * chapter, as Saikou's Downloaded chapters does. The ticked set is the whole stored key, a flag
     * that does not apply included.
     */
    fun init(current: Manga, target: Manga) {
        mutableState.update {
            State(current = current, target = target, selectedFlags = sourcePreferences.migrationFlags.get())
        }
        viewModelScope.launch {
            val applicableFlags = withContext(dispatchers.io) {
                val hasDownloads = downloadedChapters.subscribeByMangaId(current.id).first().isNotEmpty()
                MigrationFlag.entries.filter {
                    when (it) {
                        MigrationFlag.CHAPTER -> true
                        MigrationFlag.CATEGORY -> true
                        MigrationFlag.CUSTOM_COVER -> coverCache.getCustomCoverFile(current.id).exists()
                        MigrationFlag.DOWNLOADS -> hasDownloads
                        MigrationFlag.NOTES -> current.notes.isNotBlank()
                        MigrationFlag.REMOVE_DOWNLOAD -> hasDownloads
                    }
                }
            }
            mutableState.update {
                if (it.current == current && it.target == target) it.copy(applicableFlags = applicableFlags) else it
            }
        }
    }

    fun toggleSelection(flag: MigrationFlag) {
        mutableState.update {
            val selectedFlags = it.selectedFlags.toMutableSet()
                .apply { if (contains(flag)) remove(flag) else add(flag) }
                .toSet()
            it.copy(selectedFlags = selectedFlags)
        }
    }

    /**
     * Stores the ticked set, as upstream's does before it migrates, and migrates with it. A Migrate
     * that left downloaded chapters behind ends in [State.leftBehind] instead, for
     * [resolveLeftBehind].
     */
    fun migrate(replace: Boolean) {
        val state = state.value
        val current = state.current ?: return
        val target = state.target ?: return
        if (state.isMigrating || state.outcome != null || state.leftBehind.isNotEmpty()) return
        sourcePreferences.migrationFlags.set(state.selectedFlags)
        mutableState.update { it.copy(isMigrating = true) }
        viewModelScope.launch {
            val result = try {
                migrateManga(current, target, replace, state.selectedFlags)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not migrate entry ${current.id} onto entry ${target.id}" }
                mutableState.update { it.copy(isMigrating = false, outcome = Outcome.Failed(e)) }
                return@launch
            }
            mutableState.update {
                if (result.leftBehindDownloads.isEmpty()) {
                    it.copy(isMigrating = false, outcome = Outcome.Migrated)
                } else {
                    it.copy(
                        isMigrating = false,
                        leftBehind = result.leftBehindDownloads,
                        leftBehindCarried = result.carried,
                    )
                }
            }
        }
    }

    /**
     * The owner's answer to the downloaded chapters a Migrate left behind (not moved, or not
     * carried): Delete them deletes their files and rows, under the loading screen, and Keep them
     * leaves them where they are, listed under Downloads not in library. Either way the migration
     * ends as [Outcome.Migrated]. A delete that fails is logged: the migration stands, and the files
     * stay listed there.
     */
    fun resolveLeftBehind(delete: Boolean) {
        val state = state.value
        val leftBehind = state.leftBehind
        if (leftBehind.isEmpty() || state.isMigrating) return
        if (!delete) {
            mutableState.update { it.copy(leftBehind = emptyList(), outcome = Outcome.Migrated) }
            return
        }
        mutableState.update { it.copy(isMigrating = true) }
        viewModelScope.launch {
            try {
                migrateManga.deleteLeftBehindDownloads(leftBehind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not delete ${leftBehind.size} downloads a migration left behind" }
            }
            mutableState.update { it.copy(isMigrating = false, leftBehind = emptyList(), outcome = Outcome.Migrated) }
        }
    }

    /** The dialog acted on [State.outcome]: the next [init] starts from nothing. */
    fun consumeOutcome() {
        mutableState.update { State() }
    }

    sealed interface Outcome {
        data object Migrated : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    data class State(
        val current: Manga? = null,
        val target: Manga? = null,
        /** Null until [init] has looked at the cover and the downloads. */
        val applicableFlags: List<MigrationFlag>? = null,
        val selectedFlags: Set<MigrationFlag> = emptySet(),
        val isMigrating: Boolean = false,
        val outcome: Outcome? = null,
        /** The old entry's downloaded chapters a Migrate left on this device, waiting for an answer. */
        val leftBehind: List<Long> = emptyList(),
        /** [MigrateManga.Result.carried] of the Migrate that left [leftBehind]: which text asks. */
        val leftBehindCarried: Boolean = false,
    )
}
