package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.tracking.comick.ComickClient
import com.shinsaikou.tracking.comick.ComickEndpoints
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * Comick's client and addresses, which only `:app` can read from `BuildConfig`, on
 * [MangaBakaBindings]' pattern. Every build sends Comick's calls to Comick: no test base is bound,
 * since no device test drives Comick yet. The tracker asks for the endpoints through a provider on
 * each call, so one can be added the way MyAnimeList's was without touching it.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object ComickBindings {

    @Provides
    fun provideComickEndpoints(): ComickEndpoints = ComickEndpoints.COMICK

    /** From `saikou.comick.client.id` (or `SAIKOU_COMICK_CLIENT_ID`) at build time, empty when absent. */
    @Provides
    fun provideComickClient(): ComickClient = ComickClient(BuildConfig.COMICK_CLIENT_ID)
}
