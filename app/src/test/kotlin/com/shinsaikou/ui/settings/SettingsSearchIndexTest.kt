package com.shinsaikou.ui.settings

import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.SettingsSection
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [settingsSearchIndex] against the sections' source: every row a section draws has an entry under
 * the right section and group, every entry has a row that a [SettingsSearchTarget] wraps, and
 * nothing else. The sections are composables and this module's JVM tests have no Compose runtime,
 * so the rows are read off the source text, as [AdvancedRestartRowsTest] reads the Advanced rows.
 *
 * A row's title is what a row call names: `labelRes = R.string.X` of the four helpers (which wrap
 * themselves), `title =` and `label = stringResource(R.string.X)`, `SettingsChipRow(R.string.X)`,
 * `HeadingItem(R.string.X)` and `SectionLabel(stringResource(R.string.X))`. A row in a
 * `SettingsSearchGroup(R.string.G) { }` block is in group G; a helper drawn inside groups of
 * another function ([places]) is in each of them.
 */
class SettingsSearchIndexTest {

    private data class Key(val section: SettingsSection, val title: String, val group: String?)

    private data class Place(val section: SettingsSection, val group: String? = null)

    /** The unit test process runs in the `app/` module directory, as `AdvancedRestartRowsTest` relies on. */
    private fun source(path: String): String = File("src/main/kotlin/com/shinsaikou/ui/$path").let { file ->
        check(file.isFile) { "Expected ${file.absolutePath}; the working directory is assumed to be app/." }
        file.readText()
    }

    /**
     * Where each function of the section files draws its rows. A function of `SettingsSections.kt`
     * not listed must draw none; in the other files, every function is the file's section's.
     */
    private val places: Map<String, List<Place>> = mapOf(
        "AppearanceSection" to listOf(Place(SettingsSection.APPEARANCE)),
        "LibrarySection" to listOf(Place(SettingsSection.LIBRARY)),
        "ReaderSection" to listOf(Place(SettingsSection.READER)),
        "ReaderDisplayRows" to listOf(Place(SettingsSection.READER)),
        "ReaderEInkRows" to listOf(Place(SettingsSection.READER)),
        "ReaderPagedRows" to listOf(Place(SettingsSection.READER)),
        "ReaderLongStripRows" to listOf(Place(SettingsSection.READER)),
        "ReaderNavigationRows" to listOf(Place(SettingsSection.READER)),
        // Drawn by the Paged and the Long strip groups.
        "TapZoneRows" to listOf(
            Place(SettingsSection.READER, "pager_viewer"),
            Place(SettingsSection.READER, "webtoon_viewer"),
        ),
        "BrowseSection" to listOf(Place(SettingsSection.BROWSE)),
        "DownloadsSection" to listOf(Place(SettingsSection.DOWNLOADS)),
        "DeleteChaptersGroup" to listOf(Place(SettingsSection.DOWNLOADS)),
        "DataSection" to listOf(Place(SettingsSection.DATA)),
        "SecuritySection" to listOf(Place(SettingsSection.SECURITY)),
        "AdvancedSection" to listOf(Place(SettingsSection.ADVANCED)),
        "AppUpdateRows" to listOf(Place(SettingsSection.ADVANCED)),
        "ExperimentalSection" to listOf(Place(SettingsSection.EXPERIMENTAL)),
    )

    /** The other files a section is drawn from, and the functions of each that draw it. */
    private val otherFiles: List<Triple<String, SettingsSection, Set<String>?>> = listOf(
        Triple("settings/SyncSection.kt", SettingsSection.SYNC, null),
        Triple("settings/TrackingSection.kt", SettingsSection.TRACKING, null),
        Triple("settings/LibraryExportRow.kt", SettingsSection.DATA, null),
        Triple("storage/StorageLocationPicker.kt", SettingsSection.DATA, setOf("StorageLocationRows")),
    )

    /** Row titles a section draws that the index leaves out, each with why. */
    private val leftOut: Map<String, String> = mapOf(
        "pref_flash_duration" to "shown only while Flash on page change is on",
        "pref_flash_page_interval" to "shown only while Flash on page change is on",
        "pref_flash_with" to "shown only while Flash on page change is on",
        "pref_vertical_navigator_height" to "shown only while a reading mode has the navigator",
        "lock_when_idle" to "shown only while the app lock is on",
        "pref_adopt_summary" to "shown only once an adoption has finished",
        "pref_storage_move_left" to "shown only while a move left files behind",
        "sync_drive_account" to "the signed-in account, a fact rather than a setting",
        "sync_server" to "the signed-in server, a fact rather than a setting",
        "sync_webdav_user" to "the signed-in user name, a fact rather than a setting",
        "sync_battery_exemption" to "shown only until the exemption is granted",
        "sync_reports_earlier" to "shown only once there are earlier reports",
        "tracking_compare_title_format" to "one row per signed-in tracker, named after it",
        "library_export_column_title" to "a column of the export's dialog",
        "library_export_column_author" to "a column of the export's dialog",
        "library_export_column_artist" to "a column of the export's dialog",
    )

    private val stringNames: Map<Int, String> =
        R.string::class.java.fields.associate { field -> field.getInt(null) to field.name }

    private fun name(id: Int): String = stringNames[id] ?: error("No string resource has id $id")

    private val indexKeys: List<Key> = settingsSearchIndex.map { entry ->
        Key(entry.section, name(entry.titleRes), entry.groupRes?.let(::name))
    }

    @Test
    fun `every row a section draws is in the index under its section and group, and nothing else is`() {
        val drawn = mutableSetOf<Key>()
        val titlesBySection = mutableMapOf<SettingsSection, MutableSet<String>>()
        val targetsBySection = mutableMapOf<SettingsSection, MutableSet<String>>()
        fun read(chunk: Chunk, chunkPlaces: List<Place>) {
            val titles = rowTitles(chunk.text)
            val targets = targets(chunk.text)
            chunkPlaces.forEach { place ->
                titlesBySection.getOrPut(place.section) { mutableSetOf() } += titles
                targets.forEach { (title, offset) ->
                    targetsBySection.getOrPut(place.section) { mutableSetOf() } += title
                    drawn += Key(place.section, title, chunk.groupAt(offset) ?: place.group)
                }
            }
        }

        functions(source("settings/SettingsSections.kt")).forEach { chunk ->
            val chunkPlaces = places[chunk.name]
            if (chunkPlaces == null) {
                withClue("${chunk.name} draws rows; say in `places` where") {
                    (rowTitles(chunk.text) + targets(chunk.text).map { it.first }).shouldBeEmpty()
                }
            } else {
                read(chunk, chunkPlaces)
            }
        }
        otherFiles.forEach { (path, section, only) ->
            functions(source(path)).filter { only == null || it.name in only }.forEach { read(it, listOf(Place(section))) }
        }

        withClue("titles drawn without a SettingsSearchTarget, by section") {
            titlesBySection.mapValues { (section, titles) ->
                titles - targetsBySection[section].orEmpty() - leftOut.keys
            }.filterValues { it.isNotEmpty() } shouldBe emptyMap()
        }
        withClue("left out, yet in the index") {
            indexKeys.filter { it.title in leftOut }.shouldBeEmpty()
        }
        val expected = drawn.filterNot { it.title in leftOut }.toSet()
        withClue("drawn, but not in the index") { (expected - indexKeys.toSet()).shouldBeEmpty() }
        withClue("in the index, but not drawn there") { (indexKeys.toSet() - expected).shouldBeEmpty() }
    }

    @Test
    fun `the four helpers that take a labelRes wrap themselves in a target`() {
        val chunks = functions(source("settings/SettingsSections.kt")).associateBy { it.name }
        listOf("CheckboxRow", "ChoiceRow", "ChoiceSetRow", "IntervalRow").forEach { helper ->
            withClue(helper) { chunks.getValue(helper).text shouldContain "SettingsSearchTarget(labelRes)" }
        }
    }

    @Test
    fun `no two entries lead to the same row`() {
        indexKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.shouldBeEmpty()
    }

    @Test
    fun `the index runs through the sections in the list's order`() {
        settingsSearchIndex.map { it.section }.distinct() shouldBe SettingsSection.entries
    }

    /** A summary is drawn as it is written, so a line built from arguments would match its placeholders. */
    @Test
    fun `no title or summary the index names takes arguments`() {
        val strings = File("../i18n/src/main/res/values").listFiles { file -> file.extension == "xml" }.orEmpty()
            .flatMap { file -> STRING.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }
            .toMap()
        settingsSearchIndex.flatMap { listOfNotNull(it.titleRes, it.groupRes, it.summaryRes) }.map(::name).distinct()
            .forEach { name -> withClue(name) { strings.getValue(name) shouldNotContain "%" } }
    }

    /** One top-level function of a source file: its name and its text up to the next one. */
    private class Chunk(val name: String, val text: String) {
        private val groups: List<Triple<IntRange, String, Int>> = GROUP.findAll(text).map { match ->
            val open = text.indexOf('{', match.range.last)
            Triple(open..closingBrace(text, open), match.groupValues[1], match.range.first)
        }.toList()

        /** The innermost group block [offset] is inside; a group's own heading call is not inside it. */
        fun groupAt(offset: Int): String? =
            groups.filter { (body, _, _) -> offset in body }.minByOrNull { (body, _, _) -> body.last - body.first }?.second
    }

    private fun functions(source: String): List<Chunk> {
        val starts = FUNCTION.findAll(source).toList()
        return starts.mapIndexed { i, match ->
            val end = starts.getOrNull(i + 1)?.range?.first ?: source.length
            Chunk(match.groupValues[1], source.substring(match.range.first, end))
        }
    }

    private fun rowTitles(text: String): Set<String> = TITLE_PATTERNS.flatMap { pattern ->
        pattern.findAll(text).map { it.groupValues.last { group -> group.isNotEmpty() } }
    }.toSet()

    /** Each target's title and where it is, the helpers' `labelRes` rows among them. */
    private fun targets(text: String): List<Pair<String, Int>> = TARGET_PATTERNS.flatMap { pattern ->
        pattern.findAll(text).map { it.groupValues[1] to it.range.first }
    }

    private companion object {
        val FUNCTION = Regex("""(?m)^(?:private |internal )?fun (?:<[^>]+> )?(?:[\w.]+\.)?(\w+)\(""")
        val GROUP = Regex("""SettingsSearchGroup\(R\.string\.(\w+)\)""")
        val STRING = Regex("""<string name="(\w+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

        val TARGET_PATTERNS = listOf(
            Regex("""SettingsSearchTarget\(R\.string\.(\w+)\)"""),
            Regex("""SettingsSearchHeading\(R\.string\.(\w+)\)"""),
            GROUP,
            Regex("""\b(?:CheckboxRow|ChoiceRow|ChoiceSetRow|IntervalRow)\(\s*labelRes = R\.string\.(\w+)"""),
        )

        val TITLE_PATTERNS = listOf(
            Regex("""labelRes = R\.string\.(\w+)"""),
            // A title that changes while the row works ("Sync now", then "Syncing"): the resting one.
            Regex("""title = stringResource\((?:if \([^)]*\) R\.string\.\w+ else R\.string\.(\w+)|R\.string\.(\w+))"""),
            Regex("""label = stringResource\(R\.string\.(\w+)"""),
            Regex("""SettingsChipRow\(R\.string\.(\w+)"""),
            Regex("""HeadingItem\(R\.string\.(\w+)"""),
            Regex("""SectionLabel\(stringResource\(R\.string\.(\w+)"""),
        )

        /** The brace that closes the one at [open], past strings, characters and comments. */
        fun closingBrace(text: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < text.length) {
                when {
                    text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    text.startsWith("/*", i) -> i = text.indexOf("*/", i) + 1
                    text.startsWith("\"\"\"", i) -> i = text.indexOf("\"\"\"", i + 3) + 2
                    text[i] == '"' -> {
                        i++
                        while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    }
                    text[i] == '\'' -> {
                        i++
                        while (text[i] != '\'') i += if (text[i] == '\\') 2 else 1
                    }
                    text[i] == '{' -> depth++
                    text[i] == '}' -> if (--depth == 0) return i
                }
                i++
            }
            error("No brace closes the one at $open")
        }
    }
}
