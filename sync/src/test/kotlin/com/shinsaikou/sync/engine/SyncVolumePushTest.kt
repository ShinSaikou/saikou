package com.shinsaikou.sync.engine

import com.shinsaikou.data.DatabaseTransactionRunner
import com.shinsaikou.data.download.DownloadManifestRepositoryImpl
import com.shinsaikou.domain.chapter.interactor.GetExcludedScanlators
import com.shinsaikou.domain.chapter.interactor.SyncChaptersWithSource
import com.shinsaikou.domain.manga.interactor.UpdateManga
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.manga.interactor.FetchInterval
import java.io.File

/**
 * The one-time sync push the volume parser causes (phase 4 unit VG, challenge c2 g3 D item 3),
 * counted on a generated 3,000-entry library of [CHAPTERS] chapters each (the size of phase 3's
 * `sync-3000` library), not on a device.
 *
 * Two devices hold the same library as a build without the parser stored it: every chapter's
 * volume null. The chapter names are the parser corpus's (`volume-corpus.tsv` in `:domain`'s
 * tests), in turn, so the share of names with a volume is the corpus's. Each device then runs the
 * first library update with the parser, before either syncs (the most either can push), through
 * the real `SyncChaptersWithSource` over its own repositories and triggers. Each logs one chapter
 * line per chapter with a parsed volume and pushes them; the other's copy holds the same values, so
 * its merge writes nothing; a second update logs nothing, and both end with the same library.
 *
 * A chapter renamed out of its volume marker moves its version once, as one edit on the other
 * device does (verifier findings on VG): the merge takes a chapter's source values from the higher
 * version, so a second bump for the same edit would win over the other device's edit outright.
 */
class SyncVolumePushTest {

    private val backend = InMemorySyncBackend()
    private val a = SyncTestDevice(backend)
    private val b = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        a.close()
        b.close()
    }

    private data class Row(val name: String, val volume: Double?)

    /** The corpus rows, each name with its title taken out, which leaves the volume it reads. */
    private val corpus: List<Row> = File(checkNotNull(System.getProperty("saikou.volume.corpus"))).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val (title, name, volume) = line.split('\t')
            Row(name.replace(title, "").trim(), volume.takeUnless { it == "-" }?.toDouble())
        }

    private fun row(entry: Int, chapter: Int): Row = corpus[((entry - 1) * CHAPTERS + chapter) % corpus.size]

    private fun infos(entry: Int): List<ChapterInfo> = (0 until CHAPTERS).map { chapter ->
        ChapterInfo(url = "/series/$entry/chapter/$chapter", name = row(entry, chapter).name, dateUpload = DATE)
    }

    private fun SyncTestDevice.favourites() = exec(
        """
        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $ENTRIES)
        INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, chapter_flags,
            cover_last_modified, date_added, memo)
        SELECT i, ${SyncTestDevice.SOURCE}, '/series/' || i, 'Entry ' || i, 0, 1, 1, 0, 0, 0, 1000, CAST('{}' AS BLOB)
        FROM n
        """,
    )

    /** A refresh of each entry of [entries] with its chapter list, through the interactor the app's update runs. */
    private fun SyncTestDevice.refresh(entries: Map<Int, List<ChapterInfo>>) = runBlocking {
        val chapters = ChapterRepositoryImpl(handler)
        val getChaptersByMangaId = GetChaptersByMangaId(chapters)
        val mangas = MangaRepositoryImpl(handler)
        val sync = SyncChaptersWithSource(
            chapterRepository = chapters,
            shouldUpdateDbChapter = ShouldUpdateDbChapter(),
            updateManga = UpdateManga(mangas, FetchInterval(getChaptersByMangaId)),
            getChaptersByMangaId = getChaptersByMangaId,
            transactionRunner = DatabaseTransactionRunner(handler),
            downloadManifestRepository = DownloadManifestRepositoryImpl(handler),
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            getExcludedScanlators = GetExcludedScanlators(mangas),
            historyRepository = HistoryRepositoryImpl(handler),
        )
        entries.forEach { (entry, infos) -> sync.await(infos, mangas.getMangaById(entry.toLong())) }
    }

    /** A library update of every entry. */
    private fun SyncTestDevice.libraryUpdate() = refresh((1..ENTRIES).associateWith(::infos))

    /** The change log's rows after [seq], by entity type. */
    private fun SyncTestDevice.loggedAfter(seq: Long): Map<String, Long> =
        rows("SELECT entity_type, count(*) FROM sync_change_log WHERE seq > $seq GROUP BY entity_type", 2)
            .associate { (type, count) -> type!! to count!!.toLong() }

    /** Chapter lines in [device]'s segments, which are its pushes after its export. */
    private fun pushedChapterLines(device: SyncTestDevice): Int =
        backend.paths()
            .mapNotNull { SyncLayout.parse(it) as? RemoteName.Segment }
            .filter { it.deviceId == device.deviceId }
            .sumOf { segment -> backend.rowFile(segment.path).second.count { it.kind == RowKind.CHAPTER } }

    @Test
    fun `the first library update with the parser pushes one chapter line per chapter with a volume, from each device, once`() {
        val expected = (1..ENTRIES).sumOf { entry -> (0 until CHAPTERS).count { row(entry, it).volume != null } }

        // The library as the build without the parser stored it: the update inserts the chapters,
        // and the volumes it read are taken out again under the sync latch, which no trigger logs.
        a.favourites()
        a.libraryUpdate()
        a.exec("UPDATE chapters SET is_syncing = 1, volume = NULL")
        a.exec("UPDATE chapters SET is_syncing = 0")
        a.long("SELECT count(*) FROM chapters WHERE volume IS NOT NULL") shouldBe 0
        a.sync().shouldNotBeNull().exported shouldBe true
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        b.long("SELECT count(*) FROM chapters") shouldBe (ENTRIES * CHAPTERS).toLong()
        val seqA = a.latestSeq()
        val seqB = b.latestSeq()

        a.libraryUpdate()
        b.libraryUpdate()

        val loggedA = a.loggedAfter(seqA)
        val loggedB = b.loggedAfter(seqB)
        println("VG push count: $ENTRIES entries, ${ENTRIES * CHAPTERS} chapters, $expected with a volume")
        println("VG push count: A logged $loggedA, B logged $loggedB")
        loggedA["chapter"] shouldBe expected.toLong()
        loggedB["chapter"] shouldBe expected.toLong()
        a.long("SELECT count(*) FROM chapters WHERE volume IS NOT NULL") shouldBe expected.toLong()

        val pushA = a.sync().shouldNotBeNull()
        val pushB = b.sync().shouldNotBeNull()
        val pullA = a.sync().shouldNotBeNull()
        println(
            "VG push count: A pushed ${pushedChapterLines(a)} chapter lines in ${pushA.segmentsPushed} segments, " +
                "B pushed ${pushedChapterLines(b)} in ${pushB.segmentsPushed}",
        )
        println("VG push count: B wrote ${pushB.rowsWritten} rows from A's push, A wrote ${pullA.rowsWritten} from B's")
        // The two copies hold the same values, so neither side's merge writes a row.
        pushB.rowsWritten shouldBe 0
        pullA.rowsWritten shouldBe 0
        pushedChapterLines(a) shouldBe expected
        pushedChapterLines(b) shouldBe expected

        // Once: the next update on each device finds every volume stored and logs nothing.
        val afterA = a.latestSeq()
        val afterB = b.latestSeq()
        a.libraryUpdate()
        b.libraryUpdate()
        a.loggedAfter(afterA)["chapter"] shouldBe null
        b.loggedAfter(afterB)["chapter"] shouldBe null
        a.library() shouldBe b.library()
    }

    @Test
    fun `a rename that drops the volume marker moves the chapter's version once, as a scanlator change on the other device does`() {
        val url = "/series/1/chapter/0"
        a.addFavourite(1L)
        a.refresh(mapOf(1 to listOf(ChapterInfo(url = url, name = "Vol.1 Ch.1", dateUpload = DATE))))
        a.sync().shouldNotBeNull().exported shouldBe true
        b.sync().shouldNotBeNull().exportsApplied shouldBe 1
        val version = checkNotNull(a.long("SELECT version FROM chapters"))
        b.long("SELECT version FROM chapters") shouldBe version
        val seqA = a.latestSeq()
        val seqB = b.latestSeq()

        // Before either syncs: A's source renames the chapter out of its marker, B's credits a group.
        a.refresh(mapOf(1 to listOf(ChapterInfo(url = url, name = "Ch.1", dateUpload = DATE))))
        b.refresh(
            mapOf(1 to listOf(ChapterInfo(url = url, name = "Vol.1 Ch.1", scanlator = "Group", dateUpload = DATE))),
        )

        a.rows("SELECT name, volume FROM chapters", 2) shouldBe listOf(listOf("Ch.1", null))
        a.long("SELECT version FROM chapters") shouldBe version + 1
        b.long("SELECT version FROM chapters") shouldBe version + 1
        a.loggedAfter(seqA) shouldBe mapOf("chapter" to 1L)
        b.loggedAfter(seqB) shouldBe mapOf("chapter" to 1L)

        // Equal versions: the merge's tie-break decides, the same on both devices.
        a.sync().shouldNotBeNull()
        b.sync().shouldNotBeNull()
        a.sync().shouldNotBeNull()
        a.library() shouldBe b.library()
    }

    private companion object {
        const val ENTRIES = 3_000
        const val CHAPTERS = 20
        const val DATE = 3_000L
    }
}
