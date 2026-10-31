package com.shinsaikou.tracking.myanimelist

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where MyAnimeList's calls go (phase 3 unit T2): the authorization page the browser opens, the
 * token endpoint, and the base of API v2. `:app` binds one instance per call through [forBuild], as
 * `GoogleEndpoints` is bound (R91 item 8): MyAnimeList's own in a release build, and in a debug
 * build a test base when an instrumentation test has set one.
 *
 * A test instance swaps only the scheme, host and port of all three and keeps every path, so the
 * fake serves the paths MyAnimeList does and the code under test is the code that ships.
 */
class MyAnimeListEndpoints private constructor(
    val authorization: HttpUrl,
    val token: HttpUrl,
    /** API v2 without a trailing slash: `manga`, `users` and the rest are path segments on it. */
    val api: HttpUrl,
) {

    /** Whether these are MyAnimeList's own endpoints rather than a test base's. */
    val isMyAnimeList: Boolean get() = this === MYANIMELIST

    override fun toString(): String =
        if (isMyAnimeList) "MyAnimeListEndpoints(MyAnimeList)" else "MyAnimeListEndpoints(test base $api)"

    companion object {

        /** MyAnimeList's own endpoints (upstream's `BASE_OAUTH_URL` and `BASE_API_URL`). */
        val MYANIMELIST = MyAnimeListEndpoints(
            authorization = "https://myanimelist.net/v1/oauth2/authorize".toHttpUrl(),
            token = "https://myanimelist.net/v1/oauth2/token".toHttpUrl(),
            api = "https://api.myanimelist.net/v2".toHttpUrl(),
        )

        /**
         * The endpoints a build uses. A release build ([debug] false) uses [MYANIMELIST] whatever
         * [testBase] says. A debug build uses [at] the test base when one is given and [MYANIMELIST]
         * when it is absent or blank (R29). A test base that is not an `http://` or `https://`
         * address is refused, never read as absent, so a mistyped argument cannot send a test's
         * sign-in to MyAnimeList.
         */
        fun forBuild(debug: Boolean, testBase: String?): MyAnimeListEndpoints {
            if (!debug || testBase.isNullOrBlank()) return MYANIMELIST
            val base = requireNotNull(testBase.trim().toHttpUrlOrNull()) { "Not an http:// or https:// test base" }
            return at(base)
        }

        /** [MYANIMELIST]'s three addresses on [base]'s scheme, host and port. */
        fun at(base: HttpUrl): MyAnimeListEndpoints {
            fun HttpUrl.moved(): HttpUrl = newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
            return MyAnimeListEndpoints(
                authorization = MYANIMELIST.authorization.moved(),
                token = MYANIMELIST.token.moved(),
                api = MYANIMELIST.api.moved(),
            )
        }
    }
}

/**
 * The build's MyAnimeList client (phase 3 unit T2): `saikou.mal.client.id` in `local.properties`, or
 * `SAIKOU_MAL_CLIENT_ID`, which wins, read by `:app` into `BuildConfig`. MyAnimeList issues no secret
 * to the owner's "other" type client, so the id is all there is. Empty when absent, and then
 * MyAnimeList stays in the graph but is not configured.
 *
 * The build's id goes only to MyAnimeList's own endpoints: with any test base in effect the tracker
 * sends [TEST_BASE_CLIENT_ID], the fake's, whoever set the base.
 */
class MyAnimeListClient(val clientId: String) {

    override fun toString(): String = "MyAnimeListClient(${if (clientId.isBlank()) "blank" else "set"})"

    companion object {
        /** The client id the MyAnimeList fake accepts; public, and never MyAnimeList's. */
        const val TEST_BASE_CLIENT_ID = "saikou-mal-fixture-client"
    }
}
