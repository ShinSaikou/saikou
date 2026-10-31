package com.shinsaikou.sync

import com.shinsaikou.sync.MergeFixtures.category
import com.shinsaikou.sync.MergeFixtures.history
import com.shinsaikou.sync.merge.CategoryRecord
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The category and history rows of Q6's table, each written from the row's wording. */
class CategoryAndHistoryMergeRulesTest {

    private val engine = MergeEngine()

    /** Merges both ways round and checks the two stored rows agree. */
    private fun both(local: CategoryRecord, remote: CategoryRecord) =
        engine.mergeCategory(local, remote).also { engine.mergeCategory(remote, local).value shouldBe it.value }

    // Row: Category name, flags, order, parent (the parent as its Q7 key) | modified_at | newer clock.

    @Test
    fun `the newer category clock takes the name, flags, order and parent`() {
        val renamed = category.copy(name = "Finished", flags = 4L, order = 3L, parentKey = "parent", modifiedAt = 200L)
        val stale = category.copy(modifiedAt = 100L)

        val merged = both(stale, renamed)

        merged.value shouldBe renamed
        merged.fieldsFromRemote shouldBe setOf("name", "flags", "order", "parentKey")
    }

    @Test
    fun `an older category clock changes nothing`() {
        val mine = category.copy(name = "Mine", modifiedAt = 300L)

        val merged = engine.mergeCategory(mine, category.copy(modifiedAt = 100L))

        merged.value.name shouldBe "Mine"
        merged.changed shouldBe false
        merged.fieldsFromRemote shouldBe emptySet()
    }

    @Test
    fun `equal category clocks keep one side's values whichever side is local`() {
        val a = category.copy(name = "A", order = 1L, modifiedAt = 5L)
        val b = category.copy(name = "B", order = 0L, modifiedAt = 5L)

        val merged = both(a, b).value
        setOf(a.name to a.order, b.name to b.order).contains(merged.name to merged.order) shouldBe true
    }

    // Row: Category tombstone | deleted_at; restored_at | as chapters.

    @Test
    fun `re-creating a deleted category after the tombstone keeps it`() {
        val merged = both(category.copy(deletedAt = 100L), category.copy(restoredAt = 150L)).value

        merged.deletedAt shouldBe null
        merged.restoredAt shouldBe 150L
    }

    @Test
    fun `a tombstone newer than every restore deletes the category`() {
        val merged = both(category.copy(deletedAt = 300L, restoredAt = 100L), category.copy(restoredAt = 200L))

        merged.value.deletedAt shouldBe 300L
    }

    @Test
    fun `two different categories are never merged`() {
        shouldThrow<IllegalArgumentException> { engine.mergeCategory(category, category.copy(syncKey = "other")) }
    }

    // Row: History | none | max of last read and of time read; a cleared history does not propagate.

    @Test
    fun `history keeps the later read time and the longer duration, never the sum`() {
        val local = history.copy(readAt = 100L, readDuration = 900L)
        val remote = history.copy(readAt = 200L, readDuration = 400L)

        val merged = engine.mergeHistory(local, remote)

        merged.value shouldBe history.copy(readAt = 200L, readDuration = 900L)
        merged.fieldsFromRemote shouldBe setOf("readAt")
        engine.mergeHistory(remote, local).value shouldBe merged.value
    }

    @Test
    fun `a cleared history takes the other side's back, which is the row's known limit`() {
        engine.mergeHistory(history, history.copy(readAt = 200L, readDuration = 5L)).value.readAt shouldBe 200L
    }

    @Test
    fun `a history row the local side has never seen is taken whole`() {
        val remote = history.copy(readAt = 50L, readDuration = 10L)

        val merged = engine.mergeHistory(null, remote)

        merged.value shouldBe remote
        merged.changed shouldBe true
        merged.fieldsFromRemote shouldBe setOf("readAt", "readDuration")
    }
}
