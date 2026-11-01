package com.shinsaikou.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The line the app shows for the whole of a start without extensions, the crash screen's way out of
 * an extension that takes the app down. It has no Dismiss: extensions stay off, and library updates
 * and downloads wait, until the process ends, and Restart is the way to end it.
 */
@Composable
internal fun ExtensionsOffBanner(onRestart: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.extensions_off_banner_text),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // The banner's own text colour: the theme's primary is drawn for the background, not
            // for this container, and can clash with it.
            TextButton(
                onClick = onRestart,
                colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
            ) {
                Text(stringResource(R.string.action_restart))
            }
        }
    }
}
