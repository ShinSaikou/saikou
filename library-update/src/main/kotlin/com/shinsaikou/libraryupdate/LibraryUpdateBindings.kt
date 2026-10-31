package com.shinsaikou.libraryupdate

import androidx.work.ListenableWorker
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap

/**
 * Puts the worker's assisted factory into the map `:app`'s WorkerFactory reads (ruling R8). The
 * module contributes its own worker; nothing here reaches into the app graph.
 */
@BindingContainer
@ContributesTo(AppScope::class)
interface LibraryUpdateBindings {

    @Binds
    @IntoMap
    @WorkerKey(LibraryUpdateWorker::class)
    fun bindLibraryUpdateWorkerFactory(factory: LibraryUpdateWorker.Factory): WorkerInstanceFactory<out ListenableWorker>
}
