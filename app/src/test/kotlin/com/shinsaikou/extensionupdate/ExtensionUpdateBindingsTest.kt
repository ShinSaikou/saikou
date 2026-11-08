package com.shinsaikou.extensionupdate

import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.IntoMap
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The worker reaches WorkManager only through the map `SaikouWorkerFactory` answers from; a worker
 * left out of it is built by WorkManager's default factory, which cannot supply its constructor.
 * The full graph is pinned on a device by `AppGraphTest`; this reads the binding itself.
 */
class ExtensionUpdateBindingsTest {

    private val binding = ExtensionUpdateBindings::class.java.declaredMethods.single { it.name == "bindExtensionUpdateWorkerFactory" }

    @Test
    fun `the worker's factory is bound into the worker map under the worker's class`() {
        val key = binding.getAnnotation(WorkerKey::class.java)

        key.value shouldBe ExtensionUpdateWorker::class
        (binding.getAnnotation(Binds::class.java) != null) shouldBe true
        (binding.getAnnotation(IntoMap::class.java) != null) shouldBe true
    }

    @Test
    fun `the bound factory is the worker's own assisted factory`() {
        binding.parameterTypes.single() shouldBe ExtensionUpdateWorker.Factory::class.java
        WorkerInstanceFactory::class.java.isAssignableFrom(ExtensionUpdateWorker.Factory::class.java) shouldBe true
    }
}
