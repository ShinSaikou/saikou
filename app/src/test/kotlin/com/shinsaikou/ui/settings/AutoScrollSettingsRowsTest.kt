package com.shinsaikou.ui.settings

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Experimental auto-scroll's rows in Settings. The sections are composables and this module's JVM
 * tests have no Compose runtime, so the rows are read off the source text, as [SettingsSearchIndexTest]
 * and [AdvancedRestartRowsTest] do.
 */
class AutoScrollSettingsRowsTest {

    /** The unit test process runs in the `app/` module directory. */
    private val sections: String = File("src/main/kotlin/com/shinsaikou/ui/settings/SettingsSections.kt").let { file ->
        check(file.isFile) { "Expected ${file.absolutePath}; the working directory is assumed to be app/." }
        file.readText().replace(Regex("""\s+"""), " ")
    }

    @Test
    fun `the speed slider sits inside a block shown only while the switch is on`() {
        val gate = "ShownWhen(autoScroll) { SliderItem( value = autoScrollSpeed,"
        sections shouldContain gate
        val gated = sections.substringAfter(gate).substringBefore("onChange = preferences.stripAutoScrollSpeed::set")
        gated shouldContain "label = stringResource(R.string.pref_auto_scroll_speed)"
        gated shouldContain "valueRange = ReaderSettingsOptions.AUTO_SCROLL_SPEED_MIN..ReaderSettingsOptions.AUTO_SCROLL_SPEED_MAX"
    }

    @Test
    fun `the gate reads the stored switch, and the slider is the only row of the speed`() {
        sections shouldContain "val autoScroll by preferences.stripAutoScroll.collectAsState()"
        Regex("""R\.string\.pref_auto_scroll_speed""").findAll(sections).count() shouldBe 1
    }

    @Test
    fun `the switch is a checkbox in the Experimental section on the stored preference`() {
        val experimental = sections.substringAfter("private fun ExperimentalSection(")
        experimental shouldContain
            "CheckboxRow( labelRes = R.string.settings_experimental_auto_scroll, " +
            "pref = viewModel.readerPreferences.stripAutoScroll, " +
            "detailRes = R.string.settings_experimental_auto_scroll_summary, )"
    }
}
