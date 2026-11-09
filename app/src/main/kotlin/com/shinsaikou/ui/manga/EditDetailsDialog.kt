package com.shinsaikou.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.domain.manga.interactor.SetMangaDetailsOverride
import com.shinsaikou.domain.manga.model.detailsOverrides
import com.shinsaikou.i18n.R
import com.shinsaikou.source.model.MangaStatus
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The tags on the Edit details dialog's fields, for the instrumentation suite: the status field is a
 * read-only menu anchor, so the fields are not told apart by their text actions alone.
 */
const val EDIT_DETAILS_TITLE_TAG = "edit_details_title"

/** See [EDIT_DETAILS_TITLE_TAG]. */
const val EDIT_DETAILS_DESCRIPTION_TAG = "edit_details_description"

/** See [EDIT_DETAILS_TITLE_TAG]. */
const val EDIT_DETAILS_AUTHOR_TAG = "edit_details_author"

/** See [EDIT_DETAILS_TITLE_TAG]. */
const val EDIT_DETAILS_ARTIST_TAG = "edit_details_artist"

/** See [EDIT_DETAILS_TITLE_TAG]. */
const val EDIT_DETAILS_STATUS_TAG = "edit_details_status"

/** See [EDIT_DETAILS_TITLE_TAG]. */
const val EDIT_DETAILS_GENRE_TAG = "edit_details_genre"

/**
 * The owner's own details for an entry (phase 5 unit ED, R138 item 2; R165 item 4): one dialog with
 * the title, description, author, artist, status and genres, each prefilled with what the screen
 * shows (the owner's edit where there is one, else the source's), on Material's text buttons and
 * outlined fields, as the category name dialog is. The status is a menu of the statuses the header
 * names, under "From source"; the genres are chips, each removed by a tap, and a field that adds one
 * (several at once, split at commas).
 *
 * Save hands every value to the view model, whose interactor stores an override only where a value
 * differs from the source's, so saving the source's own value clears that edit, as do a blank
 * author or artist, From source and an empty genre list. A blank title cannot be saved: the field
 * says so and Save is disabled. Reset all asks first, then clears every edit at once; it is enabled
 * only while the entry has one.
 *
 * The values are the dialog's own `rememberSaveable` state, so a rotation keeps what was typed and
 * nothing the owner types waits on a view model round trip.
 */
@Composable
internal fun EditDetailsDialog(
    manga: Manga,
    onDismissRequest: () -> Unit,
    onSave: (SetMangaDetailsOverride.Edit) -> Unit,
    onReset: () -> Unit,
) {
    var titleText by rememberSaveable { mutableStateOf(manga.displayTitle) }
    var descriptionText by rememberSaveable { mutableStateOf(manga.displayDescription.orEmpty()) }
    var authorText by rememberSaveable { mutableStateOf(manga.displayAuthor.orEmpty()) }
    var artistText by rememberSaveable { mutableStateOf(manga.displayArtist.orEmpty()) }
    var status by rememberSaveable { mutableStateOf<Long?>(manga.displayStatus) }
    var genres by rememberSaveable { mutableStateOf(shownGenres(manga)) }
    var newGenre by rememberSaveable { mutableStateOf("") }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val titleEmpty = titleText.isBlank()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.manga_edit_details)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    modifier = Modifier.fillMaxWidth().testTag(EDIT_DETAILS_TITLE_TAG),
                    label = { Text(stringResource(R.string.manga_edit_title)) },
                    supportingText = if (titleEmpty) {
                        { Text(stringResource(R.string.manga_edit_title_empty)) }
                    } else {
                        null
                    },
                    isError = titleEmpty,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = descriptionText,
                    onValueChange = { descriptionText = it },
                    modifier = Modifier.fillMaxWidth().testTag(EDIT_DETAILS_DESCRIPTION_TAG),
                    label = { Text(stringResource(R.string.manga_edit_description)) },
                    minLines = DESCRIPTION_MIN_LINES,
                    maxLines = DESCRIPTION_MAX_LINES,
                )
                OutlinedTextField(
                    value = authorText,
                    onValueChange = { authorText = it },
                    modifier = Modifier.fillMaxWidth().testTag(EDIT_DETAILS_AUTHOR_TAG),
                    label = { Text(stringResource(R.string.manga_edit_author)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = artistText,
                    onValueChange = { artistText = it },
                    modifier = Modifier.fillMaxWidth().testTag(EDIT_DETAILS_ARTIST_TAG),
                    label = { Text(stringResource(R.string.manga_edit_artist)) },
                    singleLine = true,
                )
                StatusField(status = status, sourceStatus = manga.status, onSelect = { status = it })
                GenresField(
                    genres = genres,
                    newGenre = newGenre,
                    onNewGenreChange = { newGenre = it },
                    onChange = { genres = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        SetMangaDetailsOverride.Edit(
                            title = titleText,
                            description = descriptionText,
                            author = authorText,
                            artist = artistText,
                            status = status,
                            // A genre typed but not yet added counts, so Save never drops it.
                            genres = withTypedGenres(genres, newGenre),
                        ),
                    )
                },
                enabled = !titleEmpty,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmReset = true }, enabled = manga.detailsOverrides.any) {
                    Text(stringResource(R.string.manga_edit_reset))
                }
                TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.manga_edit_reset_title)) },
            text = { Text(stringResource(R.string.manga_edit_reset_message)) },
            confirmButton = {
                TextButton(onClick = onReset) { Text(stringResource(R.string.manga_edit_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * The status menu: [status] is the chosen code, null for From source, which names the source's own
 * [sourceStatus] so the owner sees what it brings back.
 */
@Composable
private fun StatusField(status: Long?, sourceStatus: Long, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val fromSource = stringResource(R.string.manga_edit_status_from_source_format, statusLabel(sourceStatus))
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (status == null) fromSource else statusLabel(status),
            onValueChange = {},
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .testTag(EDIT_DETAILS_STATUS_TAG),
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.manga_edit_status)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(fromSource) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
            )
            STATUS_CHOICES.forEach { code ->
                DropdownMenuItem(
                    text = { Text(statusLabel(code)) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/**
 * The genres as chips, a tap on one removing it, over a field that adds what is typed: split at
 * commas, since the column stores the list joined by them, trimmed, and without a genre already there.
 */
@Composable
private fun GenresField(
    genres: List<String>,
    newGenre: String,
    onNewGenreChange: (String) -> Unit,
    onChange: (List<String>) -> Unit,
) {
    val add = {
        val added = withTypedGenres(genres, newGenre)
        if (added != genres) onChange(added)
        onNewGenreChange("")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.manga_edit_genres), style = MaterialTheme.typography.labelLarge)
        if (genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                genres.forEach { genre ->
                    InputChip(
                        selected = false,
                        onClick = { onChange(genres - genre) },
                        label = { Text(genre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.manga_edit_genre_remove_format, genre),
                                modifier = Modifier.size(InputChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = newGenre,
            onValueChange = onNewGenreChange,
            modifier = Modifier.fillMaxWidth().testTag(EDIT_DETAILS_GENRE_TAG),
            label = { Text(stringResource(R.string.manga_edit_genre_add)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            trailingIcon = {
                IconButton(onClick = add, enabled = newGenre.isNotBlank()) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_add))
                }
            },
        )
    }
}

/**
 * [genres] with what is [typed] in the add field: split at commas, trimmed, blanks and genres already
 * there left out. The add button and Save both go through it.
 */
internal fun withTypedGenres(genres: List<String>, typed: String): List<String> =
    (genres + typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinct()

/** The statuses the menu offers under From source, the header's names in upstream's order, Unknown last. */
private val STATUS_CHOICES = listOf(
    MangaStatus.ONGOING,
    MangaStatus.COMPLETED,
    MangaStatus.LICENSED,
    MangaStatus.PUBLISHING_FINISHED,
    MangaStatus.CANCELLED,
    MangaStatus.ON_HIATUS,
    MangaStatus.UNKNOWN,
).map { it.code.toLong() }

private const val DESCRIPTION_MIN_LINES = 3
private const val DESCRIPTION_MAX_LINES = 8
