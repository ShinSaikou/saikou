package com.shinsaikou.domain.library.search

/**
 * The library search for one genre, `genre:"X"`, which [LibrarySearchMatcher] matches as a whole
 * genre before it falls back to a substring. The lexer reads a quoted value up to the next quote of
 * the same kind and has no escape, so a genre holding a double quote is single quoted, and one
 * holding both kinds drops its double quotes.
 */
fun genreSearchQuery(genre: String): String {
    val value = genre.trim()
    return when {
        '"' !in value -> "genre:\"$value\""
        '\'' !in value -> "genre:'$value'"
        else -> "genre:\"${value.replace("\"", "")}\""
    }
}
