package com.shinsaikou.tracking

import com.shinsaikou.tracking.DelayedTrackingStore.DelayedTrackingItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.io.IOException

/**
 * The retry store (phase 3 units T1 and TQ): one entry per manga and tracker holding the highest
 * number to push for the service's entry it was recorded against, every write through the
 * committing writer, app-state keys only, a removal that never takes a number raised after the
 * caller read it nor a push for another entry, and T1's keys and number-only values deleted. The
 * backup half of "app state" is `:backup`'s `DelayedTrackingBackupTest`.
 */
class DelayedTrackingStoreTest {

    private val preferences = MapPreferenceStore()
    private val writes = mutableListOf<Pair<String, String?>>()
    private val store = storeOver(preferences)

    private fun storeOver(preferences: MapPreferenceStore) = DelayedTrackingStore(preferences) { key, value ->
        writes += key to value
        preferences.write(key, value)
    }

    @Test
    fun `two pushes for one manga and tracker keep the higher number`() {
        store.add(MANGA_ID, 1, ENTRY, 12.0)
        store.add(MANGA_ID, 1, ENTRY, 10.0)
        store.add(MANGA_ID, 2, ENTRY, 3.0)
        store.add(MANGA_ID, 2, ENTRY, 4.5)

        store.getItems() shouldBe listOf(
            DelayedTrackingItem(MANGA_ID, 1, ENTRY, 12.0),
            DelayedTrackingItem(MANGA_ID, 2, ENTRY, 4.5),
        )
    }

    @Test
    fun `one tracker on two manga is two entries, and so is one manga on two trackers`() {
        store.add(7, 1, ENTRY, 2.0)
        store.add(8, 1, ENTRY, 3.0)
        store.add(7, 2, ENTRY, 4.0)

        store.getItems() shouldBe listOf(
            DelayedTrackingItem(7, 1, ENTRY, 2.0),
            DelayedTrackingItem(7, 2, ENTRY, 4.0),
            DelayedTrackingItem(8, 1, ENTRY, 3.0),
        )
    }

    @Test
    fun `a decimal chapter number is kept as written`() {
        store.add(MANGA_ID, 1, ENTRY, 10.1)
        store.getItems().single().chapterNumber shouldBe 10.1
    }

    @Test
    fun `the queue survives a new store over the same preferences`() {
        store.add(MANGA_ID, 1, ENTRY, 12.0)
        store.add(7, 1, ENTRY, 3.0)

        storeOver(preferences).getItems() shouldBe listOf(
            DelayedTrackingItem(7, 1, ENTRY, 3.0),
            DelayedTrackingItem(MANGA_ID, 1, ENTRY, 12.0),
        )
    }

    @Test
    fun `the key names the manga and the tracker`() {
        store.add(MANGA_ID, 1, ENTRY, 12.0)

        preferences.values.keys shouldBe setOf(Preference.appStateKey("tracking_queue_${MANGA_ID}_1"))
        DelayedTrackingStore.key(MANGA_ID, 1) shouldBe Preference.appStateKey("tracking_queue_${MANGA_ID}_1")
    }

    @Test
    fun `every key is an app-state key, so no backup carries it and no restore overwrites it`() {
        store.add(MANGA_ID, 1, ENTRY, 12.0)
        store.add(7, 2, ENTRY, 1.0)

        preferences.values.keys.forEach { Preference.isAppState(it) shouldBe true }
        preferences.values.keys.forEach { Preference.isPrivate(it) shouldBe false }
    }

    @Test
    fun `every change goes through the writer and a lower number writes nothing`() {
        store.add(MANGA_ID, 1, ENTRY, 12.0)
        store.add(MANGA_ID, 1, ENTRY, 11.0)
        store.remove(MANGA_ID, 1, ENTRY, 12.0)

        writes shouldBe listOf(
            DelayedTrackingStore.key(MANGA_ID, 1) to "$ENTRY:12.0",
            DelayedTrackingStore.key(MANGA_ID, 1) to null,
        )
    }

    @Test
    fun `a write the disk refused is thrown and leaves nothing queued`() {
        val refusing = DelayedTrackingStore(preferences) { _, _ -> throw IOException("fixture: commit refused") }

        shouldThrow<IOException> { refusing.add(MANGA_ID, 1, ENTRY, 12.0) }
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a removal leaves a number raised after the caller read it`() {
        store.add(MANGA_ID, 1, ENTRY, 12.0)
        store.add(MANGA_ID, 1, ENTRY, 13.0)

        store.remove(MANGA_ID, 1, ENTRY, 12.0)
        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, ENTRY, 13.0))

        store.remove(MANGA_ID, 1, ENTRY, 13.0)
        store.getItems().shouldBeEmpty()
    }

    @Test
    fun `a removal touches only its own manga and tracker`() {
        store.add(MANGA_ID, 1, ENTRY, 5.0)
        store.add(MANGA_ID, 2, ENTRY, 5.0)
        store.add(7, 1, ENTRY, 5.0)

        store.remove(MANGA_ID, 1, ENTRY, 5.0)

        store.getItems() shouldBe listOf(
            DelayedTrackingItem(7, 1, ENTRY, 5.0),
            DelayedTrackingItem(MANGA_ID, 2, ENTRY, 5.0),
        )
    }

    @Test
    fun `the value names the service's entry and the number`() {
        store.add(MANGA_ID, 1, ENTRY, 10.1)

        preferences.values[DelayedTrackingStore.key(MANGA_ID, 1)] shouldBe "$ENTRY:10.1"
        DelayedTrackingStore.value(ENTRY, 10.1) shouldBe "$ENTRY:10.1"
    }

    @Test
    fun `a push recorded against another entry of the pair replaces the queued one whatever its number`() {
        store.add(MANGA_ID, 1, ENTRY, 12.0)
        store.add(MANGA_ID, 1, OTHER_ENTRY, 3.0)

        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, OTHER_ENTRY, 3.0))
    }

    @Test
    fun `a removal for another entry of the pair leaves the queued one`() {
        store.add(MANGA_ID, 1, OTHER_ENTRY, 3.0)
        writes.clear()

        store.remove(MANGA_ID, 1, ENTRY, 12.0)

        writes.shouldBeEmpty()
        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, OTHER_ENTRY, 3.0))
    }

    @Test
    fun `a value without a remote id is not an entry, and removeOtherKeys deletes it`() {
        // The number alone is what this unit's first form wrote under the pair key.
        val numberOnly = DelayedTrackingStore.key(MANGA_ID, 1)
        preferences.values[numberOnly] = "3.0"
        preferences.values[DelayedTrackingStore.key(7, 1)] = "x:3.0"
        preferences.values[DelayedTrackingStore.key(8, 1)] = "1:2:3.0"
        store.add(9, 1, ENTRY, 4.0)
        writes.clear()

        store.getItems() shouldBe listOf(DelayedTrackingItem(9, 1, ENTRY, 4.0))
        val removed = store.removeOtherKeys()

        removed shouldContainExactlyInAnyOrder listOf(
            numberOnly,
            DelayedTrackingStore.key(7, 1),
            DelayedTrackingStore.key(8, 1),
        )
        writes shouldContainExactlyInAnyOrder removed.map { it to null }
        preferences.values.keys shouldBe setOf(DelayedTrackingStore.key(9, 1))
    }

    @Test
    fun `keys of other features in the same file are not entries`() {
        preferences.values["pref_mangasync_username_1"] = "owner"
        preferences.values[Preference.appStateKey("tracking_queue_notanumber")] = "12.0"
        store.add(3, 1, ENTRY, 2.0)

        store.getItems() shouldBe listOf(DelayedTrackingItem(3, 1, ENTRY, 2.0))
    }

    @Test
    fun `a key in T1's form by track row id is not an entry, and removeOtherKeys deletes it through the writer`() {
        val oldForm = Preference.appStateKey("tracking_queue_12")
        preferences.values[oldForm] = "7.0"
        preferences.values[Preference.appStateKey("tracking_queue_1_2_3")] = "7.0"
        preferences.values[Preference.appStateKey("tracking_queue_")] = "7.0"
        store.add(MANGA_ID, 1, ENTRY, 4.0)
        writes.clear()

        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, ENTRY, 4.0))
        val removed = store.removeOtherKeys()

        removed shouldContainExactlyInAnyOrder listOf(
            oldForm,
            Preference.appStateKey("tracking_queue_1_2_3"),
            Preference.appStateKey("tracking_queue_"),
        )
        writes shouldContainExactlyInAnyOrder removed.map { it to null }
        preferences.values.keys shouldBe setOf(DelayedTrackingStore.key(MANGA_ID, 1))
        store.getItems() shouldBe listOf(DelayedTrackingItem(MANGA_ID, 1, ENTRY, 4.0))
    }

    @Test
    fun `removeOtherKeys keeps every key in the new form and every key of another feature`() {
        preferences.values["pref_mangasync_username_1"] = "owner"
        preferences.values[Preference.appStateKey("incognito_mode")] = true
        store.add(MANGA_ID, 1, ENTRY, 4.0)
        store.add(7, 2, ENTRY, 1.5)
        val before = preferences.values.toMap()
        writes.clear()

        store.removeOtherKeys().shouldBeEmpty()

        writes.shouldBeEmpty()
        preferences.values shouldBe before
    }

    @Test
    fun `clear removes the pair's entry whatever entry and number it holds, and no other`() {
        store.add(MANGA_ID, 1, OTHER_ENTRY, 40.0)
        store.add(MANGA_ID, 2, ENTRY, 3.0)
        store.add(7, 1, ENTRY, 5.0)

        store.clear(MANGA_ID, 1)

        store.getItems() shouldBe listOf(
            DelayedTrackingItem(7, 1, ENTRY, 5.0),
            DelayedTrackingItem(MANGA_ID, 2, ENTRY, 3.0),
        )
        writes.last() shouldBe (DelayedTrackingStore.key(MANGA_ID, 1) to null)
    }

    @Test
    fun `clear of a pair with nothing queued writes nothing`() {
        store.clear(MANGA_ID, 1)

        writes.shouldBeEmpty()
    }
}

private const val ENTRY = 1001L
private const val OTHER_ENTRY = 2002L
