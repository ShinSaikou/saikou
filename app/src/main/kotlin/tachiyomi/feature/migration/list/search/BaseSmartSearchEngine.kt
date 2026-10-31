// Vendored from upstream v0.20.4: app/src/main/java/tachiyomi/feature/migration/list/search/BaseSmartSearchEngine.kt (modified: yes) every candidate is scored, and titles are compared lower-cased without brackets in both modes, so a lone or differently cased hit is no longer taken blindly
package tachiyomi.feature.migration.list.search

import com.aallam.similarity.NormalizedLevenshtein
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import java.util.Locale

typealias SearchAction<T> = suspend (String) -> List<T>

/**
 * Saikou: the queries' dispatcher is a constructor parameter ([dispatcher], the graph's default
 * dispatcher in the product) where upstream names `Dispatchers.Default`.
 *
 * The matching differs from upstream's in three places. Every candidate is scored: upstream takes a
 * lone result of a lone query at 1.0 whatever its title, so a source that answers a title it does
 * not carry with one unrelated entry was a match. The regular search compares the titles lower-cased,
 * with text in brackets and repeated spaces removed ([normalizeTitle]), where upstream compares them
 * raw, so the same title in capitals scored under the threshold. And the deep search keeps a title's non-Latin
 * letters when stripping the rest would leave five characters or fewer once trimmed, where upstream
 * measured the untrimmed spaces and sent a wholly non-Latin title as an empty query.
 */
abstract class BaseSmartSearchEngine<T>(
    private val extraSearchParams: String? = null,
    private val eligibleThreshold: Double = MIN_ELIGIBLE_THRESHOLD,
    private val dispatcher: CoroutineDispatcher,
) {
    private val normalizedLevenshtein = NormalizedLevenshtein()

    protected abstract fun getTitle(result: T): String

    protected suspend fun regularSearch(searchAction: SearchAction<T>, title: String): T? {
        val normalizedTitle = normalizeTitle(title)
        return baseSearch(searchAction, listOf(title)) {
            normalizedLevenshtein.similarity(normalizedTitle, normalizeTitle(getTitle(it)))
        }
    }

    protected suspend fun deepSearch(searchAction: SearchAction<T>, title: String): T? {
        val cleanedTitle = cleanDeepSearchTitle(title)

        val queries = getDeepSearchQueries(cleanedTitle)

        return baseSearch(searchAction, queries) {
            val cleanedMangaTitle = cleanDeepSearchTitle(getTitle(it))
            normalizedLevenshtein.similarity(cleanedTitle, cleanedMangaTitle)
        }
    }

    private suspend fun baseSearch(
        searchAction: SearchAction<T>,
        queries: List<String>,
        calculateDistance: (T) -> Double,
    ): T? {
        val eligibleManga = supervisorScope {
            queries.map { query ->
                async(dispatcher) {
                    val builtQuery = if (!extraSearchParams.isNullOrBlank()) {
                        "$query $extraSearchParams"
                    } else {
                        query
                    }

                    searchAction(builtQuery)
                        .map { SearchEntry(it, calculateDistance(it)) }
                        .filter { it.distance >= eligibleThreshold }
                }
            }
                .flatMap { it.await() }
        }

        return eligibleManga.maxByOrNull { it.distance }?.entry
    }

    /**
     * The regular search's comparison form: lower-cased, text in brackets removed as the deep
     * search removes it, and runs of spaces made one. A title that is nothing but brackets keeps
     * its lower-cased text, so it still compares as something.
     */
    private fun normalizeTitle(title: String): String {
        val lowerCased = title.lowercase()
        var cleaned = removeTextInBrackets(lowerCased, true)
        if (cleaned.length <= 5) cleaned = removeTextInBrackets(lowerCased, false)
        return cleaned.replace(consecutiveSpacesRegex, " ").trim().ifEmpty { lowerCased.trim() }
    }

    private fun cleanDeepSearchTitle(title: String): String {
        val preTitle = title.lowercase(Locale.getDefault())

        // Remove text in brackets
        var cleanedTitle = removeTextInBrackets(preTitle, true)
        if (cleanedTitle.length <= 5) { // Title is suspiciously short, try parsing it backwards
            cleanedTitle = removeTextInBrackets(preTitle, false)
        }

        // Strip chapter reference RU
        cleanedTitle = cleanedTitle.replace(chapterRefCyrillicRegexp, " ").trim()

        // Strip non-special characters
        val cleanedTitleEng = cleanedTitle.replace(titleRegex, " ")

        // Do not strip foreign language letters if cleanedTitle is too short
        cleanedTitle = if (cleanedTitleEng.trim().length <= 5) {
            cleanedTitle.replace(titleCyrillicRegex, " ")
        } else {
            cleanedTitleEng
        }

        // Strip splitters and consecutive spaces
        cleanedTitle = cleanedTitle.trim().replace(" - ", " ").replace(consecutiveSpacesRegex, " ").trim()

        return cleanedTitle
    }

    private fun removeTextInBrackets(text: String, readForward: Boolean): String {
        val openingChars = if (readForward) "([<{" else ")]}>"
        val closingChars = if (readForward) ")]}>" else "([<{"
        var depth = 0

        return buildString {
            for (char in (if (readForward) text else text.reversed())) {
                when (char) {
                    in openingChars -> depth++
                    in closingChars -> if (depth > 0) depth-- // Avoid depth going negative on mismatched closing
                    else -> if (depth == 0) {
                        // If reading backward, the result is reversed, so prepend
                        if (readForward) append(char) else insert(0, char)
                    }
                }
            }
        }
    }

    private fun getDeepSearchQueries(cleanedTitle: String): List<String> {
        val splitCleanedTitle = cleanedTitle.split(" ")
        val splitSortedByLargest = splitCleanedTitle.sortedByDescending { it.length }

        if (splitCleanedTitle.isEmpty()) {
            return emptyList()
        }

        // Search cleaned title
        // Search two largest words
        // Search largest word
        // Search first two words
        // Search first word
        val searchQueries = listOf(
            listOf(cleanedTitle),
            splitSortedByLargest.take(2),
            splitSortedByLargest.take(1),
            splitCleanedTitle.take(2),
            splitCleanedTitle.take(1),
        )

        return searchQueries
            .map { it.joinToString(" ").trim() }
            .distinct()
    }

    companion object {
        const val MIN_ELIGIBLE_THRESHOLD = 0.4

        private val titleRegex = Regex("[^a-zA-Z0-9- ]")
        private val titleCyrillicRegex = Regex("[^\\p{L}0-9- ]")
        private val consecutiveSpacesRegex = Regex(" +")
        private val chapterRefCyrillicRegexp = Regex("""((- часть|- глава) \d*)""")
    }
}

data class SearchEntry<T>(val entry: T, val distance: Double)
