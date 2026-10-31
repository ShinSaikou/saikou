package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportError
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A backup names an entry's categories by order value, and sync can leave two categories with one
 * sort value. The export writes each category's place instead, so a file it writes never ties; a
 * file an older build wrote with a tie restores each of its entries into every tied category rather
 * than emptying one category into another.
 */
class CategoryOrderTieTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `tied categories export to their own places and restore without merging`() = runTest {
        val exported = BackupTestEnvironment(File(tempDir, "from")).use { env ->
            val library = library(
                categories = listOf(BackupCategory("Other", order = 0L, id = 1L)),
                entries = mapOf("/a" to emptyList(), "/b" to emptyList(), "/c" to listOf(0L)),
            )
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(library)), RestoreOptions()) {}
            env.insert("categories", mapOf("name" to "Tie A", "sort" to 5L, "flags" to 0L, "sync_key" to "tie a"))
            env.insert("categories", mapOf("name" to "Tie B", "sort" to 5L, "flags" to 0L, "sync_key" to "tie b"))
            val categories = env.categoryRepository.getAll().associate { it.name to it.id }
            val manga = env.mangaRepository.getFavorites().associate { it.url to it.id }
            env.insert("mangas_categories", mapOf("manga_id" to manga["/a"], "category_id" to categories["Tie A"]))
            env.insert("mangas_categories", mapOf("manga_id" to manga["/b"], "category_id" to categories["Tie B"]))
            env.export(BackupOptions())
        }

        exported.backupCategories.map { it.name to it.order } shouldContainExactly
            listOf("Other" to 0L, "Tie A" to 1L, "Tie B" to 2L)

        BackupTestEnvironment(File(tempDir, "to")).use { env ->
            env.importer.import(env.writeImportFile(SyntheticBackupFixture.tachibk(exported)), RestoreOptions()) {}

            categoriesOf(env, "/a") shouldContainExactly listOf("Tie A")
            categoriesOf(env, "/b") shouldContainExactly listOf("Tie B")
            categoriesOf(env, "/c") shouldContainExactly listOf("Other")
        }
    }

    @Test
    fun `an older file's shared order restores into every category that has it and says so`() = runTest {
        val backup = library(
            categories = listOf(
                BackupCategory("Other", order = 0L, id = 1L),
                BackupCategory("Tie A", order = 1L, id = 2L),
                BackupCategory("Tie B", order = 1L, id = 3L),
            ),
            entries = mapOf("/a" to listOf(1L), "/c" to listOf(0L)),
        )

        BackupTestEnvironment(tempDir).use { env ->
            val file = env.writeImportFile(SyntheticBackupFixture.tachibk(backup))
            val report = env.importer.import(file, RestoreOptions()) {}

            categoriesOf(env, "/a") shouldContainExactlyInAnyOrder listOf("Tie A", "Tie B")
            categoriesOf(env, "/c") shouldContainExactly listOf("Other")
            // The restore gives each category a place of its own here.
            val orders = env.categoryRepository.getAll().filterNot { it.isSystemCategory }.map { it.order }
            orders.distinct() shouldHaveSize 3
            val notes = report.errors.filter { it.kind == ImportError.Kind.CATEGORY }
            notes.map { it.subject } shouldContainExactly listOf("Tie A, Tie B")
        }
    }

    private fun library(categories: List<BackupCategory>, entries: Map<String, List<Long>>) = Backup(
        backupManga = entries.map { (url, orders) ->
            BackupManga(source = SyntheticBackupFixture.SOURCE_A, url = url, title = url, categories = orders)
        },
        backupCategories = categories,
    )

    private suspend fun categoriesOf(env: BackupTestEnvironment, url: String): List<String> {
        val manga = env.mangaRepository.getFavorites().single { it.url == url }
        return env.categoryRepository.getCategoriesByMangaId(manga.id).map { it.name }
    }
}
