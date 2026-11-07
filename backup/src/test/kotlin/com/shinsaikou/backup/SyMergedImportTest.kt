package com.shinsaikou.backup

import com.shinsaikou.backup.fixture.BackupTestEnvironment
import com.shinsaikou.backup.fixture.SyntheticBackupFixture
import com.shinsaikou.backup.importer.ImportCheckpoint
import com.shinsaikou.backup.importer.ImportError
import com.shinsaikou.backup.importer.ImportPhase
import com.shinsaikou.backup.importer.ImportReport
import com.shinsaikou.backup.importer.sha256Hex
import com.shinsaikou.domain.merged.model.MergedGroup
import com.shinsaikou.domain.source.model.SourceAlias
import com.shinsaikou.source.SourceIds
import com.shinsaikou.tracking.TrackerIds
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import com.shinsaikou.data.DatabaseHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.data.Database
import java.io.File

/**
 * A TachiyomiSY file's merged entries (R163 item 4). No real file is to hand, so one is built here
 * with [SyBackup], which knows nothing of Saikou's models, from TachiyomiSY's own sources: a synthetic
 * entry at source 6969, with fields 600 and 604, beside its underlying entries, most of them not
 * favourites. Field numbers are therefore checked on the wire and not only by name.
 */
class SyMergedImportTest {

    @TempDir
    lateinit var tempDir: File

    private val a = SyntheticBackupFixture.SOURCE_A
    private val b = SyntheticBackupFixture.SOURCE_B

    private fun underlying(
        source: Long,
        url: String,
        favorite: Boolean = false,
        chapters: List<SyChapter> = listOf(SyChapter("$url/1", "Chapter 1", read = false, number = 1f)),
    ) = SyManga(source, url, "Title of $url", chapters, emptyList(), favorite, nextUpdate = 123L, null)

    private fun ref(
        source: Long,
        url: String,
        priority: Int,
        fetch: Boolean = true,
        info: Boolean = false,
        sortMode: Int = 0,
    ) = SyMergedEntry(info, fetch, sortMode, priority, true, "/merge", url, source)

    private fun synthetic(
        refs: List<SyMergedEntry>,
        settings: SyMergeSettings? = null,
        categories: List<Long> = emptyList(),
        favorite: Boolean = true,
        tracking: List<SyTrack> = emptyList(),
    ) = SyManga(
        source = SourceIds.SY_MERGED,
        url = "/merge",
        title = "The title",
        chapters = listOf(SyChapter("/merge/1", "A copy of a chapter", read = true, number = 1f)),
        categories = categories,
        favorite = favorite,
        nextUpdate = 0L,
        tracking = tracking,
        mergedEntries = refs,
        mergeSettings = settings,
    )

    private val twoSources = listOf(underlying(a, "/a"), underlying(b, "/b"))

    private fun newFormat(sortMode: Int = 0) = synthetic(
        refs = listOf(ref(a, "/a", priority = 1), ref(b, "/b", priority = 0, fetch = false)),
        settings = SyMergeSettings(sortMode, "/a", a),
    )

    private suspend fun restore(env: BackupTestEnvironment, vararg entries: SyManga, categories: List<SyCategory> = emptyList()): ImportReport {
        val backup = SyBackup(
            entries.toList(),
            categories,
            listOf(
                SySource("Fixture Source A", a),
                SySource("Fixture Source B", b),
                SySource("Merged", SourceIds.SY_MERGED),
            ),
        )
        val bytes = ProtoBuf.encodeToByteArray(SyBackup.serializer(), backup)
        return env.importer.import(env.writeImportFile(bytes), RestoreOptions(installExtensions = false)) {}
    }

    private fun id(env: BackupTestEnvironment, url: String): Long = row(env, url)["_id"] as Long

    private fun row(env: BackupTestEnvironment, url: String): Map<String, Any?> =
        env.rows("mangas").single { it["url"] == url }

    private suspend fun group(env: BackupTestEnvironment): MergedGroup =
        env.mergedGroupRepository.getLiveGroups().single()

    private fun rowsOf(env: BackupTestEnvironment) = env.rows("merged_groups") to env.rows("merged_members")

    @Test
    fun `a new-format merge is a group of favourites, in chapter priority, under the info entry`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val report = restore(env, *twoSources.toTypedArray(), newFormat())

            val group = group(env)
            group.mainMangaId shouldBe id(env, "/a")
            // Priority 0 first, whichever is the info entry.
            group.members.map { it.url } shouldContainExactly listOf("/b", "/a")
            group.members.map { it.checkUpdates } shouldContainExactly listOf(false, true)
            group.showAll shouldBe false
            row(env, "/a")["favorite"] shouldBe 1L
            row(env, "/b")["favorite"] shouldBe 1L
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
            report.mergedTitles.shouldNotBeNull().missing.shouldBeEmpty()
            report.mergedTitles.shouldNotBeNull().unmerged.shouldBeEmpty()
        }
    }

    @Test
    fun `the group is the same whichever side of its members the synthetic entry is written`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, newFormat(), *twoSources.toTypedArray())

            group(env).members.map { it.url } shouldContainExactly listOf("/b", "/a")
        }
    }

    @Test
    fun `no dedupe is Show every copy, and every other sort mode is not`() = runTest {
        for ((mode, showAll) in listOf(0 to false, 1 to true, 2 to false, 3 to false, 4 to false)) {
            BackupTestEnvironment(File(tempDir, "mode$mode").apply { mkdirs() }).use { env ->
                restore(env, *twoSources.toTypedArray(), newFormat(sortMode = mode))

                group(env).showAll shouldBe showAll
            }
        }
    }

    @Test
    fun `with no info entry the main is the first by chapter priority`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val merge = synthetic(
                refs = listOf(ref(a, "/a", priority = 1), ref(b, "/b", priority = 0)),
                settings = SyMergeSettings(0, "", 0L),
            )

            restore(env, *twoSources.toTypedArray(), merge)

            group(env).mainMangaId shouldBe id(env, "/b")
        }
    }

    @Test
    fun `the synthetic entry leaves no row, no chapter and no missing source`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val report = restore(env, *twoSources.toTypedArray(), newFormat())

            env.rows("mangas").map { it["url"] } shouldContainExactlyInAnyOrder listOf("/a", "/b")
            env.rows("chapters").map { it["url"] } shouldContainExactlyInAnyOrder listOf("/a/1", "/b/1")
            report.sourcesWithoutExtension shouldNotContain SourceIds.SY_MERGED
            report.mangaInserted shouldBe 2
            report.errors.shouldBeEmpty()
            env.stubSourceNames.find(SourceIds.SY_MERGED).shouldBeNull()
        }
    }

    @Test
    fun `the check before a restore does not call the merged source missing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val backup = SyBackup(twoSources + newFormat(), emptyList(), listOf(SySource("Merged", SourceIds.SY_MERGED)))
            val header = env.decoder.header(Buffer().write(ProtoBuf.encodeToByteArray(SyBackup.serializer(), backup)))

            env.validator.validate(header).missingSources.shouldBeEmpty()
        }
    }

    @Test
    fun `a legacy merge reads its info entry off the flag and its sort off the self reference`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            // SY's reference to the merge itself: no member, priority -1, and where the sort mode lived.
            val self = ref(SourceIds.SY_MERGED, "/merge", priority = -1, fetch = false, sortMode = 1)
            val merge = synthetic(
                refs = listOf(
                    ref(a, "/a", priority = 0),
                    ref(b, "/b", priority = 1, info = true),
                    self,
                ),
            )

            val report = restore(env, *twoSources.toTypedArray(), merge)

            val group = group(env)
            group.mainMangaId shouldBe id(env, "/b")
            group.members.map { it.url } shouldContainExactly listOf("/a", "/b")
            group.showAll shouldBe true
            report.mergedTitles.shouldNotBeNull().missing.shouldBeEmpty()
        }
    }

    @Test
    fun `a member missing from the file is left out and named`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val merge = synthetic(
                refs = listOf(ref(a, "/a", 0), ref(b, "/b", 1), ref(a, "/gone", 2)),
                settings = SyMergeSettings(0, "/a", a),
            )

            val report = restore(env, *twoSources.toTypedArray(), merge)

            group(env).members.map { it.url } shouldContainExactly listOf("/a", "/b")
            report.mergedTitles.shouldNotBeNull().missing shouldContainExactly listOf(
                ImportReport.MissingSource("The title", "Fixture Source A", "/gone"),
            )
        }
    }

    @Test
    fun `an info entry missing from the file gives the main to the first by priority`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val merge = synthetic(
                refs = listOf(ref(a, "/a", 0), ref(b, "/b", 1), ref(a, "/gone", 2)),
                settings = SyMergeSettings(0, "/gone", a),
            )

            restore(env, *twoSources.toTypedArray(), merge)

            group(env).mainMangaId shouldBe id(env, "/a")
        }
    }

    @Test
    fun `one member found is no group, and the entries stay ordinary`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val merge = synthetic(
                refs = listOf(ref(a, "/a", 0), ref(a, "/gone", 1)),
                settings = SyMergeSettings(0, "/a", a),
            )

            val report = restore(env, underlying(a, "/a"), merge)

            env.rows("merged_groups").shouldBeEmpty()
            env.rows("merged_members").shouldBeEmpty()
            row(env, "/a")["favorite"] shouldBe 0L
            val titles = report.mergedTitles.shouldNotBeNull()
            titles.merged shouldBe 0
            titles.unmerged shouldContainExactly listOf("The title")
            titles.missing.map { it.url } shouldContainExactly listOf("/gone")
        }
    }

    @Test
    fun `a synthetic entry with no members is no group`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val merge = synthetic(refs = emptyList())

            val report = restore(env, *twoSources.toTypedArray(), merge)

            env.rows("merged_groups").shouldBeEmpty()
            report.mergedTitles.shouldNotBeNull().unmerged shouldContainExactly listOf("The title")
        }
    }

    @Test
    fun `restoring the same file twice leaves the same groups and writes nothing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, *twoSources.toTypedArray(), newFormat())
            val rows = rowsOf(env)
            val logged = env.count("sync_change_log")

            val report = restore(env, *twoSources.toTypedArray(), newFormat())

            rowsOf(env) shouldBe rows
            env.count("sync_change_log") shouldBe logged
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
            report.mergedTitles.shouldNotBeNull().unmerged.shouldBeEmpty()
        }
    }

    @Test
    fun `a group on the device keeps a member it holds, and the rest form their own group`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, underlying(a, "/a", favorite = true), underlying(a, "/own", favorite = true))
            env.mergedGroupRepository.merge(id(env, "/own"), listOf(id(env, "/own"), id(env, "/a")))
            val merge = synthetic(
                refs = listOf(ref(a, "/a", 0), ref(b, "/b", 1), ref(b, "/c", 2)),
                settings = SyMergeSettings(0, "/a", a),
            )

            val report = restore(env, underlying(a, "/a"), underlying(b, "/b"), underlying(b, "/c"), merge)

            val groups = env.mergedGroupRepository.getLiveGroups().associate { group ->
                group.mainMangaId to group.members.map { it.url }
            }
            groups shouldBe mapOf(id(env, "/own") to listOf("/own", "/a"), id(env, "/b") to listOf("/b", "/c"))
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
        }
    }

    @Test
    fun `a title left with one free member is no group, and that entry stays ordinary`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, underlying(a, "/a", favorite = true), underlying(a, "/own", favorite = true))
            env.mergedGroupRepository.merge(id(env, "/own"), listOf(id(env, "/own"), id(env, "/a")))

            val report = restore(env, underlying(a, "/a"), underlying(b, "/b"), newFormat())

            env.mergedGroupRepository.getLiveGroups() shouldHaveSize 1
            row(env, "/b")["favorite"] shouldBe 0L
            report.mergedTitles.shouldNotBeNull().unmerged shouldContainExactly listOf("The title")
        }
    }

    @Test
    fun `an unmerged title's tombstone is reused by a later restore`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, *twoSources.toTypedArray(), newFormat())
            env.mergedGroupRepository.unmerge(group(env).id)

            restore(env, *twoSources.toTypedArray(), newFormat())

            group(env).members.map { it.url } shouldContainExactly listOf("/b", "/a")
        }
    }

    @Test
    fun `with the switch off the rows go in, the report says so, and the switch stays off`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.libraryPreferences.mergedEntries.get() shouldBe false

            val report = restore(env, *twoSources.toTypedArray(), newFormat())

            env.rows("merged_members") shouldHaveSize 2
            env.libraryPreferences.mergedEntries.get() shouldBe false
            val titles = report.mergedTitles.shouldNotBeNull()
            titles.merged shouldBe 1
            titles.switchOn shouldBe false
        }
    }

    @Test
    fun `with the switch on the report says so, and read marks reach every copy`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.libraryPreferences.mergedEntries.set(true)
            val read = underlying(a, "/a", chapters = listOf(SyChapter("/a/1", "Chapter 1", read = true, number = 1f)))

            val report = restore(env, read, underlying(b, "/b"), newFormat())

            report.mergedTitles.shouldNotBeNull().switchOn shouldBe true
            env.rows("chapters").map { it["url"] to it["read"] } shouldContainExactlyInAnyOrder
                listOf("/a/1" to 1L, "/b/1" to 1L)
        }
    }

    @Test
    fun `the synthetic entry's categories go to the main entry, beside its own`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val categories = listOf(SyCategory("Own", 1L), SyCategory("Merged tab", 2L))
            val main = SyManga(a, "/a", "Title", emptyList(), listOf(1L), true, 0L, null)

            restore(env, main, underlying(b, "/b"), newFormat().copy(categories = listOf(2L)), categories = categories)

            val names = env.categoryRepository.getCategoriesByMangaId(id(env, "/a")).map { it.name }
            names shouldContainExactlyInAnyOrder listOf("Own", "Merged tab")
            env.categoryRepository.getCategoriesByMangaId(id(env, "/b")).shouldBeEmpty()
        }
    }

    @Test
    fun `a failed category write for the main entry still lowers its sync latch and the rest of the title restores`() = runTest {
        val refuse = RefuseMainCategories(mainUrl = "/a")
        BackupTestEnvironment(tempDir, handlerFactory = { refuse.wrap(it) }).use { env ->
            refuse.env = env
            val categories = listOf(SyCategory("Merged tab", 1L))
            val track = SyTrack(syncId = TrackerIds.ALL.first().toInt(), libraryId = 0L, title = "Remote title", mediaId = 7L)

            val report = restore(
                env,
                *twoSources.toTypedArray(),
                newFormat().copy(categories = listOf(1L), tracking = listOf(track)),
                categories = categories,
            )

            refuse.refused shouldBe true
            report.errors.map { it.kind } shouldContainExactly listOf(ImportError.Kind.CATEGORIES_OF_MANGA)
            row(env, "/a")["is_syncing"] shouldBe 0L
            env.categoryRepository.getCategoriesByMangaId(id(env, "/a")).shouldBeEmpty()
            group(env).members.map { it.mangaId } shouldContainExactlyInAnyOrder listOf(id(env, "/a"), id(env, "/b"))
            env.trackRepository.getTracksByMangaId(id(env, "/a")).map { it.remoteId } shouldContainExactly listOf(7L)
        }
    }

    @Test
    fun `the synthetic entry's tracks go to the main entry`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val track = SyTrack(syncId = TrackerIds.ALL.first().toInt(), libraryId = 0L, title = "Remote title", mediaId = 7L)

            restore(env, *twoSources.toTypedArray(), newFormat().copy(tracking = listOf(track)))

            env.trackRepository.getTracksByMangaId(id(env, "/a")).map { it.remoteId } shouldContainExactly listOf(7L)
        }
    }

    @Test
    fun `a member's source is found through its alias`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            env.sourceAliasRepository.upsert(SourceAlias(oldSourceId = OLD_SOURCE, newSourceId = a, dateAdded = 0L))

            restore(env, underlying(OLD_SOURCE, "/a"), underlying(b, "/b"), newFormatFrom(OLD_SOURCE))

            group(env).members.map { it.source to it.url } shouldContainExactly listOf(a to "/a", b to "/b")
        }
    }

    private fun newFormatFrom(source: Long) = synthetic(
        refs = listOf(ref(source, "/a", 0), ref(b, "/b", 1)),
        settings = SyMergeSettings(0, "/a", source),
    )

    @Test
    fun `a run resumed past every entry still forms the group`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, *twoSources.toTypedArray())
            val options = RestoreOptions(installExtensions = false)
            val backup = SyBackup(twoSources + newFormat(), emptyList(), emptyList())
            val file = env.writeImportFile(ProtoBuf.encodeToByteArray(SyBackup.serializer(), backup))
            env.checkpoints.save(
                ImportCheckpoint(
                    fileSha256 = file.sha256Hex(),
                    fileSize = file.length(),
                    options = options.asBooleanArray().toList(),
                    phase = ImportPhase.MANGA,
                    mangaCursor = 3,
                    mangaTotal = 3,
                    startedAt = 0L,
                    updatedAt = 0L,
                ),
            )

            val report = env.importer.import(file, options) {}

            report.resumed shouldBe true
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
            group(env).members.map { it.url } shouldContainExactly listOf("/b", "/a")
        }
    }

    @Test
    fun `a synthetic entry the library had dropped brings nothing back`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val report = restore(env, *twoSources.toTypedArray(), newFormat().copy(favorite = false))

            env.rows("merged_groups").shouldBeEmpty()
            row(env, "/a")["favorite"] shouldBe 0L
            env.rows("mangas") shouldHaveSize 2
            report.mergedTitles.shouldBeNull()
        }
    }

    @Test
    fun `a restore of the library alone turned off writes no group`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val backup = SyBackup(twoSources + newFormat(), emptyList(), emptyList())
            val bytes = ProtoBuf.encodeToByteArray(SyBackup.serializer(), backup)

            env.importer.import(env.writeImportFile(bytes), RestoreOptions(libraryEntries = false, installExtensions = false)) {}

            env.rows("merged_groups").shouldBeEmpty()
        }
    }

    @Test
    fun `an export after the restore holds the members and no merge`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, *twoSources.toTypedArray(), newFormat())

            val exported = env.export(BackupOptions()).backupManga

            exported.map { it.url } shouldContainExactlyInAnyOrder listOf("/a", "/b")
            exported.forEach {
                it.mergedEntries.shouldBeEmpty()
                it.mergeSettings.shouldBeNull()
            }
        }
    }

    // Edges ---------------------------------------------------------------------------------------

    private fun second(refs: List<SyMergedEntry>, settings: SyMergeSettings?) =
        synthetic(refs = refs, settings = settings).copy(url = "/merge2", title = "Second title")

    @Test
    fun `a restore stopped between two titles keeps the first, and a run again forms the second`() = runTest {
        val stopper = StopBetweenTitles()
        BackupTestEnvironment(tempDir, handlerFactory = { stopper.wrap(it) }).use { env ->
            stopper.env = env
            val entries = arrayOf(
                underlying(a, "/a"),
                underlying(b, "/b"),
                underlying(a, "/c"),
                underlying(b, "/d"),
                newFormat(),
                second(
                    listOf(ref(a, "/c", priority = 0), ref(b, "/d", priority = 1)),
                    SyMergeSettings(0, "/c", a),
                ),
            )

            shouldThrow<CancellationException> { restore(env, *entries) }

            stopper.stopped shouldBe true
            env.mergedGroupRepository.getLiveGroups() shouldHaveSize 1
            val first = rowsOf(env)

            val report = restore(env, *entries)

            report.resumed shouldBe true
            env.mergedGroupRepository.getLiveGroups().map { group -> group.members.map { it.url } } shouldContainExactlyInAnyOrder
                listOf(listOf("/b", "/a"), listOf("/c", "/d"))
            // The first title's rows are the ones the stopped run wrote, not written again.
            env.rows("merged_groups").first() shouldBe first.first.first()
            report.mergedTitles.shouldNotBeNull().merged shouldBe 2
            report.mergedTitles.shouldNotBeNull().unmerged.shouldBeEmpty()
        }
    }

    @Test
    fun `a member whose entry failed to restore is named missing and the title is no group`() = runTest {
        // With no categories to write, transactions 1 and 2 are the phases and 3 is the chunk of three
        // entries; 4 to 6 are the rows it falls back to, in file order. So the chunk fails, and so
        // does the second row, /b, which is the entry that fails to restore.
        val failing = FailingTransactions(setOf(3, 5))
        BackupTestEnvironment(tempDir, handlerFactory = { failing.wrap(it) }).use { env ->
            val report = restore(env, *twoSources.toTypedArray(), newFormat())

            env.rows("mangas").map { it["url"] } shouldContainExactly listOf("/a")
            env.rows("merged_groups").shouldBeEmpty()
            val titles = report.mergedTitles.shouldNotBeNull()
            titles.merged shouldBe 0
            titles.unmerged shouldContainExactly listOf("The title")
            titles.missing shouldContainExactly listOf(ImportReport.MissingSource("The title", "Fixture Source B", "/b"))
            row(env, "/a")["favorite"] shouldBe 0L
        }
    }

    @Test
    fun `a member the device holds as a tombstone is brought back into the group`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            restore(env, *twoSources.toTypedArray())
            env.mangaRepository.softDelete(id(env, "/b"), 1_000L)
            row(env, "/b")["deleted_at"].shouldNotBeNull()

            val report = restore(env, *twoSources.toTypedArray(), newFormat())

            row(env, "/b")["deleted_at"].shouldBeNull()
            row(env, "/b")["favorite"] shouldBe 1L
            group(env).members.map { it.url } shouldContainExactly listOf("/b", "/a")
            report.mergedTitles.shouldNotBeNull().merged shouldBe 1
        }
    }

    @Test
    fun `two merges in one file claiming the same member leave the member in the first group`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val claims = second(
                listOf(ref(b, "/b", priority = 0), ref(a, "/c", priority = 1), ref(b, "/d", priority = 2)),
                SyMergeSettings(0, "/b", b),
            )

            val report = restore(
                env,
                underlying(a, "/a"),
                underlying(b, "/b"),
                underlying(a, "/c"),
                underlying(b, "/d"),
                newFormat(),
                claims,
            )

            val groups = env.mergedGroupRepository.getLiveGroups().map { group -> group.members.map { it.url } }
            groups shouldContainExactlyInAnyOrder listOf(listOf("/b", "/a"), listOf("/c", "/d"))
            report.mergedTitles.shouldNotBeNull().merged shouldBe 2
            env.rows("merged_members").map { it["member_url"] }.distinct() shouldHaveSize 4
        }
    }

    @Test
    fun `a second merge left with one free member is no group, and its entry stays ordinary`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val claims = second(listOf(ref(b, "/b", priority = 0), ref(a, "/c", priority = 1)), SyMergeSettings(0, "/b", b))

            val report = restore(env, underlying(a, "/a"), underlying(b, "/b"), underlying(a, "/c"), newFormat(), claims)

            env.mergedGroupRepository.getLiveGroups().single().members.map { it.url } shouldContainExactly listOf("/b", "/a")
            row(env, "/c")["favorite"] shouldBe 0L
            val titles = report.mergedTitles.shouldNotBeNull()
            titles.merged shouldBe 1
            titles.unmerged shouldContainExactly listOf("Second title")
        }
    }

    @Test
    fun `a legacy merge with no self reference reads its sort mode off the first reference`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val merge = synthetic(refs = listOf(ref(a, "/a", priority = 0, sortMode = 1), ref(b, "/b", priority = 1)))

            restore(env, *twoSources.toTypedArray(), merge)

            group(env).showAll shouldBe true
            group(env).mainMangaId shouldBe id(env, "/a")
        }
    }

    @Test
    fun `a legacy self reference listed first is no member and does not move the priority order`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val self = ref(SourceIds.SY_MERGED, "/merge", priority = -1, fetch = false)
            val merge = synthetic(refs = listOf(self, ref(b, "/b", priority = 1), ref(a, "/a", priority = 0)))

            val report = restore(env, *twoSources.toTypedArray(), merge)

            group(env).members.map { it.url } shouldContainExactly listOf("/a", "/b")
            report.mergedTitles.shouldNotBeNull().missing.shouldBeEmpty()
        }
    }

    @Test
    fun `a legacy merge whose only other row is the self reference is no group and names nothing missing`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val self = ref(SourceIds.SY_MERGED, "/merge", priority = -1, fetch = false)
            val merge = synthetic(refs = listOf(self, ref(a, "/a", priority = 0)))

            val report = restore(env, underlying(a, "/a"), merge)

            env.rows("merged_groups").shouldBeEmpty()
            val titles = report.mergedTitles.shouldNotBeNull()
            titles.unmerged shouldContainExactly listOf("The title")
            titles.missing.shouldBeEmpty()
        }
    }

    @Test
    fun `an empty info url in the settings ignores the flag on a later member`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val merge = synthetic(
                refs = listOf(ref(a, "/a", priority = 0), ref(b, "/b", priority = 1, info = true)),
                settings = SyMergeSettings(0, "", a),
            )

            restore(env, *twoSources.toTypedArray(), merge)

            group(env).mainMangaId shouldBe id(env, "/a")
        }
    }

    @Test
    fun `a synthetic entry the library had dropped leaves favourite members as they are`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val members = listOf(underlying(a, "/a", favorite = true), underlying(b, "/b", favorite = true))

            val report = restore(env, *members.toTypedArray(), newFormat().copy(favorite = false))

            env.rows("merged_groups").shouldBeEmpty()
            row(env, "/a")["favorite"] shouldBe 1L
            row(env, "/b")["favorite"] shouldBe 1L
            report.mergedTitles.shouldBeNull()
        }
    }

    @Test
    fun `a member's own track reaches the main entry beside the synthetic entry's`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val trackers = TrackerIds.ALL.toList()
            val ownTrack = SyTrack(syncId = trackers[0].toInt(), libraryId = 0L, title = "Own", mediaId = 11L)
            val member = underlying(b, "/b").copy(tracking = listOf(ownTrack))

            restore(env, underlying(a, "/a"), member, newFormat())

            env.trackRepository.getTracksByMangaId(id(env, "/a")).map { it.remoteId } shouldContainExactly listOf(11L)
        }
    }

    @Test
    fun `with categories left out of the restore the synthetic entry's categories reach no entry`() = runTest {
        BackupTestEnvironment(tempDir).use { env ->
            val categories = listOf(SyCategory("Merged tab", 1L))
            val backup = SyBackup(
                twoSources + newFormat().copy(categories = listOf(1L)),
                categories,
                listOf(SySource("Fixture Source A", a), SySource("Fixture Source B", b), SySource("Merged", SourceIds.SY_MERGED)),
            )
            val bytes = ProtoBuf.encodeToByteArray(SyBackup.serializer(), backup)

            env.importer.import(env.writeImportFile(bytes), RestoreOptions(categories = false, installExtensions = false)) {}

            group(env).members shouldHaveSize 2
            env.categoryRepository.getCategoriesByMangaId(id(env, "/a")).shouldBeEmpty()
            env.categoryRepository.getCategoriesByMangaId(id(env, "/b")).shouldBeEmpty()
        }
    }

    /** Throws a [CancellationException] as the next outermost transaction opens, once a group stands. */
    private class StopBetweenTitles {
        var env: BackupTestEnvironment? = null
        var stopped = false
        private var depth = 0

        fun wrap(delegate: DatabaseHandler): DatabaseHandler = object : DatabaseHandler by delegate {
            override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                if (inTransaction && depth == 0 && !stopped && (env?.count("merged_groups") ?: 0L) >= 1L) {
                    stopped = true
                    throw CancellationException("stopped by the owner")
                }
                depth++
                try {
                    return delegate.await(inTransaction, block)
                } finally {
                    depth--
                }
            }
        }
    }

    /** Refuses the category write of the entry at [mainUrl] while its sync latch is up, once. */
    private class RefuseMainCategories(private val mainUrl: String) {
        var env: BackupTestEnvironment? = null
        var refused = false

        fun wrap(delegate: DatabaseHandler): DatabaseHandler = object : DatabaseHandler by delegate {
            override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                val writesCategories = Throwable().stackTrace.any { it.methodName == "setMangaCategories" }
                if (!refused && writesCategories && latchIsUp()) {
                    refused = true
                    throw IllegalStateException("The category write could not be made")
                }
                return delegate.await(inTransaction, block)
            }
        }

        private fun latchIsUp(): Boolean =
            env?.rows("mangas")?.singleOrNull { it["url"] == mainUrl }?.get("is_syncing") == 1L
    }

    /** Refuses the outermost transactions numbered [numbers], counting from one. */
    private class FailingTransactions(private val numbers: Set<Int>) {
        private var seen = 0
        private var depth = 0

        fun wrap(delegate: DatabaseHandler): DatabaseHandler = object : DatabaseHandler by delegate {
            override suspend fun <T> await(inTransaction: Boolean, block: suspend Database.() -> T): T {
                if (!inTransaction) return delegate.await(inTransaction, block)
                if (depth == 0 && ++seen in numbers) throw IllegalStateException("The transaction could not be opened")
                depth++
                try {
                    return delegate.await(inTransaction, block)
                } finally {
                    depth--
                }
            }
        }
    }

    /**
     * The messages as TachiyomiSY writes them, with only the fields these tests use. No field has a
     * default, because the encoder leaves a field at its default out of the bytes; field 113 is one
     * Saikou's model does not know.
     */
    @Serializable
    private class SyBackup(
        @ProtoNumber(1) val backupManga: List<SyManga>,
        @ProtoNumber(2) val backupCategories: List<SyCategory>,
        @ProtoNumber(101) val backupSources: List<SySource>,
    )

    @Serializable
    private data class SyManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(3) val title: String,
        @ProtoNumber(16) val chapters: List<SyChapter>,
        @ProtoNumber(17) val categories: List<Long>,
        @ProtoNumber(100) val favorite: Boolean,
        @ProtoNumber(113) val nextUpdate: Long,
        @ProtoNumber(604) val mergeSettings: SyMergeSettings?,
        @ProtoNumber(18) val tracking: List<SyTrack> = emptyList(),
        @ProtoNumber(600) val mergedEntries: List<SyMergedEntry> = emptyList(),
    )

    @Serializable
    private class SyChapter(
        @ProtoNumber(1) val url: String,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(4) val read: Boolean,
        @ProtoNumber(9) val number: Float,
    )

    @Serializable
    private class SyCategory(@ProtoNumber(1) val name: String, @ProtoNumber(2) val order: Long)

    @Serializable
    private class SySource(@ProtoNumber(1) val name: String, @ProtoNumber(2) val sourceId: Long)

    @Serializable
    private class SyTrack(
        @ProtoNumber(1) val syncId: Int,
        @ProtoNumber(2) val libraryId: Long,
        @ProtoNumber(5) val title: String,
        @ProtoNumber(100) val mediaId: Long,
    )

    /** Field 6 is the merge's own url, which older files carry and Saikou does not read. */
    @Serializable
    private class SyMergedEntry(
        @ProtoNumber(1) val isInfoManga: Boolean,
        @ProtoNumber(2) val fetchChapterUpdates: Boolean,
        @ProtoNumber(3) val chapterSortMode: Int,
        @ProtoNumber(4) val chapterPriority: Int,
        @ProtoNumber(5) val downloadChapters: Boolean,
        @ProtoNumber(6) val mergeUrl: String,
        @ProtoNumber(7) val mangaUrl: String,
        @ProtoNumber(8) val mangaSourceId: Long,
    )

    @Serializable
    private class SyMergeSettings(
        @ProtoNumber(1) val chapterSortMode: Int,
        @ProtoNumber(2) val infoMangaUrl: String,
        @ProtoNumber(3) val infoMangaSourceId: Long,
    )

    private companion object {
        const val OLD_SOURCE = 4242L
    }
}
