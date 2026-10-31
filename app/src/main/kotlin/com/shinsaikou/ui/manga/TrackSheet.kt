package com.shinsaikou.ui.manga

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.tracking.DeletableTracker
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.ui.components.MangaCover
import com.shinsaikou.ui.manga.TrackSheetViewModel.Page
import com.shinsaikou.ui.manga.TrackSheetViewModel.Row
import com.shinsaikou.ui.manga.TrackSheetViewModel.SearchResults
import com.shinsaikou.ui.util.ErrorText
import com.shinsaikou.ui.util.copyToClipboard
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.track.model.Track
import tachiyomi.presentation.core.components.AdaptiveSheet
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.WheelNumberPicker
import tachiyomi.presentation.core.components.WheelTextPicker
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.secondaryItemAlpha
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The track sheet (phase 3 unit T3), upstream's `TrackInfoDialog` and its pages in one
 * [AdaptiveSheet]: a tap outside or a swipe closes it only from the home page, and back on any other
 * page goes back a page, as upstream's navigator in the sheet does.
 *
 * [onOpenTrackingSettings] closes the sheet and opens the Tracking settings, where an expired
 * sign-in is renewed; [onOpenInBrowser] opens a tracker's page for the entry.
 */
@Composable
internal fun TrackSheet(
    mangaId: Long,
    mangaTitle: String,
    onDismissRequest: () -> Unit,
    onOpenTrackingSettings: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
) {
    val viewModel = assistedMetroViewModel<TrackSheetViewModel, TrackSheetViewModel.Factory> { create(mangaId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.open() }
    val dismiss = {
        viewModel.close()
        onDismissRequest()
    }
    // A page about a row whose tracker was signed out meanwhile has nothing left to show.
    val page = state.page.takeIf { it == Page.Home || state.row(it.trackerId) != null } ?: Page.Home

    val dateFormat = remember { viewModel.dateFormat() }

    AdaptiveSheet(onDismissRequest = dismiss, enableImplicitDismiss = page == Page.Home) {
        BackHandler(enabled = page != Page.Home, onBack = viewModel::back)
        when (page) {
            Page.Home -> TrackHome(
                rows = state.rows,
                problems = state.problems,
                offers = state.offers,
                dateFormat = dateFormat,
                onNewSearch = { viewModel.showSearch(it, mangaTitle) },
                onStatusClick = viewModel::showStatus,
                onChapterClick = viewModel::showChapter,
                onScoreClick = viewModel::showScore,
                onDateClick = viewModel::showDate,
                onOpenInBrowser = onOpenInBrowser,
                onRemove = viewModel::showRemove,
                onTogglePrivate = viewModel::togglePrivate,
                onMarkReadUpToRemote = viewModel::markReadUpToRemote,
                onSignInAgain = {
                    viewModel.close()
                    onOpenTrackingSettings()
                },
            )
            is Page.Search -> TrackSearch(
                page = page,
                supportsPrivateTracking = state.row(page.trackerId)!!.tracker.supportsPrivateTracking,
                onSearch = { viewModel.search(page.trackerId, it) },
                onSelect = viewModel::select,
                onConfirm = viewModel::bindSelected,
                onOpenInBrowser = onOpenInBrowser,
                onDismissRequest = viewModel::back,
            )
            is Page.Status -> {
                val row = state.row(page.trackerId)!!
                TrackStatusSelector(
                    tracker = row.tracker,
                    initial = row.track?.status ?: 0,
                    onConfirm = { viewModel.setStatus(page.trackerId, it) },
                    onDismissRequest = viewModel::back,
                )
            }
            is Page.Chapter -> {
                val track = state.row(page.trackerId)!!.track
                TrackChapterSelector(
                    initial = track?.lastChapterRead?.toInt() ?: 0,
                    // Upstream's range: up to the entry's chapter count, or 10000 while it has none.
                    range = 0..(track?.totalChapters?.takeIf { it > 0 }?.toInt() ?: 10000),
                    onConfirm = { viewModel.setChapter(page.trackerId, it) },
                    onDismissRequest = viewModel::back,
                )
            }
            is Page.Score -> {
                val row = state.row(page.trackerId)!!
                TrackScoreSelector(
                    initial = row.track?.let(row.tracker::displayScore).orEmpty(),
                    selections = row.tracker.getScoreList(),
                    onConfirm = { viewModel.setScore(page.trackerId, it) },
                    onDismissRequest = viewModel::back,
                )
            }
            is Page.Date -> {
                val track = state.row(page.trackerId)!!.track
                val current = if (page.start) track?.startDate else track?.finishDate
                TrackDateSelector(
                    start = page.start,
                    track = track,
                    onConfirm = { viewModel.setDate(page.trackerId, page.start, it) },
                    onRemove = { viewModel.showRemoveDate(page.trackerId, page.start) }.takeIf { (current ?: 0) > 0 },
                    onDismissRequest = viewModel::back,
                )
            }
            is Page.RemoveDate -> TrackRemoveDateDialog(
                start = page.start,
                trackerName = state.row(page.trackerId)!!.tracker.name,
                onConfirm = { viewModel.removeDate(page.trackerId, page.start) },
                onDismissRequest = viewModel::back,
            )
            is Page.Remove -> {
                val row = state.row(page.trackerId)!!
                TrackRemoveDialog(
                    trackerName = row.tracker.name,
                    // An expired sign-in removes the local row only: the service would refuse it.
                    canDeleteRemote = row.tracker is DeletableTracker && !row.authExpired,
                    onConfirm = viewModel::remove,
                    onDismissRequest = viewModel::back,
                )
            }
        }
    }
}

private val Page.trackerId: Long
    get() = when (this) {
        Page.Home -> error("the home page is about every row")
        is Page.Search -> trackerId
        is Page.Status -> trackerId
        is Page.Chapter -> trackerId
        is Page.Score -> trackerId
        is Page.Date -> trackerId
        is Page.RemoveDate -> trackerId
        is Page.Remove -> trackerId
    }

@Composable
private fun TrackHome(
    rows: List<Row>,
    problems: Map<Long, TrackSheetViewModel.Problem>,
    offers: Map<Long, TrackSheetViewModel.Offer>,
    dateFormat: DateTimeFormatter,
    onNewSearch: (Long) -> Unit,
    onStatusClick: (Long) -> Unit,
    onChapterClick: (Long) -> Unit,
    onScoreClick: (Long) -> Unit,
    onDateClick: (Long, Boolean) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onRemove: (Long) -> Unit,
    onTogglePrivate: (Long) -> Unit,
    onMarkReadUpToRemote: (Long) -> Unit,
    onSignInAgain: () -> Unit,
) {
    val resources = LocalResources.current
    Column(
        modifier = Modifier
            .animateContentSize()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag(TRACK_SHEET_HOME_TAG),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        rows.forEach { row ->
            val id = row.tracker.id
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    row.authExpired -> TrackInfoItemExpired(
                        row = row,
                        onRemove = { onRemove(id) },
                        onSignInAgain = onSignInAgain,
                    )
                    row.track != null -> TrackInfoItem(
                        track = row.track,
                        tracker = row.tracker,
                        dateFormat = dateFormat,
                        onNewSearch = { onNewSearch(id) },
                        onStatusClick = { onStatusClick(id) },
                        onChapterClick = { onChapterClick(id) },
                        onScoreClick = { onScoreClick(id) },
                        onDateClick = { start -> onDateClick(id, start) },
                        onOpenInBrowser = { onOpenInBrowser(row.track.remoteUrl) },
                        onRemove = { onRemove(id) },
                        onTogglePrivate = { onTogglePrivate(id) },
                    )
                    else -> TrackInfoItemEmpty(tracker = row.tracker, onNewSearch = { onNewSearch(id) })
                }
                offers[id]?.takeIf { !row.authExpired && row.track != null }?.let { offer ->
                    TrackRemoteAheadOffer(
                        trackerName = row.tracker.name,
                        chapter = offer.chapter,
                        onMarkRead = { onMarkReadUpToRemote(id) },
                    )
                }
                problems[id]?.let { problem ->
                    Text(
                        text = stringResource(
                            R.string.track_error,
                            row.tracker.name,
                            ErrorText.of(resources, problem.key, problem.detail),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * The refresh on open found the service ahead of the chapters read here: its chapter, and the action
 * that marks them read up to it (Compare's "Mark as read" on this entry).
 */
@Composable
private fun TrackRemoteAheadOffer(trackerName: String, chapter: Int, onMarkRead: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.track_remote_ahead_format, trackerName, chapter),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onMarkRead, modifier = Modifier.testTag(TRACK_SHEET_MARK_READ_TAG)) {
            Text(text = stringResource(R.string.track_mark_read_up_to_format, chapter))
        }
    }
}

@Composable
private fun TrackInfoItem(
    track: Track,
    tracker: Tracker,
    dateFormat: DateTimeFormatter,
    onNewSearch: () -> Unit,
    onStatusClick: () -> Unit,
    onChapterClick: () -> Unit,
    onScoreClick: () -> Unit,
    onDateClick: (start: Boolean) -> Unit,
    onOpenInBrowser: () -> Unit,
    onRemove: () -> Unit,
    onTogglePrivate: () -> Unit,
) {
    val context = LocalContext.current
    val copied = stringResource(R.string.copied_to_clipboard)
    val supportsScoring = tracker.getScoreList().isNotEmpty()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackLogoIcon(
                tracker = tracker,
                onClick = onOpenInBrowser,
                onLongClick = { context.copyToClipboard(track.remoteUrl, copied) },
            )
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .weight(1f)
                    .combinedClickable(
                        onClick = onNewSearch,
                        onLongClick = { context.copyToClipboard(track.title, copied) },
                    )
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (track.private) {
                Icon(
                    imageVector = Icons.Filled.VisibilityOff,
                    contentDescription = stringResource(R.string.tracked_privately),
                    modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            VerticalDivider(modifier = Modifier.height(48.dp))
            TrackInfoItemMenu(
                onOpenInBrowser = onOpenInBrowser,
                onCopyLink = { context.copyToClipboard(track.remoteUrl, copied) },
                private = track.private,
                onTogglePrivate = onTogglePrivate.takeIf { tracker.supportsPrivateTracking },
                onRemove = onRemove,
            )
        }

        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            Column {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    TrackDetailsItem(
                        modifier = Modifier.weight(1f),
                        text = tracker.getStatus(track.status)?.let { stringResource(it) } ?: "",
                        onClick = onStatusClick,
                    )
                    VerticalDivider()
                    TrackDetailsItem(
                        modifier = Modifier.weight(1f),
                        text = "${track.lastChapterRead.toInt()}".let { chapters ->
                            if (track.totalChapters > 0) "$chapters / ${track.totalChapters}" else chapters
                        },
                        onClick = onChapterClick,
                    )
                    // Upstream's score cell: always there while the tracker scores, "Score" while unscored.
                    if (supportsScoring) {
                        VerticalDivider()
                        TrackDetailsItem(
                            modifier = Modifier.weight(1f),
                            text = tracker.displayScore(track).takeIf { track.score != 0.0 },
                            placeholder = stringResource(R.string.track_score),
                            onClick = onScoreClick,
                        )
                    }
                }
                if (tracker.supportsReadingDates) {
                    HorizontalDivider()
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        TrackDetailsItem(
                            modifier = Modifier.weight(1f),
                            text = trackDateText(track.startDate, dateFormat),
                            placeholder = stringResource(R.string.track_started_reading_date),
                            onClick = { onDateClick(true) },
                        )
                        VerticalDivider()
                        TrackDetailsItem(
                            modifier = Modifier.weight(1f),
                            text = trackDateText(track.finishDate, dateFormat),
                            placeholder = stringResource(R.string.track_finished_reading_date),
                            onClick = { onDateClick(false) },
                        )
                    }
                }
            }
        }
    }
}

/** A start or finish date as the row writes it in [dateFormat]; null while none is set. */
internal fun trackDateText(date: Long, dateFormat: DateTimeFormatter): String? =
    date.takeIf { it != 0L }?.let { dateFormat.format(it.toJavaLocalDate()) }

/** Upstream's `Long.toLocalDate`: a stored date is local midnight. */
private fun Long.toJavaLocalDate(): java.time.LocalDate =
    Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()).date.toJavaLocalDate()

private const val UNSET_TEXT_ALPHA = 0.5F

@Composable
private fun TrackDetailsItem(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .fillMaxHeight()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text ?: placeholder,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (text == null) UNSET_TEXT_ALPHA else 1f),
        )
    }
}

@Composable
private fun TrackInfoItemEmpty(tracker: Tracker, onNewSearch: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TrackLogoIcon(tracker)
        TextButton(
            onClick = onNewSearch,
            modifier = Modifier
                .padding(start = 16.dp)
                .weight(1f),
        ) {
            Text(text = stringResource(R.string.add_tracking))
        }
    }
}

/**
 * A tracker whose sign-in the service no longer accepts (R95): "Sign in again" and the way to the
 * Tracking settings in place of the service's actions, and a remove that deletes the local row only.
 */
@Composable
private fun TrackInfoItemExpired(row: Row, onRemove: () -> Unit, onSignInAgain: () -> Unit) {
    val resources = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackLogoIcon(row.tracker)
            Text(
                text = row.track?.title ?: row.tracker.name,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (row.track != null) {
                TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove)) }
            }
        }
        Text(
            text = ErrorText.of(resources, com.shinsaikou.domain.error.ErrorKey.TRACKER_AUTH_EXPIRED, null),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onSignInAgain, modifier = Modifier.testTag(TRACK_SHEET_SIGN_IN_AGAIN_TAG)) {
            Text(stringResource(R.string.tracking_sign_in_again))
        }
    }
}

@Composable
private fun TrackInfoItemMenu(
    onOpenInBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    private: Boolean,
    onTogglePrivate: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        IconButton(onClick = { expanded = true }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = stringResource(R.string.label_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_in_browser)) },
                onClick = {
                    onOpenInBrowser()
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_copy_link)) },
                onClick = {
                    onCopyLink()
                    expanded = false
                },
            )
            if (onTogglePrivate != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(if (private) R.string.action_toggle_private_off else R.string.action_toggle_private_on),
                        )
                    },
                    onClick = {
                        onTogglePrivate()
                        expanded = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_remove)) },
                onClick = {
                    onRemove()
                    expanded = false
                },
            )
        }
    }
}

/** Upstream's `TrackLogoIcon`; a tracker with no logo (a test's) shows its name's first letter. */
@Composable
private fun TrackLogoIcon(tracker: Tracker, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val modifier = Modifier
        .size(48.dp)
        .clip(MaterialTheme.shapes.medium)
        .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier)
    val logo = tracker.getLogo()
    if (logo != 0) {
        Image(painter = painterResource(logo), contentDescription = tracker.name, modifier = modifier)
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(tracker.name.take(1), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackSearch(
    page: Page.Search,
    supportsPrivateTracking: Boolean,
    onSearch: (String) -> Unit,
    onSelect: (TrackSearchResult) -> Unit,
    onConfirm: (private: Boolean) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var query by rememberSaveable(page.trackerId) { mutableStateOf(page.query) }
    val dispatchQueryAndClearFocus: () -> Unit = {
        onSearch(query)
        focusManager.clearFocus()
    }

    Scaffold(
        modifier = Modifier.testTag(TRACK_SHEET_SEARCH_TAG),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    title = {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .runOnEnterKeyPressed(action = dispatchQueryAndClearFocus)
                                .testTag(TRACK_SHEET_QUERY_TAG),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { dispatchQueryAndClearFocus() }),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { field ->
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.action_search_hint),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                field()
                            },
                        )
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    query = ""
                                    focusRequester.requestFocus()
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        },
        bottomBar = {
            if (page.selected != null) {
                Row(
                    modifier = Modifier
                        .padding(MaterialTheme.padding.small)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Button(
                        onClick = { onConfirm(false) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TRACK_SHEET_TRACK_TAG),
                        elevation = ButtonDefaults.elevatedButtonElevation(),
                    ) {
                        Text(text = stringResource(R.string.action_track))
                    }
                    if (supportsPrivateTracking) {
                        Button(onClick = { onConfirm(true) }, elevation = ButtonDefaults.elevatedButtonElevation()) {
                            Icon(
                                imageVector = Icons.Filled.VisibilityOff,
                                contentDescription = stringResource(R.string.action_toggle_private_on),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        when (val results = page.results) {
            null -> LoadingScreen(modifier = Modifier.padding(innerPadding))
            is SearchResults.Failed -> EmptyScreen(
                message = ErrorText.of(LocalResources.current, results.problem.key, results.problem.detail),
                modifier = Modifier.padding(innerPadding),
            )
            is SearchResults.Found -> if (results.results.isEmpty()) {
                EmptyScreen(stringRes = R.string.no_results_found, modifier = Modifier.padding(innerPadding))
            } else {
                ScrollbarLazyColumn(
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = results.results, key = { it.remoteId }) { result ->
                        SearchResultItem(
                            result = result,
                            selected = result == page.selected,
                            onClick = {
                                focusManager.clearFocus()
                                onSelect(result)
                            },
                            onOpenInBrowser = onOpenInBrowser,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: TrackSearchResult,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenInBrowser: (String) -> Unit,
) {
    val context = LocalContext.current
    val copied = stringResource(R.string.copied_to_clipboard)
    val type = result.publishingType.lowercase().replaceFirstChar { it.titlecase() }
    val status = result.publishingStatus.lowercase().replaceFirstChar { it.titlecase() }
    val description = result.summary.trim()
    val shape = RoundedCornerShape(16.dp)
    var menuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(width = 2.dp, color = if (selected) MaterialTheme.colorScheme.outline else Color.Transparent, shape = shape)
            .combinedClickable(onLongClick = { menuExpanded = true }, onClick = onClick)
            .padding(12.dp)
            .testTag(trackSearchResultTag(result.remoteId)),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column {
            Row {
                MangaCover(data = result.coverUrl, modifier = Modifier.height(96.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = result.title,
                        modifier = Modifier.padding(end = 28.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_to_clipboard)) },
                            onClick = {
                                context.copyToClipboard(result.title, copied)
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_open_in_browser)) },
                            onClick = {
                                if (result.trackingUrl.isNotBlank()) onOpenInBrowser(result.trackingUrl)
                                menuExpanded = false
                            },
                        )
                    }
                    if (result.authors.isNotEmpty() || result.artists.isNotEmpty()) {
                        Text(
                            text = (result.authors + result.artists).distinct().joinToString(),
                            modifier = Modifier.secondaryItemAlpha(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (type.isNotBlank()) SearchResultItemDetails(stringResource(R.string.track_type), type)
                    if (result.startDate.isNotBlank()) SearchResultItemDetails(stringResource(R.string.label_started), result.startDate)
                    if (status.isNotBlank()) SearchResultItemDetails(stringResource(R.string.track_status), status)
                    if (result.score >= 0) {
                        SearchResultItemDetails(stringResource(R.string.track_score), result.score.toString())
                    }
                }
            }
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    modifier = Modifier
                        .paddingFromBaseline(top = 24.dp)
                        .secondaryItemAlpha(),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SearchResultItemDetails(title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        Text(text = title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
        Text(
            text = text,
            modifier = Modifier
                .weight(1f)
                .secondaryItemAlpha(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TrackStatusSelector(
    tracker: Tracker,
    initial: Long,
    onConfirm: (Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var selection by rememberSaveable { mutableLongStateOf(initial) }
    BaseSelector(
        title = stringResource(R.string.track_status),
        content = {
            val state = rememberLazyListState()
            ScrollbarLazyColumn(state = state) {
                items(tracker.getStatusList()) { status ->
                    val isSelected = selection == status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .selectable(selected = isSelected, onClick = { selection = status })
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag(trackStatusTag(status)),
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Text(
                            text = tracker.getStatus(status)?.let { stringResource(it) } ?: "",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 24.dp),
                        )
                    }
                }
            }
            if (state.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
            if (state.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
        },
        onConfirm = { onConfirm(selection) },
        onDismissRequest = onDismissRequest,
    )
}

@Composable
private fun TrackChapterSelector(
    initial: Int,
    range: IntRange,
    onConfirm: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var selection by rememberSaveable { mutableIntStateOf(initial.coerceIn(range)) }
    val items = remember(range) { range.toList() }
    BaseSelector(
        title = stringResource(R.string.track_chapters),
        content = {
            WheelNumberPicker(
                items = items,
                modifier = Modifier.align(Alignment.Center),
                startIndex = items.indexOf(selection).coerceAtLeast(0),
                onSelectionChanged = { selection = items[it] },
            )
        },
        onConfirm = { onConfirm(selection) },
        onDismissRequest = onDismissRequest,
    )
}

@Composable
private fun TrackScoreSelector(
    initial: String,
    selections: List<String>,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    // Upstream's start: the row's score, or the middle of the list while it has none (index 0).
    val startIndex = remember(selections) { selections.indexOf(initial).takeIf { it > 0 } ?: (selections.size / 2) }
    var selection by rememberSaveable { mutableStateOf(selections[startIndex]) }
    BaseSelector(
        title = stringResource(R.string.track_score),
        content = {
            WheelTextPicker(
                items = selections,
                modifier = Modifier.align(Alignment.Center),
                startIndex = startIndex,
                onSelectionChanged = { selection = selections[it] },
            )
        },
        onConfirm = { onConfirm(selection) },
        onDismissRequest = onDismissRequest,
    )
}

/**
 * Upstream's date picker: no future date, no start after the finish date and no finish before the
 * start date ([trackSelectableDates]). [onRemove] is null while the row has no such date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackDateSelector(
    start: Boolean,
    track: Track?,
    onConfirm: (utcMillis: Long) -> Unit,
    onRemove: (() -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    val startDate = track?.startDate ?: 0L
    val finishDate = track?.finishDate ?: 0L
    val selectableDates = remember(start, startDate, finishDate) { trackSelectableDates(start, startDate, finishDate) }
    val initial = remember(start, startDate, finishDate) {
        ((if (start) startDate else finishDate).takeIf { it != 0L } ?: Clock.System.now().toEpochMilliseconds())
            .convertEpochMillisZone(TimeZone.currentSystemDefault(), TimeZone.UTC)
    }
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial, selectableDates = selectableDates)
    AlertDialogContent(
        title = {
            Text(
                stringResource(if (start) R.string.track_started_reading_date else R.string.track_finished_reading_date),
            )
        },
        content = {
            Column {
                DatePicker(state = pickerState, title = null, headline = null, showModeToggle = false)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
                ) {
                    if (onRemove != null) {
                        TextButton(onClick = onRemove, modifier = Modifier.testTag(TRACK_SHEET_REMOVE_DATE_TAG)) {
                            Text(text = stringResource(R.string.action_remove))
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    TextButton(onClick = onDismissRequest) { Text(text = stringResource(R.string.action_cancel)) }
                    TextButton(
                        onClick = { pickerState.selectedDateMillis?.let(onConfirm) },
                        modifier = Modifier.testTag(TRACK_SHEET_OK_TAG),
                    ) {
                        Text(text = stringResource(R.string.action_ok))
                    }
                }
            }
        },
    )
}

/**
 * Upstream's `SelectableDates` for a start ([start]) or a finish date, given the row's two dates,
 * compared as calendar days. The picker's days are UTC midnights, while the row's dates and today
 * are days in the device's [zone]; upstream read both sides in UTC, which east of UTC refused today
 * before the UTC date turned and a start on the finish day, and west of UTC a finish on the start day.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun trackSelectableDates(
    start: Boolean,
    startDate: Long,
    finishDate: Long,
    clock: Clock = Clock.System,
    zone: TimeZone = TimeZone.currentSystemDefault(),
) = object : SelectableDates {
    private fun today(): LocalDate = clock.now().toLocalDateTime(zone).date

    private fun dayOf(storedDate: Long): LocalDate =
        Instant.fromEpochMilliseconds(storedDate).toLocalDateTime(zone).date

    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val target = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date
        // Disallow future dates
        if (target > today()) return false
        return when {
            // Disallow setting start date after finish date
            start && finishDate > 0 -> target <= dayOf(finishDate)
            // Disallow setting finish date before start date
            !start && startDate > 0 -> dayOf(startDate) <= target
            else -> true
        }
    }

    override fun isSelectableYear(year: Int): Boolean {
        // Disallow future years
        if (year > today().year) return false
        return when {
            start && finishDate > 0 -> year <= dayOf(finishDate).year
            !start && startDate > 0 -> dayOf(startDate).year <= year
            else -> true
        }
    }
}

@Composable
private fun TrackRemoveDateDialog(
    start: Boolean,
    trackerName: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialogContent(
        icon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
        title = { Text(text = stringResource(R.string.track_remove_date_conf_title), textAlign = TextAlign.Center) },
        text = {
            Text(
                text = stringResource(
                    if (start) R.string.track_remove_start_date_conf_text else R.string.track_remove_finish_date_conf_text,
                    trackerName,
                ),
            )
        },
        buttons = { DestructiveButtons(onConfirm = onConfirm, onDismissRequest = onDismissRequest) },
    )
}

/** Upstream's remove dialog: the service's box, unticked at first, only for a tracker that can delete there. */
@Composable
private fun TrackRemoveDialog(
    trackerName: String,
    canDeleteRemote: Boolean,
    onConfirm: (alsoRemote: Boolean) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var removeRemoteTrack by rememberSaveable { mutableStateOf(false) }
    AlertDialogContent(
        icon = { Icon(imageVector = Icons.Filled.Delete, contentDescription = null) },
        title = { Text(text = stringResource(R.string.track_delete_title, trackerName), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.track_delete_text))
                if (canDeleteRemote) {
                    LabeledCheckbox(
                        label = stringResource(R.string.track_delete_remote_text, trackerName),
                        checked = removeRemoteTrack,
                        onCheckedChange = { removeRemoteTrack = it },
                        // The shared checkbox row carries a checkbox role and no state; the state
                        // is given here, so the row reads as ticked or not.
                        modifier = Modifier
                            .testTag(TRACK_SHEET_REMOTE_BOX_TAG)
                            .semantics { toggleableState = ToggleableState(removeRemoteTrack) },
                    )
                }
            }
        },
        buttons = {
            DestructiveButtons(onConfirm = { onConfirm(canDeleteRemote && removeRemoteTrack) }, onDismissRequest = onDismissRequest)
        },
    )
}

@Composable
private fun DestructiveButtons(onConfirm: () -> Unit, onDismissRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
    ) {
        TextButton(onClick = onDismissRequest) { Text(text = stringResource(R.string.action_cancel)) }
        FilledTonalButton(
            onClick = onConfirm,
            modifier = Modifier.testTag(TRACK_SHEET_CONFIRM_REMOVE_TAG),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        ) {
            Text(text = stringResource(R.string.action_ok))
        }
    }
}

@Composable
private fun BaseSelector(
    title: String,
    content: @Composable BoxScope.() -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialogContent(
        title = { Text(text = title) },
        text = { Box(modifier = Modifier.fillMaxWidth(), content = content) },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
            ) {
                TextButton(onClick = onDismissRequest) { Text(text = stringResource(R.string.action_cancel)) }
                TextButton(onClick = onConfirm, modifier = Modifier.testTag(TRACK_SHEET_OK_TAG)) {
                    Text(text = stringResource(R.string.action_ok))
                }
            }
        },
    )
}

internal const val TRACK_SHEET_HOME_TAG = "track_sheet_home"
internal const val TRACK_SHEET_SEARCH_TAG = "track_sheet_search"
internal const val TRACK_SHEET_QUERY_TAG = "track_sheet_query"
internal const val TRACK_SHEET_TRACK_TAG = "track_sheet_track"
internal const val TRACK_SHEET_OK_TAG = "track_sheet_ok"
internal const val TRACK_SHEET_REMOVE_DATE_TAG = "track_sheet_remove_date"
internal const val TRACK_SHEET_REMOTE_BOX_TAG = "track_sheet_remote_box"
internal const val TRACK_SHEET_CONFIRM_REMOVE_TAG = "track_sheet_confirm_remove"
internal const val TRACK_SHEET_SIGN_IN_AGAIN_TAG = "track_sheet_sign_in_again"
internal const val TRACK_SHEET_MARK_READ_TAG = "track_sheet_mark_read"

internal fun trackSearchResultTag(remoteId: Long) = "track_sheet_result_$remoteId"
internal fun trackStatusTag(status: Long) = "track_sheet_status_$status"
