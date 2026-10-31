package com.shinsaikou.ui.browse.settings

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.extensions.cookies.CookieImporter
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.ConfigurableSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * One source's own settings: the preference tree the extension builds for itself, and the cookie
 * jar it shares with every other client in the process.
 *
 * The source's preferences are deliberately absent from [State]. They are `androidx.preference`
 * objects that persist themselves into the `source_<id>` file the extension reads at runtime, so
 * the screen owns them directly and this view model only says whether there are any. Putting them
 * in state would mean copying values out of that file and writing them back, which is the one way
 * to end up with the host and the extension disagreeing about a setting.
 *
 * Everything cookie related is addressed by URL rather than by host. That is what
 * `AndroidCookieJar` and `android.webkit.CookieManager` take, and narrowing it to a bare host here
 * would only mean re-deriving a URL further down.
 */
@AssistedInject
class SourceSettingsViewModel(
    @Assisted private val sourceId: Long,
    private val sourceManager: SourceManager,
    private val cookieImporter: CookieImporter,
    private val dispatchers: Dispatchers,
) : StateViewModel<SourceSettingsViewModel.State>(State()) {

    data class State(
        /** True until the source has been looked up, which waits for the registry's first load. */
        val loading: Boolean = true,
        val sourceName: String? = null,
        val installed: Boolean = true,
        val configurable: Boolean = false,
        /** The source's landing page, null for a source with no web presence such as Local. */
        val homeUrl: String? = null,
        val cookieHost: String = "",
        val cookieHeader: String = "",
        val cookieNames: List<String> = emptyList(),
        val importing: Boolean = false,
    )

    sealed interface Event {
        data class Message(@StringRes val messageRes: Int) : Event

        /** A message whose only argument is a count, formatted by the screen where Resources live. */
        data class CountMessage(@StringRes val messageRes: Int, val count: Int) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /**
     * The live source when it has preferences of its own, handed to the screen so it can ask the
     * source to populate a `PreferenceScreen`. It is not in [State] and never survives into a
     * snapshot: it holds the extension's classloader alive and only the composition should touch
     * it, on the main thread, which is what `setupPreferenceScreen` requires. Null until the
     * source has been looked up; [State.configurable] turns true when it is set.
     */
    var configurableSource: ConfigurableSource? = null
        private set

    init {
        viewModelScope.launch {
            // Ruling R69 item 4. A settings screen restored after process death comes back as the
            // process starts, when an installed extension's source still reads as missing, and the
            // source is looked up once for the screen's life. So the lookup waits for the registry,
            // with the screen's loading state up meanwhile.
            sourceManager.isInitialized.first { it }
            open(sourceManager.get(sourceId))
        }
    }

    private fun open(source: Source?) {
        configurableSource = source as? ConfigurableSource
        val home = source?.homeUrl
        mutableState.update {
            it.copy(
                loading = false,
                sourceName = source?.name,
                installed = source != null,
                configurable = configurableSource != null,
                homeUrl = home,
                cookieHost = home.orEmpty(),
            )
        }
        refreshCookies()
    }

    fun setCookieHost(text: String) {
        mutableState.update { it.copy(cookieHost = text) }
        refreshCookies()
    }

    fun setCookieHeader(text: String) {
        mutableState.update { it.copy(cookieHeader = text) }
    }

    /**
     * Writes a pasted `Cookie` header into the shared jar. The header field is cleared only when
     * something was actually written, so a header that parsed to nothing stays there to be fixed.
     */
    fun importCookies() {
        val current = state.value
        if (current.importing) return
        val url = current.cookieHost.trim()
        if (url.toHttpUrlOrNull() == null) {
            emit(Event.Message(R.string.source_cookies_invalid_url))
            return
        }
        val header = current.cookieHeader.trim()
        if (header.isEmpty()) return
        mutableState.update { it.copy(importing = true) }
        viewModelScope.launch {
            val written = withContext(dispatchers.io) { cookieImporter.importCookieHeader(url, header) }
            val names = withContext(dispatchers.io) { namesFor(url) }
            mutableState.update {
                it.copy(
                    importing = false,
                    cookieHeader = if (written > 0) "" else it.cookieHeader,
                    cookieNames = if (it.cookieHost.trim() == url) names else it.cookieNames,
                )
            }
            eventChannel.send(Event.CountMessage(R.string.source_cookies_imported_format, written))
        }
    }

    /**
     * Removes every cookie the jar holds for the address in the field, and forces the removal out to
     * disk.
     *
     * The flush is the whole point of the call. A removal only expires the cookie in
     * `CookieManager`'s memory, and the manager writes its store out on a schedule of its own, so a
     * session the owner deliberately dropped is back on the next launch if the process dies first:
     * the deletion is undone and every extension request for that host goes out carrying it again.
     * Import already forces its write out for the same reason, and this is the direction where being
     * wrong costs more.
     */
    fun clearCookies() {
        val url = state.value.cookieHost.trim()
        if (url.toHttpUrlOrNull() == null) {
            emit(Event.Message(R.string.source_cookies_invalid_url))
            return
        }
        viewModelScope.launch {
            // The null is spelled out: this is the "every name for this host" call, not a default
            // that a later reader has to go and look up.
            val removed = withContext(dispatchers.io) {
                val count = cookieImporter.clear(url, names = null)
                // Conditional for the same reason the import is: the flush is a real write to the
                // store, and a host that had nothing set has nothing to write out.
                if (count > 0) cookieImporter.flush()
                count
            }
            val names = withContext(dispatchers.io) { namesFor(url) }
            mutableState.update {
                it.copy(cookieNames = if (it.cookieHost.trim() == url) names else it.cookieNames)
            }
            eventChannel.send(Event.CountMessage(R.string.source_cookies_cleared_format, removed))
        }
    }

    /**
     * Re-reads the jar for whatever address is in the field. It stays silent on an address that
     * cannot be parsed, because it runs on every keystroke: a snackbar per character typed into a
     * half finished URL would bury the messages that matter.
     */
    fun refreshCookies() {
        val url = state.value.cookieHost.trim()
        if (url.toHttpUrlOrNull() == null) {
            mutableState.update { it.copy(cookieNames = emptyList()) }
            return
        }
        viewModelScope.launch {
            val names = withContext(dispatchers.io) { namesFor(url) }
            // A slow lookup for an address the owner has already edited away from must not
            // overwrite the newer one's list.
            mutableState.update { if (it.cookieHost.trim() == url) it.copy(cookieNames = names) else it }
        }
    }

    private fun namesFor(url: String): List<String> = cookieImporter.cookiesFor(url).map { it.name }

    private fun emit(event: Event) {
        viewModelScope.launch { eventChannel.send(event) }
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(sourceId: Long): SourceSettingsViewModel
    }
}
