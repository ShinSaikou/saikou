package com.shinsaikou.downloads

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.IOException

/**
 * The staging store of the delete after reading (phase 3 unit DR), upstream's `DownloadPendingDeleter`:
 * every write through the committing writer, app-state keys only, and a take that empties what it
 * answers.
 */
class PendingChapterDeleteStoreTest {

    private val values = mutableMapOf<String, Any>()
    private val preferences = object : PreferenceStore by InMemoryPreferenceStore() {
        override fun getAll(): Map<String, *> = values.toMap()
    }
    private val writes = mutableListOf<Pair<String, String?>>()

    private fun store() = PendingChapterDeleteStore(preferences) { key, value ->
        writes += key to value
        if (value == null) values.remove(key) else values[key] = value
    }

    @Test
    fun `a staged chapter is written through the committing writer before add returns`() {
        store().add(mangaId = 1, chapterId = 3)

        writes shouldContainExactly listOf(PendingChapterDeleteStore.key(3) to "1")
    }

    @Test
    fun `take answers every staged chapter with its entry and empties the store`() {
        val store = store()
        store.add(mangaId = 1, chapterId = 3)
        store.add(mangaId = 2, chapterId = 7)

        store.takeAll() shouldBe mapOf(3L to 1L, 7L to 2L)
        store.takeAll() shouldBe emptyMap()
        values shouldBe emptyMap()
    }

    @Test
    fun `staging a chapter twice keeps one entry`() {
        val store = store()
        store.add(mangaId = 1, chapterId = 3)
        store.add(mangaId = 1, chapterId = 3)

        store.pending() shouldBe mapOf(3L to 1L)
    }

    /** A killed process: the next store over the same file sees what the last one staged. */
    @Test
    fun `a staged chapter survives a new store over the same preferences`() {
        store().add(mangaId = 1, chapterId = 3)

        store().takeAll() shouldBe mapOf(3L to 1L)
    }

    @Test
    fun `every key is an app-state key, so no backup carries it and no restore overwrites it`() {
        store().add(mangaId = 1, chapterId = 3)

        Preference.isAppState(values.keys.single()) shouldBe true
    }

    @Test
    fun `other keys in the file are neither answered nor removed`() {
        values["pref_remove_bookmarked"] = true
        values[Preference.appStateKey("tracking_queue_1")] = "12.0"
        store().add(mangaId = 1, chapterId = 3)

        store().takeAll() shouldBe mapOf(3L to 1L)
        values.keys shouldBe setOf("pref_remove_bookmarked", Preference.appStateKey("tracking_queue_1"))
    }

    @Test
    fun `a write the disk refused is thrown to the caller`() {
        val refusing = PendingChapterDeleteStore(preferences) { _, _ -> throw IOException("fixture: commit refused") }

        shouldThrow<IOException> { refusing.add(mangaId = 1, chapterId = 3) }
    }
}
