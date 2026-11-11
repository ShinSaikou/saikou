package com.shinsaikou.tracking.mangabaka

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Where MangaBaka's calls go, as [com.shinsaikou.tracking.anilist.AnilistEndpoints] holds AniList's:
 * the authorization page the browser opens, the token and revocation endpoints of its OAuth server,
 * and the API's base, under which every call names its version (`v1/series/...`). `:app` binds
 * [MANGABAKA]. A test instance ([at]) swaps only the scheme, host and port of all four and keeps
 * every path, so a fake serves the paths MangaBaka does and the code under test is the code that
 * ships.
 */
class MangaBakaEndpoints private constructor(
    val authorization: HttpUrl,
    val token: HttpUrl,
    val revocation: HttpUrl,
    /** The API's root, without a trailing segment: `v1`, `series` and the rest are path segments on it. */
    val api: HttpUrl,
) {

    /** Whether these are MangaBaka's own endpoints rather than a test base's. */
    val isMangaBaka: Boolean get() = this === MANGABAKA

    override fun toString(): String =
        if (isMangaBaka) "MangaBakaEndpoints(MangaBaka)" else "MangaBakaEndpoints(test base $api)"

    companion object {

        /** MangaBaka's own endpoints, as its OpenID discovery document names them. */
        val MANGABAKA = MangaBakaEndpoints(
            authorization = "https://mangabaka.org/auth/oauth2/authorize".toHttpUrl(),
            token = "https://mangabaka.org/auth/oauth2/token".toHttpUrl(),
            revocation = "https://mangabaka.org/auth/oauth2/revoke".toHttpUrl(),
            api = "https://api.mangabaka.org/".toHttpUrl(),
        )

        /** [MANGABAKA]'s four addresses on [base]'s scheme, host and port. */
        fun at(base: HttpUrl): MangaBakaEndpoints {
            fun HttpUrl.moved(): HttpUrl = newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
            return MangaBakaEndpoints(
                authorization = MANGABAKA.authorization.moved(),
                token = MANGABAKA.token.moved(),
                revocation = MANGABAKA.revocation.moved(),
                api = MANGABAKA.api.moved(),
            )
        }
    }
}

/**
 * The build's MangaBaka client: `saikou.mangabaka.client.id` in `local.properties`, or
 * `SAIKOU_MANGABAKA_CLIENT_ID`, which wins, read by `:app` into `BuildConfig`. A public "Native App"
 * client, which MangaBaka gives no secret: PKCE stands in for one, so the id is all there is. Empty
 * when absent, and then MangaBaka stays in the graph but is not configured, as AniList is.
 *
 * The build's id goes only to MangaBaka's own endpoints: on any test base the tracker sends
 * [TEST_BASE_CLIENT_ID] instead.
 */
class MangaBakaClient(val clientId: String) {

    override fun toString(): String = "MangaBakaClient(${if (clientId.isBlank()) "blank" else "set"})"

    companion object {
        /** The client id a test base gets; public, and never MangaBaka's. */
        const val TEST_BASE_CLIENT_ID = "saikou-mangabaka-fixture-client"
    }
}
