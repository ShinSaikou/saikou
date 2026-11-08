package com.shinsaikou.extensionupdate

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shinsaikou.core.common.preferences.SecurityPreferences
import com.shinsaikou.i18n.R
import com.shinsaikou.ui.main.MainDestination
import com.shinsaikou.ui.main.MainIntents
import io.kotest.matchers.shouldBe
import io.mockk.MockKAnswerScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/**
 * The one notification of a run. The platform's notification classes are stubs on the JVM, so the
 * manager and the builder's `build` are mocks, the context answers each string id with a tag that
 * names the id and its arguments, and the test reads what the builder was given when it was built.
 */
class ExtensionUpdateNotifierTest {

    private val manager = mockk<NotificationManagerCompat>(relaxed = true)
    private val posted = mutableListOf<Pair<Int, Notification>>()
    private val built = mutableListOf<Built>()
    private var pending = Built()
    private val resources = mockk<Resources>()
    private val context = mockk<Context>(relaxed = true)
    private val securityPreferences = SecurityPreferences(InMemoryPreferenceStore())
    private val openIntent = mockk<Intent>(relaxed = true)
    private val pendingIntent = mockk<PendingIntent>()
    private val pendingArguments = mutableListOf<List<Any?>>()
    private val notifier = ExtensionUpdateNotifier(context, securityPreferences)

    @BeforeEach
    fun setUp() {
        every { context.resources } returns resources
        every { context.getString(any()) } answers { "string:${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } answers {
            "string:${firstArg<Int>()}:" + args.drop(1).flatMap { if (it is Array<*>) it.toList() else listOf(it) }.joinToString("|")
        }
        every { resources.getQuantityString(any(), any(), *anyVararg()) } answers {
            "plural:${firstArg<Int>()}:${secondArg<Int>()}"
        }
        mockkStatic(NotificationManagerCompat::class)
        every { NotificationManagerCompat.from(any()) } returns manager
        every { manager.areNotificationsEnabled() } returns true
        every { manager.notify(any<Int>(), any()) } answers { posted += firstArg<Int>() to secondArg<Notification>() }
        mockkConstructor(NotificationCompat.Builder::class)
        // The builder's setters are recorded rather than run: its fields are package-private and the
        // platform classes under them are stubs.
        every { anyConstructed<NotificationCompat.Builder>().setContentTitle(any()) } answers {
            record { title = firstArg<CharSequence>().toString() }
        }
        every { anyConstructed<NotificationCompat.Builder>().setContentText(any()) } answers {
            record { text = firstArg<CharSequence>().toString() }
        }
        every { anyConstructed<NotificationCompat.Builder>().setStyle(any()) } answers {
            record { style = firstArg() }
        }
        every { anyConstructed<NotificationCompat.Builder>().setSmallIcon(any<Int>()) } answers {
            record { icon = firstArg() }
        }
        every { anyConstructed<NotificationCompat.Builder>().setAutoCancel(any()) } answers {
            record { autoCancel = firstArg() }
        }
        every { anyConstructed<NotificationCompat.Builder>().setContentIntent(any()) } answers {
            record { contentIntent = firstArg() }
        }
        every { anyConstructed<NotificationCompat.Builder>().build() } answers {
            // One notification is built at a time, so the setters since the last build are this one's.
            built += pending
            pending = Built()
            mockk<Notification>()
        }
        mockkObject(MainIntents)
        every { MainIntents.newIntent(any(), any()) } returns openIntent
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getActivity(any(), any(), any(), any()) } answers {
            pendingArguments += listOf(secondArg<Int>(), thirdArg<Intent>(), arg<Int>(3))
            pendingIntent
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /** What one builder was given. */
    private class Built {
        var title: String? = null
        var text: String? = null
        var style: NotificationCompat.Style? = null
        var icon: Int? = null
        var autoCancel: Boolean? = null
        var contentIntent: PendingIntent? = null
    }

    private fun MockKAnswerScope<NotificationCompat.Builder, NotificationCompat.Builder>.record(set: Built.() -> Unit): NotificationCompat.Builder {
        pending.set()
        return self as NotificationCompat.Builder
    }

    private fun title(builder: Built) = builder.title

    private fun text(builder: Built) = builder.text

    private fun bigText(builder: Built): String {
        val style = builder.style as NotificationCompat.BigTextStyle
        val field = NotificationCompat.BigTextStyle::class.java.getDeclaredField("mBigText").apply { isAccessible = true }
        return (field.get(style) as CharSequence).toString()
    }

    private fun names(id: Int, list: List<String>) = "string:$id:${list.joinToString(", ")}"

    @Test
    fun `a run that updated and failed nothing posts nothing`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome())

        posted shouldBe emptyList()
        verify(exactly = 0) { manager.notify(any<Int>(), any()) }
    }

    @Test
    fun `a run that only skipped silently posts nothing even when it was busy`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(busy = true))

        posted shouldBe emptyList()
    }

    @Test
    fun `a run that updated something posts one notification under the finished id with the updated title`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(updated = listOf("One")))

        posted.map { it.first } shouldBe listOf(ExtensionUpdateNotifier.ID_FINISHED)
        title(built.single()) shouldBe "string:${R.string.notification_extensions_updated}"
    }

    @Test
    fun `the title is the not-installed title when nothing succeeded`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(failed = listOf("One")))
        notifier.showFinished(ExtensionAutoUpdate.Outcome(needsYou = listOf("Two")))
        notifier.showFinished(ExtensionAutoUpdate.Outcome(failed = listOf("One"), needsYou = listOf("Two")))

        built.map(::title).toSet() shouldBe setOf("string:${R.string.notification_extensions_not_updated}")
        built.size shouldBe 3
    }

    @Test
    fun `one success keeps the updated title beside failures`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(updated = listOf("One"), failed = listOf("Two")))

        title(built.single()) shouldBe "string:${R.string.notification_extensions_updated}"
    }

    @Test
    fun `the three lists become up to three lines, in updated, failed, waiting order`() {
        notifier.showFinished(
            ExtensionAutoUpdate.Outcome(
                updated = listOf("A", "B"),
                failed = listOf("C"),
                needsYou = listOf("D", "E"),
            ),
        )

        val expected = listOf(
            names(R.string.notification_extensions_updated_names, listOf("A", "B")),
            names(R.string.notification_extensions_failed_names, listOf("C")),
            names(R.string.notification_extensions_needs_you_names, listOf("D", "E")),
        )
        bigText(built.single()) shouldBe expected.joinToString("\n")
        text(built.single()) shouldBe expected.first()
    }

    @Test
    fun `a list with nothing in it gets no line`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(failed = listOf("C"), needsYou = listOf("D")))

        val expected = listOf(
            names(R.string.notification_extensions_failed_names, listOf("C")),
            names(R.string.notification_extensions_needs_you_names, listOf("D")),
        )
        bigText(built.single()) shouldBe expected.joinToString("\n")
        text(built.single()) shouldBe expected.first()
    }

    @Test
    fun `with notification content hidden only counts are shown, never a name`() {
        securityPreferences.hideNotificationContent.set(true)

        notifier.showFinished(
            ExtensionAutoUpdate.Outcome(updated = listOf("Secret A", "Secret B"), failed = listOf("Secret C"), needsYou = listOf("Secret D")),
        )

        val expected = listOf(
            "plural:${R.plurals.notification_extensions_updated_count}:2",
            "plural:${R.plurals.notification_extensions_failed_count}:1",
            "plural:${R.plurals.notification_extensions_needs_you_count}:1",
        )
        bigText(built.single()) shouldBe expected.joinToString("\n")
        text(built.single()) shouldBe expected.first()
        title(built.single()) shouldBe "string:${R.string.notification_extensions_updated}"
    }

    @Test
    fun `with notification content hidden a list with nothing in it still gets no count line`() {
        securityPreferences.hideNotificationContent.set(true)

        notifier.showFinished(ExtensionAutoUpdate.Outcome(needsYou = listOf("Secret")))

        bigText(built.single()) shouldBe "plural:${R.plurals.notification_extensions_needs_you_count}:1"
    }

    @Test
    fun `a tap opens Browse Extensions through an immutable pending intent`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(updated = listOf("One")))

        verify(exactly = 1) { MainIntents.newIntent(context, MainDestination.EXTENSIONS) }
        pendingArguments.single() shouldBe listOf(
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        built.single().contentIntent shouldBe pendingIntent
    }

    @Test
    fun `the notification closes itself when tapped`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(updated = listOf("One")))

        built.single().autoCancel shouldBe true
    }

    @Test
    fun `the icon is the done icon after a success and the error icon when nothing was installed`() {
        notifier.showFinished(ExtensionAutoUpdate.Outcome(updated = listOf("One")))
        notifier.showFinished(ExtensionAutoUpdate.Outcome(failed = listOf("One")))

        built.map { it.icon } shouldBe listOf(android.R.drawable.stat_sys_download_done, android.R.drawable.stat_notify_error)
    }

    @Test
    fun `nothing is posted while the app's notifications are off`() {
        every { manager.areNotificationsEnabled() } returns false

        notifier.showFinished(ExtensionAutoUpdate.Outcome(updated = listOf("One")))

        posted shouldBe emptyList()
    }
}
