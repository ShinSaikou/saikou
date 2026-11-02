package com.shinsaikou.reader.ui

import android.content.res.Resources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.window.DialogProperties
import com.shinsaikou.i18n.R
import com.shinsaikou.reader.state.ReaderDialog
import com.shinsaikou.reader.state.ReaderEvent
import com.shinsaikou.reader.state.ReaderState
import com.shinsaikou.reader.viewer.PageImageActions
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.runtime.getValue

/**
 * One `when` over `state.dialog`, one dismiss lambda. Contract 07 part 2 section 5.6.
 *
 * `ReaderSettingsViewModel` is remembered by `ReaderScreen` rather than here, so it survives the
 * settings sheet opening and closing.
 */
@Composable
internal fun ReaderDialogs(
    state: ReaderState,
    settingsViewModel: ReaderSettingsViewModel,
    pageImageActions: PageImageActions,
    messages: ReaderMessages,
    onEvent: (ReaderEvent) -> Unit,
) {
    val context = LocalContext.current
    // Resources rather than the context: LocalContext is not re-read on a configuration
    // change, so a string pulled through it survives a locale switch stale. The ids here
    // arrive as callback arguments, so stringResource cannot hoist them.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val showReadingMode by settingsViewModel.preferences.showReadingMode.collectAsState()
    val dismiss = { onEvent(ReaderEvent.DismissDialog) }

    when (val dialog = state.dialog) {
        null -> Unit

        // Back closes it while the chapter goes on loading, so a slow source never traps the
        // reader behind it; a tap outside does not, as upstream's cannot be dismissed at all.
        ReaderDialog.Loading -> AlertDialog(
            onDismissRequest = dismiss,
            properties = DialogProperties(dismissOnClickOutside = false),
            confirmButton = {},
            text = { Text(stringResource(R.string.loading)) },
            icon = { CircularProgressIndicator() },
        )

        ReaderDialog.Settings -> ReaderSettingsDialog(
            onDismissRequest = dismiss,
            onShowMenus = { onEvent(ReaderEvent.ShowMenu) },
            onHideMenus = { onEvent(ReaderEvent.HideMenu) },
            viewModel = settingsViewModel,
        )

        ReaderDialog.ReadingModeSelect -> ReadingModeSelectDialog(
            onDismissRequest = dismiss,
            viewModel = settingsViewModel,
            onChange = { res -> if (!showReadingMode) context.toast(resources.getString(res)) },
        )

        ReaderDialog.OrientationSelect -> OrientationSelectDialog(
            onDismissRequest = dismiss,
            viewModel = settingsViewModel,
            onChange = { res -> context.toast(resources.getString(res)) },
        )

        is ReaderDialog.PageActions -> {
            val manga = state.manga
            val chapter = state.currentChapter?.chapter
            if (manga == null || chapter == null) {
                dismiss()
            } else {
                ReaderPageActionsDialog(
                    onDismissRequest = dismiss,
                    onSetAsCover = {
                        scope.launch {
                            pageImageActions.setAsCover(manga, dialog.page)
                                .onSuccess { messages.show(resources.getString(R.string.cover_updated)) }
                                .showFailure(messages, resources)
                        }
                    },
                    onShare = {
                        // The chooser is its own feedback, so a successful share says nothing.
                        scope.launch {
                            pageImageActions.share(manga, chapter, dialog.page).showFailure(messages, resources)
                        }
                    },
                    onSave = {
                        scope.launch {
                            pageImageActions.save(manga, chapter, dialog.page)
                                .onSuccess { messages.show(resources.getString(R.string.picture_saved)) }
                                .showFailure(messages, resources)
                        }
                    },
                    onNote = { onEvent(ReaderEvent.EditNote(dialog.page)) },
                )
            }
        }

        is ReaderDialog.PageNote -> PageNoteDialog(
            note = dialog.note,
            onTextChange = { onEvent(ReaderEvent.NoteTextChanged(it)) },
            onSave = { onEvent(ReaderEvent.SaveNote(dialog.chapterId, dialog.pageIndex, it)) },
            onDismissRequest = dismiss,
        )
    }
}

private fun <T> Result<T>.showFailure(messages: ReaderMessages, resources: Resources) = onFailure {
    messages.show(it.message ?: resources.getString(R.string.unknown_error))
}
