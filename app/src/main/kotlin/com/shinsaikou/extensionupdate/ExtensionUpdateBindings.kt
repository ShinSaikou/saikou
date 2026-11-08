package com.shinsaikou.extensionupdate

import androidx.work.ListenableWorker
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap

/** The automatic extension updates' worker factory, in the map `SaikouWorkerFactory` answers WorkManager from (R8). */
@BindingContainer
@ContributesTo(AppScope::class)
interface ExtensionUpdateBindings {

    @Binds
    @IntoMap
    @WorkerKey(ExtensionUpdateWorker::class)
    fun bindExtensionUpdateWorkerFactory(factory: ExtensionUpdateWorker.Factory): WorkerInstanceFactory<out ListenableWorker>
}
