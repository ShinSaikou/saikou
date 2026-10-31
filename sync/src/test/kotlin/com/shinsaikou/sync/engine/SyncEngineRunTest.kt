package com.shinsaikou.sync.engine

import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.sync.InMemorySyncBackend
import com.shinsaikou.sync.RemoteFile
import com.shinsaikou.sync.SyncBackend
import com.shinsaikou.sync.format.ChapterRow
import com.shinsaikou.sync.format.RemoteName
import com.shinsaikou.sync.format.SegmentReader
import com.shinsaikou.sync.format.SyncLayout
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One run at a time (Q10), and an apply that holds its latches no longer than its transaction. */
class SyncEngineRunTest {

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
    fun `a run that finds the lock held returns at once, and the device id is made once, under the lock`() {
        val backend = InMemorySyncBackend()
        SyncTestDevice(backend, exported = true).use { device ->
            device.addFavourite(1)
            val gated = GatedBackend(backend)

            runBlocking {
                val first = async(Dispatchers.Default) { device.engine.run(gated) }
                gated.entered.await()
                val id = device.deviceId
                id shouldNotBe ""

                // A run that waited for the lock, or ran beside the first, would still be waiting here.
                withTimeout(5_000) { device.engine.run(gated) }.shouldBeNull()
                device.deviceId shouldBe id

                gated.release.complete(Unit)
                first.await().shouldNotBeNull().segmentsPushed shouldBe 1
                device.deviceId shouldBe id
            }
        }
    }

    /** Calls [duringApply] inside every apply's transaction, after the engine's writes. */
    private class HookedStore(private val real: SyncStore, private val duringApply: () -> Unit) : SyncStore by real {
        override suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T =
            real.apply { block().also { duringApply() } }
    }

    /**
     * The plan's test: a write to a row the apply is writing, made from another coroutine while the
     * apply's transaction is open, waits for the commit and then fires its triggers as any local
     * write does, so it is logged and travels. A file database, since only that gives the second
     * thread its own connection, as the app's driver gives each writer.
     */
    @Test
    fun `a write to the same row from another coroutine during an apply is logged`(@TempDir dir: Path) {
        val backend = InMemorySyncBackend()
        val applyThread = Executors.newSingleThreadExecutor()
        val writerThread = Executors.newSingleThreadExecutor()
        var writer: Future<*>? = null
        var writerDoneBeforeCommit: Boolean? = null
        var hookArmed = false
        lateinit var b: SyncTestDevice
        val hook = {
            if (hookArmed) {
                hookArmed = false
                val started = CountDownLatch(1)
                writer = writerThread.submit {
                    started.countDown()
                    b.exec("UPDATE chapters SET bookmark = 1 WHERE _id = 10")
                }
                started.await(5, TimeUnit.SECONDS)
                // Long enough for the write to reach SQLite's lock; it cannot pass it before the commit.
                Thread.sleep(300)
                writerDoneBeforeCommit = writer!!.isDone
            }
        }

        SyncTestDevice(backend, exported = true).use { a ->
            b = SyncTestDevice(
                backend,
                exported = true,
                url = "jdbc:sqlite:${dir.resolve("b.db")}",
                dispatcher = applyThread.asCoroutineDispatcher(),
                storeWrapper = { HookedStore(it, hook) },
            )
            b.use {
                listOf(a, b).forEach { device ->
                    device.addFavourite(1)
                    device.addChapter(10, mangaId = 1)
                    device.sync()
                }
                a.exec("UPDATE chapters SET last_page_read = 3 WHERE _id = 10")
                a.sync()
                b.startReading(a.deviceId, seq = 2)
                val pushedBefore = b.preferences.lastPushedSeq.get()
                hookArmed = true

                b.sync().shouldNotBeNull().segmentsApplied shouldBe 1
                writer.shouldNotBeNull().get(10, TimeUnit.SECONDS)
                // Anything the first run's push missed, because the write landed after it read the log.
                b.sync()

                writerDoneBeforeCommit shouldBe false
                b.long("SELECT last_page_read FROM chapters WHERE _id = 10") shouldBe 3
                b.long("SELECT bookmark FROM chapters WHERE _id = 10") shouldBe 1
                // Logged, so pushed: the one chapter line B wrote since, with the write and the merge.
                val pushed = pushedChapters(backend, b.deviceId, after = pushedBefore)
                pushed.map { it.bookmark to it.lastPageRead } shouldBe listOf(true to 3L)
                b.long("SELECT is_syncing FROM chapters WHERE _id = 10") shouldBe 0
            }
        }
        applyThread.shutdown()
        writerThread.shutdown()
    }

    /** Every chapter line [deviceId] pushed in segments that start at or after [after]. */
    private fun pushedChapters(backend: InMemorySyncBackend, deviceId: String, after: Long): List<ChapterRow> =
        runBlocking { backend.list(SyncLayout.devicePrefix(deviceId)) }
            .mapNotNull { SyncLayout.parse(it.path) as? RemoteName.Segment }
            .filter { it.afterSeq >= after }
            .flatMap { segment ->
                SegmentReader(ByteArrayInputStream(backend.bytes(segment.path)!!)).use { reader ->
                    generateSequence { reader.next() }.filterIsInstance<ChapterRow>().toList()
                }
            }
}
