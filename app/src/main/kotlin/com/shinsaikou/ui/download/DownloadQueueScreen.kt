package com.shinsaikou.ui.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.downloads.DownloadStatus
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.util.ErrorText
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The download queue, reached from More and from the download notifications. Start doubles as
 * retry, because the queue's start requeues every failed row before the worker runs. A request
 * waiting for its network shows Pause, which drops it, since Start would only leave it waiting.
 */
@Composable
fun DownloadQueueScreen() {
    val viewModel: DownloadQueueViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.download_queue_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.rows.isNotEmpty()) {
                        if (state.started) {
                            IconButton(onClick = viewModel::pause) {
                                Icon(
                                    Icons.Outlined.Pause,
                                    contentDescription = stringResource(R.string.download_queue_pause),
                                )
                            }
                        } else {
                            IconButton(onClick = viewModel::start) {
                                Icon(
                                    Icons.Outlined.PlayArrow,
                                    contentDescription = stringResource(R.string.download_queue_start),
                                )
                            }
                        }
                        IconButton(onClick = viewModel::clear) {
                            Icon(
                                Icons.Outlined.ClearAll,
                                contentDescription = stringResource(R.string.download_queue_clear),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            state.rows.isEmpty() -> EmptyScreen(
                stringRes = R.string.download_queue_empty,
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item { InlineNotice(stringResource(statusLabel(state.status))) }
                items(state.rows, key = DownloadQueueViewModel.Row::chapterId) { row ->
                    QueueRow(row, onCancel = { viewModel.cancel(row.chapterId) })
                }
            }
        }
    }
}

@Composable
private fun QueueRow(row: DownloadQueueViewModel.Row, onCancel: () -> Unit) {
    Column {
        ListRow(
            title = row.mangaTitle ?: stringResource(R.string.manga_title_fallback),
            detail = listOfNotNull(row.chapterName, stateLabel(row)).joinToString(" · "),
            trailing = stringResource(R.string.download_queue_cancel),
            onTrailing = onCancel,
        )
        if (row.state == DownloadState.DOWNLOADING && row.totalPages > 0) {
            LinearProgressIndicator(
                progress = { row.downloadedPages.toFloat() / row.totalPages },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

private fun statusLabel(status: DownloadStatus): Int = when (status) {
    DownloadStatus.IDLE -> R.string.download_queue_stopped
    DownloadStatus.RUNNING -> R.string.download_queue_running
    DownloadStatus.WAITING_FOR_WIFI -> R.string.download_queue_waiting_for_wifi
    DownloadStatus.WAITING_FOR_NETWORK -> R.string.download_queue_waiting_for_network
    DownloadStatus.RETRYING -> R.string.download_queue_retrying
    DownloadStatus.STORAGE_FULL -> R.string.download_queue_storage_full
}

@Composable
private fun stateLabel(row: DownloadQueueViewModel.Row): String = when (row.state) {
    DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.DONE -> stringResource(R.string.download_state_queued)
    DownloadState.DOWNLOADING -> if (row.totalPages > 0) {
        stringResource(R.string.download_state_progress_format, row.downloadedPages, row.totalPages)
    } else {
        stringResource(R.string.download_state_starting)
    }
    DownloadState.ERROR -> row.errorMessage?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.download_state_failed_format, ErrorText.ofStored(LocalResources.current, it)) }
        ?: stringResource(R.string.download_state_failed)
}
