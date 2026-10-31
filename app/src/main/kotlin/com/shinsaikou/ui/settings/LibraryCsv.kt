package com.shinsaikou.ui.settings

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.manga.model.Manga

/** Which of upstream's three columns the library list holds, in this order. */
data class LibraryCsvColumns(
    val title: Boolean = true,
    val author: Boolean = true,
    val artist: Boolean = true,
) {
    val any: Boolean get() = title || author || artist
}

/**
 * The library list as CSV, upstream v0.20.4's library export (dropped by Saikou until now): one line
 * per entry, the chosen columns in upstream's order, no header line, CRLF between lines as RFC 4180
 * has it. A field holding a comma, a quote or a line break is quoted, with its quotes doubled; a
 * missing author or artist is an empty field. The title is the one the library shows, the owner's
 * edit where there is one, and the lines go in that title's order, as the library's title sort.
 */
internal fun libraryCsv(entries: List<Manga>, columns: LibraryCsvColumns): String = entries
    .sortedWith { a, b -> a.displayTitle.compareToWithCollator(b.displayTitle) }
    .joinToString(CSV_LINE_BREAK) { manga ->
        buildList {
            if (columns.title) add(manga.displayTitle)
            if (columns.author) add(manga.author)
            if (columns.artist) add(manga.artist)
        }.joinToString(",") { csvField(it) }
    }

private fun csvField(value: String?): String = when {
    value.isNullOrBlank() -> ""
    value.any { it == ',' || it == '"' || it == '\r' || it == '\n' } -> "\"" + value.replace("\"", "\"\"") + "\""
    else -> value
}

private const val CSV_LINE_BREAK = "\r\n"
