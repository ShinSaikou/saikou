package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.tracking.mangabaka.MangaBakaClient
import com.shinsaikou.tracking.mangabaka.MangaBakaEndpoints
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * MangaBaka's client and addresses, which only `:app` can read from `BuildConfig`, on
 * [AnilistBindings]' pattern. Every build sends MangaBaka's calls to MangaBaka: no test base is
 * bound, since no device test drives MangaBaka yet. The tracker asks for the endpoints through a
 * provider on each call, so one can be added the way MyAnimeList's was without touching it.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object MangaBakaBindings {

    @Provides
    fun provideMangaBakaEndpoints(): MangaBakaEndpoints = MangaBakaEndpoints.MANGABAKA

    /** From `saikou.mangabaka.client.id` (or `SAIKOU_MANGABAKA_CLIENT_ID`) at build time, empty when absent. */
    @Provides
    fun provideMangaBakaClient(): MangaBakaClient = MangaBakaClient(BuildConfig.MANGABAKA_CLIENT_ID)
}
