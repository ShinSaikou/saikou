package com.shinsaikou.ui.util

import android.content.Context
import android.content.res.Resources
import com.shinsaikou.core.common.network.CloudflareBudgetExceededException
import com.shinsaikou.core.common.oauth.LoopbackDeniedException
import com.shinsaikou.core.common.oauth.LoopbackPortInUseException
import com.shinsaikou.core.common.oauth.LoopbackTimeoutException
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.download.model.ChapterFolderInUseException
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.manga.model.MangaNotFoundException
import com.shinsaikou.i18n.R
import com.shinsaikou.libraryupdate.FlatLibraryUpdate
import com.shinsaikou.libraryupdate.LibraryUpdateError
import com.shinsaikou.libraryupdate.LibraryUpdateErrorStore
import com.shinsaikou.libraryupdate.SiteStoppedException
import com.shinsaikou.source.SourceNotInstalledException
import com.shinsaikou.sync.SyncSafetyBackupException
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.drive.DriveHttpException
import com.shinsaikou.sync.drive.DriveMalformedResponseException
import com.shinsaikou.sync.drive.DriveStorageFullException
import com.shinsaikou.sync.drive.DriveUnavailableException
import com.shinsaikou.sync.drive.DriveWriteNotStoredException
import com.shinsaikou.sync.format.MalformedSyncFileException
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.google.GoogleAuthRefusedException
import com.shinsaikou.sync.google.GoogleInvalidGrantException
import com.shinsaikou.sync.google.GoogleUnavailableException
import com.shinsaikou.sync.webdav.WebDavHostNameMismatchException
import com.shinsaikou.sync.webdav.WebDavHttpException
import com.shinsaikou.sync.webdav.WebDavMalformedResponseException
import com.shinsaikou.sync.webdav.WebDavRedirectException
import com.shinsaikou.sync.webdav.WebDavUntrustedCertificateException
import com.shinsaikou.sync.webdav.WebDavUnreachableException
import com.shinsaikou.sync.webdav.WebDavUrlRefusedException
import com.shinsaikou.sync.webdav.WebDavWriteNotStoredException
import com.shinsaikou.tracking.TrackerAuthExpiredException
import com.shinsaikou.tracking.TrackerSignInRefusedException
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ErrorTextTest {

    private val resources = mockk<Resources> {
        every { getString(R.string.error_generic) } returns "generic"
        every { getString(R.string.error_no_network) } returns "no network"
        every { getString(R.string.error_timeout) } returns "timeout"
        every { getString(R.string.error_cloudflare_budget) } returns "cloudflare budget"
        every { getString(R.string.error_source_not_installed) } returns "source not installed"
        every { getString(R.string.error_no_chapters) } returns "no chapters"
        every { getString(R.string.error_cloudflare) } returns "cloudflare"
        every { getString(R.string.error_page_list_empty) } returns "page list empty"
        every { getString(R.string.manga_not_found) } returns "entry not found"
        every { getString(R.string.error_download_folder_in_use) } returns "folder in use"
        every { getString(R.string.error_site_stopped_format, "example.invalid") } returns "example.invalid stopped"
        every { getString(R.string.error_site_stopped) } returns "site stopped"
        listOf(403, 404, 429, 503).forEach { code ->
            every { getString(R.string.error_http_format, code) } returns "HTTP error $code"
        }
        every { getString(R.string.update_error_detail_format, "generic", "IllegalStateException") } returns
            "generic (IllegalStateException)"
        every { getString(R.string.error_sync_newer_format) } returns "update saikou"
        every { getString(R.string.error_sync_malformed_file) } returns "malformed file"
        every { getString(R.string.error_sync_signed_out) } returns "signed out"
        every { getString(R.string.error_sync_safety_backup_failed) } returns "safety backup failed"
        every { getString(R.string.error_webdav_untrusted_certificate) } returns "untrusted certificate"
        every { getString(R.string.error_webdav_host_name_mismatch) } returns "host name mismatch"
        every { getString(R.string.error_webdav_write_not_stored) } returns "write not stored"
        every { getString(R.string.error_webdav_malformed_response) } returns "malformed response"
        every { getString(R.string.error_webdav_http_format, 507) } returns "webdav HTTP 507"
        every { getString(R.string.error_webdav_redirect_format, "http://elsewhere.example.invalid") } returns
            "redirect to http://elsewhere.example.invalid"
        every { getString(R.string.error_webdav_redirect) } returns "redirect"
        every { getString(R.string.error_webdav_url_cleartext) } returns "url cleartext"
        every { getString(R.string.error_webdav_url_not_base) } returns "url not base"
        every { getString(R.string.error_webdav_url_not_a_url) } returns "url not a url"
        every { getString(R.string.error_webdav_unreachable) } returns "server did not answer"
        every { getString(R.string.error_oauth_port_in_use_format, 18796) } returns "port 18796 in use"
        every { getString(R.string.error_oauth_port_in_use) } returns "port in use"
        every { getString(R.string.error_oauth_timed_out) } returns "sign-in timed out"
        every { getString(R.string.error_oauth_denied_format, "access_denied") } returns "denied (access_denied)"
        every { getString(R.string.error_oauth_denied) } returns "denied"
        every { getString(R.string.error_google_invalid_grant) } returns "google signed out"
        every { getString(R.string.error_google_auth_refused_format, "invalid_client") } returns "google refused (invalid_client)"
        every { getString(R.string.error_google_auth_refused) } returns "google refused"
        every { getString(R.string.error_google_unavailable_format, 503) } returns "google HTTP 503"
        every { getString(R.string.error_google_unavailable) } returns "google did not answer"
        every { getString(R.string.error_drive_storage_full) } returns "drive full"
        every { getString(R.string.error_drive_malformed_response) } returns "drive malformed"
        every { getString(R.string.error_drive_http_format, "400 badRequest") } returns "drive HTTP 400 badRequest"
        every { getString(R.string.error_drive_http_format, "404") } returns "drive HTTP 404"
        every { getString(R.string.error_drive_http) } returns "drive error"
        every { getString(R.string.error_drive_write_not_stored) } returns "drive write not stored"
        every { getString(R.string.error_drive_unavailable_format, 503) } returns "drive HTTP 503"
        every { getString(R.string.error_drive_unavailable) } returns "drive did not answer"
        every { getString(R.string.error_tracker_sign_in_refused_format, "invalid_grant") } returns "tracker refused (invalid_grant)"
        every { getString(R.string.error_tracker_sign_in_refused) } returns "tracker refused"
        every { getString(R.string.error_tracker_auth_expired) } returns "tracker sign in again"
    }

    /**
     * The browser sign-in's failures (phase 3 unit O): the loopback listener's, matched by class, and
     * the Google client's and a tracker's (unit T1), each declaring its key. Each reads the same from
     * its stored key and detail.
     */
    private val signInCases = listOf(
        Case(LoopbackPortInUseException(18796, BindException("fixture")), ErrorKey.OAUTH_PORT_IN_USE, "port 18796 in use"),
        Case(LoopbackTimeoutException(5.minutes), ErrorKey.OAUTH_TIMED_OUT, "sign-in timed out"),
        Case(LoopbackDeniedException("access_denied"), ErrorKey.OAUTH_DENIED, "denied (access_denied)"),
        Case(GoogleInvalidGrantException("refresh_token"), ErrorKey.GOOGLE_INVALID_GRANT, "google signed out"),
        Case(GoogleAuthRefusedException("invalid_client", 401), ErrorKey.GOOGLE_AUTH_REFUSED, "google refused (invalid_client)"),
        Case(GoogleUnavailableException(503), ErrorKey.GOOGLE_UNAVAILABLE, "google HTTP 503"),
        Case(GoogleUnavailableException(null, IOException("fixture: dropped")), ErrorKey.GOOGLE_UNAVAILABLE, "google did not answer"),
        Case(TrackerSignInRefusedException(1L, "invalid_grant"), ErrorKey.TRACKER_SIGN_IN_REFUSED, "tracker refused (invalid_grant)"),
        Case(TrackerAuthExpiredException(1L), ErrorKey.TRACKER_AUTH_EXPIRED, "tracker sign in again"),
    )

    /**
     * Sync's failures (phase 3 unit G), each with the text of its own key. The sync run record stores
     * a failure as `ErrorKey.of` and `ErrorKey.detail`, never a message.
     */
    private val syncCases = listOf(
        Case(NewerFormatException(found = 2), ErrorKey.SYNC_NEWER_FORMAT, "update saikou"),
        Case(MalformedSyncFileException("fixture: a cut line"), ErrorKey.SYNC_MALFORMED_FILE, "malformed file"),
        Case(SyncSignedOutException("fixture: 401"), ErrorKey.SYNC_SIGNED_OUT, "signed out"),
        Case(SyncSafetyBackupException(IOException("fixture")), ErrorKey.SYNC_SAFETY_BACKUP_FAILED, "safety backup failed"),
        Case(
            WebDavUntrustedCertificateException("dav.example.invalid", SSLHandshakeException("fixture")),
            ErrorKey.WEBDAV_UNTRUSTED_CERTIFICATE,
            "untrusted certificate",
        ),
        Case(
            WebDavHostNameMismatchException("dav.example.invalid", SSLPeerUnverifiedException("fixture")),
            ErrorKey.WEBDAV_HOST_NAME_MISMATCH,
            "host name mismatch",
        ),
        Case(WebDavWriteNotStoredException("saikou-sync/v1/x", "fixture"), ErrorKey.WEBDAV_WRITE_NOT_STORED, "write not stored"),
        Case(WebDavMalformedResponseException("fixture"), ErrorKey.WEBDAV_MALFORMED_RESPONSE, "malformed response"),
        Case(WebDavHttpException(507, "PUT", "saikou-sync/v1/x"), ErrorKey.WEBDAV_HTTP, "webdav HTTP 507"),
        Case(
            WebDavRedirectException(307, "PUT", "saikou-sync/v1/x", "http", "elsewhere.example.invalid"),
            ErrorKey.WEBDAV_REDIRECT,
            "redirect to http://elsewhere.example.invalid",
        ),
        Case(WebDavRedirectException(302, "GET", "saikou-sync/v1/x", null, null), ErrorKey.WEBDAV_REDIRECT, "redirect"),
        Case(
            WebDavUrlRefusedException(WebDavUrlRefusedException.Reason.CLEARTEXT, "fixture"),
            ErrorKey.WEBDAV_URL_REFUSED,
            "url cleartext",
        ),
        Case(
            WebDavUrlRefusedException(WebDavUrlRefusedException.Reason.NOT_A_BASE_URL, "fixture"),
            ErrorKey.WEBDAV_URL_REFUSED,
            "url not base",
        ),
        Case(
            WebDavUrlRefusedException(WebDavUrlRefusedException.Reason.NOT_A_URL, "fixture"),
            ErrorKey.WEBDAV_URL_REFUSED,
            "url not a url",
        ),
        Case(
            WebDavUnreachableException("dav.example.invalid", ConnectException("fixture")),
            ErrorKey.WEBDAV_UNREACHABLE,
            "server did not answer",
        ),
        // The Drive backend's (unit H).
        Case(DriveStorageFullException("files.create saikou-sync/v1/x"), ErrorKey.DRIVE_STORAGE_FULL, "drive full"),
        Case(DriveMalformedResponseException("fixture: no size"), ErrorKey.DRIVE_MALFORMED_RESPONSE, "drive malformed"),
        Case(DriveHttpException(400, "badRequest", "files.list"), ErrorKey.DRIVE_HTTP, "drive HTTP 400 badRequest"),
        Case(DriveHttpException(404, null, "files.delete"), ErrorKey.DRIVE_HTTP, "drive HTTP 404"),
        Case(DriveWriteNotStoredException("saikou-sync/v1/x", "fixture"), ErrorKey.DRIVE_WRITE_NOT_STORED, "drive write not stored"),
        Case(DriveUnavailableException(503, null), ErrorKey.DRIVE_UNAVAILABLE, "drive HTTP 503"),
        Case(DriveUnavailableException(null, null, IOException("fixture: dropped")), ErrorKey.DRIVE_UNAVAILABLE, "drive did not answer"),
    )

    @Test
    fun `a null throwable returns the generic error message`() {
        ErrorText.of(resources, null) shouldBe "generic"
    }

    @Test
    fun `an unknown host exception returns the no network message`() {
        ErrorText.of(resources, UnknownHostException()) shouldBe "no network"
    }

    @Test
    fun `a connect exception returns the no network message`() {
        ErrorText.of(resources, ConnectException()) shouldBe "no network"
    }

    @Test
    fun `a socket timeout exception returns the timeout message`() {
        ErrorText.of(resources, SocketTimeoutException()) shouldBe "timeout"
    }

    @Test
    fun `a cloudflare budget exceeded exception returns the cloudflare budget message`() {
        val error = CloudflareBudgetExceededException(host = "example.invalid", budget = 30.seconds)

        ErrorText.of(resources, error) shouldBe "cloudflare budget"
    }

    @Test
    fun `an http exception passes its code as the format argument`() {
        ErrorText.of(resources, HttpException(404)) shouldBe "HTTP error 404"
    }

    @Test
    fun `a source not installed exception returns the source not installed message`() {
        ErrorText.of(resources, SourceNotInstalledException(sourceId = 1L)) shouldBe "source not installed"
    }

    @Test
    fun `a no chapters exception returns the no chapters message`() {
        ErrorText.of(resources, NoChaptersException()) shouldBe "no chapters"
    }

    /** The reader closes on this one with a toast through the same resolver (ruling R77 rule 3). */
    @Test
    fun `a page list empty exception returns the page list empty message, not its own`() {
        ErrorText.of(resources, PageListEmptyException(chapterId = 7L)) shouldBe "page list empty"
    }

    @Test
    fun `a throwable named cloudflare bypass exception returns the cloudflare message`() {
        ErrorText.of(resources, CloudflareBypassException()) shouldBe "cloudflare"
    }

    /**
     * The shape production hands over: `CloudflareInterceptor` catches both Cloudflare failures and
     * rethrows each as a plain `IOException` whose message is the network module's own generic text
     * and whose cause is the original. The two tests above give the bare exception, which no screen
     * ever receives.
     */
    @Test
    fun `a budget exceeded exception wrapped by the interceptor returns the cloudflare budget message`() {
        val error = IOException(
            "interceptor text",
            CloudflareBudgetExceededException(host = "example.invalid", budget = 45.seconds),
        )

        ErrorText.of(resources, error) shouldBe "cloudflare budget"
    }

    @Test
    fun `a bypass failure wrapped by the interceptor returns the cloudflare message`() {
        ErrorText.of(resources, IOException("interceptor text", CloudflareBypassException())) shouldBe "cloudflare"
    }

    @Test
    fun `an unrelated cause does not change the wrapper's message`() {
        ErrorText.of(resources, IOException("wrapper detail", IllegalStateException("inner"))) shouldBe
            "wrapper detail"
    }

    @Test
    fun `a throwable with a blank message returns the generic error message`() {
        ErrorText.of(resources, Throwable(" ")) shouldBe "generic"
    }

    @Test
    fun `a throwable with a message returns the message verbatim`() {
        ErrorText.of(resources, Throwable("fixture failure detail")) shouldBe "fixture failure detail"
    }

    @Test
    fun `a throwable with a message of several lines returns its first line that is not blank`() {
        ErrorText.of(resources, Throwable("\n  fixture first line \nfixture second line")) shouldBe "fixture first line"
    }

    @Test
    fun `a coroutine timeout returns the timeout message`() = runTest {
        val watchdog = runCatching { withTimeout(1) { delay(10) } }.exceptionOrNull()

        ErrorText.of(resources, watchdog as TimeoutCancellationException) shouldBe "timeout"
    }

    /**
     * The two call sites of the shared resolver: a snackbar formats the throwable where it was
     * caught, and the update error list stores a key and formats it when shown. Each named failure
     * must read the same in both, the HTTP status code included, and must be the text of the key
     * [ErrorKey.of] gives it. Where the resolver looks through a cause, the failure is also given
     * the way the Cloudflare interceptor hands it on: a plain `IOException` carrying it as its cause.
     */
    @Test
    fun `each named failure reads the same in the update error list as in a snackbar`(
        @TempDir filesDir: File,
    ) = runTest {
        val watchdog = runCatching { withTimeout(1) { delay(10) } }.exceptionOrNull() as TimeoutCancellationException
        val budget = CloudflareBudgetExceededException(host = "example.invalid", budget = 45.seconds)
        fun wrapped(cause: Throwable) = IOException("interceptor text", cause)
        val cases = listOf(
            Case(budget, ErrorKey.CLOUDFLARE_BUDGET, "cloudflare budget"),
            Case(wrapped(budget), ErrorKey.CLOUDFLARE_BUDGET, "cloudflare budget"),
            Case(CloudflareBypassException(), ErrorKey.CLOUDFLARE, "cloudflare"),
            Case(wrapped(CloudflareBypassException()), ErrorKey.CLOUDFLARE, "cloudflare"),
            Case(UnknownHostException("example.invalid"), ErrorKey.NO_NETWORK, "no network"),
            Case(wrapped(UnknownHostException()), ErrorKey.NO_NETWORK, "no network"),
            Case(ConnectException("refused"), ErrorKey.NO_NETWORK, "no network"),
            Case(wrapped(ConnectException()), ErrorKey.NO_NETWORK, "no network"),
            Case(HttpException(403), ErrorKey.HTTP, "HTTP error 403"),
            Case(HttpException(429), ErrorKey.HTTP, "HTTP error 429"),
            Case(HttpException(503), ErrorKey.HTTP, "HTTP error 503"),
            Case(wrapped(HttpException(503)), ErrorKey.HTTP, "HTTP error 503"),
            Case(SocketTimeoutException("Read timed out"), ErrorKey.TIMEOUT, "timeout"),
            Case(wrapped(SocketTimeoutException()), ErrorKey.TIMEOUT, "timeout"),
            Case(watchdog, ErrorKey.TIMEOUT, "timeout"),
            Case(wrapped(watchdog), ErrorKey.TIMEOUT, "timeout"),
            Case(NoChaptersException(), ErrorKey.NO_CHAPTERS, "no chapters"),
            Case(SourceNotInstalledException(sourceId = 1L), ErrorKey.SOURCE_NOT_INSTALLED, "source not installed"),
            Case(PageListEmptyException(chapterId = 1L), ErrorKey.PAGE_LIST_EMPTY, "page list empty"),
            Case(MangaNotFoundException(mangaId = 1L), ErrorKey.MANGA_NOT_FOUND, "entry not found"),
            Case(ChapterFolderInUseException(chapterId = 1L), ErrorKey.DOWNLOAD_FOLDER_IN_USE, "folder in use"),
            Case(SiteStoppedException("example.invalid"), ErrorKey.SITE_STOPPED, "example.invalid stopped"),
        )
        // Every named key has at least one case here or in the sync table; GENERIC differs on
        // purpose and has its own tests.
        (cases + syncCases + signInCases).map { it.key }.toSet() shouldBe ErrorKey.entries.toSet() - ErrorKey.GENERIC

        val stored = storeRows(filesDir, cases.map { it.error })

        stored.map { it.key } shouldContainExactly cases.map { it.key }
        cases.map { ErrorKey.of(it.error) } shouldContainExactly cases.map { it.key }
        cases.map { ErrorText.of(resources, it.error) } shouldContainExactly cases.map { it.text }
        stored.map { ErrorText.of(resources, it) } shouldContainExactly cases.map { it.text }
        // Only a generic row carries a message under its text; a named one's detail is in its text.
        stored.map { ErrorText.messageOf(it) } shouldContainExactly List(cases.size) { null }
    }

    /**
     * A sync failure reads the same where it was caught (a snackbar at sign-in) and from the key and
     * detail the run record stored (the last outcome in Sync settings).
     */
    @Test
    fun `each sync failure reads the same from its stored key and detail as where it was caught`() {
        syncCases.map { ErrorKey.of(it.error) } shouldContainExactly syncCases.map { it.key }
        syncCases.map { ErrorText.of(resources, it.error) } shouldContainExactly syncCases.map { it.text }
        syncCases.map { ErrorText.of(resources, ErrorKey.of(it.error), ErrorKey.detail(it.error)) } shouldContainExactly
            syncCases.map { it.text }
    }

    @Test
    fun `each sign-in failure reads the same from its stored key and detail as where it was caught`() {
        signInCases.map { ErrorKey.of(it.error) } shouldContainExactly signInCases.map { it.key }
        signInCases.map { ErrorText.of(resources, it.error) } shouldContainExactly signInCases.map { it.text }
        signInCases.map { ErrorText.of(resources, ErrorKey.of(it.error), ErrorKey.detail(it.error)) } shouldContainExactly
            signInCases.map { it.text }
        ErrorText.of(resources, ErrorKey.OAUTH_PORT_IN_USE, null) shouldBe "port in use"
        ErrorText.of(resources, ErrorKey.OAUTH_DENIED, null) shouldBe "denied"
        ErrorText.of(resources, ErrorKey.GOOGLE_AUTH_REFUSED, null) shouldBe "google refused"
        ErrorText.of(resources, ErrorKey.TRACKER_SIGN_IN_REFUSED, null) shouldBe "tracker refused"
    }

    /** A site stopped by name reads with its host; one that names no host, and a row stored without a detail, read without. */
    @Test
    fun `a stopped site reads with its host when it has one and without when it has none`() {
        val hostless = SiteStoppedException(null)

        ErrorKey.of(hostless) shouldBe ErrorKey.SITE_STOPPED
        ErrorKey.detail(hostless) shouldBe null
        ErrorText.of(resources, hostless) shouldBe "site stopped"
        ErrorText.of(resources, ErrorKey.SITE_STOPPED, null) shouldBe "site stopped"
        ErrorText.of(resources, ErrorKey.SITE_STOPPED, "example.invalid") shouldBe "example.invalid stopped"
    }

    @Test
    fun `a sync key stored without the detail its text needs reads without it`() {
        ErrorText.of(resources, ErrorKey.WEBDAV_HTTP, null) shouldBe "generic"
        ErrorText.of(resources, ErrorKey.WEBDAV_HTTP, "not a code") shouldBe "generic"
        ErrorText.of(resources, ErrorKey.WEBDAV_REDIRECT, null) shouldBe "redirect"
        ErrorText.of(resources, ErrorKey.WEBDAV_URL_REFUSED, "A_LATER_REASON") shouldBe "url not a url"
        ErrorText.of(resources, ErrorKey.DRIVE_HTTP, null) shouldBe "drive error"
        ErrorText.of(resources, ErrorKey.DRIVE_UNAVAILABLE, "not a code") shouldBe "drive did not answer"
    }

    /** R162 item 8: the source's own explanation is kept, under the generic text. */
    @Test
    fun `an unknown failure in the update error list shows the generic text with the source's message under it`(
        @TempDir filesDir: File,
    ) = runTest {
        val row = storeRows(filesDir, listOf(IllegalStateException("Log in through the browser view first"))).single()

        row.key shouldBe ErrorKey.GENERIC
        ErrorText.of(resources, row) shouldBe "generic"
        ErrorText.messageOf(row) shouldBe "Log in through the browser view first"
    }

    /**
     * A snackbar shows a generic failure's message in place of the generic text (R67 rule 4); the list
     * shows it under that text. Either way it is the same first line, cut to the same length.
     */
    @Test
    fun `an unknown failure's message reads the same in a snackbar as in the update error list`(
        @TempDir filesDir: File,
    ) = runTest {
        val failures = listOf(
            IllegalStateException("  Rate limited, try again in a minute \n\tat a stack line"),
            IOException("\n<html><head><title>Down for maintenance</title>" + "x".repeat(500)),
            IllegalStateException("Plain one-liner"),
        )

        val messages = storeRows(filesDir, failures).map { ErrorText.messageOf(it) }

        messages shouldContainExactly failures.map { ErrorText.of(resources, it) }
        messages[0] shouldBe "Rate limited, try again in a minute"
        messages[1]!!.length shouldBe ErrorKey.MESSAGE_MAX_LENGTH
    }

    @Test
    fun `an unknown failure without a message names its class under the generic text, in both places`(
        @TempDir filesDir: File,
    ) = runTest {
        val row = storeRows(filesDir, listOf(IllegalStateException())).single()

        ErrorText.of(resources, row) shouldBe "generic"
        ErrorText.messageOf(row) shouldBe "IllegalStateException"
        ErrorText.of(resources, IllegalStateException()) shouldBe "generic"
    }

    /** Every build before R162 item 8 stored a generic failure's class name; such a row still reads sensibly. */
    @Test
    fun `a generic row stored with its class name shows it under the generic text`() {
        val row = legacyRow("ignored").copy(key = ErrorKey.GENERIC, detail = "IllegalStateException")

        ErrorText.of(resources, row) shouldBe "generic"
        ErrorText.messageOf(row) shouldBe "IllegalStateException"
        ErrorText.messageOf(row.copy(detail = null)) shouldBe null
        ErrorText.messageOf(row.copy(detail = " ")) shouldBe null
        // The sync run record and a tracker's problems keep the class name beside the text.
        ErrorText.of(resources, ErrorKey.GENERIC, "IllegalStateException") shouldBe "generic (IllegalStateException)"
    }

    @Test
    fun `a row stored before keys were stored shows the text it was stored with`() {
        ErrorText.of(resources, legacyRow("No chapters found")) shouldBe "No chapters found"
        ErrorText.of(resources, legacyRow(" ")) shouldBe "generic"
        ErrorText.messageOf(legacyRow("No chapters found")) shouldBe null
    }

    @Test
    fun `an http row without a readable code shows the generic text`() {
        val row = legacyRow("ignored").copy(key = ErrorKey.HTTP, detail = null)

        ErrorText.of(resources, row) shouldBe "generic"
        ErrorText.of(resources, row.copy(detail = "not a code")) shouldBe "generic"
        ErrorText.of(resources, row.copy(detail = "503")) shouldBe "HTTP error 503"
    }

    @Test
    fun `a download queue row reads a stored key in the current language and stored text as it is`() {
        ErrorText.ofStored(resources, "page_list_empty") shouldBe "page list empty"
        ErrorText.ofStored(resources, "source_not_installed") shouldBe "source not installed"
        // A row written before keys were, and an HTTP failure, which keeps its status as text.
        ErrorText.ofStored(resources, "No pages found") shouldBe "No pages found"
        ErrorText.ofStored(resources, "HTTP error 503") shouldBe "HTTP error 503"
    }

    /** R104 item 3: the queue stores this failure's key; a row stored as English text before reads as it was. */
    @Test
    fun `a download queue row refused a folder in use reads its stored key, and its text from before as stored`() {
        ErrorText.ofStored(resources, "download_folder_in_use") shouldBe "folder in use"
        ErrorText.ofStored(resources, "Another download already has its folder") shouldBe
            "Another download already has its folder"
    }

    /** Writes [errors] through the real store, then reads them back from its file. */
    private suspend fun storeRows(filesDir: File, errors: List<Throwable>): List<LibraryUpdateError> {
        val context = mockk<Context> { every { this@mockk.filesDir } returns filesDir }
        LibraryUpdateErrorStore(context, Json).replace(
            errors.mapIndexed { index, error ->
                val manga = Manga.create().copy(id = index.toLong(), title = "Fixture $index")
                FlatLibraryUpdate.Failure(manga, error)
            },
        )
        return LibraryUpdateErrorStore(context, Json).also { it.load() }.errors.value
    }

    private class Case(val error: Throwable, val key: ErrorKey, val text: String)

    private fun legacyRow(text: String) =
        LibraryUpdateError(mangaId = 1L, title = "Fixture", sourceId = 1L, key = null, detail = text, at = 0L)

    // Matched only by simple name in ErrorKey, so a plain local subclass is enough to exercise that branch.
    private class CloudflareBypassException : Exception()
}
