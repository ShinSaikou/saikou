package com.shinsaikou.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.shinsaikou.reader.ReaderIntents
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The library update's notifications reach the app through these pending intents. A chapter's
 * notification opened the reader as a task of its own, so Back from a cold start left the app; and
 * the request codes kept 16 bits of an id, so two notifications could share one intent and open or
 * mark the other's chapters. The platform's intents are stubs on the JVM, so the intents are mocks
 * and the test reads what reaches `PendingIntent`.
 */
class AppLibraryUpdateIntentsTest {

    private val context = mockk<Context>(relaxed = true)
    private val entryIntent = mockk<Intent>(relaxed = true)
    private val readerIntent = mockk<Intent>(relaxed = true)
    private val stacks = mutableListOf<List<Intent>>()
    private val activityCodes = mutableListOf<Int>()
    private val broadcastCodes = mutableListOf<Int>()

    @BeforeEach
    fun setUp() {
        mockkObject(MainIntents, ReaderIntents, NotificationReceiver.Companion)
        every { MainIntents.newIntent(any(), MainDestination.ENTRY, MANGA_ID) } returns entryIntent
        every { ReaderIntents.newIntent(any(), MANGA_ID, any(), any()) } returns readerIntent
        every { NotificationReceiver.markAsRead(any(), any(), any()) } returns mockk(relaxed = true)
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getActivities(any(), any(), any(), any()) } answers {
            activityCodes += secondArg<Int>()
            stacks += thirdArg<Array<Intent>>().toList()
            mockk()
        }
        every { PendingIntent.getActivity(any(), any(), any(), any()) } answers {
            activityCodes += secondArg<Int>()
            stacks += listOf(thirdArg<Intent>())
            mockk()
        }
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } answers {
            broadcastCodes += secondArg<Int>()
            mockk()
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a chapter opens over its entry so Back stays in the app`() {
        AppLibraryUpdateIntents(context).openChapter(MANGA_ID, 70L)

        stacks.single() shouldContainExactly listOf(entryIntent, readerIntent)
    }

    @Test
    fun `two chapters 65536 ids apart get intents of their own`() {
        val intents = AppLibraryUpdateIntents(context)

        intents.openChapter(MANGA_ID, 1L)
        intents.openChapter(MANGA_ID, 1L + 65_536L)

        activityCodes[0] shouldNotBe activityCodes[1]
    }

    @Test
    fun `two notifications 65536 ids apart get mark read intents of their own`() {
        val intents = AppLibraryUpdateIntents(context)

        intents.markChaptersRead(MANGA_ID, listOf(1L), notificationId = 1_001)
        intents.markChaptersRead(MANGA_ID, listOf(2L), notificationId = 1_001 + 65_536)

        broadcastCodes[0] shouldNotBe broadcastCodes[1]
    }

    private companion object {
        const val MANGA_ID = 7L
    }
}
