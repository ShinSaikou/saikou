package com.shinsaikou.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The buttons of the tabs' top bars, so Library, Updates, History and Browse draw them alike. One
 * order for all four: a status icon that comes and goes first, so the steady ones never move when it
 * appears; then Search; then the toggles that change what the list shows; then the tab's own action;
 * and the overflow last, only when it holds more than one item.
 *
 * Every button is an outlined icon at Material's 24 dp in its 48 dp touch target, named by its
 * content description and, as upstream's `AppBarActions` does, by a plain tooltip on long press.
 */

/**
 * An icon button. [highlighted] tints it in the primary colour, for an action whose screen holds a
 * setting that is on (the library's filter): a filter that is on and invisible is how a list comes
 * to look half missing.
 */
@Composable
fun TopBarAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    TopBarTooltip(label) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
    }
}

/**
 * An icon that switches something on and off, in the primary colour while [checked], with toggleable
 * semantics so a screen reader says which way it is. [label] names what a tap does.
 */
@Composable
fun TopBarToggleAction(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    TopBarTooltip(label) {
        IconToggleButton(checked = checked, onCheckedChange = onCheckedChange) {
            Icon(imageVector = icon, contentDescription = label)
        }
    }
}

/** A line of [TopBarOverflow]'s menu. */
data class TopBarMenuItem(val label: String, val onClick: () -> Unit)

/** The overflow button and its menu, which closes before an item's action runs. Nothing without [items]. */
@Composable
fun TopBarOverflow(items: List<TopBarMenuItem>) {
    if (items.isEmpty()) return
    var menuOpen by remember { mutableStateOf(false) }
    TopBarAction(
        label = stringResource(R.string.action_menu_overflow_description),
        icon = Icons.Outlined.MoreVert,
        onClick = { menuOpen = true },
    )
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        items.forEach { item ->
            DropdownMenuItem(
                text = { Text(item.label) },
                onClick = {
                    menuOpen = false
                    item.onClick()
                },
            )
        }
    }
}

/** [label] in a plain tooltip under [content] on a long press, or while a pointer rests on it. */
@Composable
private fun TopBarTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}
