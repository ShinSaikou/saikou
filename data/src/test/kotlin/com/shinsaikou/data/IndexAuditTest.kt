package com.shinsaikou.data

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Fails the build when a `.sq` query filters, joins, orders or groups on a column that no index
 * leads with. It is a text-level audit over the `.sq` sources; the `EXPLAIN QUERY PLAN` half lives
 * in [SchemaTest] (05-data sections 6.1 and 6.2).
 *
 * A finding is silenced by adding a line to `src/test/resources/index-audit-allowlist.txt`. An
 * entry that no longer matches a finding fails too, so the list cannot rot.
 */
class IndexAuditTest {

    private val schema = SqSources.schema()

    @Test
    fun `every predicate, join, group and order column is served by an index`() {
        val allowlist = Allowlist.read()
        val findings = SqSources.auditable().flatMap { (file, query, sql) ->
            IndexAudit(schema).findings(file, query, sql)
        }.distinct().sortedBy { it.key }

        val unexpected = findings.filterNot { allowlist.entries.containsKey(it.key) }
        val stale = allowlist.entries.keys - findings.map { it.key }.toSet()

        val problems = unexpected.map { it.message() } +
            stale.sorted().map { "Stale allow-list entry, no finding matches it: $it" }
        problems.shouldBeEmpty()
    }

    @Test
    fun `the allow-list covers exactly the twenty-eight findings no index serves`() {
        val allowlist = Allowlist.read()
        allowlist.entries.keys.size shouldBe 28
        allowlist.entries.keys shouldContain "view/historyView.sq:history:mangas.title"
        allowlist.entries.keys shouldContain "mangas.sq:getDuplicateLibraryManga:mangas.title"
        allowlist.entries.keys shouldContain "chapters.sq:getScanlatorsByMangaId:chapters.scanlator"
        allowlist.entries.keys shouldContain
            "view/updatesView.sq:getRecentUpdatesWithFilters:chapters.bookmark"
        allowlist.entries.keys shouldContain
            "mangas.sq:refreshNetworkMangaIfUninitialized:mangas.initialized"
        allowlist.entries.keys shouldContain "manga_sync.sq:insertIfBoundTo:manga_sync.remote_id"
        allowlist.entries.keys shouldContain "manga_sync.sq:deleteIfBoundTo:manga_sync.remote_id"
        allowlist.entries.keys shouldContain "extension_store.sq:updateIfSigningKey:extension_store.signing_key"
        allowlist.entries.keys shouldContain "library_summary.sq:recomputeLibrarySummary:chapters.scanlator"
        // Schema version 4 dropped idx_chapters_last_page_read, which no plan used.
        allowlist.entries.keys shouldContain "mangas.sq:getReadMangaNotInLibrary:chapters.last_page_read"
        allowlist.entries.keys shouldContain "mangas.sq:deleteNonLibraryManga:chapters.last_page_read"
        allowlist.entries.keys shouldContain "mangas.sq:deleteNonLibraryMangaByIds:chapters.last_page_read"
        allowlist.entries.keys shouldContain "mangas.sq:backupGetLibraryPage:chapters.last_page_read"
        allowlist.entries.keys shouldContain
            "sync_change_log.sq:logChaptersWithoutProgressOnFavorite:chapters.last_page_read"
        allowlist.entries.keys shouldContain "view/updatesView.sq:getRecentUpdatesWithFilters:chapters.last_page_read"
        // It also dropped idx_chapters_read and idx_chapters_date_fetch with the queries they served.
        allowlist.entries.keys shouldContain "chapters.sq:backupGetChaptersByMangaIds:chapters.manga_id"
        allowlist.entries.keys shouldContain "history.sq:backupGetHistoryByMangaIds:chapters.manga_id"
        allowlist.entries.keys shouldContain "mangas.sq:backupGetLibraryPage:chapters.read"
        allowlist.entries.keys shouldContain "mangas.sq:deleteNonLibraryManga:chapters.read"
        allowlist.entries.keys shouldContain "mangas.sq:deleteNonLibraryMangaByIds:chapters.read"
        allowlist.entries.keys shouldContain "mangas.sq:getReadMangaNotInLibrary:chapters.read"
        allowlist.entries.keys shouldContain "mangas.sq:syncCountLibrary:chapters.read"
        allowlist.entries.keys shouldContain "view/updatesView.sq:getRecentUpdatesWithFilters:chapters.read"
        allowlist.entries.keys shouldContain "view/updatesView.sq:getRecentUpdatesWithFilters:chapters.date_fetch"
        allowlist.entries.keys shouldContain "view/updatesView.sq:updatesView:chapters.date_fetch"
        // The folder-name read that replaced three whole-manifest reads, until a schema version indexes it.
        allowlist.entries.keys shouldContain
            "downloads_manifest.sq:getByChapterDirNames:downloads_manifest.chapter_dir_name"
        // Schema version 5's read reconcile, whose number no index serves on purpose.
        allowlist.entries.keys shouldContain "merged_groups.sq:reconcileRead:chapters.chapter_number"
        allowlist.entries.keys shouldContain "merged_groups.sq:reconcileRead:chapters.read"
    }

    @Test
    fun `a predicate no index leads with is reported, with the fix in the message`() {
        val findings = IndexAudit(schema).findings(
            file = "chapters.sq",
            query = "someNewQuery",
            sql = "SELECT * FROM chapters WHERE scanlator = :scanlator",
        )
        findings.map { it.key } shouldBe listOf("chapters.sq:someNewQuery:chapters.scanlator")
        findings.single().message() shouldContain
            """Add "CREATE INDEX idx_chapters_scanlator ON chapters(scanlator);" to chapters.sq"""
    }

    @Test
    fun `every index name is unique, starts with idx_ and no two indexes share a column list`() {
        val problems = mutableListOf<String>()

        val byName = schema.indexes.groupBy { it.name }
        byName.filterValues { it.size > 1 }.keys.sorted()
            .forEach { problems += "Duplicate index name: $it" }
        schema.indexes.map { it.name }.filterNot { it.startsWith("idx_") }.sorted()
            .forEach { problems += "Index name does not start with idx_: $it" }

        schema.indexes.groupBy { it.table to it.columns }
            .filterValues { it.size > 1 }
            .forEach { (key, dupes) ->
                problems += "Indexes ${dupes.map { it.name }.sorted()} share the column list " +
                    "${key.first}(${key.second.joinToString()})"
            }

        problems.shouldBeEmpty()
    }
}

// ---------------------------------------------------------------------------------------------
// The allow-list
// ---------------------------------------------------------------------------------------------

internal class Allowlist(val entries: Map<String, String>) {
    companion object {
        private const val RESOURCE = "index-audit-allowlist.txt"

        fun read(): Allowlist {
            val text = Allowlist::class.java.classLoader!!.getResourceAsStream(RESOURCE)
                ?.bufferedReader()?.readText()
                ?: error("$RESOURCE is missing from the test resources")
            val entries = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { line ->
                    val key = line.substringBefore('#').trim()
                    key to line.substringAfter('#', "").trim()
                }
            return Allowlist(entries)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reading the .sq sources
// ---------------------------------------------------------------------------------------------

internal data class SqStatement(val file: String, val name: String?, val sql: String)

internal object SqSources {

    /** The module's `.sq` root, relative to the test working directory (the module directory). */
    private val root: Path by lazy {
        val configured = System.getProperty("saikou.sqldelight.dir")
        val path = if (configured != null) Paths.get(configured) else Paths.get("src/main/sqldelight")
        require(Files.isDirectory(path)) { "No .sq source directory at ${path.toAbsolutePath()}" }
        path
    }

    /** Every statement in every `.sq` file, in file order, with `import` lines dropped. */
    val statements: List<SqStatement> by lazy {
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "sq" }
                .sorted()
                .toList()
        }.flatMap { path ->
            val relative = root.relativize(path).joinToString("/").removePrefix("tachiyomi/")
                .removePrefix("data/")
            SqSplitter.split(SqSplitter.stripComments(path.readText()))
                .filterNot { it.trimStart().startsWith("import ", ignoreCase = true) }
                .map { chunk ->
                    val match = NAMED.find(chunk)
                    if (match != null) {
                        SqStatement(relative, match.groupValues[1], chunk.removeRange(match.range).trim())
                    } else {
                        SqStatement(relative, null, chunk.trim())
                    }
                }
        }
    }

    private val NAMED = Regex("""^\s*([A-Za-z_]\w*)\s*:\s*""")

    fun schema(): SqlSchemaModel = SqlSchemaModel.from(statements)

    /** Named queries plus the `SELECT` inside each view, which is what the audit walks. */
    fun auditable(): List<Triple<String, String, String>> {
        val named = statements.filter { it.name != null && !it.sql.startsWith("CREATE", true) }
            .map { Triple(it.file, it.name!!, it.sql) }
        val views = statements.mapNotNull { statement ->
            val view = SqlSchemaModel.VIEW.find(statement.sql) ?: return@mapNotNull null
            Triple(statement.file, view.groupValues[1], view.groupValues[2])
        }
        return views + named
    }
}

internal object SqSplitter {

    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\'') inString = false
                    i++
                }
                c == '\'' -> { out.append(c); inString = true; i++ }
                c == '-' && text.startsWith("--", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end == -1) text.length else end + 2
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * Splits on `;` at `BEGIN`/`CASE` depth zero, so a trigger body and the `CASE ... END` inside
     * it stay with the statement that owns them.
     */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        var depth = 0
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                buffer.append(c)
                if (c == '\'') inString = false
                i++
                continue
            }
            when {
                c == '\'' -> { buffer.append(c); inString = true; i++ }
                c == ';' && depth == 0 -> {
                    if (buffer.isNotBlank()) out += buffer.toString().trim()
                    buffer.setLength(0)
                    i++
                }
                else -> {
                    val word = WORD.matchAt(text, i)
                    if (word != null) {
                        when (word.value.uppercase()) {
                            "BEGIN", "CASE" -> depth++
                            "END" -> if (depth > 0) depth--
                        }
                        buffer.append(word.value)
                        i += word.value.length
                    } else {
                        buffer.append(c)
                        i++
                    }
                }
            }
        }
        if (buffer.isNotBlank()) out += buffer.toString().trim()
        return out
    }

    private val WORD = Regex("""[A-Za-z_]\w*""")
}

// ---------------------------------------------------------------------------------------------
// The schema model: tables, indexes and views, read out of the CREATE statements
// ---------------------------------------------------------------------------------------------

internal data class TableModel(
    val name: String,
    val columns: List<String>,
    val primaryKey: String?,
    /** Every UNIQUE column list, plus the primary key as a one-column list. */
    val keyLists: List<List<String>>,
) {
    fun declares(column: String) = columns.any { it.equals(column, ignoreCase = true) }
}

internal data class IndexModel(
    val name: String,
    val table: String,
    val columns: List<String>,
    val where: String?,
)

internal data class ViewModel(
    val name: String,
    val select: String,
    /** Output column to the base `table to column` it projects. */
    val projections: Map<String, Pair<String, String>>,
    /** Base tables pulled in whole by a `T.*` projection. */
    val starTables: List<String>,
)

internal class SqlSchemaModel(
    val tables: Map<String, TableModel>,
    val indexes: List<IndexModel>,
    val views: Map<String, ViewModel>,
) {
    fun table(name: String) = tables[name.lowercase()]
    fun view(name: String) = views[name.lowercase()]

    fun indexLists(table: String): List<List<String>> =
        indexes.filter { it.table.equals(table, ignoreCase = true) }.map { it.columns } +
            (this.table(table)?.keyLists ?: emptyList())

    fun partialIndexPredicates(table: String): List<String> =
        indexes.filter { it.table.equals(table, ignoreCase = true) }.mapNotNull { it.where }

    /** Resolves a view column to the base `table to column` behind it, or null when it is derived. */
    fun resolveViewColumn(view: ViewModel, column: String): Pair<String, String>? {
        view.projections.entries.firstOrNull { it.key.equals(column, ignoreCase = true) }
            ?.let { return it.value }
        return view.starTables.firstOrNull { table(it)?.declares(column) == true }?.let { it to column }
    }

    companion object {
        val VIEW = Regex("""CREATE\s+VIEW\s+(\w+)\s+AS\s+([\s\S]*)""", RegexOption.IGNORE_CASE)
        private val TABLE = Regex("""CREATE\s+TABLE\s+(\w+)\s*\(""", RegexOption.IGNORE_CASE)
        private val INDEX = Regex(
            """CREATE\s+(?:UNIQUE\s+)?INDEX\s+(\w+)\s+ON\s+(\w+)\s*\(([^)]*)\)\s*(?:WHERE\s+([\s\S]*))?""",
            RegexOption.IGNORE_CASE,
        )

        fun from(statements: List<SqStatement>): SqlSchemaModel {
            val tables = mutableMapOf<String, TableModel>()
            val indexes = mutableListOf<IndexModel>()
            val views = mutableMapOf<String, ViewModel>()

            statements.forEach { statement ->
                val sql = statement.sql
                TABLE.find(sql)?.takeIf { it.range.first == 0 }?.let { match ->
                    val body = Sql.balanced(sql, match.range.last)
                    tables[match.groupValues[1].lowercase()] = parseTable(match.groupValues[1], body)
                }
                INDEX.matchEntire(sql.trim())?.let { match ->
                    indexes += IndexModel(
                        name = match.groupValues[1],
                        table = match.groupValues[2],
                        columns = match.groupValues[3].split(',').map { it.trim() },
                        where = match.groupValues[4].trim().ifEmpty { null },
                    )
                }
                VIEW.matchEntire(sql.trim())?.let { match ->
                    views[match.groupValues[1].lowercase()] =
                        parseView(match.groupValues[1], match.groupValues[2])
                }
            }
            return SqlSchemaModel(tables, indexes, views)
        }

        private fun parseTable(name: String, body: String): TableModel {
            val columns = mutableListOf<String>()
            val keyLists = mutableListOf<List<String>>()
            var primaryKey: String? = null

            Sql.splitTopLevel(body, ',').forEach { part ->
                val trimmed = part.trim()
                val head = trimmed.substringBefore(' ').uppercase()
                when {
                    head == "UNIQUE" || trimmed.uppercase().startsWith("PRIMARY KEY") -> {
                        val cols = Sql.balanced(trimmed, trimmed.indexOf('('))
                            .split(',').map { it.trim() }
                        keyLists += cols
                    }
                    head == "FOREIGN" || head == "CHECK" || head == "CONSTRAINT" -> Unit
                    else -> {
                        val column = trimmed.substringBefore(' ')
                        columns += column
                        val definition = trimmed.substringAfter(' ', "").uppercase()
                        if (Regex("""\bPRIMARY\s+KEY\b""").containsMatchIn(definition)) {
                            primaryKey = column
                            keyLists += listOf(column)
                        }
                        if (Regex("""\bUNIQUE\b""").containsMatchIn(definition)) {
                            keyLists += listOf(column)
                        }
                    }
                }
            }
            return TableModel(name, columns, primaryKey, keyLists)
        }

        private fun parseView(name: String, select: String): ViewModel {
            val projectionList = select.substringAfter("SELECT").let { rest ->
                val from = Sql.indexOfKeyword(rest, "FROM")
                if (from == -1) rest else rest.substring(0, from)
            }
            val projections = mutableMapOf<String, Pair<String, String>>()
            val starTables = mutableListOf<String>()
            val aliases = Sql.aliasesOf(select)

            Sql.splitTopLevel(projectionList, ',').forEach { item ->
                val trimmed = item.trim()
                if (trimmed.endsWith(".*")) {
                    aliases[trimmed.removeSuffix(".*").lowercase()]?.let { starTables += it }
                    return@forEach
                }
                val asIndex = Sql.indexOfKeyword(trimmed, "AS")
                val expression = (if (asIndex == -1) trimmed else trimmed.substring(0, asIndex)).trim()
                val output = if (asIndex == -1) {
                    expression.substringAfterLast('.')
                } else {
                    trimmed.substring(asIndex + 2).trim()
                }
                if (!Regex("""^[A-Za-z_][\w.]*$""").matches(expression)) return@forEach
                val qualifier = expression.substringBeforeLast('.', "")
                val column = expression.substringAfterLast('.')
                val table = if (qualifier.isEmpty()) null else aliases[qualifier.lowercase()]
                if (table != null) projections[output] = table to column
            }
            return ViewModel(name, select, projections, starTables)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Small SQL text utilities shared by the audit and by SchemaTest
// ---------------------------------------------------------------------------------------------

internal object Sql {

    /** The text between the parenthesis at [open] and its match. */
    fun balanced(text: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
            i++
        }
        return text.substring(open + 1)
    }

    fun splitTopLevel(text: String, separator: Char): List<String> {
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        var depth = 0
        text.forEach { c ->
            when {
                c == '(' -> { depth++; buffer.append(c) }
                c == ')' -> { depth--; buffer.append(c) }
                c == separator && depth == 0 -> { out += buffer.toString(); buffer.setLength(0) }
                else -> buffer.append(c)
            }
        }
        if (buffer.isNotBlank()) out += buffer.toString()
        return out
    }

    /** Index of [keyword] as a whole word at parenthesis depth zero, or -1. */
    fun indexOfKeyword(text: String, vararg keyword: String): Int {
        var depth = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '(' -> { depth++; i++ }
                c == ')' -> { depth--; i++ }
                c == '\'' -> { i = text.indexOf('\'', i + 1).let { if (it == -1) text.length else it + 1 } }
                c.isLetter() || c == '_' -> {
                    val word = WORD.matchAt(text, i)!!.value
                    if (depth == 0 && keyword.any { it.equals(word, ignoreCase = true) }) return i
                    i += word.length
                }
                else -> i++
            }
        }
        return -1
    }

    /** Alias (and bare table name) to base table or view, from FROM, JOIN, UPDATE, INSERT, DELETE. */
    fun aliasesOf(sql: String): MutableMap<String, String> {
        val out = mutableMapOf<String, String>()
        SOURCES.findAll(sql).forEach { match ->
            val table = match.groupValues[1]
            val alias = match.groupValues[2]
            out[table.lowercase()] = table
            if (alias.isNotEmpty() && alias.uppercase() !in KEYWORDS) out[alias.lowercase()] = table
        }
        SINGLE.findAll(sql).forEach { match ->
            val table = match.groupValues[1]
            out[table.lowercase()] = table
        }
        return out
    }

    /** Aliases introduced by a LEFT JOIN, whose `IS NULL` predicates are anti-joins. */
    fun leftJoinedAliases(sql: String): Set<String> =
        LEFT_JOIN.findAll(sql).flatMap { match ->
            val table = match.groupValues[1]
            val alias = match.groupValues[2]
            listOfNotNull(
                table.lowercase(),
                alias.takeIf { it.isNotEmpty() && it.uppercase() !in KEYWORDS }?.lowercase(),
            )
        }.toSet()

    val WORD = Regex("""[A-Za-z_]\w*""")

    // The alias sits in a lookahead so a match does not swallow the JOIN keyword that starts the
    // next source.
    private val SOURCES = Regex(
        """\b(?:FROM|JOIN)\s+([A-Za-z_]\w*)(?=(?:\s+(?:AS\s+)?([A-Za-z_]\w*))?)""",
        RegexOption.IGNORE_CASE,
    )
    private val SINGLE = Regex(
        """\b(?:UPDATE|INSERT\s+INTO|DELETE\s+FROM)\s+([A-Za-z_]\w*)""",
        RegexOption.IGNORE_CASE,
    )
    private val LEFT_JOIN = Regex(
        """\bLEFT\s+(?:OUTER\s+)?JOIN\s+([A-Za-z_]\w*)(?=(?:\s+(?:AS\s+)?([A-Za-z_]\w*))?)""",
        RegexOption.IGNORE_CASE,
    )

    val KEYWORDS = setOf(
        "ON", "WHERE", "GROUP", "ORDER", "LIMIT", "LEFT", "INNER", "OUTER", "CROSS", "NATURAL",
        "JOIN", "USING", "HAVING", "SELECT", "UNION", "SET", "VALUES", "AND", "OR", "AS", "NOT",
        "IS", "IN", "LIKE", "WHEN", "THEN", "ELSE", "END", "CASE", "NULL", "DISTINCT", "BY",
        "ASC", "DESC", "INTO", "DO", "CONFLICT", "UPDATE", "INSERT", "DELETE", "FROM", "EXISTS",
        "COLLATE", "BETWEEN", "ALL", "TRUE", "FALSE", "CAST", "DEFAULT", "REPLACE", "IGNORE",
    )
}

// ---------------------------------------------------------------------------------------------
// The audit itself
// ---------------------------------------------------------------------------------------------

internal data class Finding(
    val file: String,
    val query: String,
    val table: String,
    val column: String,
    val clause: String,
) {
    val key: String get() = "$file:$query:$table.$column"

    fun message(): String = buildString {
        appendLine("Unindexed predicate: $table.$column in $file:$query ($clause)")
        appendLine("""  Add "CREATE INDEX idx_${table}_$column ON $table($column);" to $table.sq""")
        append("""  or add "$key # <reason>" to index-audit-allowlist.txt""")
    }
}

private sealed interface Operand {
    data class Col(val qualifier: String?, val name: String) : Operand
    data object Param : Operand
    data object Literal : Operand
    data object NullValue : Operand
    data object Expression : Operand
}

private data class Predicate(
    val clause: String,
    val operator: String,
    val left: Operand,
    val right: Operand,
)

internal class IndexAudit(private val schema: SqlSchemaModel) {

    fun findings(file: String, query: String, sql: String): List<Finding> {
        val out = mutableListOf<Finding>()
        analyze(file, query, sql, emptyMap(), out)
        return out
    }

    private fun analyze(
        file: String,
        query: String,
        rawSql: String,
        outerAliases: Map<String, String>,
        out: MutableList<Finding>,
    ) {
        var sql = rawSql
        val subSelects = mutableListOf<String>()
        while (true) {
            val open = indexOfSubSelect(sql) ?: break
            val content = Sql.balanced(sql, open)
            subSelects += content
            sql = sql.substring(0, open) + "(NULL)" + sql.substring(open + content.length + 2)
        }

        val aliases = outerAliases + Sql.aliasesOf(sql)
        val leftJoined = Sql.leftJoinedAliases(sql)
        subSelects.forEach { analyze(file, query, it, aliases, out) }

        val regions = regions(sql)
        val predicates = regions.filter { it.first == "WHERE" || it.first == "ON" }
            .flatMap { (kind, text) -> predicates(kind, text) }

        val equality = mutableMapOf<String, MutableSet<String>>()
        predicates.filter { it.operator == "=" && it.clause == "WHERE" }.forEach { predicate ->
            val column = predicate.left as? Operand.Col ?: return@forEach
            if (predicate.right is Operand.Col) return@forEach
            resolve(column, aliases)?.let { (table, name) ->
                equality.getOrPut(table.lowercase()) { mutableSetOf() } += name.lowercase()
            }
        }

        predicates.forEach { predicate ->
            check(file, query, predicate, aliases, leftJoined, equality, out)
        }

        regions.filter { it.first == "GROUP" }.forEach { (_, text) ->
            columnList(text).forEach { column ->
                val resolved = resolve(column, aliases) ?: return@forEach
                if (!covered(resolved.first, resolved.second, equality)) {
                    out += Finding(file, query, resolved.first, resolved.second, "GROUP BY")
                }
            }
        }

        regions.filter { it.first == "ORDER" }.forEach { (_, text) ->
            val resolved = columnList(text).mapNotNull { resolve(it, aliases) }
            if (resolved.isEmpty()) return@forEach
            val table = resolved.first().first
            if (resolved.all { it.first.equals(table, ignoreCase = true) }) {
                if (!orderCovered(table, resolved.map { it.second }, equality)) {
                    out += Finding(file, query, table, resolved.first().second, "ORDER BY")
                }
            } else {
                resolved.filterNot { covered(it.first, it.second, equality) }
                    .forEach { out += Finding(file, query, it.first, it.second, "ORDER BY") }
            }
        }
    }

    private fun check(
        file: String,
        query: String,
        predicate: Predicate,
        aliases: Map<String, String>,
        leftJoined: Set<String>,
        equality: Map<String, MutableSet<String>>,
        out: MutableList<Finding>,
    ) {
        val left = predicate.left
        val right = predicate.right

        // A predicate comparing two columns is skipped; inside an ON clause only one side needs
        // coverage, which is what a join drives on.
        if (left is Operand.Col && right is Operand.Col) {
            if (predicate.clause != "ON") return
            val sides = listOfNotNull(resolve(left, aliases), resolve(right, aliases))
            if (sides.isEmpty()) return
            if (sides.any { covered(it.first, it.second, equality) }) return
            out += Finding(file, query, sides.first().first, sides.first().second, "JOIN ON")
            return
        }

        val column = (left as? Operand.Col) ?: (right as? Operand.Col) ?: return
        val other = if (left is Operand.Col) right else left
        val resolved = resolve(column, aliases) ?: return

        if (predicate.operator.endsWith("IS") || predicate.operator == "IS NOT") {
            // deleted_at IS [NOT] NULL is the soft-delete guard, and an IS NULL on a LEFT JOINed
            // table is an anti-join; neither is a lookup an index could serve.
            if (other is Operand.NullValue) {
                if (resolved.second.equals("deleted_at", ignoreCase = true)) return
                if (column.qualifier?.lowercase() in leftJoined) return
                if (resolved.first.lowercase() in leftJoined) return
            }
        }

        // A column compared only to the literal that is also a partial index's predicate is served
        // by that index.
        if (other is Operand.Literal && predicate.operator == "=") {
            val text = "${resolved.second} = "
            if (schema.partialIndexPredicates(resolved.first).any { it.startsWith(text, true) }) return
        }

        if (covered(resolved.first, resolved.second, equality)) return
        out += Finding(file, query, resolved.first, resolved.second, predicate.clause)
    }

    /**
     * A column is covered when it is the primary key, or sits in an index or UNIQUE column list
     * whose preceding columns are all pinned by an equality predicate in the same query.
     */
    private fun covered(
        table: String,
        column: String,
        equality: Map<String, MutableSet<String>>,
    ): Boolean {
        val model = schema.table(table) ?: return true
        if (model.primaryKey.equals(column, ignoreCase = true)) return true
        val pinned = equality[table.lowercase()].orEmpty()
        return schema.indexLists(table).any { list ->
            val position = list.indexOfFirst { it.equals(column, ignoreCase = true) }
            position >= 0 && list.take(position).all { it.lowercase() in pinned }
        }
    }

    private fun orderCovered(
        table: String,
        columns: List<String>,
        equality: Map<String, MutableSet<String>>,
    ): Boolean {
        if (schema.table(table) == null) return true
        val pinned = equality[table.lowercase()].orEmpty()
        val lists = schema.indexLists(table)
        val matchesPrefix = lists.any { list ->
            val rest = list.dropWhile { it.lowercase() in pinned }
            rest.size >= columns.size &&
                rest.take(columns.size).map { it.lowercase() } == columns.map { it.lowercase() }
        }
        if (matchesPrefix) return true
        if (columns.size == 1) {
            if (schema.table(table)?.primaryKey.equals(columns.single(), ignoreCase = true)) return true
            return lists.any { it.firstOrNull()?.equals(columns.single(), ignoreCase = true) == true }
        }
        return columns.all { covered(table, it, equality) }
    }

    /** Resolves a column reference to its base `table to column`, or null when it is derived. */
    private fun resolve(column: Operand.Col, aliases: Map<String, String>): Pair<String, String>? {
        val qualifier = column.qualifier?.lowercase()
        if (qualifier != null) {
            val source = aliases[qualifier] ?: return null
            schema.view(source)?.let { return schema.resolveViewColumn(it, column.name) }
            val table = schema.table(source) ?: return null
            return if (table.declares(column.name)) table.name to column.name else null
        }
        val candidates = aliases.values.distinct().mapNotNull { source ->
            schema.view(source)?.let { view ->
                return@mapNotNull schema.resolveViewColumn(view, column.name)
            }
            schema.table(source)?.takeIf { it.declares(column.name) }?.let { it.name to column.name }
        }.distinct()
        require(candidates.size <= 1) {
            "Ambiguous column ${column.name}: it resolves to $candidates"
        }
        return candidates.firstOrNull()
    }

    private fun columnList(text: String): List<Operand.Col> =
        Sql.splitTopLevel(text.trim().removePrefix("BY").removePrefix("by"), ',').mapNotNull { item ->
            val first = Sql.WORD.find(item.trim()) ?: return@mapNotNull null
            val reference = Regex("""[A-Za-z_][\w.]*""").find(item.trim())!!.value
            if (first.value.uppercase() in Sql.KEYWORDS) return@mapNotNull null
            Operand.Col(reference.substringBeforeLast('.', "").ifEmpty { null }, reference.substringAfterLast('.'))
        }

    private fun indexOfSubSelect(sql: String): Int? {
        var i = 0
        while (i < sql.length) {
            when (sql[i]) {
                '\'' -> i = sql.indexOf('\'', i + 1).let { if (it == -1) sql.length else it + 1 }
                '(' -> {
                    val rest = sql.substring(i + 1).trimStart()
                    if (rest.startsWith("SELECT", ignoreCase = true)) return i
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    private fun regions(sql: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, StringBuilder>>()
        var current = "SKIP" to StringBuilder()
        out += current
        fun start(kind: String) {
            current = kind to StringBuilder()
            out += current
        }

        var depth = 0
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    val end = sql.indexOf('\'', i + 1).let { if (it == -1) sql.length else it + 1 }
                    current.second.append(sql, i, end)
                    i = end
                }
                c == '(' -> { depth++; current.second.append(c); i++ }
                c == ')' -> { depth--; current.second.append(c); i++ }
                c.isLetter() || c == '_' -> {
                    val word = Sql.WORD.matchAt(sql, i)!!.value
                    val upper = word.uppercase()
                    val boundary = depth == 0 && upper in REGION_KEYWORDS
                    if (boundary) {
                        when (upper) {
                            "WHERE", "HAVING" -> start("WHERE")
                            "GROUP" -> start("GROUP")
                            "ORDER" -> start("ORDER")
                            "ON" -> {
                                val next = Sql.WORD.find(sql, i + word.length)?.value?.uppercase()
                                start(if (next == "CONFLICT") "SKIP" else "ON")
                            }
                            else -> start("SKIP")
                        }
                    } else {
                        current.second.append(word)
                    }
                    i += word.length
                }
                else -> { current.second.append(c); i++ }
            }
        }
        return out.map { it.first to it.second.toString() }
    }

    private fun predicates(clause: String, text: String): List<Predicate> {
        val tokens = tokenize(text)
        val out = mutableListOf<Predicate>()
        var i = 0
        while (i < tokens.size) {
            val upper = tokens[i].uppercase()
            val next = tokens.getOrNull(i + 1)?.uppercase()
            val operator: String?
            val width: Int
            when {
                upper == "IS" && next == "NOT" -> { operator = "IS NOT"; width = 2 }
                upper == "IS" -> { operator = "IS"; width = 1 }
                upper == "NOT" && (next == "IN" || next == "LIKE") -> { operator = next; width = 2 }
                upper == "IN" || upper == "LIKE" -> { operator = upper; width = 1 }
                tokens[i] in COMPARISONS -> { operator = tokens[i]; width = 1 }
                else -> { operator = null; width = 1 }
            }
            if (operator != null && i > 0 && i + width < tokens.size) {
                out += Predicate(
                    clause = clause,
                    operator = operator,
                    left = operandBefore(tokens, i),
                    right = operand(tokens[i + width]),
                )
            }
            i += width
        }
        return out
    }

    private fun operandBefore(tokens: List<String>, operatorIndex: Int): Operand {
        val previous = tokens[operatorIndex - 1]
        if (previous != ")") return operand(previous)
        // lower(title) LIKE ... : unwrap a one-argument function call so the column is audited.
        var depth = 0
        var i = operatorIndex - 1
        while (i >= 0) {
            if (tokens[i] == ")") depth++
            if (tokens[i] == "(") {
                depth--
                if (depth == 0) break
            }
            i--
        }
        if (i <= 0 || operatorIndex - 1 - i != 2) return Operand.Expression
        val name = tokens[i - 1]
        if (!name.first().isLetter()) return Operand.Expression
        return operand(tokens[i + 1])
    }

    private fun operand(token: String): Operand = when {
        token.startsWith(':') -> Operand.Param
        token.startsWith('\'') -> Operand.Literal
        token.first().isDigit() -> Operand.Literal
        token.first().isLetter() || token.first() == '_' -> {
            val upper = token.uppercase()
            when {
                upper == "NULL" -> Operand.NullValue
                upper == "TRUE" || upper == "FALSE" -> Operand.Literal
                upper in Sql.KEYWORDS -> Operand.Expression
                else -> Operand.Col(
                    token.substringBeforeLast('.', "").ifEmpty { null },
                    token.substringAfterLast('.'),
                )
            }
        }
        else -> Operand.Expression
    }

    private fun tokenize(text: String): List<String> = TOKEN.findAll(text).map { it.value }.toList()

    private companion object {
        val COMPARISONS = setOf("=", "!=", "<>", "<", "<=", ">", ">=")
        val REGION_KEYWORDS = setOf(
            "WHERE", "HAVING", "GROUP", "ORDER", "ON", "LIMIT", "SELECT", "SET", "VALUES", "FROM",
            "JOIN", "LEFT", "INNER", "CROSS", "NATURAL", "RETURNING", "DO", "UNION",
        )
        val TOKEN = Regex("""'[^']*'|:[A-Za-z_]\w*|[A-Za-z_][\w.]*|\d+(?:\.\d+)?|<=|>=|!=|<>|\|\||[^\s]""")
    }
}
