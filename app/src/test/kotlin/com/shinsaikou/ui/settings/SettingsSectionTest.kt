package com.shinsaikou.ui.settings

import com.shinsaikou.navigation.SettingsSection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The section list and its bodies are keyed by [SettingsSection]: the device suite taps each row by
 * its label and finds each body by its tag, so two sections sharing either would let a dropped or
 * misrouted `when` branch pass as the other section. Units that add a section (G, then T1, then
 * phase 4's SR) land one at a time; this fails the day two of them collide.
 *
 * Which sections the list shows depends on the graph (phase 3 unit T1): Tracking only while a
 * configured tracker is in it. [shownSettingsSections] is what both the screen and
 * `MainActivitySmokeTest` read, so the device test asserts the rows the graph it runs on shows.
 */
class SettingsSectionTest {

    @Test
    fun `every section has a label and a body tag no other section shares`() {
        val labels = SettingsSection.entries.map { it.labelRes }
        val tags = SettingsSection.entries.map { settingsSectionBodyTag(it) }

        labels.toSet().size shouldBe SettingsSection.entries.size
        tags.toSet().size shouldBe SettingsSection.entries.size
    }

    @Test
    fun `every section has a summary and an icon no other section shares`() {
        SettingsSection.entries.map { it.summaryRes }.toSet().size shouldBe SettingsSection.entries.size
        SettingsSection.entries.map { it.icon }.toSet().size shouldBe SettingsSection.entries.size
    }

    @Test
    fun `tracking sits after downloads, sync after data, and security just before advanced`() {
        SettingsSection.entries.map { it.name } shouldContainExactly listOf(
            "APPEARANCE",
            "LIBRARY",
            "READER",
            "DOWNLOADS",
            "TRACKING",
            "BROWSE",
            "DATA",
            "SYNC",
            "SECURITY",
            "ADVANCED",
        )
    }

    @Test
    fun `with no configured tracker the list shows every section but tracking, in order`() {
        shownSettingsSections(trackingOffered = false).map { it.name } shouldContainExactly listOf(
            "APPEARANCE",
            "LIBRARY",
            "READER",
            "DOWNLOADS",
            "BROWSE",
            "DATA",
            "SYNC",
            "SECURITY",
            "ADVANCED",
        )
    }

    @Test
    fun `with a configured tracker the list shows every section, tracking included`() {
        shownSettingsSections(trackingOffered = true) shouldContainExactly SettingsSection.entries
    }
}
