package com.shinsaikou.extensions.adapter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * An extension built against an API the host lacks fails with a `LinkageError`, an `Error` that a
 * caller catching `Exception` lets through to the thread's crash handler. [abiCall] turns it into
 * an exception; an error about the VM itself is left alone.
 */
class AbiCallsTest {

    @Test
    fun `a linkage error from extension code becomes an exception carrying it`() = runTest {
        listOf(NoSuchMethodError("fixture"), AbstractMethodError("fixture"), NoClassDefFoundError("fixture"))
            .forEach { error ->
                val thrown = shouldThrow<IllegalStateException> { abiCall<Unit> { throw error } }
                // Coroutines may hand back a copy of the exception for its stack trace, so the
                // original is looked for down the chain rather than one level deep.
                generateSequence<Throwable>(thrown) { it.cause }.any { it === error } shouldBe true
            }
    }

    @Test
    fun `a caller catching only Exception now survives a linkage error`() = runTest {
        val result = try {
            abiCall<String> { throw NoSuchMethodError("fixture") }
        } catch (e: Exception) {
            e.shouldBeInstanceOf<IllegalStateException>()
            "caught"
        }

        result shouldBe "caught"
    }

    @Test
    fun `an error about the VM itself is never converted`() = runTest {
        shouldThrow<OutOfMemoryError> { abiCall<Unit> { throw OutOfMemoryError("fixture") } }
        shouldThrow<StackOverflowError> { abiCall<Unit> { throw StackOverflowError("fixture") } }
    }
}
