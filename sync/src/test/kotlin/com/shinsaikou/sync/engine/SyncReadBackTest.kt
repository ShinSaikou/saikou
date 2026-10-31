package com.shinsaikou.sync.engine

import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.SyncContent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream

/**
 * One read-back per written file (phase 5 unit SC3, closing R88 carry-over 5 and R89 carry-over 2):
 * the engine reads back and compares what it wrote only through a backend whose own `create` does
 * not ([SyncBackend.createReadsBack] false, the default and the in-memory fake's), and a write such a
 * backend dropped still fails the run. The real backends' single read is counted against the
 * fixture in `WebDavSyncEngineTest` and `DriveSyncEngineTest`.
 */
class SyncReadBackTest {

    private val backend = InMemorySyncBackend()
    private val device = SyncTestDevice(backend)

    @AfterEach
    fun close() {
        device.close()
    }

    /** [delegate], counting every write and read by path, and saying [readsBack] of its own create. */
    private class Counting(private val delegate: InMemorySyncBackend, private val readsBack: Boolean) :
        SyncBackend by delegate {
        val writes = mutableListOf<String>()
        val reads = mutableMapOf<String, Int>()

        override val createReadsBack: Boolean get() = readsBack

        override suspend fun create(path: String, content: SyncContent) {
            writes += path
            delegate.create(path, content)
        }

        override suspend fun <T> read(path: String, block: suspend (InputStream) -> T): T {
            reads[path] = (reads[path] ?: 0) + 1
            return delegate.read(path, block)
        }
    }

    /** A first push over two parts and done.json, then a segment. */
    private fun pushThrough(remote: SyncBackend) = runBlocking {
        device.bulkLibrary(entries = 6, chapters = 999)
        device.engine.run(remote).shouldNotBeNull().exported shouldBe true
        device.exec("UPDATE chapters SET read = 1 WHERE _id = 1011")
        device.engine.run(remote).shouldNotBeNull().segmentsPushed shouldBe 1
    }

    @Test
    fun `a backend that does not check its writes has each file read back once by the engine`() {
        val counting = Counting(backend, readsBack = false)

        pushThrough(counting)

        counting.writes.size shouldBe 4
        // done.json is also read once per run by compaction, to learn what the export covers.
        counting.reads shouldBe counting.writes.associateWith { if (it.endsWith("/done.json")) 1 + 2 else 1 }
    }

    @Test
    fun `a backend whose create reads back has no file read again by the engine`() {
        val counting = Counting(backend, readsBack = true)

        pushThrough(counting)

        counting.writes.size shouldBe 4
        // Only compaction's read of done.json, once per run.
        counting.reads shouldBe counting.writes.filter { it.endsWith("/done.json") }.associateWith { 2 }
    }

    @Test
    fun `a write the in-memory backend drops still fails the run at the engine's read-back`() {
        backend.createReadsBack shouldBe false
        device.addFavourite(1)
        backend.dropWrites = true

        // The read finds no file at the part's path: FileNotFoundException, an IOException.
        shouldThrow<IOException> { device.sync() }

        device.preferences.exportedBackend.get() shouldBe ""
    }
}
