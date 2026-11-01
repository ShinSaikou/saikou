package com.shinsaikou.tracking

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Whether a failed send may have reached the service. Apply shows a send that may have as
 * unanswered, so the owner checks the service before sending again; a connection that never opened
 * sent nothing.
 */
class CompareFailuresTest {

    @Test
    fun `a send whose connection never opened is not unanswered, refused or offline`() {
        val refused = ConnectException("Failed to connect to example.invalid/192.0.2.1:443")
            .apply { initCause(ConnectException("Connection refused")) }

        listOf(refused, UnknownHostException("example.invalid"), ConnectException()).forEach { error ->
            CompareFailures.classify(error, send = true).shouldBeInstanceOf<CompareFailure.Stop>()
                .sendUnanswered shouldBe false
        }
    }

    @Test
    fun `a send that failed after the connection opened is unanswered`() {
        CompareFailures.classify(IOException("unexpected end of stream"), send = true)
            .shouldBeInstanceOf<CompareFailure.Stop>().sendUnanswered shouldBe true
    }
}
