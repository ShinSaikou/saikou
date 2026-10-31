package com.shinsaikou.downloads

import androidx.work.ListenableWorker
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import com.shinsaikou.downloads.adoption.AdoptionWorker
import com.shinsaikou.downloads.export.DownloadExportWorker
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap

/**
 * Puts the workers' assisted factories into the map `:app`'s WorkerFactory reads (ruling R8): the
 * download queue's, the adoption of upstream's downloads', and the export of the downloads'. The three interfaces are bound where
 * they are implemented, with `@ContributesBinding`.
 */
@BindingContainer
@ContributesTo(AppScope::class)
interface DownloadBindings {

    @Binds
    @IntoMap
    @WorkerKey(DownloadWorker::class)
    fun bindDownloadWorkerFactory(factory: DownloadWorker.Factory): WorkerInstanceFactory<out ListenableWorker>

    @Binds
    @IntoMap
    @WorkerKey(AdoptionWorker::class)
    fun bindAdoptionWorkerFactory(factory: AdoptionWorker.Factory): WorkerInstanceFactory<out ListenableWorker>

    @Binds
    @IntoMap
    @WorkerKey(DownloadExportWorker::class)
    fun bindDownloadExportWorkerFactory(factory: DownloadExportWorker.Factory): WorkerInstanceFactory<out ListenableWorker>
}
