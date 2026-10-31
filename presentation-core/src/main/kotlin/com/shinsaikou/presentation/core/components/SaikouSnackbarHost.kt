package com.shinsaikou.presentation.core.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Every screen's snackbar host: the snackbar on `inverseSurface` with `inverseOnSurface` text
 * (`design/plans/phase5-start.md` 3.1 item 12, which the look rethink keeps), its action in
 * `inversePrimary`. These are Material's own defaults, written out so the rule does not rest on a
 * library default.
 *
 * Here rather than in `:app` so the reader, which cannot see `:app`, shows its messages on the same
 * host (unit ID3); `:app`'s `SaikouSnackbarHost` hands on to this one.
 */
@Composable
fun SaikouSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    SnackbarHost(hostState, modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = scheme.inverseSurface,
            contentColor = scheme.inverseOnSurface,
            actionColor = scheme.inversePrimary,
            dismissActionContentColor = scheme.inverseOnSurface,
        )
    }
}
