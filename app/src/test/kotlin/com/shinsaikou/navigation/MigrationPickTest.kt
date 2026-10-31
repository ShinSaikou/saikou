package com.shinsaikou.navigation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The pure half of `SaikouNavigator.pickMigrationTarget` (phase 4 unit MB): where a target picked by
 * hand in manual search or a source's listing goes. The back stack half needs a device.
 */
class MigrationPickTest {

    @Test
    fun `a target that is one of the list's entries is refused`() {
        decidePick(listIds = listOf(1L, 2L, 3L), entryId = 1L, targetId = 3L) shouldBe MigrationPick.IN_BATCH
        decidePick(listIds = listOf(1L, 2L, 3L), entryId = 1L, targetId = 1L) shouldBe MigrationPick.IN_BATCH
    }

    @Test
    fun `with no list below, the entry itself is refused`() {
        decidePick(listIds = null, entryId = 5L, targetId = 5L) shouldBe MigrationPick.IN_BATCH
    }

    @Test
    fun `with no list below, another target migrates the one entry`() {
        decidePick(listIds = null, entryId = 5L, targetId = 6L) shouldBe MigrationPick.NO_LIST
    }

    @Test
    fun `a target outside the list's entries goes back to the list`() {
        decidePick(listIds = listOf(1L, 2L), entryId = 1L, targetId = 9L) shouldBe MigrationPick.RETURNED
    }
}
