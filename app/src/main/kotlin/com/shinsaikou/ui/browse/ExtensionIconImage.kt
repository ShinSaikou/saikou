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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
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
    ExtensionIconImage(icon, modifier.size(size))
}

/** [ExtensionIconImage] at whatever size [modifier] gives it, as the extension list's ring needs. */
@Composable
fun ExtensionIconImage(icon: ExtensionIcon?, modifier: Modifier) {
    var failed by remember(icon) { mutableStateOf(false) }
    if (icon == null || failed) {
        ExtensionMark(modifier)
    } else {
        AsyncImage(
            model = icon,
            contentDescription = null,
            modifier = modifier,
            onError = { failed = true },
        )
    }
}

/**
 * The icon an index lists for an extension not yet installed, as upstream's `ExtensionIcon` draws
 * one: a faint square while it loads, the image with slightly rounded corners, and the generic mark
 * when it does not load.
 */
@Composable
fun AvailableExtensionIcon(iconUrl: String, modifier: Modifier) {
    var failed by remember(iconUrl) { mutableStateOf(false) }
    if (failed) {
        ExtensionMark(modifier)
    } else {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            placeholder = ColorPainter(ICON_PLACEHOLDER),
            modifier = modifier.clip(MaterialTheme.shapes.extraSmall),
            onError = { failed = true },
        )
    }
}

@Composable
private fun ExtensionMark(modifier: Modifier) {
    Icon(
        imageVector = Icons.Outlined.Extension,
        contentDescription = null,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** Upstream's placeholder grey, at the same alpha. */
private val ICON_PLACEHOLDER = Color(0x1F888888)

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
