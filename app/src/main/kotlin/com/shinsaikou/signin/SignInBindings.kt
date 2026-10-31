package com.shinsaikou.signin

import androidx.work.ListenableWorker
import com.shinsaikou.BuildConfig
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.WorkerKey
import com.shinsaikou.sync.google.GoogleClientCredentials
import com.shinsaikou.sync.google.GoogleEndpoints
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provides

/** The sign-in hold's worker factory, in the map `SaikouWorkerFactory` answers WorkManager from (R8). */
@BindingContainer
@ContributesTo(AppScope::class)
interface SignInBindings {

    @Binds
    @IntoMap
    @WorkerKey(SignInHoldWorker::class)
    fun bindSignInHoldWorkerFactory(factory: SignInHoldWorker.Factory): WorkerInstanceFactory<out ListenableWorker>
}

/**
 * Google's client and addresses, which only `:app` can read from `BuildConfig` (phase 3 unit O).
 *
 * The endpoints are one binding for every Google call: Google's constants in a release build, and in
 * a debug build the [GoogleTestBaseSetting] when a test has set one. A release build never reads
 * it at all (fix round 1 item 1): [endpointsFor] does not call for it. Not a singleton, so a client
 * built after a test sets its base uses it. Whatever the endpoints, the build's client goes only to
 * Google's: `GoogleOAuthClient` sends the fixture's test client to any test base.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object GoogleBindings {

    @Provides
    fun provideGoogleEndpoints(testBase: Lazy<GoogleTestBaseSetting>): GoogleEndpoints =
        endpointsFor(debug = BuildConfig.DEBUG) { testBase.value.value }

    /** From `saikou.drive.client.id` and `saikou.drive.client.secret` at build time, empty when absent. */
    @Provides
    fun provideGoogleClientCredentials(): GoogleClientCredentials =
        GoogleClientCredentials(BuildConfig.DRIVE_CLIENT_ID, BuildConfig.DRIVE_CLIENT_SECRET)

    /** Google's constants when [debug] is false, without calling [testBase]; a debug build reads it. */
    internal fun endpointsFor(debug: Boolean, testBase: () -> String?): GoogleEndpoints =
        if (debug) GoogleEndpoints.forBuild(debug = true, testBase = testBase()) else GoogleEndpoints.GOOGLE
}
