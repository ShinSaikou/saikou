package com.shinsaikou.migration

import com.shinsaikou.domain.merged.model.MergedGroup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import java.io.File

/**
 * A migration of a merged title's member (R163): the target takes the old entry's place in the
 * group, its position, its Check for updates and the main entry's role included, in the migration's
 * transaction and with the group's sync line, so the title outlives the entry. A copy leaves the
 * group alone. Over the real schema and triggers, as [MigrateMangaTest].
 */
class MigrateMangaMergedTest {

    @TempDir
    lateinit var tempDir: File

    private val environments = mutableListOf<MigrationTestEnvironment>()

    @AfterEach
    fun tearDown() {
        environments.forEach { it.close() }
    }

    private fun env() = MigrationTestEnvironment(tempDir).also { environments += it }

    private class Seeded(val old: Manga, val other: Manga, val target: Manga)

    /** Two favourites on [OLD_SOURCE] and [OTHER_SOURCE], and a target with a chapter outside the library on [NEW_SOURCE]. */
    private suspend fun MigrationTestEnvironment.seed(): Seeded {
        val old = manga(OLD_SOURCE, "/old", "Title") { it.copy(favorite = true) }
        val other = manga(OTHER_SOURCE, "/other", "Title") { it.copy(favorite = true) }
        val target = manga(NEW_SOURCE, "/new", "Title")
        val chapters = chapters(target.id, MigrationTestEnvironment.chapter("/new/1", 1.0))
        source(NEW_SOURCE, chapters.map(MigrationTestEnvironment.Companion::info))
        return Seeded(old, other, target)
    }

    private suspend fun MigrationTestEnvironment.group(mangaId: Long): MergedGroup? = mergedGroups.getLiveGroup(mangaId)

    private fun MigrationTestEnvironment.groupLogLines(): Long =
        long("SELECT count(*) FROM sync_change_log WHERE entity_type = 'merged_group'") ?: 0L

    @Test
    fun `migrating the main entry makes the target the main entry of the title, with the same members in the same order`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.old.id, mangaIds = listOf(seeded.old.id, seeded.other.id))
        val groupId = env.group(seeded.old.id).shouldNotBeNull().id

        env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())

        val group = env.group(seeded.target.id).shouldNotBeNull()
        // A group is keyed by its main entry, so the title moves to the target's row and the old row is emptied.
        env.long("SELECT count(*) FROM merged_members WHERE group_id = $groupId") shouldBe 0L
        group.mainMangaId shouldBe seeded.target.id
        group.members.map { it.mangaId } shouldContainExactly listOf(seeded.target.id, seeded.other.id)
        env.group(seeded.old.id).shouldBeNull()
    }

    @Test
    fun `migrating a plain member keeps the main entry and gives the target the member's place`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.other.id, mangaIds = listOf(seeded.other.id, seeded.old.id))

        env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())

        val group = env.group(seeded.other.id).shouldNotBeNull()
        group.mainMangaId shouldBe seeded.other.id
        group.members.map { it.mangaId } shouldContainExactly listOf(seeded.other.id, seeded.target.id)
        env.group(seeded.old.id).shouldBeNull()
    }

    @Test
    fun `the target keeps the old member's Check for updates`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.other.id, mangaIds = listOf(seeded.other.id, seeded.old.id))
        env.mergedGroups.setCheckUpdates(seeded.old.id, false)

        env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())

        env.group(seeded.other.id).shouldNotBeNull().members.associate { it.mangaId to it.checkUpdates } shouldBe
            mapOf(seeded.other.id to true, seeded.target.id to false)
    }

    @Test
    fun `the re-point is logged for sync, so the other devices follow`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.old.id, mangaIds = listOf(seeded.old.id, seeded.other.id))
        env.execute("DELETE FROM sync_change_log")

        env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())

        (env.groupLogLines() > 0) shouldBe true
    }

    @Test
    fun `an entry in no group writes no group row and no sync line`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.execute("DELETE FROM sync_change_log")

        env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())

        env.long("SELECT count(*) FROM merged_groups") shouldBe 0L
        env.long("SELECT count(*) FROM merged_members") shouldBe 0L
        env.groupLogLines() shouldBe 0L
    }

    @Test
    fun `a copy leaves the group as it was, and the target outside it`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.old.id, mangaIds = listOf(seeded.old.id, seeded.other.id))
        env.execute("DELETE FROM sync_change_log")

        env.migrateManga(seeded.old, seeded.target, replace = false, flags = emptySet())

        val group = env.group(seeded.old.id).shouldNotBeNull()
        group.mainMangaId shouldBe seeded.old.id
        group.members.map { it.mangaId } shouldContainExactly listOf(seeded.old.id, seeded.other.id)
        env.group(seeded.target.id).shouldBeNull()
        env.groupLogLines() shouldBe 0L
    }

    @Test
    fun `a failed favourite write rolls the group back with the rest`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.old.id, mangaIds = listOf(seeded.old.id, seeded.other.id))
        val before = env.group(seeded.old.id)
        env.mangaWriteAnswersFalse = true

        shouldThrow<IllegalStateException> {
            env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())
        }

        env.group(seeded.old.id) shouldBe before
        env.long("SELECT count(*) FROM merged_members WHERE member_url = '/new'") shouldBe 0L
    }

    @Test
    fun `the group's read marks are reconciled for the target after the commit`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.old.id, mangaIds = listOf(seeded.old.id, seeded.other.id))

        env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())

        coVerify(exactly = 1) { env.reconcileMergedRead.await(seeded.target.id) }
    }

    @Test
    fun `a reconcile that fails is logged and the migration stays done`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mergedGroups.merge(mainId = seeded.old.id, mangaIds = listOf(seeded.old.id, seeded.other.id))
        coEvery { env.reconcileMergedRead.await(any<Long>()) } throws IllegalStateException("db busy")

        env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())

        env.group(seeded.target.id).shouldNotBeNull().mainMangaId shouldBe seeded.target.id
        env.mangaRepository.getMangaById(seeded.target.id).favorite shouldBe true
    }

    @Test
    fun `a migration that rolled back reconciles nothing`() = runTest {
        val env = env()
        val seeded = env.seed()
        env.mangaWriteAnswersFalse = true

        shouldThrow<IllegalStateException> {
            env.migrateManga(seeded.old, seeded.target, replace = true, flags = emptySet())
        }

        coVerify(exactly = 0) { env.reconcileMergedRead.await(any<Long>()) }
    }

    private companion object {
        const val OLD_SOURCE = 11L
        const val OTHER_SOURCE = 12L
        const val NEW_SOURCE = 13L
    }
}
