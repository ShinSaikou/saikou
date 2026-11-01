package com.shinsaikou.ui.crash

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import kotlinx.coroutines.launch
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File

/**
 * The crash screen's body: one line saying what happened and that the log stays on the device, the
 * log itself, and the actions. [content] is null for the moment the file is being read; a crash
 * file is a few kilobytes, so nothing is drawn rather than a spinner that only flickers. [onShare]
 * answers false when nothing could take the file.
 *
 * Start without extensions is Restart for when an extension is what takes the app down at every
 * start. The buttons wrap onto a second line where they do not fit on one, as on a phone.
 */
@Composable
internal fun CrashScreen(
    content: CrashScreenContent?,
    onShare: (File) -> Boolean,
    onRestart: () -> Unit,
    onStartWithoutExtensions: () -> Unit,
    onClose: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val shareFailed = stringResource(R.string.crash_share_failed)

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(title = stringResource(R.string.crash_screen_title), scrollBehavior = scrollBehavior)
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.crash_screen_text),
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (content) {
                    null -> Unit
                    is CrashScreenContent.Log -> LogText(content.text)
                    CrashScreenContent.Unreadable -> Text(
                        stringResource(R.string.crash_read_failed),
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Two groups, so a line too narrow for every button breaks between leaving and starting
            // again rather than leaving Restart on a line of its own. The text's side margin, so the
            // last button ends as far from the right edge as the text starts from the left one.
            ButtonLine(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            ) {
                ButtonLine {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                    if (content is CrashScreenContent.Log) {
                        OutlinedButton(
                            onClick = {
                                if (!onShare(content.file)) scope.launch { snackbarHostState.showSnackbar(shareFailed) }
                            },
                        ) {
                            Text(stringResource(R.string.action_share))
                        }
                    }
                }
                ButtonLine {
                    OutlinedButton(onClick = onStartWithoutExtensions) {
                        Text(stringResource(R.string.crash_start_without_extensions))
                    }
                    Button(onClick = onRestart) { Text(stringResource(R.string.action_restart)) }
                }
            }
        }
    }
}

/** Buttons from the end, wrapping onto another line where they do not fit on one. */
@Composable
private fun ButtonLine(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        content = content,
    )
}

/**
 * As the crash log screen's viewer shows a log: selectable, monospaced and unwrapped, scrolling
 * both ways, since a stack frame broken across two lines is what makes a trace hard to read.
 */
@Composable
private fun LogText(text: String) {
    SelectionContainer {
        Text(
            text,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.medium),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}
