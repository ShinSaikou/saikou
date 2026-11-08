package com.shinsaikou.ui.main

import android.app.SearchManager
import android.content.Intent
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.model.Extension
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.ui.browse.enabledSources
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A search another app asked for (Experimental, `SourcePreferences.searchLinks`).
 *
 * [query] is what Global Search is given. [addresses] are the web addresses shared text carried, in
 * order, so the source whose site one of them names can search that address instead;
 * [extensionPackage] is set when an extension's link handler named itself in upstream's `filter`
 * extra. Which of the two a request ends on depends on the installed sources, so
 * [SearchRequestResolver] decides it, never the intent alone.
 */
data class SearchRequest(
    val query: String,
    val addresses: List<String> = emptyList(),
    val extensionPackage: String? = null,
)

/** The one source a [SearchRequest] is for, and what that source is asked: [query]. */
data class SourceSearch(val sourceId: Long, val query: String)

/**
 * The intents the search alias in the manifest accepts, read into a [SearchRequest]. Every query is
 * normalised the same way, so a shared page, a spoken search and an extension's link all reach the
 * search screens in the same shape.
 */
object SearchRequests {

    /** The search Google's app and the Assistant send to an app that offers search. */
    const val ACTION_GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"

    /**
     * Upstream's search action, with its two extras, which extensions' link handlers send: the
     * handler turns a page of its site into a query and names its own package as the filter.
     */
    const val ACTION_EXTENSION_SEARCH = "eu.kanade.tachiyomi.SEARCH"
    const val EXTRA_EXTENSION_QUERY = "query"
    const val EXTRA_EXTENSION_FILTER = "filter"

    /** Long enough for any title or address, short enough that a pasted page is not sent to every source. */
    const val MAX_QUERY_LENGTH = 500

    /** Null for any other action and for a query that is empty once normalised. */
    fun of(intent: Intent): SearchRequest? = when (intent.action) {
        Intent.ACTION_SEND -> sharedText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        Intent.ACTION_SEARCH, ACTION_GMS_SEARCH ->
            normalise(intent.getCharSequenceExtra(SearchManager.QUERY)?.toString())?.let { SearchRequest(it) }
        ACTION_EXTENSION_SEARCH ->
            normalise(intent.getCharSequenceExtra(EXTRA_EXTENSION_QUERY)?.toString())?.let { query ->
                val filter = intent.getStringExtra(EXTRA_EXTENSION_FILTER)?.trim()?.takeIf { it.isNotEmpty() }
                SearchRequest(query, extensionPackage = filter)
            }
        else -> null
    }

    /**
     * Shared text keeps every web address it holds, for a source whose site one names, and its words
     * with the addresses taken out are the query for everything else. A browser shares a page as
     * "Title https://..." and a selection as the selected words in quotes, a line break and a link
     * to them ("https://...#:~:text=..."), so the words are the title and one pair of quotes round
     * them goes. Text that is an address and nothing more searches the address.
     *
     * One pass over the text's runs of non-blank characters, never a regular expression over the
     * whole of it: the text arrives from another app, on the main thread, and a pattern that starts
     * with optional white space scans a long blank run again from each of its characters. The
     * text is never cut before the pass, so an address at the end of a whole page is still found.
     */
    internal fun sharedText(text: String?): SearchRequest? {
        if (text == null) return null
        val addresses = mutableListOf<String>()
        val words = mutableListOf<String>()
        for (token in tokens(text)) {
            // An address runs from its scheme to the end of the run, as it would in a browser's bar.
            val start = ADDRESS_START.find(token)?.takeIf { it.range.last < token.lastIndex }?.range?.first
            if (start == null) {
                words += token
            } else {
                if (start > 0) words += token.substring(0, start)
                addresses += token.substring(start).take(MAX_QUERY_LENGTH)
            }
        }
        val query = normalise(unquote(words.joinToString(" "))) ?: addresses.firstOrNull() ?: return null
        return SearchRequest(query, addresses)
    }

    /**
     * Every run of white space, line breaks included, folded into one space and the ends trimmed,
     * capped at [MAX_QUERY_LENGTH]; null when empty.
     */
    internal fun normalise(text: String?): String? =
        text?.let { tokens(it).joinToString(" ") }?.take(MAX_QUERY_LENGTH)?.trim()?.takeIf { it.isNotEmpty() }

    /** One pair of straight or curly quotes, single or double, round the whole of [text], taken off. */
    internal fun unquote(text: String): String {
        if (text.length < 2) return text
        val pair = text.first() to text.last()
        return if (pair in QUOTE_PAIRS) text.substring(1, text.length - 1) else text
    }

    /** The runs of non-blank characters of [text], in order, in one pass. */
    private fun tokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var start = -1
        for (index in text.indices) {
            if (text[index].isWhitespace()) {
                if (start >= 0) tokens += text.substring(start, index)
                start = -1
            } else if (start < 0) {
                start = index
            }
        }
        if (start >= 0) tokens += text.substring(start)
        return tokens
    }

    /** Whether two hosts name the same site, a leading "www." on either aside. */
    internal fun sameSite(a: String, b: String): Boolean =
        a.lowercase().removePrefix("www.") == b.lowercase().removePrefix("www.")

    /** A web address's scheme, looked for inside one run of non-blank characters at a time. */
    private val ADDRESS_START = Regex("""https?://""", RegexOption.IGNORE_CASE)

    private val QUOTE_PAIRS = setOf(
        '"' to '"',
        '\'' to '\'',
        '\u201C' to '\u201D',
        '\u2018' to '\u2019',
    )
}

/**
 * Which one source a [SearchRequest] is for, when it names one, and what it searches there. None,
 * or several that cannot be settled (see `pickOne`), leave it to Global Search, which searches every
 * source and has no way to be limited to a few. For shared text the first address whose site
 * settles on one source wins, and that source searches the address itself, which many extensions
 * answer with the page's entry.
 *
 * Only the sources Global Search would ask count ([enabledSources]). An extension for all languages
 * registers one source per language on one site, so a page of that site matches every one of them
 * that is enabled. A hidden source or a language turned off is never the answer: when only those
 * match, the query goes to Global Search, which does not ask them either.
 *
 * It waits for the source registry first (ruling R69 item 4): a share that starts the app arrives
 * before the extensions have loaded, when every source would read as missing.
 */
class SearchRequestResolver(
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val sourcePreferences: SourcePreferences,
    private val dispatchers: Dispatchers,
) {

    suspend fun sourceSearchOf(request: SearchRequest): SourceSearch? {
        if (request.addresses.isEmpty() && request.extensionPackage == null) return null
        sourceManager.isInitialized.first { it }
        return withContext(dispatchers.io) {
            val installed = extensionManager.installedExtensions.value
            val extensionPackage = request.extensionPackage
            if (extensionPackage != null) {
                pickOne(extensionSources(extensionPackage, installed), installed)?.let { SourceSearch(it.id, request.query) }
            } else {
                addressMatch(request.addresses, installed)
            }
        }
    }

    /**
     * The enabled sources of the extension [extensionPackage] names. One that is not installed has
     * none, which leaves the query to Global Search rather than dropping it.
     */
    private fun extensionSources(extensionPackage: String, installed: List<Extension.Installed>): List<CatalogueSource> {
        val extension = installed.firstOrNull { it.packageName == extensionPackage } ?: return emptyList()
        return extension.sources
            .mapNotNull { sourceManager.get(it.id) as? CatalogueSource }
            .enabledSources(sourcePreferences, installed)
    }

    /** The first of [addresses] whose site's enabled sources [pickOne] settles, searched on that source. */
    private suspend fun addressMatch(addresses: List<String>, installed: List<Extension.Installed>): SourceSearch? {
        // The Local source names no site, and the language rule exempts it, so it is left out here.
        val sites = sourceManager.getCatalogueSources().first()
            .filterNot { it.id == SourceIds.LOCAL }
            .enabledSources(sourcePreferences, installed)
            .mapNotNull { source ->
                // Reading the base URL runs extension code, which may throw.
                val host = runCatching { source.baseUrl }.getOrNull()?.toHttpUrlOrNull()?.host
                host?.let { source to it }
            }
        return addresses.firstNotNullOfOrNull { address ->
            val host = address.toHttpUrlOrNull()?.host ?: return@firstNotNullOfOrNull null
            val matches = sites.filter { (_, site) -> SearchRequests.sameSite(site, host) }.map { (source, _) -> source }
            pickOne(matches, installed)?.let { SourceSearch(it.id, address) }
        }
    }

    /**
     * The one source of [matches] a search is for, or null to leave it to Global Search.
     *
     * A lone match is the answer. Several are settled only when they all come from one extension:
     * an extension for many languages registers one source per language on one site, and the
     * "all" language is enabled by default, so its "all" source and the owner's own language both
     * match a page of that site. Which language the owner reads is then told, in order, by the one
     * pinned among them, by the one Browse lists as last used, or by the one for "all", which
     * covers every language the site has. Sources of different extensions on one site are
     * different sources, and nothing here can say which the owner meant, so they stay ambiguous.
     */
    private fun pickOne(matches: List<CatalogueSource>, installed: List<Extension.Installed>): CatalogueSource? {
        if (matches.size <= 1) return matches.singleOrNull()
        val owners = matches.map { source ->
            installed.firstOrNull { extension -> extension.sources.any { it.id == source.id } }?.packageName
        }
        if (owners.any { it == null } || owners.distinct().size != 1) return null
        val pinned = sourcePreferences.pinnedSources.get()
        matches.singleOrNull { it.id.toString() in pinned }?.let { return it }
        val lastUsed = sourcePreferences.lastUsedSource.get()
        matches.firstOrNull { it.id == lastUsed }?.let { return it }
        return matches.singleOrNull { it.lang == LANGUAGE_ALL }
    }

    private companion object {
        /** The language code a source for every language of its site reports (ruling R57). */
        const val LANGUAGE_ALL = "all"
    }
}
