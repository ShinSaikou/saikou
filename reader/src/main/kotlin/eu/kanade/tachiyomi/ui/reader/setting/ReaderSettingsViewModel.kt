// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderSettingsViewModel.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.setting

import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.reader.state.ReaderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.domain.manga.model.Manga

/**
 * Modified (I) and (H), and rewritten to contract 07 part 2 section 5.6's real constructor.
 *
 * It is a plain class rather than a `ViewModel`, so `viewModelScope` becomes `ReaderScreen`'s
 * `rememberCoroutineScope()`, and `ReaderPreferences` and the reader state arrive as parameters
 * where upstream reaches Injekt and its own `ReaderViewModel.State`.
 *
 * Upstream's `viewerFlow` is gone: `ReadingModePage` used it only to pick between the pager settings
 * and the webtoon settings, and [isWebtoonFlow] decides the same thing from the effective mode
 * without holding a reference to a live `Viewer` (decision D23). [readingModeFlow] and
 * [orientationFlow] carry the raw per-manga values, which may be `DEFAULT`, because that is what
 * the chips show.
 */
class ReaderSettingsViewModel(
    val preferences: ReaderPreferences,
    readerState: StateFlow<ReaderState>,
    scope: CoroutineScope,
    val onChangeReadingMode: (ReadingMode) -> Unit,
    val onChangeOrientation: (ReaderOrientation) -> Unit,
) {

    val mangaFlow: StateFlow<Manga?> = readerState
        .map { it.manga }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, readerState.value.manga)

    val readingModeFlow: StateFlow<ReadingMode> = readerState
        .map { it.mangaReadingMode }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, readerState.value.mangaReadingMode)

    val orientationFlow: StateFlow<ReaderOrientation> = readerState
        .map { it.mangaOrientation }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, readerState.value.mangaOrientation)

    val isWebtoonFlow: StateFlow<Boolean> = readerState
        .map { it.readingMode.isWebtoon }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, readerState.value.readingMode.isWebtoon)
}
