package com.shinsaikou.ui.migration

import android.content.Context
import com.shinsaikou.domain.download.DownloadedChapters
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.migration.MigrateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import tachiyomi.domain.migration.models.MigrationFlag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Upstream's migrate dialog's view model (phase 4 unit MA): which flags apply to the old entry, the
 * stored key as the ticked set, the key written before the migration and the ticked set handed to
 * it, and a migration's end as one outcome the dialog acts on.
 */
class MigrateDialogViewModelTest {

    @TempDir
    lateinit var filesDir: File

    private val testDispatcher = StandardTestDispatcher()
    private val sourcePreferences = SourcePreferences(InMemoryPreferenceStore())
    private val downloaded = mutableMapOf<Long, Set<Long>>()
    private val downloadedChapters = mockk<DownloadedChapters> {
        every { subscribeByMangaId(any()) } answers { flowOf(downloaded[firstArg()].orEmpty()) }
    }
    private val migrateManga = mockk<MigrateManga>()

    private val current = Manga.create().copy(id = 1L, source = 10L, title = "Same title", favorite = true)
    private val target = Manga.create().copy(id = 2L, source = 20L, title = "Same title")

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { migrateManga(any(), any(), any(), any()) } returns MigrateManga.Result(emptyList(), carried = true)
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun coverCache(): CoverCache {
        val context = mockk<Context> { every { filesDir } returns this@MigrateDialogViewModelTest.filesDir }
        return CoverCache(context)
    }

    private fun viewModel(coverCache: CoverCache = coverCache()) = MigrateDialogViewModel(
        sourcePreferences = sourcePreferences,
        coverCache = coverCache,
        downloadedChapters = downloadedChapters,
        migrateManga = migrateManga,
        dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher),
    )

    @Test
    fun `an entry with no cover, notes or downloads is offered Chapters and Categories only`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.init(current, target)
        advanceUntilIdle()

        viewModel.state.value.applicableFlags shouldBe listOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY)
    }

    @Test
    fun `a custom cover, notes and a downloaded chapter each add their flag, in upstream's order`() = runTest(testDispatcher) {
        val coverCache = coverCache()
        coverCache.getCustomCoverFile(current.id).apply { parentFile?.mkdirs() }.writeBytes(byteArrayOf(1))
        downloaded[current.id] = setOf(100L)
        val viewModel = viewModel(coverCache)

        viewModel.init(current.copy(notes = "kept"), target)
        advanceUntilIdle()

        viewModel.state.value.applicableFlags shouldBe MigrationFlag.entries
    }

    @Test
    fun `a downloaded chapter offers Downloaded chapters after Custom cover and before Delete downloaded`() =
        runTest(testDispatcher) {
            downloaded[current.id] = setOf(100L)
            val viewModel = viewModel()

            viewModel.init(current, target)
            advanceUntilIdle()

            viewModel.state.value.applicableFlags shouldBe listOf(
                MigrationFlag.CHAPTER,
                MigrationFlag.CATEGORY,
                MigrationFlag.DOWNLOADS,
                MigrationFlag.REMOVE_DOWNLOAD,
            )
            MigrationFlag.entries.indexOf(MigrationFlag.DOWNLOADS) shouldBe
                MigrationFlag.entries.indexOf(MigrationFlag.CUSTOM_COVER) + 1
            MigrationFlag.DOWNLOADS.labelRes() shouldBe R.string.downloaded_chapters
        }

    @Test
    fun `Downloaded chapters is ticked while nothing is stored, and a stored key without it leaves it unticked`() =
        runTest(testDispatcher) {
            val fresh = viewModel()
            fresh.init(current, target)
            advanceUntilIdle()
            (MigrationFlag.DOWNLOADS in fresh.state.value.selectedFlags) shouldBe true

            sourcePreferences.migrationFlags.set(setOf(MigrationFlag.CHAPTER, MigrationFlag.REMOVE_DOWNLOAD))
            val stored = viewModel()
            stored.init(current, target)
            advanceUntilIdle()
            (MigrationFlag.DOWNLOADS in stored.state.value.selectedFlags) shouldBe false
        }

    @Test
    fun `a Migrate that left downloads behind asks first, and Delete them deletes them before the migration ends`() =
        runTest(testDispatcher) {
            coEvery { migrateManga(any(), any(), any(), any()) } returns MigrateManga.Result(listOf(7L, 8L), carried = true)
            coEvery { migrateManga.deleteLeftBehindDownloads(any()) } returns Unit
            val viewModel = viewModel()
            viewModel.init(current, target)
            advanceUntilIdle()

            viewModel.migrate(replace = true)
            advanceUntilIdle()

            viewModel.state.value.leftBehind shouldBe listOf(7L, 8L)
            viewModel.state.value.outcome.shouldBeNull()

            viewModel.resolveLeftBehind(delete = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { migrateManga.deleteLeftBehindDownloads(listOf(7L, 8L)) }
            viewModel.state.value.leftBehind shouldBe emptyList()
            viewModel.state.value.outcome shouldBe MigrateDialogViewModel.Outcome.Migrated
        }

    @Test
    fun `a Migrate that carried nothing asks about the downloads it kept, with the same Delete them`() =
        runTest(testDispatcher) {
            sourcePreferences.migrationFlags.set(setOf(MigrationFlag.CHAPTER))
            coEvery { migrateManga(any(), any(), any(), any()) } returns MigrateManga.Result(listOf(7L, 8L), carried = false)
            coEvery { migrateManga.deleteLeftBehindDownloads(any()) } returns Unit
            val viewModel = viewModel()
            viewModel.init(current, target)
            advanceUntilIdle()

            viewModel.migrate(replace = true)
            advanceUntilIdle()

            viewModel.state.value.leftBehind shouldBe listOf(7L, 8L)
            viewModel.state.value.leftBehindCarried shouldBe false
            viewModel.state.value.outcome.shouldBeNull()

            viewModel.resolveLeftBehind(delete = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { migrateManga.deleteLeftBehindDownloads(listOf(7L, 8L)) }
            viewModel.state.value.outcome shouldBe MigrateDialogViewModel.Outcome.Migrated
        }

    @Test
    fun `a Migrate whose moves left chapters behind asks with the carried text`() = runTest(testDispatcher) {
        coEvery { migrateManga(any(), any(), any(), any()) } returns MigrateManga.Result(listOf(7L), carried = true)
        val viewModel = viewModel()
        viewModel.init(current, target)
        advanceUntilIdle()

        viewModel.migrate(replace = true)
        advanceUntilIdle()

        viewModel.state.value.leftBehindCarried shouldBe true
    }

    @Test
    fun `Keep them ends the migration and deletes nothing`() = runTest(testDispatcher) {
        coEvery { migrateManga(any(), any(), any(), any()) } returns MigrateManga.Result(listOf(7L), carried = true)
        val viewModel = viewModel()
        viewModel.init(current, target)
        advanceUntilIdle()
        viewModel.migrate(replace = true)
        advanceUntilIdle()

        viewModel.resolveLeftBehind(delete = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { migrateManga.deleteLeftBehindDownloads(any()) }
        viewModel.state.value.outcome shouldBe MigrateDialogViewModel.Outcome.Migrated
    }

    @Test
    fun `blank notes and another entry's downloads do not apply`() = runTest(testDispatcher) {
        downloaded[target.id] = setOf(200L)
        val viewModel = viewModel()

        viewModel.init(current.copy(notes = "  "), target)
        advanceUntilIdle()

        viewModel.state.value.applicableFlags shouldBe listOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY)
    }

    @Test
    fun `the ticked set is the stored key, a flag that does not apply included`() = runTest(testDispatcher) {
        sourcePreferences.migrationFlags.set(setOf(MigrationFlag.CATEGORY, MigrationFlag.REMOVE_DOWNLOAD))
        val viewModel = viewModel()

        viewModel.init(current, target)
        advanceUntilIdle()

        viewModel.state.value.selectedFlags shouldBe setOf(MigrationFlag.CATEGORY, MigrationFlag.REMOVE_DOWNLOAD)
    }

    @Test
    fun `Migrate stores the ticked set and migrates with it, replacing`() = runTest(testDispatcher) {
        sourcePreferences.migrationFlags.set(MigrationFlag.entries.toSet())
        coEvery { migrateManga(any(), any(), any(), any()) } answers {
            // The key is written before the migration starts, as upstream's dialog writes it.
            sourcePreferences.migrationFlags.get() shouldBe MigrationFlag.entries.toSet() - MigrationFlag.CHAPTER
            MigrateManga.Result(emptyList(), carried = true)
        }
        val viewModel = viewModel()
        viewModel.init(current, target)
        advanceUntilIdle()

        viewModel.toggleSelection(MigrationFlag.CHAPTER)
        viewModel.migrate(replace = true)
        advanceUntilIdle()

        val ticked = MigrationFlag.entries.toSet() - MigrationFlag.CHAPTER
        coVerify(exactly = 1) { migrateManga(current, target, true, ticked) }
        sourcePreferences.migrationFlags.get() shouldBe ticked
        viewModel.state.value.outcome shouldBe MigrateDialogViewModel.Outcome.Migrated
    }

    @Test
    fun `Copy migrates without replacing`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.init(current, target)
        advanceUntilIdle()

        viewModel.migrate(replace = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { migrateManga(current, target, false, MigrationFlag.entries.toSet()) }
    }

    @Test
    fun `a second tap while the migration runs starts nothing`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { migrateManga(any(), any(), any(), any()) } coAnswers {
            gate.await()
            MigrateManga.Result(emptyList(), carried = true)
        }
        val viewModel = viewModel()
        viewModel.init(current, target)
        advanceUntilIdle()

        viewModel.migrate(replace = true)
        advanceUntilIdle()
        viewModel.state.value.isMigrating shouldBe true
        viewModel.migrate(replace = false)
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { migrateManga(any(), any(), any(), any()) }
        viewModel.state.value.isMigrating shouldBe false
    }

    @Test
    fun `a migration that throws ends as a failure carrying the cause`() = runTest(testDispatcher) {
        val failure = IOException("offline")
        coEvery { migrateManga(any(), any(), any(), any()) } throws failure
        val viewModel = viewModel()
        viewModel.init(current, target)
        advanceUntilIdle()

        viewModel.migrate(replace = true)
        advanceUntilIdle()

        val outcome = viewModel.state.value.outcome
        outcome.shouldBeInstanceOf<MigrateDialogViewModel.Outcome.Failed>()
        outcome.error shouldBe failure
        viewModel.state.value.isMigrating shouldBe false
    }

    @Test
    fun `an outcome acted on leaves nothing for the next opening`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        viewModel.init(current, target)
        advanceUntilIdle()
        viewModel.migrate(replace = true)
        advanceUntilIdle()

        viewModel.consumeOutcome()

        viewModel.state.value.outcome.shouldBeNull()
        viewModel.state.value.current.shouldBeNull()
        viewModel.init(current, target)
        advanceUntilIdle()
        viewModel.state.value.applicableFlags!! shouldContainExactly listOf(MigrationFlag.CHAPTER, MigrationFlag.CATEGORY)
    }
}
