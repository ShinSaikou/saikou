package com.shinsaikou.ui.settings

import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.i18n.R
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.model.Category

/**
 * The "Delete chapters" group phase 3 unit DR adds to the Downloads section: each row's preference
 * is upstream's key with upstream's default, and the after reading row offers upstream's choices in upstream's
 * order with its labels. What the keys do is `ReadChapterCleanupRulesTest`'s.
 */
class DeleteChaptersSettingsTest {

    private val downloadPreferences = DownloadPreferences(InMemoryPreferenceStore())

    @Test
    fun `after manually marked as read is upstream's key and starts off`() {
        downloadPreferences.removeAfterMarkedAsRead.key() shouldBe "pref_remove_after_marked_as_read_key"
        downloadPreferences.removeAfterMarkedAsRead.get() shouldBe false
    }

    @Test
    fun `after reading automatically delete offers disabled and the last five read chapters, disabled by default`() {
        removeAfterReadChoices shouldContainExactly listOf(
            -1 to R.string.disabled,
            0 to R.string.last_read_chapter,
            1 to R.string.second_to_last,
            2 to R.string.third_to_last,
            3 to R.string.fourth_to_last,
            4 to R.string.fifth_to_last,
        )
        downloadPreferences.removeAfterReadSlots.key() shouldBe "remove_after_read_slots"
        downloadPreferences.removeAfterReadSlots.get() shouldBe -1
    }

    @Test
    fun `allow deleting bookmarked chapters is upstream's key and starts off`() {
        downloadPreferences.removeBookmarkedChapters.key() shouldBe "pref_remove_bookmarked"
        downloadPreferences.removeBookmarkedChapters.get() shouldBe false
    }

    @Test
    fun `the excluded categories are upstream's key and start empty`() {
        downloadPreferences.removeExcludeCategories.key() shouldBe "remove_exclude_categories"
        downloadPreferences.removeExcludeCategories.get() shouldBe emptySet()
    }

    @Test
    fun `the excluded categories row names the default category and skips an id no category has`() {
        val categories = listOf(category(0, ""), category(4, "Reading"), category(7, "Dropped"))

        excludedCategories(categories, setOf("7", "0", "12")).map { it.id } shouldContainExactly listOf(0L, 7L)
        excludedCategories(categories, emptySet()) shouldBe emptyList()
    }

    private fun category(id: Long, name: String) = Category(id = id, name = name, order = id, flags = 0)
}
