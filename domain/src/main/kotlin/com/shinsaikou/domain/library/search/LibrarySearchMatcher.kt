package com.shinsaikou.domain.library.search

import com.shinsaikou.domain.library.model.LibraryItem
import dev.zacsweers.metro.Inject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import tachiyomi.domain.library.model.search.AndNode
import tachiyomi.domain.library.model.search.ComparisonField
import tachiyomi.domain.library.model.search.ComparisonQueryNode
import tachiyomi.domain.library.model.search.EmptyQueryNode
import tachiyomi.domain.library.model.search.FieldQueryNode
import tachiyomi.domain.library.model.search.GeneralQueryNode
import tachiyomi.domain.library.model.search.MangaField
import tachiyomi.domain.library.model.search.NotNode
import tachiyomi.domain.library.model.search.OrNode
import tachiyomi.domain.library.model.search.QueryNode
import tachiyomi.domain.source.model.Source

/**
 * Evaluates a parsed library query against one library row. Upstream parses the query but never
 * evaluates it in its domain module, so these rules are Saikou's own and are fixed by test. An
 * edited title, description, author or artist (phase 5 unit ED, R165 item 4) matches as well as the
 * source's text under it: the owner types what they see, and a search for the source's title still
 * finds the entry. Edited genres replace the source's list, as the entry screen shows them, so a
 * genre the owner removed no longer matches.
 */
@Inject
class LibrarySearchMatcher {

    /**
     * @param node the parsed query
     * @param item the library row
     * @param source the resolved source, or null when the source is not installed
     */
    fun matches(node: QueryNode, item: LibraryItem, source: Source?): Boolean = when (node) {
        is EmptyQueryNode -> true
        is AndNode -> node.children.all { matches(it, item, source) }
        is OrNode -> node.children.any { matches(it, item, source) }
        is NotNode -> !matches(node.child, item, source)
        is GeneralQueryNode -> node.negated xor matchesGeneral(node.value, item, source)
        is FieldQueryNode -> node.negated xor matchesField(node, item, source)
        is ComparisonQueryNode -> node.negated xor matchesComparison(node, item)
    }

    private fun matchesGeneral(value: String, item: LibraryItem, source: Source?): Boolean {
        val manga = item.manga
        val haystack = sequence {
            yield(manga.title)
            manga.titleOverride?.let { yield(it) }
            manga.author?.let { yield(it) }
            manga.authorOverride?.let { yield(it) }
            manga.artist?.let { yield(it) }
            manga.artistOverride?.let { yield(it) }
            manga.description?.let { yield(it) }
            manga.descriptionOverride?.let { yield(it) }
            manga.displayGenre?.forEach { yield(it) }
            source?.name?.let { yield(it) }
            yield(manga.notes)
        }
        return haystack.any { it.containsIgnoringCase(value) }
    }

    private fun matchesField(node: FieldQueryNode, item: LibraryItem, source: Source?): Boolean {
        val manga = item.manga
        val value = node.value
        return when (node.field) {
            MangaField.TITLE -> manga.title.containsIgnoringCase(value) ||
                manga.titleOverride?.containsIgnoringCase(value) == true
            MangaField.AUTHOR -> manga.author?.containsIgnoringCase(value) == true ||
                manga.authorOverride?.containsIgnoringCase(value) == true
            MangaField.ARTIST -> manga.artist?.containsIgnoringCase(value) == true ||
                manga.artistOverride?.containsIgnoringCase(value) == true
            MangaField.DESCRIPTION -> manga.description?.containsIgnoringCase(value) == true ||
                manga.descriptionOverride?.containsIgnoringCase(value) == true
            MangaField.NOTES -> manga.notes.containsIgnoringCase(value)
            MangaField.GENRE -> matchesGenre(manga.displayGenre, value)
            MangaField.SOURCE -> source?.name?.containsIgnoringCase(value)
                ?: manga.source.toString().containsIgnoringCase(value)
            MangaField.LANGUAGE -> source?.lang?.equals(value, ignoreCase = true)
                ?: manga.source.toString().equals(value, ignoreCase = true)
            MangaField.SOURCE_ID -> manga.source.toString() == value
        }
    }

    private fun matchesGenre(genres: List<String>?, value: String): Boolean {
        if (genres.isNullOrEmpty()) return false
        val exact = genres.any { it.trim().equals(value, ignoreCase = true) }
        if (exact) return true
        return genres.any { it.containsIgnoringCase(value) }
    }

    private fun matchesComparison(node: ComparisonQueryNode, item: LibraryItem): Boolean {
        val manga = item.manga
        val comparator = node.queryComparator
        return when (node.field) {
            ComparisonField.ID -> node.value.toLongOrNull()?.let { comparator.apply(manga.id, it) }
            ComparisonField.DATE_ADDED -> parseDate(node.value)?.let { comparator.apply(manga.dateAdded, it) }
            ComparisonField.NEXT_UPDATE -> parseDate(node.value)?.let { comparator.apply(manga.nextUpdate, it) }
            ComparisonField.FETCH_INTERVAL ->
                node.value.toIntOrNull()?.let { comparator.apply(manga.fetchInterval, it) }
            ComparisonField.UNREAD -> node.value.toLongOrNull()?.let { comparator.apply(item.unreadCount, it) }
            ComparisonField.READ ->
                node.value.toLongOrNull()?.let { comparator.apply(item.libraryManga.readCount, it) }
            ComparisonField.TOTAL ->
                node.value.toLongOrNull()?.let { comparator.apply(item.libraryManga.totalChapters, it) }
        } ?: false
    }

    /** Epoch millis when every character is a digit, otherwise `yyyy-MM-dd` at the start of day. */
    private fun parseDate(value: String): Long? {
        if (value.isNotEmpty() && value.all { it.isDigit() }) return value.toLongOrNull()
        return runCatching {
            LocalDate.parse(value).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }.getOrNull()
    }
}

/**
 * `contains(other, ignoreCase = true)`, the same answer for every pair of strings, without its cost
 * per character (phase 5 unit SC4, item 5): at 40,000 entries the library filter took 370 to 578 ms
 * of a search, and the standard comparison maps both characters through the case tables at every
 * position it tries. A pair of identical characters, or of two ASCII
 * characters, is decided here; at the first pair that is neither, the position is handed to the
 * platform's own `regionMatches(ignoreCase = true)`, so every non-ASCII case rule stays the
 * platform's.
 */
internal fun String.containsIgnoringCase(other: String): Boolean {
    val last = length - other.length
    if (last < 0) return false
    for (start in 0..last) {
        if (regionMatchesIgnoringCase(start, other)) return true
    }
    return false
}

private fun String.regionMatchesIgnoringCase(start: Int, other: String): Boolean {
    for (index in other.indices) {
        val a = this[start + index]
        val b = other[index]
        if (a == b) continue
        if (a.code >= ASCII_LIMIT || b.code >= ASCII_LIMIT) {
            return regionMatches(start, other, 0, other.length, ignoreCase = true)
        }
        val folded = a.code or ASCII_CASE_BIT
        if (folded != (b.code or ASCII_CASE_BIT) || folded !in 'a'.code..'z'.code) return false
    }
    return true
}

private const val ASCII_LIMIT = 0x80

/** The bit that tells an ASCII letter's lower case from its upper case. */
private const val ASCII_CASE_BIT = 0x20
