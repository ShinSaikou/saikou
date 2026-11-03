package com.shinsaikou.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.util.secondaryItemAlpha
import com.shinsaikou.presentation.core.components.SaikouSnackbarHost as CoreSnackbarHost
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clearFocusOnSoftKeyboardHide
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import tachiyomi.presentation.core.util.selectedBackground
import tachiyomi.presentation.core.util.showSoftKeyboard

/**
 * The small pieces every screen in `:app` shares. Anything with a screen's own behaviour in it
 * belongs in that screen's package; what lives here is the plain chrome, so a list row looks the
 * same on Library, Updates, History and Browse without each one restating it.
 */

/**
 * A back arrow, not a text button: the top bar is the one place every screen is navigated from. A
 * title too long for one line ends in an ellipsis, as upstream's `AppBar` title does, so a cut title
 * reads as cut ("Compare with MyAnimeList" on a phone was "Compare with"). A bar so narrow that its
 * actions leave the title less than [MIN_TITLE_WIDTH] draws no title, rather than a lone ellipsis:
 * an entry in the library's pane with two panes forced on a phone had a "." for its title.
 *
 * [colors] are upstream's `AppBar` colours; a selection bar passes [selectionTopBarColors], upstream's
 * action mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    colors: TopAppBarColors = screenTopBarColors(),
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            BoxWithConstraints {
                if (maxWidth >= MIN_TITLE_WIDTH) Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = { actions() },
        scrollBehavior = scrollBehavior,
        colors = colors,
    )
}

/** Room for a couple of a title's characters and its ellipsis, a touch target's width. */
private val MIN_TITLE_WIDTH = 48.dp

/** Upstream's `AppBar` colours: the surface at no elevation, Material's scrolled colour under a list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun screenTopBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(0.dp))

/** Upstream's `AppBar` in action mode, while rows are selected: the surface at 3 dp of elevation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun selectionTopBarColors(): TopAppBarColors =
    TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))

/**
 * Upstream's `SearchToolbar`: a top bar that becomes its own search field. With [searchQuery] null it
 * is [ScreenTopBar] with a search action ahead of [actions]. A search action sets the query to "",
 * and from then on the title slot is a full width field with the keyboard up, a back arrow on the
 * start side closes the search (null), a clear button empties the field while it holds text, and
 * [actions] stay where they were. The field sits in the bar rather than in the list below it.
 *
 * [searchQuery] has the same rule as [SearchField]'s value: the screen's own state, current by the
 * next frame, or the field loses keys.
 *
 * [onBack] is the back arrow of a pushed screen while no search is open, as [ScreenTopBar] draws it;
 * a tab's bar passes none.
 *
 * [statusAction] is an icon that comes and goes (the library's update errors), drawn ahead of the
 * search action, so the search and [actions] stay put when it appears.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    placeholder: String,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onBack: (() -> Unit)? = null,
    statusAction: @Composable () -> Unit = {},
    actions: @Composable () -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    TopAppBar(
        title = {
            if (searchQuery == null) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                val focusManager = LocalFocusManager.current
                val keyboard = LocalSoftwareKeyboardController.current
                val hideKeyboard: () -> Unit = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
                val interactionSource = remember { MutableInteractionSource() }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .runOnEnterKeyPressed(action = hideKeyboard)
                        .showSoftKeyboard(remember { searchQuery.isEmpty() })
                        .clearFocusOnSoftKeyboardHide(),
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Normal,
                        fontSize = 18.sp,
                    ),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { hideKeyboard() }),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    interactionSource = interactionSource,
                    decorationBox = { innerTextField ->
                        TextFieldDefaults.DecorationBox(
                            value = searchQuery,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = true,
                            visualTransformation = VisualTransformation.None,
                            interactionSource = interactionSource,
                            placeholder = {
                                Text(
                                    modifier = Modifier.secondaryItemAlpha(),
                                    text = placeholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                )
                            },
                            container = {},
                        )
                    },
                )
            }
        },
        navigationIcon = {
            when {
                searchQuery != null -> IconButton(onClick = { onSearchQueryChange(null) }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_close))
                }
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            statusAction()
            when {
                searchQuery == null -> TopBarAction(
                    label = stringResource(R.string.action_search),
                    icon = Icons.Outlined.Search,
                    onClick = { onSearchQueryChange("") },
                )
                searchQuery.isNotEmpty() -> TopBarAction(
                    label = stringResource(R.string.action_clear),
                    icon = Icons.Outlined.Close,
                    onClick = {
                        onSearchQueryChange("")
                        focusRequester.requestFocus()
                    },
                )
            }
            actions()
        },
        scrollBehavior = scrollBehavior,
        colors = screenTopBarColors(),
    )
}

/**
 * Material 3's extended floating action button, with its label read by a screen reader. Material 3
 * clears the semantics of the label it draws (`clearAndSetSemantics` on the text's row), so the
 * button alone read as an unlabelled button; [text] is set as the button's own content description,
 * which a screen reader reads whether [expanded] shows the label or not. No visual change.
 */
@Composable
fun LabelledExtendedFloatingActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
    expanded: Boolean = true,
) {
    ExtendedFloatingActionButton(
        text = { Text(text = text) },
        icon = icon,
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = text },
        expanded = expanded,
    )
}

/**
 * A row led by an icon, as upstream draws the rows of its More tab and its settings list: Material's
 * list item with the icon in the primary colour, the title, and [detail] under it when there is one.
 * No divider, as upstream's rows have none. [selected] tints the row, as the tablet's settings list
 * marks the open section; [trailing] is a control drawn last, such as the switch of a row whose tap
 * flips it. A row with no [onClick] only says something, and a tap on it does nothing.
 */
@Composable
fun IconListRow(
    title: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    detail: String? = null,
    selected: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = trailing,
        modifier = modifier
            .selectedBackground(selected)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        // Transparent, so a selected row's tint shows through.
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * The list row of every plain list in the app. [leading] carries a cover when the row has one, so
 * the text block and the trailing action keep the same geometry with or without it. [trailingIcon]
 * is an icon action drawn last, such as the source list's pin, for a row whose action is a glyph
 * rather than a word. [onLongClick] is for a row whose long press does something of its own.
 */
@Composable
fun ListRow(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    onTrailing: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    ListRowLayout(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        leading = leading,
        trailing = {
            if (trailing != null) {
                TextButton(onClick = onTrailing ?: {}) { Text(trailing) }
            }
            trailingIcon?.invoke()
        },
    ) {
        // Two lines at most, ending in an ellipsis when cut, as upstream's list rows do.
        ListRowTitle(title)
        if (detail.isNotBlank()) ListRowDetail(detail)
    }
}

/**
 * The frame every list row is drawn in, [ListRow]'s and the rows a screen lays out itself because it
 * needs more than one trailing action (Updates, History): [ListRowBody] at 16 dp from the edges and
 * 12 dp top and bottom, and a divider under it, as [ListRow] drew at `905982f9`.
 *
 * [leading] gets a fixed [LIST_ROW_LEADING_WIDTH] slot (a cover's width), so rows with and without a
 * cover line up; a row that keeps the slot empty to line up with its neighbours passes an empty
 * lambda. [selected] tints the row as a selected grid cell is tinted. The click, the long press and
 * [modifier] are on the row itself, not the divider.
 */
@Composable
fun ListRowLayout(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    ListRowBody(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected,
        leading = leading,
        trailing = trailing,
        content = content,
    )
    HorizontalDivider()
}

/**
 * [ListRowLayout]'s row without its divider, for a row whose upstream counterpart draws none and sets
 * its own spacing: the entry screen's chapter rows (upstream's `MangaChapterListItem`) and the Updates
 * rows (`UpdatesUiItem`). [contentPadding] is the row's padding, [leadingGap] the space after the
 * [leading] slot, [contentSpacing] the space between the lines of [content], and
 * [verticalAlignment] where the slots sit in the row. The defaults are [ListRowLayout]'s.
 */
@Composable
fun ListRowBody(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(horizontal = LIST_ROW_HORIZONTAL_PADDING, vertical = 12.dp),
    leadingGap: Dp = LIST_ROW_LEADING_GAP,
    contentSpacing: Dp = 0.dp,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectedBackground(selected)
            .let {
                when {
                    onLongClick != null -> it.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
                    onClick != null -> it.clickable(onClick = onClick)
                    else -> it
                }
            }
            .padding(contentPadding),
        verticalAlignment = verticalAlignment,
    ) {
        if (leading != null) {
            Box(modifier = Modifier.width(LIST_ROW_LEADING_WIDTH), contentAlignment = Alignment.Center) {
                leading()
            }
            Spacer(Modifier.width(leadingGap))
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
            content = content,
        )
        trailing()
    }
}

/** A row's title: `titleSmall`, two lines at most, as [ListRow]'s was at `905982f9`. */
@Composable
fun ListRowTitle(text: String, maxLines: Int = 2) {
    Text(text, style = MaterialTheme.typography.titleSmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** A row's supporting line: `bodySmall` in `onSurfaceVariant`, as [ListRow]'s was at `905982f9`. */
@Composable
fun ListRowDetail(text: String, maxLines: Int = 2) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A library or browse row: the cover plus whatever badges that screen computed, drawn at the row's
 * end as upstream's `MangaListItem` draws its badge group. [coverAlpha] fades the cover, as browse
 * does for an entry already in the library.
 */
@Composable
fun MangaListRow(
    cover: Any?,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
    onTrailing: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    coverAlpha: Float = 1f,
    badges: @Composable (RowScope.() -> Unit)? = null,
) {
    ListRow(
        title = title,
        detail = detail,
        modifier = modifier,
        trailing = trailing,
        onClick = onClick,
        onTrailing = onTrailing,
        onLongClick = onLongClick,
        leading = {
            MangaCover(
                cover,
                modifier = Modifier.width(LIST_ROW_LEADING_WIDTH),
                contentDescription = title,
                alpha = coverAlpha,
            )
        },
        trailingIcon = badges?.let { content -> { BadgeGroup(content = content) } },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun InlineNotice(text: String) {
    Text(
        text,
        modifier = Modifier.padding(16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * The search field used inside a list, above the rows.
 *
 * [value] must already hold the new text by the next frame: the screen's own state written in
 * [onValueChange], or a view model's state updated on the main thread inside that call. A view
 * model's query that comes back through a flow on another dispatcher arrives a frame or more late,
 * and the text field, recomposed with the text from before the key, moves its cursor and loses the
 * keys typed in between ("Asura" typed a key at a time became "suraA" on the Extensions page,
 * 2026-10-02). Such a screen keeps the text itself and passes it to the view model, as the library
 * and the Extensions page do.
 *
 * With [onSubmit] and no [keyboardActions] of the caller's, the keyboard's key reads Search, and it
 * and a hardware Enter both submit and put the keyboard away, as the trailing button does: the
 * soft keyboard covers that button, and a key that only hid the keyboard left a source's search
 * never run. A caller that passes its own [keyboardOptions] or [keyboardActions] gets exactly those.
 *
 * The field keeps its focus, as Material's own Done key leaves it. Clearing it does not stick before
 * Android 9: the platform hands focus straight back to the Compose view, the field takes it again,
 * and the keyboard reopens.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submitFromKeyboard: (() -> Unit)? = onSubmit?.takeIf { keyboardActions == null }?.let { submit ->
        {
            submit()
            keyboard?.hide()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .then(submitFromKeyboard?.let { Modifier.runOnEnterKeyPressed(action = it) } ?: Modifier),
        singleLine = true,
        keyboardOptions = keyboardOptions ?: KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = if (submitFromKeyboard != null) ImeAction.Search else ImeAction.Default,
        ),
        keyboardActions = keyboardActions
            ?: submitFromKeyboard?.let { KeyboardActions(onSearch = { it() }) }
            ?: KeyboardActions.Default,
        trailingIcon = {
            if (onSubmit != null) {
                TextButton(onClick = onSubmit) { Text(stringResource(R.string.action_search)) }
            }
        },
    )
}

/**
 * Every screen's snackbar host: Material's snackbar on `inverseSurface` (plan 3.1 item 12). The host
 * itself is `:presentation-core`'s, which the reader shares (unit ID3).
 */
@Composable
fun SaikouSnackbarHost(hostState: SnackbarHostState) = CoreSnackbarHost(hostState)

/** A settings section body: scrolling is the section's own, so a long one never clips. */
@Composable
fun SettingsColumn(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = { content() },
    )
}

/** The row's side margin. */
private val LIST_ROW_HORIZONTAL_PADDING = 16.dp

/** The leading slot: a list cover's width, 48 dp, upstream's list thumbnail. */
val LIST_ROW_LEADING_WIDTH = 48.dp

private val LIST_ROW_LEADING_GAP = 12.dp
