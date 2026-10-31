// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/components/AppBar.kt (modified: yes)
package eu.kanade.presentation.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shinsaikou.i18n.R
import tachiyomi.presentation.core.components.DropdownMenu
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Modified: trimmed to what `ReaderTopBar` needs, which is [AppBar], [AppBarTitle], [AppBarActions],
 * [UpIcon] and the [AppBar] action types. Dropped: `SearchToolbar` (the reader has no search) and
 * the whole action-mode half of the two `AppBar` overloads (the reader selects nothing), which also
 * drops upstream's `action_cancel` and `action_reset` references.
 *
 * Provenance note, and a carry-over: this component belongs in `:presentation-core` beside the rest
 * of the shared chrome, but that module is merged and this unit may not write to it. `:reader` is
 * the first consumer, so the trimmed copy lives here. When `:app` needs the full `AppBar`, move
 * this file to `:presentation-core` with `SearchToolbar` restored and delete it from `:reader`;
 * declaring it in both modules would violate ruling R12.
 */
@Composable
fun AppBar(
    title: String?,
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    subtitle: String? = null,
    navigateUp: (() -> Unit)? = null,
    navigationIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    Column(modifier = modifier) {
        TopAppBar(
            navigationIcon = {
                navigateUp?.let {
                    IconButton(onClick = it) {
                        UpIcon(navigationIcon = navigationIcon)
                    }
                }
            },
            title = { AppBarTitle(title, subtitle = subtitle) },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = backgroundColor
                    ?: MaterialTheme.colorScheme.surfaceColorAtElevation(elevation = 0.dp),
            ),
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
fun AppBarTitle(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier = modifier) {
        title?.let {
            Text(
                text = it,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(repeatDelayMillis = MARQUEE_DELAY_MILLIS),
            )
        }
    }
}

/**
 * Modified: the tooltip wrappers are dropped with upstream's `TooltipBox`, whose
 * `TooltipAnchorPosition` API moved between Material3 versions. The icon's `contentDescription`
 * already carries the same text for accessibility.
 */
@Composable
fun AppBarActions(actions: List<AppBar.AppBarAction>) {
    var showMenu by remember { mutableStateOf(false) }

    actions.filterIsInstance<AppBar.Action>().forEach {
        IconButton(onClick = it.onClick, enabled = it.enabled) {
            Icon(
                imageVector = it.icon,
                tint = it.iconTint ?: LocalContentColor.current,
                contentDescription = it.title,
            )
        }
    }

    val overflowActions = actions.filterIsInstance<AppBar.OverflowAction>()
    if (overflowActions.isNotEmpty()) {
        IconButton(onClick = { showMenu = !showMenu }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.action_menu_overflow_description),
            )
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            overflowActions.forEach {
                DropdownMenuItem(
                    onClick = {
                        it.onClick()
                        showMenu = false
                    },
                    text = { Text(it.title, fontWeight = FontWeight.Normal) },
                )
            }
        }
    }
}

@Composable
fun UpIcon(
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
) {
    Icon(
        imageVector = navigationIcon ?: Icons.AutoMirrored.Outlined.ArrowBack,
        contentDescription = stringResource(R.string.action_bar_up_description),
        modifier = modifier,
    )
}

sealed interface AppBar {
    sealed interface AppBarAction

    data class Action(
        val title: String,
        val icon: ImageVector,
        val iconTint: Color? = null,
        val onClick: () -> Unit,
        val enabled: Boolean = true,
    ) : AppBarAction

    data class OverflowAction(
        val title: String,
        val onClick: () -> Unit,
    ) : AppBarAction
}

private const val MARQUEE_DELAY_MILLIS = 2_000
