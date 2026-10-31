package com.shinsaikou.ui.crash

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.io.File

@Composable
fun CrashLogScreen() {
    val viewModel: CrashLogViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    // The chooser is started from the activity this screen is composed in, as the banner's is.
    val activity = LocalActivity.current
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CrashLogViewModel.Event.Snackbar ->
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
                is CrashLogViewModel.Event.Share ->
                    if (activity == null || !CrashLogExport.share(activity, File(event.path))) {
                        snackbarHostState.showSnackbar(resources.getString(R.string.crash_share_failed))
                    }
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.crash_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.files.isNotEmpty()) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.action_menu_overflow_description),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_clear_all)) },
                                onClick = {
                                    menuOpen = false
                                    viewModel.clear()
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            state.files.isEmpty() -> EmptyScreen(
                stringRes = R.string.crash_empty,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(state.files, key = CrashLogViewModel.LogFile::path) { file ->
                    LogRow(
                        file = file,
                        onOpen = { viewModel.open(file.path) },
                        onShare = { viewModel.share(file.path) },
                        onDelete = { viewModel.delete(file.path) },
                    )
                }
            }
        }
    }

    val selected = state.selected
    if (selected != null) {
        LogViewer(
            title = state.files.firstOrNull { it.path == selected }?.name.orEmpty(),
            text = state.selectedText,
            onShare = { viewModel.share(selected) },
            onDismiss = viewModel::closeViewer,
        )
    }
}

/**
 * One log: its name, then the crash time beside its size (Q17), and tapping it opens the viewer. Its
 * two actions share or delete it. Icons rather than `ListRow`'s one text action, since a row carries
 * two, as a category row carries four; the frame is `ListRowLayout`'s.
 */
@Composable
private fun LogRow(
    file: CrashLogViewModel.LogFile,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    ListRowLayout(
        onClick = onOpen,
        trailing = {
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        },
    ) {
        ListRowTitle(file.name)
        ListRowDetail(
            listOf(
                crashTimeText(LocalContext.current, file.modifiedAt),
                stringResource(R.string.crash_size_format, file.sizeKib),
            ).joinToString(" · "),
            maxLines = 1,
        )
    }
}

/**
 * When the crash happened, from the file's timestamp, written the way the device writes a date and
 * time: its locale, and its 12 or 24 hour setting. The file name carries the same instant as epoch
 * milliseconds, which no one reads.
 */
internal fun crashTimeText(context: Context, millis: Long): String = DateUtils.formatDateTime(
    context,
    millis,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or
        DateUtils.FORMAT_SHOW_TIME,
)

@Composable
private fun LogViewer(title: String, text: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onShare) { Text(stringResource(R.string.action_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        title = { Text(title, maxLines = 1) },
        text = {
            // A stack trace is wide as well as long, and wrapping a frame across two lines is what
            // makes one hard to read, so the viewer scrolls in both directions instead.
            SelectionContainer {
                Text(
                    text,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                )
            }
        },
    )
}
