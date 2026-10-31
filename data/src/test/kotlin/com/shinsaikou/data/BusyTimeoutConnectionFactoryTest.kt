package com.shinsaikou.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Every connection the app's driver opens waits up to three seconds for a lock, as upstream's do,
 * where SQLite's default fails at once with "database is locked". The driver and its connections
 * are fakes, because the bundled SQLite the product opens does not load on the host.
 */
class BusyTimeoutConnectionFactoryTest {

    private val prepared = mutableListOf<String>()

    private fun connection(failure: Throwable? = null): SQLiteConnection = mockk(relaxed = true) {
        every { prepare(any()) } answers {
            prepared += firstArg<String>()
            failure?.let { throw it }
            mockk(relaxed = true)
        }
    }

    @Test
    fun `each connection it opens sets the busy timeout before it is handed out`() {
        val first = connection()
        val second = connection()
        val driver = mockk<SQLiteDriver> { every { open(any()) } returnsMany listOf(first, second) }
        val factory = BusyTimeoutConnectionFactory(driver)

        factory.createConnection("saikou.db") shouldBeSameInstanceAs first
        factory.createConnection("saikou.db") shouldBeSameInstanceAs second

        prepared shouldContainExactly listOf("PRAGMA busy_timeout = 3000", "PRAGMA busy_timeout = 3000")
        factory.driver shouldBeSameInstanceAs driver
    }

    @Test
    fun `a connection that refuses the pragma is closed and the failure thrown`() {
        val failure = IllegalStateException("fixture: the pragma failed")
        val refusing = connection(failure)
        val factory = BusyTimeoutConnectionFactory(mockk { every { open(any()) } returns refusing })

        shouldThrow<IllegalStateException> { factory.createConnection("saikou.db") } shouldBe failure

        verify(exactly = 1) { refusing.close() }
    }
}
