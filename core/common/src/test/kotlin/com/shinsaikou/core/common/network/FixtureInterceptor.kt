package com.shinsaikou.core.common.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * Serves canned responses keyed by method and URL and fails on anything else, so a unit test can
 * exercise code that builds an [okhttp3.OkHttpClient] without ever reaching the network. Copy this
 * file into the `src/test` of any module that needs it; contract 01 section 8.1 keeps it here.
 */
class FixtureInterceptor(
    private val fixtures: Map<Key, Fixture> = emptyMap(),
) : Interceptor {

    data class Key(val method: String, val url: String)

    data class Fixture(
        val body: String,
        val code: Int = 200,
        val message: String = "OK",
        val contentType: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
    )

    val requests: MutableList<Key> = mutableListOf()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val key = Key(request.method, request.url.toString())
        requests += key
        val fixture = fixtures[key] ?: throw IOException("no fixture for ${key.method} ${key.url}")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(fixture.code)
            .message(fixture.message)
            .body(fixture.body.toResponseBody(fixture.contentType.toMediaType()))
            .apply { fixture.headers.forEach { (name, value) -> header(name, value) } }
            .build()
    }
}
