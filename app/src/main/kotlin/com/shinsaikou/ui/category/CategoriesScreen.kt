package com.shinsaikou.ui.category

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun CategoriesScreen() {
    val viewModel: CategoriesViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CategoriesViewModel.Event.Snackbar ->
                    snackbarHostState.showSnackbar(resources.getString(event.messageRes))
            }
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.categories_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    // Only where there is an order to change.
                    if (state.categories.size > 1) CategoriesOverflowMenu(onSortByName = viewModel::sortByName)
                },
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::showCreate) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.categories_add))
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingScreen(Modifier.padding(padding))
            state.categories.isEmpty() -> EmptyScreen(
                stringRes = R.string.categories_empty,
                modifier = Modifier.padding(padding),
                actions = listOf(
                    EmptyScreenAction(
                        stringRes = R.string.categories_add,
                        icon = Icons.Outlined.Add,
                        onClick = viewModel::showCreate,
                    ),
                ),
            )
            else -> ScrollbarLazyColumn(contentPadding = padding) {
                items(state.rows, key = { it.category.id }) { row ->
                    val category = row.category
                    val siblings = state.siblings(category)
                    CategoryRow(
                        category = category,
                        depth = row.depth,
                        canMoveUp = siblings.firstOrNull()?.id != category.id,
                        canMoveDown = siblings.lastOrNull()?.id != category.id,
                        canMoveUnder = state.parentCandidates(category).isNotEmpty(),
                        onMoveUp = { viewModel.moveUp(category) },
                        onMoveDown = { viewModel.moveDown(category) },
                        onRename = { viewModel.showRename(category) },
                        onDelete = { viewModel.showDelete(category) },
                        onMoveUnder = { viewModel.showMoveUnder(category) },
                        onMoveToTopLevel = { viewModel.moveToTopLevel(category) },
                    )
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        null -> Unit
        CategoriesViewModel.Dialog.Create -> CategoryNameDialog(
            titleRes = R.string.categories_add,
            confirmRes = R.string.action_add,
            initialName = "",
            onConfirm = viewModel::create,
            onDismiss = viewModel::dismissDialog,
        )
        is CategoriesViewModel.Dialog.Rename -> CategoryNameDialog(
            titleRes = R.string.categories_rename,
            confirmRes = R.string.action_rename,
            initialName = dialog.category.name,
            onConfirm = { name -> viewModel.rename(dialog.category, name) },
            onDismiss = viewModel::dismissDialog,
        )
        is CategoriesViewModel.Dialog.Delete -> CategoryDeleteDialog(
            name = dialog.category.name,
            onConfirm = { viewModel.delete(dialog.category) },
            onDismiss = viewModel::dismissDialog,
        )
        is CategoriesViewModel.Dialog.MoveUnder -> MoveUnderDialog(
            name = dialog.category.name,
            candidates = dialog.candidates,
            onPick = { parent -> viewModel.moveUnder(dialog.category, parent) },
            onDismiss = viewModel::dismissDialog,
        )
    }
}

/**
 * A row of its own rather than a `ListRow`: five affordances need composable trailing content and
 * `ListRow` takes a single label there. The padding and the divider are its.
 *
 * The move buttons are disabled at the ends of the row's siblings instead of hidden, and the menu
 * with nothing to offer is disabled too, so the five icons stay in the same place on every row. A
 * row under a parent ([depth] 1) is indented [CHILD_INDENT] past the list's own inset, with no tree
 * lines (phase 5 unit CT). Its menu offers "Move under..." when it holds no category and has a
 * top-level category to go under, and "Move to top level" when it is under one.
 */
@Composable
private fun CategoryRow(
    category: Category,
    depth: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canMoveUnder: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUnder: () -> Unit,
    onMoveToTopLevel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LIST_INSET + CHILD_INDENT * depth, top = 4.dp, end = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            category.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                Icons.Outlined.ArrowUpward,
                contentDescription = stringResource(R.string.categories_move_up),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                Icons.Outlined.ArrowDownward,
                contentDescription = stringResource(R.string.categories_move_down),
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_rename))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
        }
        CategoryRowMenu(
            canMoveUnder = canMoveUnder,
            canMoveToTopLevel = depth > 0,
            onMoveUnder = onMoveUnder,
            onMoveToTopLevel = onMoveToTopLevel,
        )
    }
    HorizontalDivider()
}

@Composable
private fun CategoryRowMenu(
    canMoveUnder: Boolean,
    canMoveToTopLevel: Boolean,
    onMoveUnder: () -> Unit,
    onMoveToTopLevel: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = canMoveUnder || canMoveToTopLevel) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_menu_overflow_description))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canMoveUnder) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.categories_move_under)) },
                    onClick = {
                        open = false
                        onMoveUnder()
                    },
                )
            }
            if (canMoveToTopLevel) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.categories_move_to_top_level)) },
                    onClick = {
                        open = false
                        onMoveToTopLevel()
                    },
                )
            }
        }
    }
}

/** The app bar's overflow, which holds Sort A to Z. */
@Composable
private fun CategoriesOverflowMenu(onSortByName: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_menu_overflow_description),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.categories_sort_by_name)) },
                onClick = {
                    open = false
                    onSortByName()
                },
            )
        }
    }
}

/**
 * "Move under...": the top-level categories the row may go under, one tap each, as the
 * change-category dialog lists its rows. Nothing is written until a row is tapped.
 */
@Composable
private fun MoveUnderDialog(
    name: String,
    candidates: List<Category>,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_move_under_title, name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEach { parent ->
                    // A row to tap, so in a row's colour and not the dialog's body colour (CT's carry-over).
                    Text(
                        text = parent.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(parent) }
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The list's own start inset, 16 dp from the edge as `ListRow`'s. */
private val LIST_INSET: Dp = 16.dp

/** How far a child row sits past its parent's start (phase 5 unit CT). */
private val CHILD_INDENT: Dp = 24.dp

/**
 * Create and rename share one dialog: the field, the validation and the keyboard behaviour are the
 * same and only the labels differ. The typed name is `rememberSaveable` so a rotation with the
 * dialog open does not discard it.
 */
@Composable
private fun CategoryNameDialog(
    @StringRes titleRes: Int,
    @StringRes confirmRes: Int,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.categories_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(stringResource(confirmRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CategoryDeleteDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.categories_delete)) },
        text = { Text(stringResource(R.string.categories_delete_confirm, name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
