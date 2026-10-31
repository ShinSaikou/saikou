package com.shinsaikou.sync

import com.shinsaikou.sync.MergeFixtures.note
import com.shinsaikou.sync.MergeFixtures.track
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What each child collection's function reports on its own, called directly: `changed` and its one
 * name in `fieldsFromRemote` when the remote side moved the stored value, and nothing when the local
 * value stands. `mergeManga` and `mergeChapter` recompute their report from the whole record, so
 * only these cases pin the collection functions' own [MergeEngine.Merged].
 */
class CollectionMergeReportTest {

    private val engine = MergeEngine()

    private fun <T> MergeEngine.Merged<T>.shouldReport(value: T, name: String) {
        this.value shouldBe value
        changed shouldBe true
        fieldsFromRemote shouldBe setOf(name)
    }

    private fun <T> MergeEngine.Merged<T>.shouldKeep(value: T) {
        this.value shouldBe value
        changed shouldBe false
        fieldsFromRemote shouldBe emptySet()
    }

    @Test
    fun `manga categories report a newer remote set and a union that pulls in a key, and nothing else`() {
        val local = setOf("reading")
        val remote = setOf("finished")

        engine.mergeMangaCategories(local, remote, 1L, 2L).shouldReport(remote, "categories")
        engine.mergeMangaCategories(local, remote, 2L, 1L).shouldKeep(local)
        engine.mergeMangaCategories(local, remote, 5L, 5L).shouldReport(setOf("reading", "finished"), "categories")
        engine.mergeMangaCategories(setOf("a", "b"), setOf("a"), 5L, 5L).shouldKeep(setOf("a", "b"))
    }

    @Test
    fun `excluded scanlators report a newer remote un-exclusion and a union that pulls in a group, and nothing else`() {
        val local = setOf("group")
        val remote = emptySet<String>()

        engine.mergeExcludedScanlators(local, remote, 1L, 2L).shouldReport(remote, "excludedScanlators")
        engine.mergeExcludedScanlators(local, remote, 2L, 1L).shouldKeep(local)
        engine.mergeExcludedScanlators(remote, local, 5L, 5L).shouldReport(local, "excludedScanlators")
        engine.mergeExcludedScanlators(local, remote, 5L, 5L).shouldKeep(local)
    }

    @Test
    fun `scanlator priority reports a newer remote ranking and a tie the remote ranking wins, and nothing else`() {
        val local = mapOf("a" to 0L, "b" to 1L)
        val remote = mapOf("b" to 0L, "a" to 1L)

        engine.mergeScanlatorPriority(local, remote, 1L, 2L).shouldReport(remote, "scanlatorPriority")
        engine.mergeScanlatorPriority(local, remote, 2L, 1L).shouldKeep(local)

        // A tie keeps one ranking whichever side is local, so exactly one of the two orders reports it.
        val kept = engine.mergeScanlatorPriority(local, remote, 5L, 5L).value
        val (winner, loser) = if (kept == local) local to remote else remote to local
        engine.mergeScanlatorPriority(loser, winner, 5L, 5L).shouldReport(winner, "scanlatorPriority")
        engine.mergeScanlatorPriority(winner, loser, 5L, 5L).shouldKeep(winner)
    }

    @Test
    fun `tracks report a newer remote set and remote progress on a shared binding, and nothing else`() {
        val local = mapOf(1L to track.copy(lastChapterRead = 3.0))
        val further = mapOf(1L to track.copy(lastChapterRead = 8.0))

        engine.mergeTracks(local, emptyMap(), 1L, 2L).shouldReport(emptyMap(), "tracks")
        engine.mergeTracks(local, emptyMap(), 2L, 1L).shouldKeep(local)
        engine.mergeTracks(local, further, 5L, 5L).shouldReport(further, "tracks")
        engine.mergeTracks(further, local, 5L, 5L).shouldKeep(further)
    }

    @Test
    fun `bookmark notes report a newer remote set and a longer remote note on a shared page, and nothing else`() {
        val local = mapOf(1L to note("short"))
        val longer = mapOf(1L to note("much longer"))

        engine.mergeBookmarkNotes(local, emptyMap(), 1L, 2L).shouldReport(emptyMap(), "bookmarkNotes")
        engine.mergeBookmarkNotes(local, emptyMap(), 2L, 1L).shouldKeep(local)
        engine.mergeBookmarkNotes(local, longer, 5L, 5L).shouldReport(longer, "bookmarkNotes")
        engine.mergeBookmarkNotes(longer, local, 5L, 5L).shouldKeep(longer)
    }
}
