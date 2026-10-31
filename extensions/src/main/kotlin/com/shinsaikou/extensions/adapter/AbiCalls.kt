package com.shinsaikou.extensions.adapter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one call into extension bytecode.
 *
 * Always on [Dispatchers.IO]: lib-1.4 extensions do their HTTP call synchronously inside an
 * Observable's `subscribe`, and plenty of lib-1.6 ones call `execute()` from their suspend body,
 * so the caller's dispatcher is never the one that blocks.
 *
 * It translates two kinds of failure. [UninitializedPropertyAccessException] is what reading
 * `SMangaImpl.url`/`title` or `SChapterImpl.url`/`name` throws when the extension never set them. A
 * [LinkageError] (`NoSuchMethodError`, `AbstractMethodError`, a `NoClassDefFoundError`) is an
 * extension built against an API this host lacks: an `Error`, so every caller that rightly catches
 * only `Exception` would let it take the process down, though it says nothing about the process's
 * health. A [VirtualMachineError] such as `OutOfMemoryError` does, and is never caught. Everything
 * else, including the ABI's own [eu.kanade.tachiyomi.network.HttpException], propagates.
 */
internal suspend inline fun <T> abiCall(crossinline block: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UninitializedPropertyAccessException) {
            throw IllegalStateException("Extension returned an incomplete model: ${e.message}", e)
        } catch (e: LinkageError) {
            throw IllegalStateException("Extension does not match this app's extension API: $e", e)
        }
    }
