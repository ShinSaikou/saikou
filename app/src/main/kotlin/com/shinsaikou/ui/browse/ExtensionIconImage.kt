package com.shinsaikou.ui.browse

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.shinsaikou.source.SourceIds

/**
 * An installed extension's own launcher icon at [size], drawn as upstream draws it: nothing while it
 * loads, then the icon. The generic extension mark in primary, as the extension rows drew it before
 * they had icons, stands in where there is no icon to show: [icon] is null (an untrusted extension,
 * a load error), the extension is not installed (a source's stub), or the icon would not load.
 */
@Composable
fun ExtensionIconImage(icon: ExtensionIcon?, size: Dp, modifier: Modifier = Modifier) {
    var failed by remember(icon) { mutableStateOf(false) }
    if (icon == null || failed) {
        Icon(
            imageVector = Icons.Outlined.Extension,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.primary,
        )
    } else {
        AsyncImage(
            model = icon,
            contentDescription = null,
            modifier = modifier.size(size),
            onError = { failed = true },
        )
    }
}

/**
 * A source's icon on a list of sources: its extension's launcher icon, as upstream's `SourceIcon`, and
 * a folder for the Local source, which no extension provides (upstream draws its own mark there).
 */
@Composable
fun SourceIcon(sourceId: Long, size: Dp, modifier: Modifier = Modifier) {
    if (sourceId == SourceIds.LOCAL) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            modifier = modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        ExtensionIconImage(ExtensionIcon.OfSource(sourceId), size, modifier)
    }
}
