package com.shinsaikou.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.manga.MangaDetailScreen
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The tag on the pane's root, whichever half it is showing.
 *
 * Production API for the instrumentation suite, like the two tags on the manga detail screen.
 * The pane draws the same detail screen the route draws, so no assertion on its content can tell
 * the two apart, and the grid beside it already carries every title on screen.
 */
const val LIBRARY_DETAIL_PANE_TAG = "library_detail_pane"

/**
 * The end pane of the library's tablet layout: the real [MangaDetailScreen], hosted here rather
 * than pushed onto the back stack.
 *
 * Hosting it is the whole point. A reduced preview would be a second chapter list over the same
 * rows, and the duplication the carry-over sections keep recording starts exactly there. What the
 * route gives up in exchange is its ownership of the view model, which is what the rest of this
 * file is about.
 *
 * [mangaId] null is the resting state: nothing has been tapped yet, or what was tapped has left
 * the library. The pane says so rather than drawing an empty chapter list, and it is deliberately
 * not [tachiyomi.presentation.core.screens.EmptyScreen], whose error face reads as a failure when
 * nothing has gone wrong.
 */
@Composable
internal fun LibraryDetailPane(
    mangaId: Long?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (mangaId == null) {
        Box(
            modifier = modifier.fillMaxSize().testTag(LIBRARY_DETAIL_PANE_TAG),
            contentAlignment = Alignment.Center,
        ) {
            InlineNotice(stringResource(R.string.library_detail_pane_empty))
        }
        return
    }
    // key, so every remembered value inside the detail screen belongs to the entry it was
    // remembered for. Without it the chapter list's scroll position, the header's scroll position
    // and an open overflow menu would all carry from one entry to the next, because the pane is
    // one composable that swaps its argument rather than a screen that is entered and left.
    key(mangaId) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides rememberPaneViewModelStoreOwner(mangaId)) {
            Box(modifier = modifier.fillMaxSize().testTag(LIBRARY_DETAIL_PANE_TAG)) {
                // fromSource is false: everything reachable from this pane is already in the
                // library, so the one automatic fetch belongs to an entry that has never been
                // fetched rather than to every tap on a cover. twoPane is false because this is
                // already the second pane: the detail screen reads the window rather than the
                // space it was given, and left to itself it splits a 420 dp pane in half again.
                MangaDetailScreen(
                    mangaId = mangaId,
                    fromSource = false,
                    onBack = onClose,
                    twoPane = false,
                )
            }
        }
    }
}

/**
 * A view model store scoped to [key] instead of to the navigation entry.
 *
 * `assistedMetroViewModel` resolves its owner from `LocalViewModelStoreOwner`, and on a route that
 * owner is the back stack entry, which is created and destroyed with the screen. The pane has no
 * entry of its own: left alone it would put the detail view model into the library's store, under
 * the default key, so the assisted factory would run for the first entry tapped and every later
 * tap would be served the first entry's view model. Keying by [key] alone fixes the identity and
 * leaks instead, one live view model per cover ever tapped, each holding a chapter list.
 *
 * So the store is the pane's own and is cleared when [key] changes or when the pane leaves the
 * composition, which is what ends the outgoing entry's `viewModelScope` and with it any fetch it
 * had in flight.
 */
@Composable
private fun rememberPaneViewModelStoreOwner(key: Any): ViewModelStoreOwner {
    val owner = remember(key) { PaneViewModelStoreOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

private class PaneViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
}
