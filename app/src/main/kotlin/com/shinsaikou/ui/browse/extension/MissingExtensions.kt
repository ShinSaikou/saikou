package com.shinsaikou.ui.browse.extension

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.i18n.R
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.browse.labelRes
import com.shinsaikou.ui.components.ListRowDetail
import com.shinsaikou.ui.components.ListRowLayout
import com.shinsaikou.ui.components.ListRowTitle
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.extension.model.InstallStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The one list of sources this device lacks (unit restore-report): each with how many library
 * entries it holds and the available extension that provides it, when a configured repository lists
 * one. The restore report draws it for the sources a backup named; F17 adds `forLibrary()` for the
 * library's own sources after a sync, and draws the same rows.
 *
 * Upstream has no such list (an entry with a missing source says "Source not installed" and no more),
 * so this is a divergence the owner's rule asks for: a missing extension comes with its Install.
 */
@Inject
class MissingExtensions(
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val mangaRepository: MangaRepository,
) {

    /**
     * One source no live source serves. [available] is null when no configured repository lists the
     * source, or when its package is already on disk and untrusted (Install would not load it; the
     * Extensions tab's Trust does). [query] is what Open Extensions searches for: the source's bare
     * name, since the tab matches the query inside extension and source names and [name]'s language
     * suffix would match nothing; null when only the id is known, which would match nothing either.
     */
    data class Row(
        val sourceId: Long,
        val name: String,
        val entries: Long,
        val available: Extension.Available?,
        val query: String?,
    )

    /**
     * The sources of [ids] that no live source serves, by name, the Local source aside. Nothing is
     * emitted before the registry's first load, when every installed source still reads as missing,
     * and the list follows installs, repository fetches and the library as they change: a source an
     * install brings live leaves the list.
     *
     * The live check asks [SourceManager.get] rather than reading the combined list, as
     * `ExtensionsViewModel.managerState` does for its own flag (ruling R69 item 3): the flag can
     * arrive in the combine before the list its load published.
     */
    fun forSources(ids: Collection<Long>): Flow<List<Row>> {
        val wanted = ids.filter { it != SourceIds.LOCAL }.distinct()
        return combine(
            sourceManager.isInitialized,
            sourceManager.getSources(),
            extensionManager.availableExtensions,
            extensionManager.untrustedExtensions,
            mangaRepository.getSourceIdsWithFavoriteCountAsFlow(),
        ) { initialized, _, available, untrusted, counts ->
            if (!initialized) return@combine null
            val untrustedPackages = untrusted.mapTo(HashSet()) { it.packageName }
            wanted
                .filter { sourceManager.get(it) == null }
                .map { id ->
                    val source = sourceManager.getOrStub(id)
                    Row(
                        sourceId = id,
                        name = source.toString(),
                        entries = counts[id] ?: 0L,
                        available = available
                            .firstOrNull { extension -> extension.sources.any { it.id == id } }
                            ?.takeUnless { it.packageName in untrustedPackages },
                        query = source.name.takeUnless { it.isBlank() || it == id.toString() },
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
            .filterNotNull()
    }
}

/**
 * [MissingExtensions]' rows: the name, the entry count, and **Install** when a repository offers the
 * extension or **Open Extensions** when none does, which opens the Extensions tab searched for the
 * row's [MissingExtensions.Row.query]. While an install runs, the row shows its step in the count's place and a ring that cancels on
 * a tap, as the Extensions tab's rows do (ruling R54).
 *
 * A column rather than lazy items: a library rarely lacks more than a few dozen sources, and the
 * caller's list is the lazy one.
 */
@Composable
fun MissingExtensionsList(
    rows: List<MissingExtensions.Row>,
    installSteps: Map<String, InstallStep>,
    onInstall: (Extension.Available) -> Unit,
    onCancelInstall: (String) -> Unit,
    onOpenExtensions: (MissingExtensions.Row) -> Unit,
) {
    Column {
        rows.forEach { row ->
            val step = row.available?.let { installSteps[it.packageName] }
            MissingExtensionRow(
                row = row,
                step = step,
                onInstall = onInstall,
                onCancelInstall = onCancelInstall,
                onOpenExtensions = onOpenExtensions,
            )
        }
    }
}

@Composable
private fun MissingExtensionRow(
    row: MissingExtensions.Row,
    step: InstallStep?,
    onInstall: (Extension.Available) -> Unit,
    onCancelInstall: (String) -> Unit,
    onOpenExtensions: (MissingExtensions.Row) -> Unit,
) {
    val available = row.available
    val stepRes = step?.labelRes()
    ListRowLayout(
        trailing = {
            when {
                available != null && stepRes != null -> IconButton(onClick = { onCancelInstall(available.packageName) }) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ext_action_cancel_install),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                available != null -> TextButton(onClick = { onInstall(available) }) {
                    Text(stringResource(R.string.action_install))
                }
                else -> TextButton(onClick = { onOpenExtensions(row) }) {
                    Text(stringResource(R.string.import_report_open_extensions))
                }
            }
        },
    ) {
        ListRowTitle(row.name)
        if (stepRes != null) {
            Text(
                stringResource(stepRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        } else {
            ListRowDetail(pluralStringResource(R.plurals.import_report_source_entries, row.entries.toInt(), row.entries.toInt()))
        }
    }
}
