package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.tracking.anilist.AnilistClient
import com.shinsaikou.tracking.anilist.AnilistEndpoints
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

/**
 * AniList's client and addresses, which only `:app` can read from `BuildConfig`, on
 * [MyAnimeListBindings]' pattern. Every build sends AniList's calls to AniList: no test base is
 * bound, since no device test drives AniList yet. The tracker asks for the endpoints through a
 * provider on each call, so one can be added the way MyAnimeList's was without touching it.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object AnilistBindings {

    @Provides
    fun provideAnilistEndpoints(): AnilistEndpoints = AnilistEndpoints.ANILIST

    /** From `saikou.anilist.client.id` (or `SAIKOU_ANILIST_CLIENT_ID`) at build time, empty when absent. */
    @Provides
    fun provideAnilistClient(): AnilistClient = AnilistClient(BuildConfig.ANILIST_CLIENT_ID)
}
