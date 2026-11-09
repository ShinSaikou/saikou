package com.shinsaikou.data

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Who writes unit ED's columns (R138 item 2) and the four schema version 7 adds beside them (R165
 * item 4). Schema version 3 shipped `title_override`, `description_override` and
 * `details_modified_at` inert (phase 5 unit SC4's `OverrideColumnsInertTest`); ED made them live, and
 * this test is that one inverted: the owner's edit reaches them through one named statement,
 * `setEditedDetails`, which only the repository runs, through `MangaRepository.setDetailsOverride`,
 * which only `SetMangaDetailsOverride` calls, so no screen writes them another way; the sync apply's
 * two statements write what a merge returned, under the row's latch, and `SyncStoreImpl` reads them
 * for the wire. A restore runs the one other statement, `setDetailsOverride`, which writes all six
 * (R162 item 12, R167): it fills an override the entry lacks from the edits a backup carries, Saikou's
 * or another reader's, through `BackupDatabaseWriter` under the row's latch, so no clock or version
 * moves. SQL reaches the database only through the `.sq` files' named statements or a raw
 * string in Kotlin, so the statements and the Kotlin main sources that name the columns are listed,
 * as are the schema's own objects.
 */
class OverrideColumnsWritersTest {

    @Test
    fun `only the edit's statement and the sync apply's name the override columns`() {
        SqSources.statements
            .filter { it.name != null && COLUMNS.containsMatchIn(it.sql) }
            .map { "${it.file}:${it.name}" }
            .shouldContainExactlyInAnyOrder(
                "mangas.sq:setEditedDetails",
                "mangas.sq:setDetailsOverride",
                "mangas.sq:syncInsert",
                "mangas.sq:syncUpdate",
            )
    }

    @Test
    fun `only the schema's own objects name the override columns`() {
        SqSources.statements
            .filter { it.name == null && COLUMNS.containsMatchIn(it.sql) }
            .map { OBJECT.find(it.sql)?.value ?: it.sql.take(60) }
            .shouldContainExactlyInAnyOrder(
                "CREATE TABLE mangas",
                "CREATE TRIGGER update_manga_version",
                "CREATE TRIGGER update_manga_details_modified_at",
                "CREATE VIEW updatesView",
                "CREATE VIEW historyView",
            )
    }

    @Test
    fun `only the sync store's mapping names the override columns in Kotlin`() {
        val sources = mainSources()
        sources.filter { COLUMNS.containsMatchIn(it.readText()) }.map { relative(it) }
            .shouldContainExactlyInAnyOrder("data/src/main/kotlin/com/shinsaikou/data/sync/SyncStoreImpl.kt")
        // The walk found the modules' sources at all: this file's module has its mappers there.
        sources.any { it.endsWith("MangaMapper.kt") } shouldBe true
    }

    @Test
    fun `the edit's statement runs in the repository alone, which only the edit's interactor calls`() {
        mainSources().filter { EDIT_CALL.containsMatchIn(it.readText()) }.map { it.fileName.toString() }
            .shouldContainExactlyInAnyOrder("MangaRepositoryImpl.kt")
        mainSources().filter { CALL.containsMatchIn(it.readText()) }.map { it.fileName.toString() }
            .shouldContainExactlyInAnyOrder(
                "SetMangaDetailsOverride.kt",
                "BackupDatabaseWriter.kt",
                "BackupImporter.kt",
            )
    }

    private val root: Path = Paths.get("..").toAbsolutePath().normalize()

    private fun relative(path: Path): String = root.relativize(path).toString()

    private fun mainSources(): List<Path> {
        val sources = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                // Build output, Gradle's and Git's folders and any nested worktree hold no source.
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName?.toString().orEmpty()
                    return if (dir != root && (name == "build" || name.startsWith("."))) {
                        FileVisitResult.SKIP_SUBTREE
                    } else {
                        FileVisitResult.CONTINUE
                    }
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file.extension == "kt" && isMainSource(root.relativize(file))) sources.add(file)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return sources
    }

    /** `<module path>/src/main/...`. */
    private fun isMainSource(relative: Path): Boolean {
        val parts = relative.map { it.toString() }
        val src = parts.indexOf("src")
        return src > 0 && parts.getOrNull(src + 1) == "main"
    }

    private companion object {
        val COLUMNS = Regex(
            """\b(title_override|description_override|author_override|artist_override|status_override|""" +
                """genre_override|details_modified_at)\b""",
        )
        val OBJECT = Regex("""^CREATE (TABLE|TRIGGER|VIEW) \w+""")

        /**
         * A call, never a declaration: the repository's `mangaRepository.setDetailsOverride(`, and the
         * restore's `mangasQueries.setDetailsOverride(` and `writer.setDetailsOverride(`.
         */
        val CALL = Regex("""\.setDetailsOverride\(""")

        /** The edit's statement, `mangasQueries.setEditedDetails(`. */
        val EDIT_CALL = Regex("""\.setEditedDetails\(""")
    }
}
