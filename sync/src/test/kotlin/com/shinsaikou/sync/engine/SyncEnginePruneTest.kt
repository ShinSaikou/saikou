package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * The three prunes of the change log (phase 3 unit E2, the plan's pruning bullet): after a verified
 * push, up to the pushed seq; at the start of a full export, up to the export's seq; and with no
 * backend configured, the whole log at app start, since the next first push is a full export.
 */
class SyncEnginePruneTest {

    private val backend = InMemorySyncBackend()

    @Test
    fun `a verified push prunes up to the pushed seq, and a write that did not read back prunes nothing`() {
        SyncTestDevice(backend, exported = true).use { device ->
            device.addFavourite(1)
            device.addChapter(10, mangaId = 1)
            backend.dropWrites = true

            shouldThrow<IOException> { device.sync() }
            device.logCount() shouldBe 2

            backend.dropWrites = false
            device.sync().shouldNotBeNull().segmentsPushed shouldBe 1
            device.logCount() shouldBe 0
            // A write after the push stays for the next one.
            device.exec("UPDATE chapters SET read = 1 WHERE _id = 10")
            device.logCount() shouldBe 1
        }
    }

    /** Refuses every write, so a run stops right after the export fixed its seq. */
    private class RefusingBackend(delegate: InMemorySyncBackend) : SyncBackend by delegate {
        override suspend fun create(path: String, content: SyncContent) = throw IOException("Refused $path")
    }

    /** Runs [duringRead] inside the first library read, as a write racing the export would land. */
    private class RacingStore(private val real: SyncStore, private val duringRead: () -> Unit) : SyncStore by real {
        private var raced = false

        override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows {
            if (!raced) {
                raced = true
                duringRead()
            }
            return real.readLibrary(mangaIds)
        }
    }

    @Test
    fun `a full export prunes the log up to its own seq when it starts, and keeps what comes after`() {
        lateinit var device: SyncTestDevice
        device = SyncTestDevice(backend, storeWrapper = { RacingStore(it) { device.exec("UPDATE chapters SET read = 1 WHERE _id = 10") } })
        device.use {
            device.addFavourite(1)
            device.addChapter(10, mangaId = 1)
            device.addFavourite(2)
            val latest = device.latestSeq()

            shouldThrow<IOException> { runBlocking { device.engine.run(RefusingBackend(backend)) } }

            val progress = device.preferences.exportProgress.get().shouldNotBeNull()
            progress.lastSeq shouldBe latest
            device.preferences.lastPushedSeq.get() shouldBe latest
            // Only the write made while the export read the library is left, after its seq.
            device.rows("SELECT seq, entity_type FROM sync_change_log", 2) shouldBe
                listOf(listOf((latest + 1).toString(), "chapter"))
        }
    }

    @Test
    fun `with no backend configured the whole log goes at app start, and the next push is a full export`() {
        SyncTestDevice(backend, exported = true).use { device ->
            device.addFavourite(1)
            device.addChapter(10, mangaId = 1)
            device.preferences.backendId.set("memory")

            runBlocking { device.engine.pruneLogWithoutBackend() }
            device.logCount() shouldBe 2

            device.preferences.backendId.set("")
            runBlocking { device.engine.pruneLogWithoutBackend() }
            device.logCount() shouldBe 0
            device.preferences.exportedBackend.get() shouldBe ""

            // Nothing in the log, and still everything travels: the first push is the export.
            val result = device.sync().shouldNotBeNull()
            result.exported shouldBe true
            result.segmentsPushed shouldBe 0
            SyncTestDevice(backend).use { reader ->
                reader.sync()
                reader.library() shouldBe device.library()
            }
        }
    }
}
