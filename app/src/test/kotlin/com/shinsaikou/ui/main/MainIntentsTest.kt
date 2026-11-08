package com.shinsaikou.ui.main

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * `MainIntents.targetOf` and `destinationOf` are the one place an intent from outside the app,
 * a notification, a shortcut, a `saikou://` link or a file a manager handed over, is turned into
 * something the activity can act on. The backup detection covered here is the newest piece: a
 * DocumentsProvider hands over an opaque document id rather than a file name, so the URI's own
 * text alone is not always enough to tell a backup from any other file the owner tapped.
 */
class MainIntentsTest {

    // -- file:// paths, named by the URI itself -------------------------------------------------

    @Test
    fun `a file URI ending in the tachibk suffix is a backup`() {
        val intent = viewIntent(uri(path = "/storage/emulated/0/Download/library.tachibk"))

        MainIntents.destinationOf(intent) shouldBe MainDestination.RESTORE_BACKUP
    }

    @Test
    fun `a file URI ending in the legacy proto gz suffix is a backup`() {
        val intent = viewIntent(uri(path = "/storage/emulated/0/Download/library.proto.gz"))

        MainIntents.destinationOf(intent) shouldBe MainDestination.RESTORE_BACKUP
    }

    // -- content:// URIs with an opaque id, named by the provider -------------------------------

    @Test
    fun `a content URI with an opaque id is a backup when the provider names it one`() {
        val data = uri(scheme = ContentResolver.SCHEME_CONTENT, path = "document/msf:1000000042")
        val intent = viewIntent(data)
        val resolver = resolverNaming(data, "library.tachibk")

        MainIntents.destinationOf(intent, resolver) shouldBe MainDestination.RESTORE_BACKUP
    }

    @Test
    fun `a content URI is not asked of the provider when its own name already resolves it`() {
        // The provider is never queried when the URI's own text already carries a recognisable
        // suffix, which is what "tried first because it costs nothing" means.
        val data = uri(scheme = ContentResolver.SCHEME_CONTENT, path = "primary:Download/library.tachibk")
        val intent = viewIntent(data)
        val resolver = mockk<ContentResolver>()

        MainIntents.destinationOf(intent, resolver) shouldBe MainDestination.RESTORE_BACKUP
    }

    // -- neither ---------------------------------------------------------------------------------

    @Test
    fun `a file that is not named like a backup is not treated as one`() {
        val intent = viewIntent(uri(path = "/storage/emulated/0/Download/photo.jpg"))

        MainIntents.destinationOf(intent) shouldBe null
    }

    @Test
    fun `a content URI whose provider names something else is not a backup`() {
        val data = uri(scheme = ContentResolver.SCHEME_CONTENT, path = "document/msf:1000000042")
        val intent = viewIntent(data)
        val resolver = resolverNaming(data, "photo.jpg")

        MainIntents.destinationOf(intent, resolver) shouldBe null
    }

    // -- the extension store link ----------------------------------------------------------------

    @Test
    fun `an extension store link resolves to the EXTENSION_STORE destination`() {
        val data = uri(scheme = MainIntents.SCHEME, host = MainIntents.HOST_EXTENSION_STORE)
        val intent = viewIntent(data)

        MainIntents.destinationOf(intent) shouldBe MainDestination.EXTENSION_STORE
    }

    @Test
    fun `storeUrlOf reads the url query parameter off an extension store link`() {
        val data = uri(
            scheme = MainIntents.SCHEME,
            host = MainIntents.HOST_EXTENSION_STORE,
            queryParameters = mapOf("url" to "https://example.invalid/index.json"),
        )
        val intent = viewIntent(data)

        MainIntents.storeUrlOf(intent) shouldBe "https://example.invalid/index.json"
    }

    @Test
    fun `storeUrlOf is null for a link carrying no url parameter`() {
        val data = uri(scheme = MainIntents.SCHEME, host = MainIntents.HOST_EXTENSION_STORE)
        val intent = viewIntent(data)

        MainIntents.storeUrlOf(intent).shouldBeNull()
    }

    @Test
    fun `storeUrlOf is null for a link whose url parameter is blank`() {
        val data = uri(
            scheme = MainIntents.SCHEME,
            host = MainIntents.HOST_EXTENSION_STORE,
            queryParameters = mapOf("url" to "   "),
        )
        val intent = viewIntent(data)

        MainIntents.storeUrlOf(intent).shouldBeNull()
    }

    // -- a URI that cannot be named at all --------------------------------------------------------

    @Test
    fun `a content URI fails safe as not a backup when there is no resolver to ask`() {
        val data = uri(scheme = ContentResolver.SCHEME_CONTENT, path = "document/msf:1000000042")
        val intent = viewIntent(data)

        MainIntents.destinationOf(intent, resolver = null) shouldBe null
    }

    @Test
    fun `a content URI fails safe as not a backup when the provider cannot answer`() {
        val data = uri(scheme = ContentResolver.SCHEME_CONTENT, path = "document/msf:1000000042")
        val intent = viewIntent(data)
        val resolver = mockk<ContentResolver> {
            every {
                query(data, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            } returns null
        }

        MainIntents.destinationOf(intent, resolver) shouldBe null
    }

    @Test
    fun `a content URI fails safe as not a backup when the provider throws`() {
        val data = uri(scheme = ContentResolver.SCHEME_CONTENT, path = "document/msf:1000000042")
        val intent = viewIntent(data)
        val resolver = mockk<ContentResolver> {
            every {
                query(data, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            } throws RuntimeException("provider gone")
        }

        MainIntents.destinationOf(intent, resolver) shouldBe null
    }

    // -- helpers -----------------------------------------------------------------------------

    // -- the download notifications --------------------------------------------------------------

    /** The action the download notifications' content intent carries, read back to its destination. */
    @Test
    fun `the download queue action resolves to the DOWNLOAD_QUEUE destination`() {
        val intent = mockk<Intent> { every { action } returns MainIntents.ACTION_OPEN_DOWNLOAD_QUEUE }

        MainIntents.destinationOf(intent) shouldBe MainDestination.DOWNLOAD_QUEUE
    }

    /** The adoption notifications open the download settings, where the adoption summary is. */
    @Test
    fun `the download settings action resolves to the DOWNLOAD_SETTINGS destination`() {
        val intent = mockk<Intent> { every { action } returns MainIntents.ACTION_OPEN_DOWNLOAD_SETTINGS }

        MainIntents.destinationOf(intent) shouldBe MainDestination.DOWNLOAD_SETTINGS
    }

    /** Sync's notices open the sync settings (fixes unit sync-notices). */
    @Test
    fun `the sync settings action resolves to the SYNC_SETTINGS destination`() {
        val intent = mockk<Intent> { every { action } returns MainIntents.ACTION_OPEN_SYNC_SETTINGS }

        MainIntents.destinationOf(intent) shouldBe MainDestination.SYNC_SETTINGS
    }

    @Test
    fun `the data settings action resolves to the DATA_SETTINGS destination`() {
        val intent = mockk<Intent> { every { action } returns MainIntents.ACTION_OPEN_DATA_SETTINGS }

        MainIntents.destinationOf(intent) shouldBe MainDestination.DATA_SETTINGS
    }

    /** A tracker's "sign in again" notice opens the tracking settings. */
    @Test
    fun `the tracking settings action resolves to the TRACKING_SETTINGS destination`() {
        val intent = mockk<Intent> { every { action } returns MainIntents.ACTION_OPEN_TRACKING_SETTINGS }

        MainIntents.destinationOf(intent) shouldBe MainDestination.TRACKING_SETTINGS
    }

    /** The Restart action on Settings > Advanced comes back there (F13, settings-restart-and-wording). */
    @Test
    fun `the advanced settings action resolves to the ADVANCED_SETTINGS destination`() {
        val intent = mockk<Intent> { every { action } returns MainIntents.ACTION_OPEN_ADVANCED_SETTINGS }

        MainIntents.destinationOf(intent) shouldBe MainDestination.ADVANCED_SETTINGS
    }

    /** The Restart action on Settings > Experimental comes back there. */
    @Test
    fun `the experimental settings action resolves to the EXPERIMENTAL_SETTINGS destination`() {
        val intent = mockk<Intent> { every { action } returns MainIntents.ACTION_OPEN_EXPERIMENTAL_SETTINGS }

        MainIntents.destinationOf(intent) shouldBe MainDestination.EXPERIMENTAL_SETTINGS
    }

    @Test
    fun `the experimental settings action is its own, not the advanced one`() {
        MainIntents.ACTION_OPEN_EXPERIMENTAL_SETTINGS shouldNotBe MainIntents.ACTION_OPEN_ADVANCED_SETTINGS
    }

    private fun viewIntent(data: Uri): Intent = mockk {
        every { action } returns Intent.ACTION_VIEW
        every { this@mockk.data } returns data
        every { hasExtra(MainIntents.EXTRA_MANGA_ID) } returns false
    }

    private fun uri(
        scheme: String = "file",
        host: String? = null,
        path: String? = null,
        queryParameters: Map<String, String> = emptyMap(),
    ): Uri {
        return mockk<Uri> {
            every { this@mockk.scheme } returns scheme
            every { this@mockk.host } returns host
            // Every fixture in this file names a path, so `hasBackupSuffix`'s `?: data.toString()`
            // fallback is never exercised and toString() is left unstubbed on purpose: mockk cannot
            // intercept a call to Any.toString() made through the recording DSL.
            every { lastPathSegment } returns path?.substringAfterLast('/')
            every { getQueryParameter(any()) } returns null
            queryParameters.forEach { (key, value) ->
                every { getQueryParameter(key) } returns value
            }
        }
    }

    private fun resolverNaming(data: Uri, name: String): ContentResolver {
        val cursor = mockk<Cursor> {
            every { getColumnIndex(OpenableColumns.DISPLAY_NAME) } returns 0
            every { moveToFirst() } returns true
            every { getString(0) } returns name
            every { close() } returns Unit
        }
        return mockk<ContentResolver> {
            every {
                query(data, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            } returns cursor
        }
    }
}
