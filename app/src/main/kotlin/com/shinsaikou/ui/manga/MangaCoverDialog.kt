package com.shinsaikou.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.shinsaikou.i18n.R
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The entry's cover, whole, over the screen (phase 5 unit ED): upstream's `MangaCoverDialog`. Close
 * sits alone at the bottom start; Share, Save and Edit cover share a pill at the end, each an icon
 * with its name as its description. Edit cover goes straight to the image
 * picker while the entry has no custom cover ([customCover] false), and otherwise opens upstream's
 * menu of the two, choose an image or delete the custom cover. Edit cover is absent where a cover
 * cannot be set ([editable] false: an entry only being browsed). A tap on the image closes it.
 *
 * Upstream draws the cover in the reader's zoomable page view; this draws it fitted, unzoomed.
 */
@Composable
internal fun MangaCoverDialog(
    manga: Manga,
    editable: Boolean,
    customCover: Boolean,
    onDismissRequest: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onChooseImage: () -> Unit,
    onDeleteCustomCover: () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                ),
        ) {
            AsyncImage(
                model = manga,
                contentDescription = manga.displayTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(bottom = ACTION_ROW_HEIGHT),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(4.dp),
            ) {
                ActionsPill {
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
                    }
                }
                Spacer(Modifier.weight(1f))
                ActionsPill {
                    IconButton(onClick = onShare) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = onSave) {
                        Icon(Icons.Outlined.Save, contentDescription = stringResource(R.string.action_save))
                    }
                    if (editable) {
                        EditCoverButton(
                            customCover = customCover,
                            onChooseImage = onChooseImage,
                            onDeleteCustomCover = onDeleteCustomCover,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditCoverButton(customCover: Boolean, onChooseImage: () -> Unit, onDeleteCustomCover: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { if (customCover) expanded = true else onChooseImage() }) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.manga_cover_edit))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manga_cover_choose)) },
                onClick = {
                    expanded = false
                    onChooseImage()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.manga_cover_delete)) },
                onClick = {
                    expanded = false
                    onDeleteCustomCover()
                },
            )
        }
    }
}

/**
 * Upstream's pill: the actions on the page colour, nearly opaque, so they read over any cover. The
 * dialog is a window of its own with no surface above it, so the content colour is set here.
 */
@Composable
private fun ActionsPill(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.background.copy(alpha = PILL_ALPHA)),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

private const val PILL_ALPHA = 0.95f

/** The action row's 48 dp buttons and its padding, kept clear of the image. */
private val ACTION_ROW_HEIGHT = 56.dp
