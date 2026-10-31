package com.shinsaikou.backup

import androidx.work.ListenableWorker
import com.shinsaikou.backup.export.BackupExportWorker
import com.shinsaikou.backup.importer.BackupImportWorker
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * The wire parser and the two worker factories. [ProtoBuf] is the companion default with no builder
 * block, which is exactly what upstream encodes and decodes with; changing it would change the bytes.
 */
@BindingContainer
@ContributesTo(AppScope::class)
interface BackupBindings {

    @Binds
    @IntoMap
    @WorkerKey(BackupImportWorker::class)
    fun bindBackupImportWorkerFactory(
        factory: BackupImportWorker.Factory,
    ): WorkerInstanceFactory<out ListenableWorker>

    @Binds
    @IntoMap
    @WorkerKey(BackupExportWorker::class)
    fun bindBackupExportWorkerFactory(
        factory: BackupExportWorker.Factory,
    ): WorkerInstanceFactory<out ListenableWorker>

    companion object {
        @Provides
        @SingleIn(AppScope::class)
        fun provideProtoBuf(): ProtoBuf = ProtoBuf
    }
}
