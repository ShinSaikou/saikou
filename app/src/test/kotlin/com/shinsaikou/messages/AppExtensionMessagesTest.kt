package com.shinsaikou.messages

import android.content.Context
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.loader.LoadError
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class AppExtensionMessagesTest {

    private val context = mockk<Context> {
        // The resource id is the payload under test: the lookup itself is Android's.
        every { getString(any()) } answers { "string:${firstArg<Int>()}" }
    }
    private val messages = AppExtensionMessages(context)

    @Test
    fun `every install rejection has its own line of text`() {
        val rendered = InstallRejection.entries.map { messages.installRejected(it) }

        rendered shouldHaveSize InstallRejection.entries.size
        rendered.toSet() shouldHaveSize InstallRejection.entries.size
        rendered.none { it.isBlank() } shouldBe true
    }

    @Test
    fun `every load error has its own line of text`() {
        val rendered = LoadError.entries.map { messages.loadError(it) }

        rendered shouldHaveSize LoadError.entries.size
        rendered.toSet() shouldHaveSize LoadError.entries.size
        rendered.none { it.isBlank() } shouldBe true
    }

    @Test
    fun `the two enums never share a string`() {
        val installs = InstallRejection.entries.map { messages.installRejected(it) }.toSet()
        val loads = LoadError.entries.map { messages.loadError(it) }.toSet()

        installs intersect loads shouldBe emptySet()
    }
}
