package com.shinsaikou.tracking.anilist

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Where AniList's calls go: the authorization page the browser opens and the GraphQL endpoint every
 * other call is sent to, as [com.shinsaikou.tracking.myanimelist.MyAnimeListEndpoints] holds
 * MyAnimeList's. `:app` binds [ANILIST]. A test instance ([at]) swaps only the scheme, host and port
 * of both and keeps every path, so a fake serves the paths AniList does and the code under test is
 * the code that ships.
 */
class AnilistEndpoints private constructor(
    val authorization: HttpUrl,
    val api: HttpUrl,
) {

    /** Whether these are AniList's own endpoints rather than a test base's. */
    val isAniList: Boolean get() = this === ANILIST

    override fun toString(): String =
        if (isAniList) "AnilistEndpoints(AniList)" else "AnilistEndpoints(test base $api)"

    companion object {

        /** AniList's own endpoints (upstream's `BASE_URL` and `API_URL`). */
        val ANILIST = AnilistEndpoints(
            authorization = "https://anilist.co/api/v2/oauth/authorize".toHttpUrl(),
            api = "https://graphql.anilist.co/".toHttpUrl(),
        )

        /** [ANILIST]'s two addresses on [base]'s scheme, host and port. */
        fun at(base: HttpUrl): AnilistEndpoints {
            fun HttpUrl.moved(): HttpUrl = newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
            return AnilistEndpoints(authorization = ANILIST.authorization.moved(), api = ANILIST.api.moved())
        }
    }
}

/**
 * The build's AniList client: `saikou.anilist.client.id` in `local.properties`, or
 * `SAIKOU_ANILIST_CLIENT_ID`, which wins, read by `:app` into `BuildConfig`. The implicit grant needs
 * no secret, so the id is all there is. Empty when absent, and then AniList stays in the graph but is
 * not configured, as MyAnimeList is.
 *
 * The build's id goes only to AniList's own endpoints: on any test base the tracker sends
 * [TEST_BASE_CLIENT_ID] instead.
 */
class AnilistClient(val clientId: String) {

    override fun toString(): String = "AnilistClient(${if (clientId.isBlank()) "blank" else "set"})"

    companion object {
        /** The client id a test base gets; public, and never AniList's. */
        const val TEST_BASE_CLIENT_ID = "saikou-anilist-fixture-client"
    }
}
