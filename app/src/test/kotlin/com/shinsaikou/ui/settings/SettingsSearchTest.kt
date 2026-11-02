package com.shinsaikou.ui.settings

import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.Route
import com.shinsaikou.navigation.SettingsSection
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The settings search's matching and the key a result travels as: [matchSettings] over resolved
 * items, and [SettingsSearchKey] through the saved state and [Route.Settings].
 */
class SettingsSearchTest {

    private fun item(title: String, summary: String? = null) = SettingsSearchItem(
        entry = SettingsSearchEntry(SettingsSection.APPEARANCE, title.hashCode()),
        title = title,
        summary = summary,
        breadcrumb = "Appearance",
    )

    private val amoled = item("Pure black in dark mode", "Use a black dark surface")
    private val theme = item("Theme")
    private val volume = item("Volume keys", "Turn pages with the volume buttons")
    private val wifi = item("Only over Wi-Fi", "Download only on an unmetered network")
    private val items = listOf(amoled, theme, volume, wifi)

    @Test
    fun `a blank query finds nothing`() {
        matchSettings(items, "").shouldBeEmpty()
        matchSettings(items, "   ").shouldBeEmpty()
    }

    @Test
    fun `a title is matched case aside`() {
        matchSettings(items, "THEME") shouldContainExactly listOf(theme)
    }

    @Test
    fun `every word has to be there, in any order`() {
        matchSettings(items, "dark black") shouldContainExactly listOf(amoled)
        matchSettings(items, "dark  volume").shouldBeEmpty()
    }

    @Test
    fun `the line under a row is matched too, after the titles that match`() {
        matchSettings(items, "dark") shouldContainExactly listOf(amoled)
        matchSettings(items, "only") shouldContainExactly listOf(wifi)
        matchSettings(items, "turn") shouldContainExactly listOf(volume)
        matchSettings(items, "network wi-fi") shouldContainExactly listOf(wifi)
    }

    @Test
    fun `title matches come before summary matches, each in the index's order`() {
        val black = item("Black", "a dark one")
        val darkTitled = item("Dark", "something")
        matchSettings(listOf(black, darkTitled), "dark") shouldContainExactly listOf(darkTitled, black)
    }

    @Test
    fun `a key survives its saved ids, with and without a group`() {
        val grouped = SettingsSearchKey(SettingsSection.READER, R.string.pref_crop_borders, R.string.pager_viewer)
        val plain = SettingsSearchKey(SettingsSection.DATA, R.string.pref_backup_interval, null)

        SettingsSearchKey.of(grouped.ids) shouldBe grouped
        SettingsSearchKey.of(plain.ids) shouldBe plain
        SettingsSearchKey.of(intArrayOf()).shouldBeNull()
    }

    @Test
    fun `a key survives the route it opens`() {
        val key = SettingsSearchKey(SettingsSection.READER, R.string.pref_crop_borders, R.string.webtoon_viewer)

        key.route shouldBe Route.Settings(SettingsSection.READER, R.string.pref_crop_borders, R.string.webtoon_viewer)
        key.route.highlightKey shouldBe key
    }

    @Test
    fun `a settings route without a row, or without a section, asks for none`() {
        Route.Settings().highlightKey.shouldBeNull()
        Route.Settings(SettingsSection.READER).highlightKey.shouldBeNull()
        Route.Settings(section = null, highlightTitle = R.string.pref_crop_borders).highlightKey.shouldBeNull()
    }
}
