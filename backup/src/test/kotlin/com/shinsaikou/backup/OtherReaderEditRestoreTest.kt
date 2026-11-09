package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.assertions.withClue
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
 * TachiyomiSY, TachiyomiJ2K and Komikku write an entry's edited details at numbers upstream does not
 * use (R162 item 8): the title at 800, the artist at 801, the author at 802, the description at 804,
 * the genres at 805 and the status at 602, where 0 is no edit. A restore reads them into the entry's
 * overrides where the entry has none, under the row's latch, so the edit's clock and the version stay
 * as they were. The files here are built with [OtherReaderManga], which knows nothing of Saikou's
 * model (no 5002 among them), so the numbers are checked on the wire and not only by name.
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
    fun `fields 801, 802, 805 and 602 become a new entry's artist, author, genre and status edit`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val bytes = otherReaderBytes(
                entry(
                    NEW,
                    customArtist = "Their artist",
                    customAuthor = "Their author",
                    customGenre = listOf("Romance", "Drama"),
                    customStatus = 2,
                ),
            )
            val decoded = env.decoder.decode(Buffer().write(bytes)).backupManga.single()
            decoded.customArtist shouldBe "Their artist"
            decoded.customAuthor shouldBe "Their author"
            decoded.customGenre shouldBe listOf("Romance", "Drama")
            decoded.customStatus shouldBe 2
            decoded.statusOverride shouldBe null

            env.importer.import(env.writeImportFile(bytes), RestoreOptions()) {}

            val row = row(env, NEW)
            row["artist_override"] shouldBe "Their artist"
            row["author_override"] shouldBe "Their author"
            // The column's joined form, as the Edit details dialog stores it.
            row["genre_override"] shouldBe "Romance, Drama"
            row["status_override"] shouldBe 2L
            // The source's details stay under the edits, and the restore stamps no clock of its own.
            row["artist"] shouldBe "Source artist"
            row["author"] shouldBe "Source author"
            row["genre"] shouldBe "Action, Comedy"
            row["status"] shouldBe 1L
            row["details_modified_at"] shouldBe null
            row["version"] shouldBe 0L
        }
    }

    @Test
    fun `602 is the status edit when there is no 5002, and 602 of 0 is none`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedEntry(env, EXISTING)

            restore(
                env,
                entry(NEW, customStatus = 3),
                entry(EXISTING, customStatus = 0),
                entry(OTHER, customStatus = 0),
            )

            row(env, NEW)["status_override"] shouldBe 3L
            row(env, EXISTING)["status_override"] shouldBe null
            row(env, OTHER)["status_override"] shouldBe null
        }
    }

    @Test
    fun `an entry's own author, artist, status and genre edits are kept, and the file fills the others`() =
        runTest {
            BackupTestEnvironment(tempDir).use { env ->
                // An edit to Unknown (0) is an edit, kept over the file's status as any other is.
                seedEntry(env, EXISTING, authorOverride = "My author", statusOverride = 0L)
                seedEntry(env, OTHER, artistOverride = "My artist", genreOverride = listOf("Mine"))
                val before = listOf(EXISTING, OTHER).associateWith { url ->
                    row(env, url).let { it["details_modified_at"].shouldNotBeNull() to it["version"] }
                }
                val theirs = { url: String ->
                    entry(
                        url,
                        customArtist = "Their artist",
                        customAuthor = "Their author",
                        customGenre = listOf("Romance", "Drama"),
                        customStatus = 3,
                    )
                }

                restore(env, theirs(EXISTING), theirs(OTHER))

                val existing = row(env, EXISTING)
                existing["author_override"] shouldBe "My author"
                existing["artist_override"] shouldBe "Their artist"
                existing["status_override"] shouldBe 0L
                existing["genre_override"] shouldBe "Romance, Drama"
                val other = row(env, OTHER)
                other["author_override"] shouldBe "Their author"
                other["artist_override"] shouldBe "My artist"
                other["status_override"] shouldBe 3L
                other["genre_override"] shouldBe "Mine"
                // Nothing moves under the latch: neither the edit's clock nor the version.
                for ((url, clockAndVersion) in before) {
                    withClue(url) {
                        row(env, url).let { it["details_modified_at"] to it["version"] } shouldBe clockAndVersion
                    }
                }
                env.rows("mangas").forEach { it["is_syncing"] shouldBe 0L }
            }
        }

    @Test
    fun `a blank author or artist, or genres with no name, are no edit`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(
                env,
                entry(NEW, customArtist = " ", customAuthor = "", customGenre = listOf(" ", "")),
                // Each genre trimmed, blanks and repeats dropped, as the Edit details dialog saves them.
                entry(OTHER, customGenre = listOf(" Romance ", "Romance", "", "Drama")),
            )

            val row = row(env, NEW)
            row["artist_override"] shouldBe null
            row["author_override"] shouldBe null
            row["genre_override"] shouldBe null
            row(env, OTHER)["genre_override"] shouldBe "Romance, Drama"
        }
    }

    @Test
    fun `a file without the fields leaves new and stored entries unedited`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            seedEntry(env, EXISTING)

            restore(env, entry(NEW), entry(EXISTING))

            for (url in listOf(NEW, EXISTING)) {
                val row = row(env, url)
                for (column in DETAIL_EDITS) row[column] shouldBe null
                row["details_modified_at"] shouldBe null
            }
        }
    }

    /**
     * Restores a plain entry, then writes the edits given outside the latch, so the clock is stamped
     * and the version moves as an edit on the entry screen makes them.
     */
    private suspend fun seedEntry(
        env: BackupTestEnvironment,
        url: String,
        titleOverride: String? = null,
        descriptionOverride: String? = null,
        authorOverride: String? = null,
        artistOverride: String? = null,
        statusOverride: Long? = null,
        genreOverride: List<String>? = null,
    ) {
        val plain = BackupManga(
            source = SyntheticBackupFixture.SOURCE_A,
            url = url,
            title = "Source title",
            description = "Source text",
        )
        val file = env.writeImportFile(SyntheticBackupFixture.tachibk(Backup(listOf(plain))))
        env.importer.import(file, RestoreOptions()) {}
        val edits =
            listOf(titleOverride, descriptionOverride, authorOverride, artistOverride, statusOverride, genreOverride)
        if (edits.all { it == null }) return
        val id = row(env, url)["_id"] as Long
        env.handler.await {
            mangasQueries.setDetailsOverride(
                titleOverride = titleOverride,
                descriptionOverride = descriptionOverride,
                authorOverride = authorOverride,
                artistOverride = artistOverride,
                statusOverride = statusOverride,
                genreOverride = genreOverride,
                mangaId = id,
            )
        }
    }

    private fun entry(
        url: String,
        customTitle: String? = null,
        customDescription: String? = null,
        customArtist: String? = null,
        customAuthor: String? = null,
        customGenre: List<String> = emptyList(),
        customStatus: Int = 0,
    ) = OtherReaderManga(
        source = SyntheticBackupFixture.SOURCE_A,
        url = url,
        title = "Source title",
        artist = "Source artist",
        author = "Source author",
        description = "Source text",
        genre = listOf("Action", "Comedy"),
        status = 1,
        favorite = true,
        customStatus = customStatus,
        customTitle = customTitle,
        customArtist = customArtist,
        customAuthor = customAuthor,
        customDescription = customDescription,
        customGenre = customGenre,
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
     * has a default, because the encoder leaves a field at its default out of the bytes: a 602 of 0
     * is on the wire. An empty genre list has no bytes either way.
     */
    @Serializable
    private class OtherReaderBackup(@ProtoNumber(1) val backupManga: List<OtherReaderManga>)

    @Serializable
    private class OtherReaderManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val title: String,
        @ProtoNumber(4) val artist: String?,
        @ProtoNumber(5) val author: String?,
        @ProtoNumber(6) val description: String?,
        @ProtoNumber(7) val genre: List<String>,
        @ProtoNumber(8) val status: Int,
        @ProtoNumber(100) val favorite: Boolean,
        @ProtoNumber(602) val customStatus: Int,
        @ProtoNumber(800) val customTitle: String?,
        @ProtoNumber(801) val customArtist: String?,
        @ProtoNumber(802) val customAuthor: String?,
        @ProtoNumber(804) val customDescription: String?,
        @ProtoNumber(805) val customGenre: List<String>,
    )

    private companion object {
        const val NEW = "/series/new"
        const val EXISTING = "/series/existing"
        const val OTHER = "/series/other"

        /** The six override columns. */
        val DETAIL_EDITS = listOf(
            "title_override", "description_override", "author_override", "artist_override", "status_override",
            "genre_override",
        )
    }
}
