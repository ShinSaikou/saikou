package com.shinsaikou.sync

import androidx.work.ListenableWorker
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap

/**
 * The sync worker's factory and the sign-in expiry notice's, in the map `SaikouWorkerFactory` answers
 * WorkManager from (R8).
 */
@BindingContainer
@ContributesTo(AppScope::class)
interface SyncBindings {

    @Binds
    @IntoMap
    @WorkerKey(SyncWorker::class)
    fun bindSyncWorkerFactory(factory: SyncWorker.Factory): WorkerInstanceFactory<out ListenableWorker>

    @Binds
    @IntoMap
    @WorkerKey(SyncExpiryNoticeWorker::class)
    fun bindSyncExpiryNoticeWorkerFactory(factory: SyncExpiryNoticeWorker.Factory): WorkerInstanceFactory<out ListenableWorker>
}
