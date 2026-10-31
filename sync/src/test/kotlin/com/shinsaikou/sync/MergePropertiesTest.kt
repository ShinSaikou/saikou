package com.shinsaikou.sync

import com.shinsaikou.sync.MergeFixtures.manga
import com.shinsaikou.sync.merge.BookmarkNoteRecord
import com.shinsaikou.sync.merge.TrackRecord
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The algebra every merge function owes the sync layout (Q3, Q6): the same stored value whichever
 * side is local (commutativity), whatever grouping a compaction folds three rows in
 * (associativity), and no change from merging a result with either input again (idempotence). The
 * report laws ride along: a row merged with itself reports nothing, `changed` is exactly "differs
 * from the local row", a named field is one where the two sides disagreed, and every field the
 * merge moved off a disagreeing local value is named.
 *
 * No field is masked. The two cases at the end are the former counterexamples of the memo and the
 * manga tombstone, which Q6 first worded as rules that were not associative; both now converge.
 */
class MergePropertiesTest {

    private val engine = MergeEngine()
    private val config = PropTestConfig(iterations = ITERATIONS)

    private suspend fun <T> checkLaws(
        arb: Arb<T>,
        fields: List<MergeEngine.Field<T>>,
        merge: (T, T) -> MergeEngine.Merged<T>,
    ) {
        checkAll(config, arb, arb, arb) { a, b, c ->
            val ab = merge(a, b).value
            withClue("commutative for\n a=$a\n b=$b") { merge(b, a).value shouldBe ab }
            withClue("idempotent for\n a=$a\n b=$b") {
                merge(a, a).value shouldBe a
                merge(ab, a).value shouldBe ab
                merge(ab, b).value shouldBe ab
                merge(a, ab).value shouldBe ab
            }
            withClue("associative for\n a=$a\n b=$b\n c=$c") {
                merge(ab, c).value shouldBe merge(a, merge(b, c).value).value
            }
            withClue("reports for\n a=$a\n b=$b") {
                merge(a, a).changed shouldBe false
                merge(a, a).fieldsFromRemote shouldBe emptySet()
                val merged = merge(a, b)
                merged.changed shouldBe (merged.value != a)
                for (name in merged.fieldsFromRemote) {
                    val field = fields.single { it.name == name }
                    // Plain equals, as the engine compares: 0.0 and -0.0 are two values here.
                    (field.get(a) == field.get(b)) shouldBe false
                    (field.get(merged.value) == field.get(a)) shouldBe false
                }
                for (field in fields) {
                    if (field.get(merged.value) != field.get(a) && field.get(b) != field.get(a)) {
                        merged.fieldsFromRemote shouldContain field.name
                    }
                }
            }
        }
    }

    /**
     * A child collection's function and the greater clock its caller stores beside the value. The
     * function reports its one [name] exactly when the merged value differs from the local one.
     */
    private suspend fun <V> checkCollectionLaws(
        values: Arb<V>,
        name: String,
        merge: (V, V, Long?, Long?) -> MergeEngine.Merged<V>,
    ) {
        val arb = Arb.bind(values, MergeArbs.clock) { value, clock -> value to clock }
        checkAll(config, arb, arb, arb) { a, b, c ->
            fun join(x: Pair<V, Long?>, y: Pair<V, Long?>) =
                merge(x.first, y.first, x.second, y.second).value to maxClock(x.second, y.second)
            val ab = join(a, b)
            withClue("commutative for a=$a b=$b") { join(b, a) shouldBe ab }
            withClue("idempotent for a=$a b=$b") {
                join(a, a) shouldBe a
                join(ab, a) shouldBe ab
                join(ab, b) shouldBe ab
            }
            withClue("associative for a=$a b=$b c=$c") { join(ab, c) shouldBe join(a, join(b, c)) }
            withClue("reports for a=$a b=$b") {
                val merged = merge(a.first, b.first, a.second, b.second)
                merged.changed shouldBe (merged.value != a.first)
                merged.fieldsFromRemote shouldBe (if (merged.changed) setOf(name) else emptySet())
                if (merged.fieldsFromRemote.isNotEmpty()) a.first shouldNotBe b.first
                merge(a.first, a.first, a.second, a.second).fieldsFromRemote shouldBe emptySet()
            }
        }
    }

    @Test
    fun `mergeManga obeys the laws`() = runTest {
        checkLaws(MergeArbs.manga, MergeEngine.MANGA_VALUES) { a, b -> engine.mergeManga(a, b) }
    }

    @Test
    fun `mergeChapter obeys the laws`() = runTest {
        checkLaws(MergeArbs.chapter, MergeEngine.CHAPTER_VALUES) { a, b -> engine.mergeChapter(a, b) }
    }

    @Test
    fun `mergeCategory obeys the laws`() = runTest {
        checkLaws(MergeArbs.category, MergeEngine.CATEGORY_VALUES) { a, b -> engine.mergeCategory(a, b) }
    }

    @Test
    fun `mergeHistory obeys the laws`() = runTest {
        checkLaws(MergeArbs.history, MergeEngine.HISTORY_VALUES) { a, b -> engine.mergeHistory(a, b) }
    }

    @Test
    fun `mergeTrack obeys the laws`() = runTest {
        checkLaws(MergeArbs.track, MergeEngine.TRACK_VALUES) { a, b -> engine.mergeTrack(a, b) }
    }

    @Test
    fun `mergeTracks obeys the laws`() = runTest {
        checkCollectionLaws<Map<Long, TrackRecord>>(MergeArbs.tracks, "tracks", engine::mergeTracks)
    }

    @Test
    fun `mergeMangaCategories obeys the laws`() = runTest {
        checkCollectionLaws(MergeArbs.keys, "categories", engine::mergeMangaCategories)
    }

    @Test
    fun `mergeExcludedScanlators obeys the laws`() = runTest {
        checkCollectionLaws(MergeArbs.keys, "excludedScanlators", engine::mergeExcludedScanlators)
    }

    @Test
    fun `mergeScanlatorPriority obeys the laws`() = runTest {
        checkCollectionLaws(MergeArbs.ranking, "scanlatorPriority", engine::mergeScanlatorPriority)
    }

    @Test
    fun `mergeBookmarkNotes obeys the laws`() = runTest {
        checkCollectionLaws<Map<Long, BookmarkNoteRecord>>(MergeArbs.notes, "bookmarkNotes", engine::mergeBookmarkNotes)
    }

    /**
     * Under the empty-memo clause Q6 first had, `(a + b) + c` kept a's memo and `a + (b + c)` kept
     * c's: a's non-empty memo was stored under b's higher version and then tied c.
     */
    @Test
    fun `the former memo counterexample keeps one memo in both groupings`() {
        val a = manga.copy(version = 1L, memo = JsonObject(mapOf("k" to JsonPrimitive("b"))))
        val b = manga.copy(version = 2L)
        val c = manga.copy(version = 2L, memo = JsonObject(mapOf("k" to JsonPrimitive("a"))))

        val left = engine.mergeManga(engine.mergeManga(a, b).value, c).value
        val right = engine.mergeManga(a, engine.mergeManga(b, c).value).value

        left shouldBe right
        left.memo shouldBe engine.mergeManga(b, c).value.memo
        left.memo shouldNotBe a.memo
    }

    /**
     * Under the shipped rule (a newer favourite clock revives the entry), `(revived + deleted) +
     * removedLater` left the row live and `revived + (deleted + removedLater)` left it deleted at 3.
     * The re-add now stamps restoredAt, and the favourite clock no longer reads the tombstone.
     */
    @Test
    fun `the former manga tombstone counterexample converges in both groupings`() {
        val revived = manga.copy(favorite = true, favoriteModifiedAt = 5L, restoredAt = 5L)
        val deleted = manga.copy(favorite = false, favoriteModifiedAt = 3L, deletedAt = 3L)
        val removedLater = manga.copy(favorite = false, favoriteModifiedAt = 7L)

        val left = engine.mergeManga(engine.mergeManga(revived, deleted).value, removedLater).value
        val right = engine.mergeManga(revived, engine.mergeManga(deleted, removedLater).value).value

        left shouldBe right
        left.deletedAt shouldBe null
        left.restoredAt shouldBe 5L
        left.favorite shouldBe false
    }

    private fun maxClock(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private companion object {
        const val ITERATIONS = 2_000
    }
}
