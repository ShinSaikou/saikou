package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * TachiyomiSY, TachiyomiJ2K and Komikku write an entry's edited title at field 800 and its edited
 * description at 804, numbers upstream does not use (R162 item 8). A restore reads them into the
 * entry's overrides where the entry has none, under the row's latch, so the edit's clock and the
 * version stay as they were. The files here are built with [OtherReaderManga], which knows nothing
 * of Saikou's model, so the numbers are checked on the wire and not only by name.
 */
class OtherReaderEditRestoreTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `fields 800 and 804 become a new entry's title and description edit`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val bytes = otherReaderBytes(entry(NEW, customTitle = "Custom title", customDescription = "Custom text"))
            val decoded = env.decoder.decode(Buffer().write(bytes)).backupManga.single()
            decoded.customTitle shouldBe "Custom title"
            decoded.customDescription shouldBe "Custom text"

            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}

            val row = row(env, NEW)
            row["title_override"] shouldBe "Custom title"
            row["description_override"] shouldBe "Custom text"
            // The source's text stays under the edit, and the restore stamps no clock of its own.
            row["title"] shouldBe "Source title"
            row["description"] shouldBe "Source text"
            row["details_modified_at"] shouldBe null
            row["version"] shouldBe 0L
        }
    }

    @Test
    fun `an entry's own edit is kept over the file's, an emptied description included`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedEntry(env, EXISTING, titleOverride = "Edited title", descriptionOverride = "")
            val clock = row(env, EXISTING)["details_modified_at"].shouldNotBeNull()

            restore(env, entry(EXISTING, customTitle = "Custom title", customDescription = "Custom text"))

            val row = row(env, EXISTING)
            row["title_override"] shouldBe "Edited title"
            row["description_override"] shouldBe ""
            row["details_modified_at"] shouldBe clock
        }
    }

    @Test
    fun `the file fills only the override the entry does not have`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedEntry(env, EXISTING, titleOverride = "Edited title", descriptionOverride = null)
            val clock = row(env, EXISTING)["details_modified_at"].shouldNotBeNull()
            val version = row(env, EXISTING)["version"]

            restore(env, entry(EXISTING, customTitle = "Custom title", customDescription = "Custom text"))

            val row = row(env, EXISTING)
            row["title_override"] shouldBe "Edited title"
            row["description_override"] shouldBe "Custom text"
            row["details_modified_at"] shouldBe clock
            row["version"] shouldBe version
        }
    }

    @Test
    fun `a blank field is no edit`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedEntry(env, EXISTING, titleOverride = null, descriptionOverride = null)

            restore(
                env,
                entry(NEW, customTitle = "  ", customDescription = ""),
                entry(EXISTING, customTitle = "Custom title", customDescription = " \n"),
            )

            row(env, NEW)["title_override"] shouldBe null
            row(env, NEW)["description_override"] shouldBe null
            row(env, EXISTING)["title_override"] shouldBe "Custom title"
            row(env, EXISTING)["description_override"] shouldBe null
        }
    }

    @Test
    fun `a file without the two fields leaves new and stored entries unedited`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedEntry(env, EXISTING, titleOverride = null, descriptionOverride = null)

            restore(env, entry(NEW), entry(EXISTING))

            for (url in listOf(NEW, EXISTING)) {
                val row = row(env, url)
                row["title_override"] shouldBe null
                row["description_override"] shouldBe null
                row["details_modified_at"] shouldBe null
            }
        }
    }

    /** Restores a plain entry, then edits it as the entry screen does, which stamps the clock. */
    private suspend fun seedEntry(
        env: BackupTestEnvironment,
        url: String,
        titleOverride: String?,
        descriptionOverride: String?,
    ) {
        val plain = BackupManga(
            source = SyntheticBackupFixture.SOURCE_A,
            url = url,
            title = "Source title",
            description = "Source text",
        )
        val file = env.writeImportFile(SyntheticBackupFixture.tachibk(Backup(listOf(plain))))
        env.importer.import(file, RestoreOptions()) {}
        if (titleOverride == null && descriptionOverride == null) return
        val id = row(env, url)["_id"] as Long
        env.handler.await {
            mangasQueries.setDetailsOverride(
                titleOverride = titleOverride,
                descriptionOverride = descriptionOverride,
                mangaId = id,
            )
        }
    }

    private fun entry(url: String, customTitle: String? = null, customDescription: String? = null) =
        OtherReaderManga(
            source = SyntheticBackupFixture.SOURCE_A,
            url = url,
            title = "Source title",
            description = "Source text",
            favorite = true,
            customTitle = customTitle,
            customDescription = customDescription,
        )

    private suspend fun restore(env: BackupTestEnvironment, vararg entries: OtherReaderManga) {
        env.importer.import(env.writeImportFile(otherReaderBytes(*entries)), RestoreOptions()) {}
    }

    private fun otherReaderBytes(vararg entries: OtherReaderManga): ByteArray =
        ProtoBuf.encodeToByteArray(OtherReaderBackup.serializer(), OtherReaderBackup(entries.toList()))

    private fun row(env: BackupTestEnvironment, url: String): Map<String, Any?> =
        env.rows("mangas").single { it["url"] == url }

    /**
     * The two messages as another reader writes them, with only the fields these tests use. No field
     * has a default, because the encoder leaves a field at its default out of the bytes.
     */
    @Serializable
    private class OtherReaderBackup(@ProtoNumber(1) val backupManga: List<OtherReaderManga>)

    @Serializable
    private class OtherReaderManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val title: String,
        @ProtoNumber(6) val description: String?,
        @ProtoNumber(100) val favorite: Boolean,
        @ProtoNumber(800) val customTitle: String?,
        @ProtoNumber(804) val customDescription: String?,
    )

    private companion object {
        const val NEW = "/series/new"
        const val EXISTING = "/series/existing"
    }
}
