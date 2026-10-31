package com.shinsaikou.tracking

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * Sends the call on OkHttp's dispatcher and suspends until its headers arrive; cancelling the
 * coroutine cancels the call, and an answer that arrives after the cancel is closed. `:tracking`'s
 * own copy of the helper `:extensions` and `:sync` each keep, since it may depend on neither.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}

/** [await], then [HttpException] with the status for any answer outside 2xx, closing it. */
internal suspend fun Call.awaitSuccess(): Response {
    val response = await()
    if (!response.isSuccessful) {
        response.close()
        throw HttpException(response.code)
    }
    return response
}

/** Reads the body as [T] and closes the response. */
internal inline fun <reified T> Response.parseAs(json: Json): T = use { json.decodeFromString<T>(it.body.string()) }
