package com.shinsaikou.ui.components

import androidx.compose.ui.state.ToggleableState
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

/**
 * The change-category dialog's rows (phase 5 unit CT, item 5): the tree, each child under its
 * parent and one level in, and each row's box read from its own membership alone.
 */
class ChangeCategoryDialogTest {

    private val reading = Category(id = 1, name = "Reading", order = 0, flags = 0)
    private val finished = Category(id = 2, name = "Finished", order = 1, flags = 0)
    private val weekly = Category(id = 3, name = "Weekly", order = 2, flags = 0, parentId = 1)
    private val monthly = Category(id = 4, name = "Monthly", order = 3, flags = 0, parentId = 1)

    @Test
    fun `the rows list each child under its parent, one level in`() {
        categoryChecks(listOf(reading, finished, weekly, monthly), membership = emptyMap(), total = 1)
            .map { it.category.name to it.depth } shouldContainExactly listOf(
                "Reading" to 0,
                "Weekly" to 1,
                "Monthly" to 1,
                "Finished" to 0,
            )
    }

    @Test
    fun `membership stays flat, a parent's box says nothing of its children's`() {
        categoryChecks(listOf(reading, finished, weekly, monthly), membership = mapOf(1L to 2, 4L to 1), total = 2)
            .map { it.category.name to it.state } shouldContainExactly listOf(
                "Reading" to ToggleableState.On,
                "Weekly" to ToggleableState.Off,
                "Monthly" to ToggleableState.Indeterminate,
                "Finished" to ToggleableState.Off,
            )
    }
}
