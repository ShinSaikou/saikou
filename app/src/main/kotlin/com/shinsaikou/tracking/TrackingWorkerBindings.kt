package com.shinsaikou.tracking

import androidx.work.ListenableWorker
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap

/** The tracker retry worker's factory, in the map `SaikouWorkerFactory` answers WorkManager from (R8). */
@BindingContainer
@ContributesTo(AppScope::class)
interface TrackingWorkerBindings {

    @Binds
    @IntoMap
    @WorkerKey(DelayedTrackingUpdateWorker::class)
    fun bindDelayedTrackingUpdateWorkerFactory(
        factory: DelayedTrackingUpdateWorker.Factory,
    ): WorkerInstanceFactory<out ListenableWorker>
}
