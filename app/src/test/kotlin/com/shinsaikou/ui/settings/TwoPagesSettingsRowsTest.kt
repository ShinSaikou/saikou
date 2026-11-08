package com.shinsaikou.ui.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Experimental two pages side by side's rows in Settings. The sections are composables and this
 * module's JVM tests have no Compose runtime, so the rows are read off the source text, as
 * [AutoScrollSettingsRowsTest] and [SettingsSearchIndexTest] do.
 */
class TwoPagesSettingsRowsTest {

    /** The unit test process runs in the `app/` module directory. */
    private val sections: String = File("src/main/kotlin/com/shinsaikou/ui/settings/SettingsSections.kt").let { file ->
        check(file.isFile) { "Expected ${file.absolutePath}; the working directory is assumed to be app/." }
        file.readText().replace(Regex("""\s+"""), " ")
    }

    @Test
    fun `the switch is a checkbox in the Experimental section on the stored preference`() {
        val experimental = sections.substringAfter("private fun ExperimentalSection(")
        experimental shouldContain
            "CheckboxRow( labelRes = R.string.settings_experimental_two_pages, " +
            "pref = viewModel.readerPreferences.twoPages, " +
            "detailRes = R.string.settings_experimental_two_pages_summary, )"
    }

    @Test
    fun `the layout row sits in the pager group, inside a block shown only while the switch is on`() {
        val paged = sections.substringAfter("private fun ReaderPagedRows(").substringBefore("private fun ReaderLongStripRows(")
        val gate = "ShownWhen(twoPages) { ChoiceRow( labelRes = R.string.pref_two_pages,"
        paged shouldContain "val twoPages by preferences.twoPages.collectAsState()"
        paged shouldContain gate
        val gated = paged.substringAfter(gate).substringBefore("onSelect = preferences.twoPageLayout::set")
        gated shouldContain "choices = TwoPageLayout.entries"
        gated shouldContain "selected = { it == twoPageLayout }"
    }

    @Test
    fun `the layout has one row in Settings`() {
        Regex("""R\.string\.pref_two_pages\b""").findAll(sections).count() shouldBe 1
    }
}
