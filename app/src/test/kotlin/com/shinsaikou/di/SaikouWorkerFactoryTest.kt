package com.shinsaikou.di

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

class SaikouWorkerFactoryTest {

    private val worker = mockk<ListenableWorker>()
    private val params = mockk<WorkerParameters>()

    private class FakeWorkerFactory(private val worker: ListenableWorker) :
        WorkerInstanceFactory<ListenableWorker> {
        var calls = 0
        override fun create(params: WorkerParameters): ListenableWorker {
            calls++
            return worker
        }
    }

    private fun factoryFor(
        vararg entries: Pair<KClass<out ListenableWorker>, WorkerInstanceFactory<out ListenableWorker>>,
    ) = SaikouWorkerFactory(entries.toMap())

    @Test
    fun `a mapped worker class is built by its own factory`() {
        val delegate = FakeWorkerFactory(worker)
        val factory = factoryFor(FakeWorker::class to delegate)

        val created = factory.createWorker(mockk(), FakeWorker::class.java.name, params)

        created shouldBe worker
        delegate.calls shouldBe 1
    }

    @Test
    fun `an unmapped worker class falls through to WorkManager's default factory`() {
        val factory = factoryFor(FakeWorker::class to FakeWorkerFactory(worker))

        factory.createWorker(mockk(), OtherWorker::class.java.name, params) shouldBe null
    }

    @Test
    fun `a class name that does not resolve is not an error`() {
        val factory = factoryFor(FakeWorker::class to FakeWorkerFactory(worker))

        factory.createWorker(mockk(), "com.shinsaikou.NoSuchWorker", params) shouldBe null
    }

    /** Only ever used as a map key and as a class name; never instantiated. */
    private abstract class FakeWorker : ListenableWorker(mockk(), mockk())

    private abstract class OtherWorker : ListenableWorker(mockk(), mockk())
}
