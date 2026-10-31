package com.shinsaikou.extensions.adapter

import logcat.LogPriority
import logcat.logcat
import com.shinsaikou.source.model.Filter as ContractFilter
import com.shinsaikou.source.model.FilterList as ContractFilterList
import eu.kanade.tachiyomi.source.Source as AbiSource
import eu.kanade.tachiyomi.source.model.Filter as AbiFilter
import eu.kanade.tachiyomi.source.model.FilterList as AbiFilterList

private const val TAG = "AbiFilters"

/**
 * Holds the filter tree an extension last handed out. The contract's filter nodes are final, but
 * extensions subclass the ABI's abstract ones and read their request parameters off the subclass,
 * so a search has to be run against the extension's own instances rather than fresh ones.
 */
internal class AbiFilterCache {
    @Volatile
    var lastAbiFilters: AbiFilterList? = null
}

/** ABI tree to contract tree. A group element that is not a filter is dropped, as upstream's UI does. */
internal fun AbiFilterList.toContract(): ContractFilterList =
    ContractFilterList(list.mapNotNull { it.toContract() })

private fun AbiFilter<*>.toContract(): ContractFilter<*>? = when (this) {
    is AbiFilter.Header -> ContractFilter.Header(name)
    is AbiFilter.Separator -> ContractFilter.Separator(name)
    is AbiFilter.Select<*> -> ContractFilter.Select(name, values.map { it.toString() }, state)
    is AbiFilter.Text -> ContractFilter.Text(name, state)
    is AbiFilter.CheckBox -> ContractFilter.CheckBox(name, state)
    is AbiFilter.TriState -> ContractFilter.TriState(name, state)
    is AbiFilter.Group<*> -> ContractFilter.Group(name, state.mapNotNull { (it as? AbiFilter<*>)?.toContract() })
    is AbiFilter.Sort -> ContractFilter.Sort(
        name,
        values.toList(),
        state?.let { ContractFilter.Sort.Selection(it.index, it.ascending) },
    )
}

/**
 * Contract tree back onto the extension's own instances.
 *
 * An empty list means "source defaults" and is passed through as an empty ABI list, which is what
 * upstream sends when the user edited nothing. Otherwise the states are copied positionally onto
 * [AbiFilterCache.lastAbiFilters], or onto a fresh tree when nothing is cached. A shape mismatch
 * (an extension update changed its tree while a browse screen was open) discards the user's states
 * and searches with the extension's defaults rather than failing the search.
 */
internal fun ContractFilterList.toAbi(delegate: AbiSource, cache: AbiFilterCache): AbiFilterList {
    if (isEmpty()) return AbiFilterList()
    val target = cache.lastAbiFilters ?: delegate.getFilterList()
    if (applyStates(list, target.list, delegate.id)) return target
    cache.lastAbiFilters = null
    return delegate.getFilterList()
}

private fun applyStates(ours: List<ContractFilter<*>>, theirs: List<AbiFilter<*>>, sourceId: Long): Boolean {
    if (ours.size != theirs.size) {
        logcat(TAG, LogPriority.WARN) { "Filter tree changed shape for source $sourceId: ${ours.size} nodes, ${theirs.size} expected" }
        return false
    }
    ours.forEachIndexed { index, our ->
        val their = theirs[index]
        val applied = when {
            our is ContractFilter.Header && their is AbiFilter.Header -> true
            our is ContractFilter.Separator && their is AbiFilter.Separator -> true
            our is ContractFilter.Select && their is AbiFilter.Select<*> -> {
                their.state = our.state
                true
            }
            our is ContractFilter.Text && their is AbiFilter.Text -> {
                their.state = our.state
                true
            }
            our is ContractFilter.CheckBox && their is AbiFilter.CheckBox -> {
                their.state = our.state
                true
            }
            our is ContractFilter.TriState && their is AbiFilter.TriState -> {
                their.state = our.state
                true
            }
            our is ContractFilter.Sort && their is AbiFilter.Sort -> {
                their.state = our.state?.let { AbiFilter.Sort.Selection(it.index, it.ascending) }
                true
            }
            our is ContractFilter.Group && their is AbiFilter.Group<*> ->
                applyStates(our.state, their.state.filterIsInstance<AbiFilter<*>>(), sourceId)
            else -> false
        }
        if (!applied) {
            logcat(TAG, LogPriority.WARN) { "Filter node $index changed type for source $sourceId" }
            return false
        }
    }
    return true
}
