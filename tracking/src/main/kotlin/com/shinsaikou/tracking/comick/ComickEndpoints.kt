package com.shinsaikou.tracking.comick

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Where Comick's calls go, as [com.shinsaikou.tracking.mangabaka.MangaBakaEndpoints] holds
 * MangaBaka's: the authorization page the browser opens and the token endpoint of comick.dev's OAuth
 * server, and the base of its integrations API, under which every call names its path (`me/library/...`).
 * `:app` binds [COMICK]. A test instance ([at]) swaps only the scheme, host and port of all three
 * and keeps every path, so a fake serves the paths Comick does and the code under test is the code
 * that ships.
 *
 * The `resource` every authorize, token and refresh call names is not an address here: it is the
 * API's identifier at Comick's OAuth server, so it stays [ComickApi.RESOURCE] on a test base too.
 */
class ComickEndpoints private constructor(
    val authorization: HttpUrl,
    val token: HttpUrl,
    /** The integrations API's root, ending in `/integrations/v1/`: `me`, `titles` and the rest are path segments on it. */
    val api: HttpUrl,
) {

    /** Whether these are Comick's own endpoints rather than a test base's. */
    val isComick: Boolean get() = this === COMICK

    override fun toString(): String =
        if (isComick) "ComickEndpoints(Comick)" else "ComickEndpoints(test base $api)"

    companion object {

        /** Comick's own endpoints. The API answers apps with JSON; only the website's pages carry a challenge. */
        val COMICK = ComickEndpoints(
            authorization = "https://comick.dev/api/auth/oauth2/authorize".toHttpUrl(),
            token = "https://comick.dev/api/auth/oauth2/token".toHttpUrl(),
            api = "https://api.comick.dev/integrations/v1/".toHttpUrl(),
        )

        /** [COMICK]'s three addresses on [base]'s scheme, host and port. */
        fun at(base: HttpUrl): ComickEndpoints {
            fun HttpUrl.moved(): HttpUrl = newBuilder().scheme(base.scheme).host(base.host).port(base.port).build()
            return ComickEndpoints(
                authorization = COMICK.authorization.moved(),
                token = COMICK.token.moved(),
                api = COMICK.api.moved(),
            )
        }
    }
}

/**
 * The build's Comick client: `saikou.comick.client.id` in `local.properties`, or
 * `SAIKOU_COMICK_CLIENT_ID`, which wins, read by `:app` into `BuildConfig`. A public client with no
 * secret: PKCE stands in for one, so the id is all there is. Empty when absent, and then Comick stays
 * in the graph but is not configured, as AniList and MangaBaka are.
 *
 * The build's id goes only to Comick's own endpoints: on any test base the tracker sends
 * [TEST_BASE_CLIENT_ID] instead.
 */
class ComickClient(val clientId: String) {

    override fun toString(): String = "ComickClient(${if (clientId.isBlank()) "blank" else "set"})"

    companion object {
        /** The client id a test base gets; public, and never Comick's. */
        const val TEST_BASE_CLIENT_ID = "saikou-comick-fixture-client"
    }
}
