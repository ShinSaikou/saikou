package com.shinsaikou.di

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlin.reflect.KClass

/**
 * The one [WorkerFactory] in the process (ruling R8). Each worker's module puts its own assisted
 * factory into [workerProviders] with `@WorkerKey`; this class only looks the class name up.
 *
 * A name that is not in the map returns null, which is WorkManager's signal to fall back to the
 * default reflective factory. That is what keeps a worker enqueued by a library the app does not
 * own from failing outright.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<WorkerFactory>())
class SaikouWorkerFactory(
    private val workerProviders: Map<KClass<out ListenableWorker>, WorkerInstanceFactory<out ListenableWorker>>,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        val key = runCatching { Class.forName(workerClassName).kotlin }.getOrNull() ?: return null
        return workerProviders[key]?.create(workerParameters)
    }
}
