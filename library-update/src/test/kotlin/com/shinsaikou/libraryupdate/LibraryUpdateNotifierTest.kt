package com.shinsaikou.libraryupdate

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.core.common.preferences.SecurityPreferences
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * The new chapters notifications of a run. Android drops an app's notifications past about fifty,
 * so a run that updated more entries than that lost some at random; and the per-entry ids kept 15
 * bits of the entry's id, so two entries 32,768 ids apart replaced each other's notification. The
 * platform's notification classes are stubs on the JVM, so the builder's `build` and the manager
 * are mocks and the test reads the ids posted.
 */
class LibraryUpdateNotifierTest {

    private val manager = mockk<NotificationManagerCompat>(relaxed = true)
    private val posted = mutableListOf<Int>()
    private val notifier = LibraryUpdateNotifier(
        context = mockk<Context>(relaxed = true),
        securityPreferences = SecurityPreferences(InMemoryPreferenceStore()),
        intents = mockk<LibraryUpdateIntents> {
            every { openUpdates() } returns null
            every { openChapter(any(), any()) } returns null
            every { openManga(any()) } returns null
            every { markChaptersRead(any(), any(), any()) } returns null
        },
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns manager
        every { manager.areNotificationsEnabled() } returns true
        every { manager.notify(any<Int>(), any()) } answers { posted += firstArg<Int>() }
        mockkConstructor(NotificationCompat.Builder::class)
        every { anyConstructed<NotificationCompat.Builder>().build() } returns mockk<Notification>()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `a run of twenty five entries posts the summary and twenty of them`() {
        val updated = (1L..25L).associate { manga(it) to listOf(Chapter.create().copy(id = it * 10, mangaId = it)) }

        notifier.showNewChapters(updated)

        posted shouldContainExactly listOf(LibraryUpdateNotifier.ID_LIBRARY_COMPLETE) +
            (1L..LibraryUpdateNotifier.MAX_ENTRY_NOTIFICATIONS.toLong()).map(LibraryUpdateNotifier::newChaptersId)
    }

    @Test
    fun `two entries 32768 ids apart get notifications of their own`() {
        LibraryUpdateNotifier.newChaptersId(1L) shouldNotBe LibraryUpdateNotifier.newChaptersId(1L + 32_768L)
    }

    @Test
    fun `an entry's id stays clear of the fixed ids for any entry id`() {
        listOf(0L, 1L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE, -1L).forEach { id ->
            LibraryUpdateNotifier.newChaptersId(id) shouldBeGreaterThan LibraryUpdateNotifier.ID_LIBRARY_SIZE_WARNING
        }
    }

    /** The reader opening an entry clears its notification, and the summary when it was the last. */
    @Test
    fun `dismissing an entry leaves the summary while another entry is still shown`() {
        val cancelled = mutableListOf<Int>()
        every { manager.cancel(any<Int>()) } answers { cancelled += firstArg<Int>() }
        every { manager.activeNotifications } returns listOf(
            shown(LibraryUpdateNotifier.ID_LIBRARY_COMPLETE),
            shown(LibraryUpdateNotifier.newChaptersId(1L)),
            shown(LibraryUpdateNotifier.newChaptersId(2L)),
        )

        notifier.dismissNewChapters(1L)

        cancelled shouldContainExactly listOf(LibraryUpdateNotifier.newChaptersId(1L))
    }

    @Test
    fun `dismissing the last entry takes the summary with it`() {
        val cancelled = mutableListOf<Int>()
        every { manager.cancel(any<Int>()) } answers { cancelled += firstArg<Int>() }
        every { manager.activeNotifications } returns listOf(
            shown(LibraryUpdateNotifier.ID_LIBRARY_COMPLETE),
            shown(LibraryUpdateNotifier.newChaptersId(1L)),
        )

        notifier.dismissNewChapters(1L)

        cancelled shouldContainExactly listOf(
            LibraryUpdateNotifier.ID_LIBRARY_COMPLETE,
            LibraryUpdateNotifier.newChaptersId(1L),
        )
    }

    @Test
    fun `an entry with no notification cancels nothing`() {
        every { manager.activeNotifications } returns listOf(shown(LibraryUpdateNotifier.ID_LIBRARY_COMPLETE))

        notifier.dismissNewChapters(1L)

        verify(exactly = 0) { manager.cancel(any<Int>()) }
    }

    private fun shown(id: Int) = mockk<StatusBarNotification> {
        every { this@mockk.id } returns id
        every { groupKey } returns GROUP_KEY
    }

    private fun manga(id: Long) = Manga.create().copy(id = id, title = "Fixture $id", favorite = true)

    private companion object {
        const val GROUP_KEY = "0|com.shinsaikou|g:com.shinsaikou.NEW_CHAPTERS"
    }
}
