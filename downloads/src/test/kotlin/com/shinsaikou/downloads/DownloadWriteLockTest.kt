package com.shinsaikou.downloads

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * [DownloadWriteLock]: [DownloadWriteLock.withLock] has the root to itself, and
 * [DownloadWriteLock.withFolderLock] holders share it with each other unless their folder names meet.
 * The test scheduler decides nothing here: a holder stays inside until the test opens its gate.
 */
class DownloadWriteLockTest {

    private val lock = DownloadWriteLock()
    private val events = mutableListOf<String>()

    /** The folder names currently held or awaited, read off the lock's private map. */
    private fun folderCount(): Int {
        val field = DownloadWriteLock::class.java.getDeclaredField("folders").apply { isAccessible = true }
        val map = field.get(lock) as Map<*, *>
        return synchronized(map) { map.size }
    }

    @Test
    fun `withLock waits for every withFolderLock holder`() = runTest {
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(listOf("a")) { releaseA.await() } }
        launch { lock.withFolderLock(listOf("b")) { releaseB.await() } }
        advanceUntilIdle()
        val whole = launch { lock.withLock { events += "whole" } }

        advanceUntilIdle()
        events shouldContainExactly emptyList()

        releaseA.complete(Unit)
        advanceUntilIdle()
        events shouldContainExactly emptyList()

        releaseB.complete(Unit)
        advanceUntilIdle()
        whole.join()
        events shouldContainExactly listOf("whole")
    }

    @Test
    fun `withFolderLock waits while a withLock holder has the root`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withLock { release.await() } }
        advanceUntilIdle()
        val shared = launch { lock.withFolderLock(listOf("a")) { events += "shared" } }

        advanceUntilIdle()
        events shouldContainExactly emptyList()

        release.complete(Unit)
        advanceUntilIdle()
        shared.join()
        events shouldContainExactly listOf("shared")
    }

    @Test
    fun `two withLock holders do not overlap`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withLock { events += "first in"; release.await(); events += "first out" } }
        advanceUntilIdle()
        launch { lock.withLock { events += "second in" } }

        advanceUntilIdle()
        events shouldContainExactly listOf("first in")

        release.complete(Unit)
        advanceUntilIdle()
        events shouldContainExactly listOf("first in", "first out", "second in")
    }

    @Test
    fun `a withFolderLock asked after a waiting withLock does not pass it`() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseWhole = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(listOf("a")) { events += "first in"; releaseFirst.await(); events += "first out" } }
        advanceUntilIdle()
        launch { lock.withLock { events += "whole in"; releaseWhole.await(); events += "whole out" } }
        advanceUntilIdle()
        // A different name, so only the waiting whole-root holder could be what holds it back.
        launch { lock.withFolderLock(listOf("b")) { events += "later in" } }

        advanceUntilIdle()
        events shouldContainExactly listOf("first in")

        releaseFirst.complete(Unit)
        advanceUntilIdle()
        events shouldContainExactly listOf("first in", "first out", "whole in")

        releaseWhole.complete(Unit)
        advanceUntilIdle()
        events shouldContainExactly listOf("first in", "first out", "whole in", "whole out", "later in")
    }

    @Test
    fun `withFolderLock holders with disjoint names run together`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(listOf("a", "b")) { events += "first"; release.await() } }
        launch { lock.withFolderLock(listOf("c", "d")) { events += "second"; release.await() } }

        advanceUntilIdle()

        events.sorted() shouldContainExactly listOf("first", "second")
        release.complete(Unit)
    }

    @Test
    fun `withFolderLock holders with a name in common run one after the other`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(listOf("a", "b")) { events += "first in"; release.await(); events += "first out" } }
        advanceUntilIdle()
        launch { lock.withFolderLock(listOf("b", "c")) { events += "second in" } }

        advanceUntilIdle()
        events shouldContainExactly listOf("first in")

        release.complete(Unit)
        advanceUntilIdle()
        events shouldContainExactly listOf("first in", "first out", "second in")
    }

    @Test
    fun `names are compared as given, so a different case does not meet`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(listOf("Chapter")) { events += "upper"; release.await() } }
        launch { lock.withFolderLock(listOf("chapter")) { events += "lower"; release.await() } }

        advanceUntilIdle()

        events.sorted() shouldContainExactly listOf("lower", "upper")
        release.complete(Unit)
    }

    @Test
    fun `holders sharing several names asked in different orders do not deadlock`() = runTest {
        val orders = listOf(
            listOf("a", "b", "c"),
            listOf("c", "b", "a"),
            listOf("b", "c", "a"),
            listOf("c", "a", "b"),
            listOf("a", "c", "b"),
            listOf("b", "a", "c"),
        )
        // The virtual timeout fires if they wait on each other, since nothing else can move.
        withTimeout(60_000) {
            orders.mapIndexed { i, names ->
                launch {
                    lock.withFolderLock(names) {
                        delay(10)
                        events += "done $i"
                    }
                }
            }.joinAll()
        }

        events.size shouldBe orders.size
        folderCount() shouldBe 0
    }

    @Test
    fun `one holder never waits on itself for a name it repeats`() = runTest {
        withTimeout(60_000) { lock.withFolderLock(listOf("a", "a", "b", "a")) { events += "in" } }

        events shouldContainExactly listOf("in")
        folderCount() shouldBe 0
    }

    @Test
    fun `a withFolderLock with no names still keeps withLock out`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(emptyList()) { events += "shared in"; release.await() } }
        advanceUntilIdle()
        val whole = launch { lock.withLock { events += "whole" } }

        advanceUntilIdle()
        events shouldContainExactly listOf("shared in")

        release.complete(Unit)
        advanceUntilIdle()
        whole.join()
        events shouldContainExactly listOf("shared in", "whole")
    }

    @Test
    fun `no more than the engine's top number of sources hold the lock at once`() = runTest {
        val release = CompletableDeferred<Unit>()
        val holders = DownloadWriteLock.SHARED_PERMITS
        repeat(holders + 1) { i -> launch { lock.withFolderLock(listOf("n$i")) { events += "in $i"; release.await() } } }

        advanceUntilIdle()
        events.size shouldBe holders

        release.complete(Unit)
        advanceUntilIdle()
        events.size shouldBe holders + 1
    }

    @Test
    fun `the folder map is empty after holders finish`() = runTest {
        lock.withFolderLock(listOf("a", "b")) { folderCount() shouldBe 2 }

        folderCount() shouldBe 0
    }

    @Test
    fun `the folder map is empty after a holder's block throws`() = runTest {
        val failure = runCatching { lock.withFolderLock(listOf("a")) { error("fixture") } }.exceptionOrNull()

        (failure is IllegalStateException) shouldBe true
        folderCount() shouldBe 0
        // The name is free again.
        lock.withFolderLock(listOf("a")) { events += "again" }
        events shouldContainExactly listOf("again")
    }

    @Test
    fun `the folder map is empty after a waiting holder is cancelled`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(listOf("a")) { release.await() } }
        advanceUntilIdle()
        val waiting = launch { lock.withFolderLock(listOf("a")) { events += "never" } }
        advanceUntilIdle()
        folderCount() shouldBe 1

        waiting.cancelAndJoin()
        release.complete(Unit)
        advanceUntilIdle()

        events shouldContainExactly emptyList()
        folderCount() shouldBe 0
    }

    @Test
    fun `withLock is free again after a waiting withLock is cancelled`() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { lock.withFolderLock(listOf("a")) { release.await() } }
        advanceUntilIdle()
        val waiting = launch { lock.withLock { events += "never" } }
        advanceUntilIdle()

        waiting.cancelAndJoin()
        release.complete(Unit)
        advanceUntilIdle()
        // Every permit it had taken came back, so a full set of shared holders can enter.
        withTimeout(60_000) {
            lock.withLock { events += "whole" }
            lock.withFolderLock(listOf("a")) { events += "shared" }
        }

        events shouldContainExactly listOf("whole", "shared")
    }
}
