package com.shinsaikou.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.downloads.adoption.AdoptionScheduler
import com.shinsaikou.downloads.adoption.AdoptionSummary
import com.shinsaikou.downloads.adoption.AdoptionSummaryStore
import com.shinsaikou.downloads.adoption.AdoptionUnmatched
import com.shinsaikou.downloads.adoption.AdoptionUnmatchedReason
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * The last copy from a folder (unit adoption-unmatched-reasons): the stored summary, its unmatched
 * paths grouped by the reason the walker gave, each group with the action that would match it next
 * time, and Copy again over the stored tree.
 *
 * A source folder's row follows the extension lists as they change: Install while a repository
 * offers an extension listing a source of that name and language (unless the package is on disk
 * and untrusted, as the restore report's list rules), and no action once such a source is live,
 * since Copy again is then what matches it. Upstream has no copy flow (it reads its folder in place),
 * so there is nothing of upstream's to follow here.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(AdoptionSummaryViewModel::class)
@Inject
class AdoptionSummaryViewModel(
    private val summaryStore: AdoptionSummaryStore,
    private val scheduler: AdoptionScheduler,
    private val extensionManager: ExtensionManager,
    private val sourceManager: SourceManager,
    private val dispatchers: Dispatchers,
) : StateViewModel<AdoptionSummaryViewModel.State>(State()) {

    @Immutable
    data class State(
        val loading: Boolean = true,
        /** Null once loaded means no copy has finished on this install. */
        val summary: AdoptionSummary? = null,
        /** The unmatched paths as [groupUnmatched] lays them out, source folders with their extension state. */
        val rows: List<UnmatchedRow> = emptyList(),
        val installSteps: Map<String, InstallStep> = emptyMap(),
        val adoption: AdoptionScheduler.State = AdoptionScheduler.State(running = false),
    )

    /** One row of the unmatched list. [path] is relative to the picked tree, and unique in the list. */
    sealed interface UnmatchedRow {
        val path: String

        /**
         * A source folder no installed source answers, read as [name] and [lang]. [available] is the
         * extension a repository offers for it; [installed] is set once a live source has that name
         * and language.
         */
        data class SourceFolder(
            override val path: String,
            val name: String,
            val lang: String?,
            val available: Extension.Available? = null,
            val installed: Boolean = false,
        ) : UnmatchedRow

        /** An entry folder no library entry of its source answers. */
        data class EntryFolder(override val path: String, val title: String, val source: String?) : UnmatchedRow

        /** The [count] chapters of the entry folder at [path] that its entry's chapter list does not name. */
        data class EntryChapters(
            override val path: String,
            val title: String,
            val source: String?,
            val mangaId: Long?,
            val count: Int,
        ) : UnmatchedRow

        /** An unfinished download, a second copy, or a path from a summary that kept no reason. */
        data class Other(override val path: String, val reason: AdoptionUnmatchedReason) : UnmatchedRow
    }

    sealed interface Event {
        /** The stored tree cannot be read again; the screen opens the picker, at [hint] when there is one. */
        data class PickFolder(val hint: String?) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** The install job per package, so a row's ring can cancel it, as `ExtensionsViewModel` keeps them. */
    private val installJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            summaryStore.load()
            val offersInstall = summaryStore.summary.value?.unmatched.orEmpty()
                .any { it.reason == AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED && it.sourceLang != null }
            if (offersInstall) refreshIfNothingAvailable()
            val extensions = combine(
                extensionManager.availableExtensions,
                extensionManager.untrustedExtensions,
                sourceManager.getSources(),
            ) { available, untrusted, live -> Triple(available, untrusted.mapTo(HashSet()) { it.packageName }, live) }
            combine(
                // Grouped once per summary; only the source folders change with the extension lists.
                summaryStore.summary.map { summary -> summary to groupUnmatched(summary?.unmatched.orEmpty()) },
                extensions,
                extensionManager.installSteps,
            ) { (summary, rows), (available, untrusted, live), steps ->
                Triple(summary, withExtensions(rows, available, untrusted, live), steps)
            }
                .flowOn(dispatchers.io)
                .collect { (summary, rows, steps) ->
                    mutableState.update {
                        it.copy(loading = false, summary = summary, rows = rows, installSteps = steps)
                    }
                }
        }
        viewModelScope.launch {
            scheduler.state.collect { adoption -> mutableState.update { it.copy(adoption = adoption) } }
        }
    }

    /**
     * Starts a run over the stored tree when it can still be read, which skips every chapter
     * already copied; otherwise, or for a summary that kept no tree, asks the screen for the picker.
     */
    fun copyAgain() {
        val tree = state.value.summary?.treeUri
        viewModelScope.launch {
            if (tree != null && scheduler.reopen(tree)) {
                scheduler.start(tree)
            } else {
                eventChannel.send(Event.PickFolder(tree))
            }
        }
    }

    /** A tree the owner picked from Copy again; the screen has taken its read grant. */
    fun copyFrom(treeUri: String) {
        viewModelScope.launch { scheduler.start(treeUri) }
    }

    /**
     * The available list lives in memory only, and nothing fills it until the Extensions tab opens or
     * a start-up check finds the last fetch over six hours old, so a source folder's row would offer
     * Open Extensions where Install belongs. The summary fetches the indexes once in that case, as the
     * Extensions tab does on opening (ruling R46), and the rows follow the list when it lands. A
     * failure leaves them on Open Extensions, whose tab shows why.
     */
    private fun refreshIfNothingAvailable() {
        if (extensionManager.availableExtensions.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                withContext(dispatchers.io) { extensionManager.refreshAvailable() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "The copy summary's index fetch failed" }
            }
        }
    }

    /** Installs [available] as the Extensions tab does, against the manager's current list. */
    fun install(available: Extension.Available) {
        val target = extensionManager.availableExtensions.value
            .firstOrNull { it.packageName == available.packageName } ?: return
        installJobs[target.packageName] = extensionManager.install(target)
    }

    fun cancelInstall(packageName: String) {
        installJobs.remove(packageName)?.cancel()
    }

    companion object {

        /**
         * [unmatched] in the screen's order: the source folders, the entry folders, then one row per
         * entry folder for its chapters with no match, then unfinished downloads, second copies and
         * paths with no reason, each one row. Within a group the walker's order (by name) is kept.
         */
        internal fun groupUnmatched(unmatched: List<AdoptionUnmatched>): List<UnmatchedRow> {
            val byReason = unmatched.groupBy { it.reason }
            fun of(reason: AdoptionUnmatchedReason) = byReason[reason].orEmpty()
            return buildList {
                of(AdoptionUnmatchedReason.SOURCE_NOT_INSTALLED).forEach {
                    add(UnmatchedRow.SourceFolder(it.path, it.sourceName ?: lastName(it.path), it.sourceLang))
                }
                of(AdoptionUnmatchedReason.NOT_IN_LIBRARY).forEach {
                    add(UnmatchedRow.EntryFolder(it.path, it.title ?: lastName(it.path), sourceLabel(it)))
                }
                of(AdoptionUnmatchedReason.NO_CHAPTER).groupBy { it.path.substringBeforeLast('/') }
                    .forEach { (entryPath, chapters) ->
                        val first = chapters.first()
                        add(
                            UnmatchedRow.EntryChapters(
                                path = entryPath,
                                title = first.title ?: lastName(entryPath),
                                source = sourceLabel(first),
                                mangaId = chapters.firstNotNullOfOrNull { it.mangaId },
                                count = chapters.size,
                            ),
                        )
                    }
                listOf(
                    AdoptionUnmatchedReason.UNFINISHED,
                    AdoptionUnmatchedReason.DUPLICATE,
                    AdoptionUnmatchedReason.UNKNOWN,
                ).forEach { reason -> of(reason).forEach { add(UnmatchedRow.Other(it.path, reason)) } }
            }
        }

        /** [rows] with each source folder's install offer and live state from the current lists. */
        internal fun withExtensions(
            rows: List<UnmatchedRow>,
            available: List<Extension.Available>,
            untrustedPackages: Set<String>,
            live: List<Source>,
        ): List<UnmatchedRow> = rows.map { row ->
            if (row !is UnmatchedRow.SourceFolder) return@map row
            row.copy(
                available = installMatch(row.name, row.lang, available, untrustedPackages),
                installed = row.lang != null &&
                    live.any { it.name.equals(row.name, ignoreCase = true) && it.lang.equals(row.lang, ignoreCase = true) },
            )
        }

        /**
         * The available extension that lists a source named [name] in [lang], both compared without
         * case, as upstream writes a source folder `"<name> (<LANG>)"`. None for a folder with no
         * language, and none for a package on disk and untrusted: installing over it would not load
         * it, and the Extensions tab's Trust does.
         */
        internal fun installMatch(
            name: String,
            lang: String?,
            available: List<Extension.Available>,
            untrustedPackages: Set<String>,
        ): Extension.Available? {
            if (lang == null) return null
            return available
                .firstOrNull { extension ->
                    extension.sources.any { it.name.equals(name, ignoreCase = true) && it.lang.equals(lang, ignoreCase = true) }
                }
                ?.takeUnless { it.packageName in untrustedPackages }
        }

        /** The source folder as upstream names it, from what the walker read of it. */
        private fun sourceLabel(unmatched: AdoptionUnmatched): String? {
            val name = unmatched.sourceName ?: return null
            return unmatched.sourceLang?.let { "$name ($it)" } ?: name
        }

        private fun lastName(path: String): String = path.substringAfterLast('/')
    }
}
