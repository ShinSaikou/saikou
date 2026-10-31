package com.shinsaikou.bench

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.domain.download.model.DownloadState
import com.shinsaikou.domain.source.toDomainChapter
import com.shinsaikou.domain.source.toDomainManga
import com.shinsaikou.downloads.adoption.AdoptionWorker
import com.shinsaikou.extensions.loader.ExtensionLoader
import com.shinsaikou.extensions.loader.InstallOutcome
import com.shinsaikou.extensions.loader.LibVersionPolicy
import com.shinsaikou.extensions.loader.LoadResult
import com.shinsaikou.extensions.trust.TrustStore
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.sync.SyncLibrarySeeder
import eu.kanade.tachiyomi.data.download.DownloadProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The debug build's half of unit R1's in-place upgrade check (phase 3 decision Q15): the state an
 * owner's device holds before the switch, generated on the device, then the snapshot
 * [InPlaceUpgradeCheckTest] compares after each install. Both methods return at once with a
 * `Skipped:` line unless the host asks for their stage, so a gate step that runs every class runs
 * nothing here.
 *
 * `seed` builds, in this order: [SyncLibrarySeeder]'s library of 2,000 entries with chapters, read
 * marks, bookmarks, categories and history; five preferences away from their defaults, one of which
 * arms the periodic library update; the frozen update fixture extension (R66), installed through the
 * private install and trusted as `GateRowsTest` installs it; one of its entries in the library under
 * [InPlaceUpgrade.FIXTURE_CATEGORY] with [InPlaceUpgrade.FIXTURE_SEEDED] of its chapters, so a library
 * update has chapters to find; its two oldest chapters downloaded from the fixture server; a small
 * upstream-layout tree (a folder and a CBZ) adopted into the download root; one secret through
 * `SecretStore`; and one crash file. Then it writes the snapshot.
 *
 * `hold` runs against `download_hold_server.py` holding from page 2: it queues the fixture entry's
 * third chapter and returns once the first page is on disk and the rest are held, so the install
 * lands on a download in flight.
 */
@RunWith(AndroidJUnit4::class)
class InPlaceUpgradeSeedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph: AppGraph get() = (context.applicationContext as SaikouApp).graph

    @Test
    fun seedsTheStateAnOwnersDeviceHolds() {
        if (InPlaceUpgrade.stage() != STAGE_SEED) return InPlaceUpgrade.skip("seedsTheStateAnOwnersDeviceHolds", STAGE_SEED)
        val apk = InstrumentationRegistry.getArguments().getString(InPlaceUpgrade.FIXTURE_APK_ARGUMENT)
        assertNotNull("no ${InPlaceUpgrade.FIXTURE_APK_ARGUMENT} argument", apk)
        runBlocking(Dispatchers.IO) {
            withTimeout(SEED_TIMEOUT_MILLIS) {
                assertTrue("the library is not empty, so this is not a fresh install", graph.mangaRepository.getFavorites().isEmpty())
                val library = SyncLibrarySeeder.seed(graph)
                Log.i(InPlaceUpgrade.LOG_TAG, "seeded ${library.mangaIds.size} entries, ${library.chaptersInserted} chapters")

                val preferenceKeys = setPreferences()
                graph.libraryUpdateScheduler.setup()

                val source = installTheFixtureExtension(apk!!)
                val (category, manga, chapters) = seedFixtureEntry(source)
                downloadTheOldestTwo(manga, chapters)
                adoptAnUpstreamTree()

                graph.secretStore.put(InPlaceUpgrade.SECRET_NAME, InPlaceUpgrade.SECRET_VALUE)
                graph.crashLog.write(Thread.currentThread(), IllegalStateException(CRASH_MESSAGE))
                graph.crashLog.markExported()
                // A preference is written in the background after `set` returns, and the runner
                // force-stops the process moments after this test ends. On the API 26 image that lost
                // the last writes, the secret among them, so everything is written through first.
                val preferenceFile = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                assertTrue("the preferences could not be written", preferenceFile.edit().commit())

                val snapshot = InPlaceUpgrade.snapshot(graph, context, preferenceKeys)
                assertTrue("the snapshot counts ${snapshot.getLong("favorites")} entries", snapshot.getLong("favorites") >= MIN_ENTRIES)
                assertEquals("the snapshot sees no crash file", 1, snapshot.getJSONArray("crashFiles").length())
                assertEquals("the snapshot sees no secret", InPlaceUpgrade.SECRET_VALUE, snapshot.getString("secret"))
                InPlaceUpgrade.writeOutcome(
                    JSONObject()
                        .put("seededBy", InPlaceUpgrade.build())
                        .put("sourceId", source.id)
                        .put("mangaId", manga.id)
                        .put("categoryId", category.id)
                        .put("preferenceKeys", JSONArray(preferenceKeys))
                        .put("heldChapterId", JSONObject.NULL)
                        .put("legs", JSONArray())
                        .put("snapshot", snapshot),
                )
                Log.i(InPlaceUpgrade.LOG_TAG, "seed ${InPlaceUpgrade.build()}: ${InPlaceUpgrade.summary(snapshot)}")
            }
        }
    }

    @Test
    fun leavesADownloadInFlight() {
        if (InPlaceUpgrade.stage() != STAGE_HOLD) return InPlaceUpgrade.skip("leavesADownloadInFlight", STAGE_HOLD)
        val outcome = InPlaceUpgrade.readOutcome()
        runBlocking(Dispatchers.IO) {
            withTimeout(HOLD_TIMEOUT_MILLIS) {
                val status = InPlaceUpgrade.fixtureServer(HOLD_STATUS_PATH)
                assertTrue("the fixture server is not holding page requests: $status", status.getBoolean("holding"))
                val manga = graph.mangaRepository.getMangaById(outcome.getLong("mangaId"))
                val third = graph.chapterRepository.getChapterByMangaId(manga.id).sortedBy { it.chapterNumber }[2]
                assertTrue("the third chapter is already downloaded", !graph.downloadManifest.isDownloaded(third.id))
                graph.downloadQueue.enqueue(manga, listOf(third), autoStart = true)
                var seen = ""
                InPlaceUpgrade.await(HOLD_TIMEOUT_MILLIS, { "the third chapter never stood mid-flight: $seen" }) {
                    val row = graph.downloadQueue.subscribe().first().firstOrNull { it.chapterId == third.id }
                    val held = InPlaceUpgrade.fixtureServer(HOLD_STATUS_PATH).getInt("held")
                    seen = "${row?.state} ${row?.downloadedPages}/${row?.totalPages}, held $held"
                    row != null && row.state == DownloadState.DOWNLOADING && row.downloadedPages in 1 until row.totalPages && held > 0
                }
                InPlaceUpgrade.writeOutcome(outcome.put("heldChapterId", third.id))
                Log.i(InPlaceUpgrade.LOG_TAG, "hold ${InPlaceUpgrade.build()}: chapter ${third.id} in flight, $seen")
            }
        }
    }

    /** Sets each preference away from its default and returns the keys, as the file stores them. */
    private fun setPreferences(): List<String> {
        val library = graph.libraryPreferences
        library.autoUpdateInterval.set(UPDATE_INTERVAL_HOURS)
        library.downloadBadge.set(true)
        library.portraitColumns.set(PORTRAIT_COLUMNS)
        // The API 26 and 28 images have no Wi-Fi, and the engine waits for it by default.
        graph.downloadPreferences.downloadOnlyOverWifi.set(false)
        // The battery prompt answered, as the device tests answer it, so a launch shows no dialog.
        graph.basePreferences.batteryExemptionRequested.set(true)
        return listOf(
            library.autoUpdateInterval.key(),
            library.downloadBadge.key(),
            library.portraitColumns.key(),
            graph.downloadPreferences.downloadOnlyOverWifi.key(),
            graph.basePreferences.batteryExemptionRequested.key(),
        )
    }

    /**
     * The frozen fixture extension through the private install, trusted through the manager, and
     * the live catalogue source of its first source by name. As `GateRowsTest` installs it.
     */
    private suspend fun installTheFixtureExtension(apkPath: String): CatalogueSource {
        val copy = File(context.cacheDir, FIXTURE_APK_COPY)
        File(apkPath).copyTo(copy, overwrite = true)
        try {
            val loader = ExtensionLoader(
                context = context,
                trustStore = TrustStore(graph.extensionStoreRepository, graph.extensionsPreferences),
                libVersionPolicy = LibVersionPolicy(graph.extensionsPreferences),
                injektBootstrap = graph.injektBootstrap,
            )
            val archive = loader.readArchive(copy)
            assertNotNull("$apkPath is not a readable extension package", archive)
            val manager = graph.extensionManager
            manager.isInitialized.first { it }
            val outcome = loader.installPrivateExtensionFile(copy)
            assertTrue("the private install refused the fixture: $outcome", outcome is InstallOutcome.Installed)
            val result = manager.reload(archive!!.packageName)
            if (result is LoadResult.Untrusted) manager.trust(result.extension)
            val installed = manager.installedExtensions.first { list -> list.any { it.packageName == archive.packageName } }
                .first { it.packageName == archive.packageName }
            val first = installed.sources.minBy { it.name }
            graph.sourceManager.isInitialized.first { it }
            val live = graph.sourceManager.getSources().first { list -> list.any { it.id == first.id } }.first { it.id == first.id }
            assertTrue("fixture source ${first.id} is not a catalogue source", live is CatalogueSource)
            return live as CatalogueSource
        } finally {
            copy.delete()
        }
    }

    /** The first entry of [source]'s listing, a favourite in its own category, with its oldest chapters. */
    private suspend fun seedFixtureEntry(source: CatalogueSource): Triple<Category, Manga, List<Chapter>> {
        val categories = graph.categoryRepository
        categories.insert(Category(id = 0, name = InPlaceUpgrade.FIXTURE_CATEGORY, order = categories.getAll().size.toLong(), flags = 0))
        val category = categories.getAll().single { it.name == InPlaceUpgrade.FIXTURE_CATEGORY }

        val info = source.getPopularManga(1).mangas.firstOrNull()
        assertNotNull("fixture source ${source.id} listed no entry", info)
        val manga = graph.mangaRepository.insertNetworkManga(listOf(info!!.toDomainManga(source.id).copy(favorite = true))).single()
        assertTrue("the fixture entry is not in the library", manga.favorite)
        val listed = source.getChapterList(info).sortedBy { it.chapterNumber }
        assertEquals("the fixture source lists another number of chapters", InPlaceUpgrade.FIXTURE_LISTED, listed.size)
        val seeded = listed.take(InPlaceUpgrade.FIXTURE_SEEDED)
        val chapters = graph.chapterRepository.addAll(
            seeded.mapIndexed { index, chapter -> chapter.toDomainChapter(manga.id).copy(sourceOrder = (seeded.size - index).toLong()) },
        ).sortedBy { it.chapterNumber }
        assertEquals("the seed did not insert every chapter", InPlaceUpgrade.FIXTURE_SEEDED, chapters.size)
        graph.mangaRepository.setMangaCategories(manga.id, listOf(category.id))
        return Triple(category, manga, chapters)
    }

    private suspend fun downloadTheOldestTwo(manga: Manga, chapters: List<Chapter>) {
        val two = chapters.take(2)
        graph.downloadQueue.enqueue(manga, two, autoStart = true)
        var waiting = ""
        InPlaceUpgrade.await(DOWNLOAD_TIMEOUT_MILLIS, { "the two chapters did not download; queued: $waiting" }) {
            val queued = graph.downloadQueue.subscribe().first().filter { row -> two.any { it.id == row.chapterId } }
            queued.firstOrNull { it.state == DownloadState.ERROR }?.let { throw AssertionError("a download failed: ${it.errorMessage}") }
            waiting = queued.joinToString { "${it.chapterId} ${it.state} ${it.downloadedPages}/${it.totalPages}" }
            queued.isEmpty() && two.all { graph.downloadManifest.isDownloaded(it.id) }
        }
    }

    /**
     * A folder and a CBZ in upstream's layout under a stub source, adopted as `DownloadAdoptionDeviceTest`
     * adopts them. The tree is removed afterwards; the copies stay in the download root.
     */
    private suspend fun adoptAnUpstreamTree() {
        graph.stubSourceRepository.upsertStubSource(ADOPTED_SOURCE_ID, ADOPTED_SOURCE_LANG, ADOPTED_SOURCE_NAME)
        val manga = graph.mangaRepository.insertNetworkManga(
            listOf(Manga.create().copy(source = ADOPTED_SOURCE_ID, url = "/upgrade-check/adopted", title = ADOPTED_TITLE, favorite = true)),
        ).single()
        val chapters = graph.chapterRepository.addAll(
            listOf(1, 2).map { number ->
                Chapter.create().copy(mangaId = manga.id, url = "/upgrade-check/adopted/$number", name = "Chapter $number", chapterNumber = number.toDouble())
            },
        ).sortedBy { it.chapterNumber }
        val ascii = graph.libraryPreferences.disallowNonAsciiFilenames.get()
        val tree = File(context.filesDir, "upgrade-adoption-tree")
        tree.deleteRecursively()
        val mangaDir = File(tree, "downloads/$ADOPTED_UPSTREAM_DIR/${DownloadProvider.getMangaDirName(ADOPTED_TITLE, ascii)}")
        File(mangaDir, DownloadProvider.getChapterDirName(chapters[0].name, null, chapters[0].url, ascii)).apply {
            assertTrue("could not create the chapter folder", mkdirs())
            PAGE_COLORS.forEachIndexed { index, color -> File(this, "%03d.png".format(index + 1)).writeBytes(png(color)) }
        }
        val archive = File(mangaDir, DownloadProvider.getChapterDirName(chapters[1].name, null, chapters[1].url, ascii) + ".cbz")
        ZipOutputStream(archive.outputStream()).use { zip ->
            PAGE_COLORS.forEachIndexed { index, color ->
                zip.putNextEntry(ZipEntry("%03d.png".format(index + 1)))
                zip.write(png(color))
                zip.closeEntry()
            }
        }
        try {
            assertTrue("the adoption did not start", graph.adoptionScheduler.start(Uri.fromFile(tree).toString()))
            var infos: List<WorkInfo> = emptyList()
            InPlaceUpgrade.await(ADOPTION_TIMEOUT_MILLIS, { "the adoption never finished: $infos" }) {
                infos = graph.workManager.getWorkInfosForUniqueWork(AdoptionWorker.WORK_NAME).get()
                infos.isNotEmpty() && infos.all { it.state.isFinished }
            }
            assertEquals(WorkInfo.State.SUCCEEDED, infos.single().state)
            val summary = checkNotNull(graph.adoptionSummaryStore.summary.value) { "the adoption stored no summary" }
            assertEquals("the adoption copied another number of chapters: $summary", 2, summary.copied)
            assertTrue("an adopted chapter has no manifest row", chapters.all { graph.downloadManifest.isDownloaded(it.id) })
        } finally {
            tree.deleteRecursively()
        }
    }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val STAGE_SEED = "seed"
        const val STAGE_HOLD = "hold"

        /** The brief's floor for the generated library. */
        const val MIN_ENTRIES = 500L

        const val UPDATE_INTERVAL_HOURS = 12
        const val PORTRAIT_COLUMNS = 4

        const val FIXTURE_APK_COPY = "saikou-upgrade-fixture.apk"
        const val HOLD_STATUS_PATH = "/hold"
        const val CRASH_MESSAGE = "in-place upgrade check fixture"

        /** No provider serves this id; the adoption stub is this check's alone. */
        const val ADOPTED_SOURCE_ID = 9_000_000_000_000_000_005L
        const val ADOPTED_SOURCE_NAME = "Upgrade Adopted"
        const val ADOPTED_SOURCE_LANG = "en"
        const val ADOPTED_UPSTREAM_DIR = "Upgrade Adopted (EN)"
        const val ADOPTED_TITLE = "Saikou upgrade check adopted entry"
        val PAGE_COLORS = listOf(0xFF402020.toInt(), 0xFF204020.toInt(), 0xFF202040.toInt())
        const val PAGE_WIDTH = 64
        const val PAGE_HEIGHT = 96

        const val SEED_TIMEOUT_MILLIS = 1_500_000L
        const val DOWNLOAD_TIMEOUT_MILLIS = 120_000L
        const val ADOPTION_TIMEOUT_MILLIS = 120_000L
        const val HOLD_TIMEOUT_MILLIS = 120_000L
    }
}
