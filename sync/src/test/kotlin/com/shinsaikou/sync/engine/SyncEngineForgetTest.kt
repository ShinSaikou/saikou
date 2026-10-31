package com.shinsaikou.sync.engine

import com.shinsaikou.sync.ExportProgress
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.PulledExportParts
import com.shinsaikou.sync.RemoteFile
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Sign-out's part in the engine (phase 3 unit G): `forgetBackend` clears the engine's state for the
 * backend and nothing else, waits for a run in progress, and leaves a device whose next run on the
 * same backend is a fresh join: a new id, a full pull, a full export, and no row lost.
 */
class SyncEngineForgetTest {

    private val backend = InMemorySyncBackend()
    private val devices = mutableListOf<SyncTestDevice>()

    private fun device(exported: Boolean = false): SyncTestDevice =
        SyncTestDevice(backend, exported = exported).also(devices::add)

    @AfterEach
    fun close() {
        devices.forEach { it.close() }
    }

    /** A backend whose listing waits for [release], so a test can hold a run inside its lock. */
    private class GatedBackend(private val delegate: InMemorySyncBackend) : SyncBackend by delegate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun list(prefix: String): List<RemoteFile> {
            entered.complete(Unit)
            release.await()
            return delegate.list(prefix)
        }
    }

    @Test
    fun `forgetting the backend clears exactly the engine's eight preferences`() {
        val device = device()
        val prefs = device.preferences
        prefs.backendId.set("memory")
        prefs.deviceId.set("device-1")
        prefs.pullCursors.set(mapOf("device-2" to 4L))
        prefs.pulledExportParts.set(mapOf("device-2" to PulledExportParts("export-2", parts = 3)))
        prefs.lastPushedSeq.set(9L)
        prefs.exportedBackend.set("memory")
        prefs.exportProgress.set(ExportProgress("memory", "export-1", lastSeq = 9L, parts = 1, lastMangaId = 3L))
        prefs.skippedRows.set(true)
        device.restorePreferences.restoredSinceSync.set(true)
        // A preference the engine does not own, in the same store.
        device.preferenceValues["unrelated"] = "kept"
        val cleared = setOf(
            prefs.backendId, prefs.deviceId, prefs.pullCursors, prefs.pulledExportParts, prefs.lastPushedSeq,
            prefs.exportedBackend, prefs.exportProgress, prefs.skippedRows,
        ).map { it.key() }.toSet()
        val before = device.preferenceValues.keys.toSet()
        // Each of the eight is set, so each one's removal is seen.
        cleared.size shouldBe 8
        before.containsAll(cleared) shouldBe true

        runBlocking { device.engine.forgetBackend() }

        device.preferenceValues.keys shouldBe before - cleared
        device.preferenceValues["unrelated"] shouldBe "kept"
        // The restore flag is the importer's and the engine's run's, never sign-out's.
        device.restorePreferences.restoredSinceSync.get() shouldBe true
    }

    @Test
    fun `forgetting the backend waits for a run in progress and then clears what that run wrote`() {
        val device = device(exported = true)
        device.addFavourite(1)
        device.preferences.backendId.set(backend.id)
        val gated = GatedBackend(backend)

        runBlocking {
            val run = async(Dispatchers.Default) { device.engine.run(gated) }
            gated.entered.await()
            val forget = async(Dispatchers.Default) { device.engine.forgetBackend() }

            // Held by the run: the forget has not cleared the id the run made.
            delay(200)
            forget.isCompleted shouldBe false
            device.deviceId shouldNotBe ""
            device.preferences.backendId.get() shouldBe backend.id

            gated.release.complete(Unit)
            run.await().shouldNotBeNull().segmentsPushed shouldBe 1
            // A hang guard, reached only if the forget never ends (R127 rule 13). The forget is one
            // lock and eight deletes, yet once took more than the 5 s this was on a swapping host
            // (R127 item 16), so the bound is sized for a loaded machine, not an idle one.
            withTimeout(30_000) { forget.await() }
            device.deviceId shouldBe ""
            device.preferences.lastPushedSeq.get() shouldBe 0L
            device.preferences.backendId.get() shouldBe ""
        }
    }

    @Test
    fun `the next run on the same backend is a fresh join with a full export, and no row is lost`() {
        val a = device()
        val b = device()
        a.buildLibrary()
        listOf(a, b, a).forEach { it.sync() }
        // B's local ids differ from A's; a chapter is named by its url.
        b.exec("UPDATE chapters SET bookmark = 1 WHERE url = '/series/1/chapter/11'")
        listOf(b, a).forEach { it.sync() }
        val oldId = b.deviceId
        a.library() shouldBe b.library()

        // Sign-out: the engine forgets; the next start with no backend drops the whole log.
        runBlocking { b.engine.forgetBackend() }
        runBlocking { b.engine.pruneLogWithoutBackend() }
        b.logCount() shouldBe 0
        // Edits made while signed out, logged and then pruned: only the full export can carry them.
        b.exec("UPDATE chapters SET last_page_read = 7 WHERE url = '/series/1/chapter/11'")
        runBlocking { b.engine.pruneLogWithoutBackend() }
        b.logCount() shouldBe 0
        // An edit on the other device meanwhile.
        a.exec("UPDATE mangas SET notes = 'from A while B was out' WHERE _id = 1")
        a.sync()

        // Sign-in to the same backend.
        b.preferences.backendId.set(backend.id)
        val join = b.sync().shouldNotBeNull()

        b.deviceId shouldNotBe ""
        b.deviceId shouldNotBe oldId
        join.exported shouldBe true
        // A full pull: A's export and B's own old id, which now reads as another device's.
        join.exportsApplied shouldBe 2
        b.string("SELECT notes FROM mangas WHERE url = '/series/1'") shouldBe "from A while B was out"

        val back = a.sync().shouldNotBeNull()
        back.exportsApplied shouldBe 1
        a.long("SELECT last_page_read FROM chapters WHERE _id = 11") shouldBe 7L
        a.long("SELECT bookmark FROM chapters WHERE _id = 11") shouldBe 1L
        a.library() shouldBe b.library()
        a.latchesRaised() shouldBe 0
        b.latchesRaised() shouldBe 0
        // The old id's files stay: a device deletes only its own (Q3).
        backend.paths().any { it.startsWith(SyncLayout.devicePrefix(oldId)) } shouldBe true
    }

    @Test
    fun `the log has unpushed changes only while it holds a row after the pushed seq`() {
        val device = device(exported = true)
        runBlocking { device.engine.hasUnpushedChanges() } shouldBe false

        device.addFavourite(1)
        runBlocking { device.engine.hasUnpushedChanges() } shouldBe true

        device.sync().shouldNotBeNull().segmentsPushed shouldBe 1
        runBlocking { device.engine.hasUnpushedChanges() } shouldBe false

        device.exec("UPDATE mangas SET notes = 'later' WHERE _id = 1")
        runBlocking { device.engine.hasUnpushedChanges() } shouldBe true
    }

    @Test
    fun `a full export past the log's rows leaves nothing unpushed`() {
        val device = device()
        device.buildLibrary()
        runBlocking { device.engine.hasUnpushedChanges() } shouldBe true

        device.sync().shouldNotBeNull().exported shouldBe true

        device.preferences.lastPushedSeq.get() shouldBe device.long("SELECT seq FROM sqlite_sequence WHERE name = 'sync_change_log'")
        runBlocking { device.engine.hasUnpushedChanges() } shouldBe false
    }
}
