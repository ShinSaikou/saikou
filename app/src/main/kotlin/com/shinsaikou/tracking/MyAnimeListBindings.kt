package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.tracking.myanimelist.MyAnimeListClient
import com.shinsaikou.tracking.myanimelist.MyAnimeListEndpoints
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * MyAnimeList's client and addresses, which only `:app` can read from `BuildConfig` (phase 3 unit
 * T2), on `GoogleBindings`' pattern.
 *
 * The endpoints are one binding for every MyAnimeList call: MyAnimeList's own in a release build,
 * and in a debug build the [MyAnimeListTestBaseSetting] when a test has set one. A release build
 * never reads it: [endpointsFor] does not call for it. Not a singleton, and the tracker asks for it
 * through a provider on each call, so a base set after the graph is built takes effect at once, and
 * whether MyAnimeList is configured follows it, as `DriveAvailability` follows Google's.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object MyAnimeListBindings {

    @Provides
    fun provideMyAnimeListEndpoints(testBase: Lazy<MyAnimeListTestBaseSetting>): MyAnimeListEndpoints =
        endpointsFor(debug = BuildConfig.DEBUG) { testBase.value.value }

    /** From `saikou.mal.client.id` (or `SAIKOU_MAL_CLIENT_ID`) at build time, empty when absent. */
    @Provides
    fun provideMyAnimeListClient(): MyAnimeListClient = MyAnimeListClient(BuildConfig.MAL_CLIENT_ID)

    /** MyAnimeList's endpoints when [debug] is false, without calling [testBase]; a debug build reads it. */
    internal fun endpointsFor(debug: Boolean, testBase: () -> String?): MyAnimeListEndpoints =
        if (debug) {
            MyAnimeListEndpoints.forBuild(debug = true, testBase = testBase())
        } else {
            MyAnimeListEndpoints.MYANIMELIST
        }
}
