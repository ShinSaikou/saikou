package com.shinsaikou.appupdate

import android.os.Build
import androidx.work.ListenableWorker
import com.shinsaikou.BuildConfig
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides

/** The app update's worker factory, in the map `SaikouWorkerFactory` answers WorkManager from (R8). */
@BindingContainer
@ContributesTo(AppScope::class)
interface AppUpdateBindings {

    @Binds
    @IntoMap
    @WorkerKey(AppUpdateWorker::class)
    fun bindAppUpdateWorkerFactory(factory: AppUpdateWorker.Factory): WorkerInstanceFactory<out ListenableWorker>
}

/** What only `:app` can read: the repository and version from `BuildConfig`, the ABI from `Build`. */
@BindingContainer
@ContributesTo(AppScope::class)
object AppUpdateBuildBindings {

    @Provides
    fun provideAppUpdateBuild(): AppUpdateBuild = AppUpdateBuild(
        repository = BuildConfig.UPDATE_REPO,
        versionName = BuildConfig.VERSION_NAME,
        automaticChecks = BuildConfig.APP_UPDATE_AUTO_CHECK,
        primaryAbi = Build.SUPPORTED_ABIS.firstOrNull(),
        apkKind = if (BuildConfig.APP_UPDATE_DEBUG_APK) ApkKind.DEBUG else ApkKind.RELEASE,
    )
}
