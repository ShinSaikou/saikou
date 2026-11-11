package com.shinsaikou.domain.error

import com.shinsaikou.core.common.network.CloudflareBudgetExceededException
import com.shinsaikou.core.common.network.CloudflareSolveRecentlyFailedException
import com.shinsaikou.core.common.oauth.LoopbackDeniedException
import com.shinsaikou.core.common.oauth.LoopbackPortInUseException
import com.shinsaikou.core.common.oauth.LoopbackTimeoutException
import com.shinsaikou.domain.chapter.model.PageListEmptyException
import com.shinsaikou.domain.download.model.ChapterFolderInUseException
import com.shinsaikou.domain.manga.model.MangaNotFoundException
import com.shinsaikou.source.SourceNotInstalledException
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.NoChaptersException
import java.io.IOException
import java.net.BindException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ErrorKeyTest {

    @Test
    fun `a socket timeout and a coroutine timeout are one key`() = runTest {
        val watchdog = runCatching { withTimeout(1) { delay(10) } }.exceptionOrNull()
        watchdog as TimeoutCancellationException

        ErrorKey.of(SocketTimeoutException("Read timed out")) shouldBe ErrorKey.TIMEOUT
        ErrorKey.of(watchdog) shouldBe ErrorKey.TIMEOUT
    }

    @Test
    fun `the two named failures each have their own key`() {
        ErrorKey.of(NoChaptersException()) shouldBe ErrorKey.NO_CHAPTERS
        ErrorKey.of(SourceNotInstalledException(sourceId = 1L)) shouldBe ErrorKey.SOURCE_NOT_INSTALLED
    }

    /** Upstream closes its reader on an empty page list with `page_list_empty_error`; so does Saikou, by key. */
    @Test
    fun `a chapter with an empty page list has its own key, whatever its message says`() {
        ErrorKey.of(PageListEmptyException(chapterId = 7L)) shouldBe ErrorKey.PAGE_LIST_EMPTY
        ErrorKey.of(IllegalStateException("chapter 7 has no pages")) shouldBe ErrorKey.GENERIC
    }

    /** The reader closes on an entry whose row left while it was open (phase 4 unit BF, R119 carry-over 1). */
    @Test
    fun `an entry whose row left has its own key, whatever its message says`() {
        ErrorKey.of(MangaNotFoundException(mangaId = 7L)) shouldBe ErrorKey.MANGA_NOT_FOUND
        ErrorKey.detail(MangaNotFoundException(mangaId = 7L)) shouldBe null
        ErrorKey.of(IllegalStateException("manga 7 is no longer in the database")) shouldBe ErrorKey.GENERIC
    }

    /** The download queue stores this one's id, so a row reads in the reader's language when shown. */
    @Test
    fun `a chapter whose download folder another download has has its own key, whatever its message says`() {
        ErrorKey.of(ChapterFolderInUseException(chapterId = 7L)) shouldBe ErrorKey.DOWNLOAD_FOLDER_IN_USE
        ErrorKey.detail(ChapterFolderInUseException(chapterId = 7L)) shouldBe null
        ErrorKey.of(IllegalStateException("Another download already has its folder")) shouldBe ErrorKey.GENERIC
    }

    @Test
    fun `the cloudflare, network and http failures each have their own key`() {
        val budget = CloudflareBudgetExceededException(host = "example.invalid", budget = 30.seconds)

        ErrorKey.of(budget) shouldBe ErrorKey.CLOUDFLARE_BUDGET
        ErrorKey.of(CloudflareBypassException()) shouldBe ErrorKey.CLOUDFLARE
        ErrorKey.of(UnknownHostException("example.invalid")) shouldBe ErrorKey.NO_NETWORK
        ErrorKey.of(ConnectException(UNREACHABLE)) shouldBe ErrorKey.NO_NETWORK
        ErrorKey.of(HttpException(503)) shouldBe ErrorKey.HTTP
        ErrorKey.httpCode(HttpException(503)) shouldBe 503
    }

    /**
     * A refused connection and an unreachable network are both a `ConnectException`, and only the
     * platform's text, which OkHttp's own failure carries as its cause, says which. A refusal means
     * something answered, so the device is online; any other connect failure is still no network.
     */
    @Test
    fun `a refused connection has its own key, and a connection the network could not make is still no network`() {
        val androidRefused = okHttpConnectFailure(ConnectException(ANDROID_REFUSED))
        val jvmRefused = okHttpConnectFailure(ConnectException("Connection refused"))

        ErrorKey.of(androidRefused) shouldBe ErrorKey.CONNECTION_REFUSED
        ErrorKey.of(jvmRefused) shouldBe ErrorKey.CONNECTION_REFUSED
        ErrorKey.of(ConnectException(ANDROID_REFUSED)) shouldBe ErrorKey.CONNECTION_REFUSED
        ErrorKey.of(IOException("interceptor text", androidRefused)) shouldBe ErrorKey.CONNECTION_REFUSED
        ErrorKey.detail(androidRefused) shouldBe null

        ErrorKey.of(okHttpConnectFailure(ConnectException(UNREACHABLE))) shouldBe ErrorKey.NO_NETWORK
        ErrorKey.of(okHttpConnectFailure(ConnectException())) shouldBe ErrorKey.NO_NETWORK
        ErrorKey.of(ConnectException()) shouldBe ErrorKey.NO_NETWORK
        // Only a connect failure's text counts: a refusal named anywhere else is not this key.
        ErrorKey.of(IOException("Connection refused")) shouldBe ErrorKey.GENERIC
    }

    /** OkHttp tries a host's addresses side by side and throws the first failure, the others suppressed under it. */
    @Test
    fun `a refusal from any of a host's addresses is a refused connection`() {
        val unreachableFirst = okHttpConnectFailure(ConnectException(UNREACHABLE)).apply {
            addSuppressed(okHttpConnectFailure(ConnectException(ANDROID_REFUSED)))
        }

        ErrorKey.of(unreachableFirst) shouldBe ErrorKey.CONNECTION_REFUSED
        ErrorKey.of(IOException("interceptor text", unreachableFirst)) shouldBe ErrorKey.CONNECTION_REFUSED

        // The first address timed out and another refused: the host answered, it is not just slow.
        val timeoutFirst = SocketTimeoutException("failed to connect to /192.0.2.1 (port 443) after 10000ms").apply {
            addSuppressed(okHttpConnectFailure(ConnectException(ANDROID_REFUSED)))
        }
        ErrorKey.of(timeoutFirst) shouldBe ErrorKey.CONNECTION_REFUSED
        ErrorKey.of(SocketTimeoutException("failed to connect to /192.0.2.1 (port 443) after 10000ms")) shouldBe
            ErrorKey.TIMEOUT
    }

    /**
     * The Cloudflare interceptor rethrows what it catches as a plain `IOException` carrying the
     * original as its cause, so each of these is also looked for one cause deep.
     */
    @Test
    fun `a cloudflare, network, http or timeout failure wrapped as a cause resolves as itself`() = runTest {
        val watchdog = runCatching { withTimeout(1) { delay(10) } }.exceptionOrNull()
        val budget = CloudflareBudgetExceededException(host = "example.invalid", budget = 45.seconds)
        fun wrapped(cause: Throwable) = IOException("interceptor text", cause)

        ErrorKey.of(wrapped(budget)) shouldBe ErrorKey.CLOUDFLARE_BUDGET
        ErrorKey.of(wrapped(CloudflareBypassException())) shouldBe ErrorKey.CLOUDFLARE
        // A caller refused because the host's last solve just failed reads as that failure.
        ErrorKey.of(wrapped(CloudflareSolveRecentlyFailedException("example.invalid"))) shouldBe ErrorKey.CLOUDFLARE
        ErrorKey.of(wrapped(UnknownHostException())) shouldBe ErrorKey.NO_NETWORK
        ErrorKey.of(wrapped(ConnectException())) shouldBe ErrorKey.NO_NETWORK
        ErrorKey.of(wrapped(HttpException(429))) shouldBe ErrorKey.HTTP
        ErrorKey.httpCode(wrapped(HttpException(429))) shouldBe 429
        ErrorKey.of(wrapped(SocketTimeoutException())) shouldBe ErrorKey.TIMEOUT
        ErrorKey.of(wrapped(watchdog as TimeoutCancellationException)) shouldBe ErrorKey.TIMEOUT
    }

    @Test
    fun `anything else is the generic key, whatever its message says`() {
        ErrorKey.of(IllegalStateException("No chapters found")) shouldBe ErrorKey.GENERIC
        ErrorKey.of(IOException("timeout")) shouldBe ErrorKey.GENERIC
        ErrorKey.of(Throwable()) shouldBe ErrorKey.GENERIC
        ErrorKey.of(IOException("wrapper", IllegalStateException("inner"))) shouldBe ErrorKey.GENERIC
        ErrorKey.httpCode(IllegalStateException("HTTP error 503")) shouldBe null
    }

    @Test
    fun `every key reads back from its persisted id, and an unknown id reads as none`() {
        ErrorKey.entries.map { ErrorKey.fromId(it.id) } shouldContainExactly ErrorKey.entries
        ErrorKey.entries.map { it.id }.toSet().size shouldBe ErrorKey.entries.size
        ErrorKey.fromId("not_a_key") shouldBe null
    }

    /** The ids are on disk in the error list, so a renamed one would turn a stored row generic. */
    @Test
    fun `the persisted ids never change`() {
        ErrorKey.entries.associateWith { it.id } shouldBe mapOf(
            ErrorKey.CLOUDFLARE_BUDGET to "cloudflare_budget",
            ErrorKey.CLOUDFLARE to "cloudflare",
            ErrorKey.CONNECTION_REFUSED to "connection_refused",
            ErrorKey.NO_NETWORK to "no_network",
            ErrorKey.HTTP to "http",
            ErrorKey.TIMEOUT to "timeout",
            ErrorKey.NO_CHAPTERS to "no_chapters",
            ErrorKey.SOURCE_NOT_INSTALLED to "source_not_installed",
            ErrorKey.PAGE_LIST_EMPTY to "page_list_empty",
            ErrorKey.MANGA_NOT_FOUND to "manga_not_found",
            ErrorKey.DOWNLOAD_FOLDER_IN_USE to "download_folder_in_use",
            ErrorKey.SITE_STOPPED to "site_stopped",
            ErrorKey.SYNC_NEWER_FORMAT to "sync_newer_format",
            ErrorKey.SYNC_MALFORMED_FILE to "sync_malformed_file",
            ErrorKey.SYNC_SIGNED_OUT to "sync_signed_out",
            ErrorKey.SYNC_SAFETY_BACKUP_FAILED to "sync_safety_backup_failed",
            ErrorKey.WEBDAV_UNTRUSTED_CERTIFICATE to "webdav_untrusted_certificate",
            ErrorKey.WEBDAV_HOST_NAME_MISMATCH to "webdav_host_name_mismatch",
            ErrorKey.WEBDAV_WRITE_NOT_STORED to "webdav_write_not_stored",
            ErrorKey.WEBDAV_MALFORMED_RESPONSE to "webdav_malformed_response",
            ErrorKey.WEBDAV_HTTP to "webdav_http",
            ErrorKey.WEBDAV_REDIRECT to "webdav_redirect",
            ErrorKey.WEBDAV_URL_REFUSED to "webdav_url_refused",
            ErrorKey.WEBDAV_UNREACHABLE to "webdav_unreachable",
            ErrorKey.OAUTH_PORT_IN_USE to "oauth_port_in_use",
            ErrorKey.OAUTH_TIMED_OUT to "oauth_timed_out",
            ErrorKey.OAUTH_DENIED to "oauth_denied",
            ErrorKey.GOOGLE_INVALID_GRANT to "google_invalid_grant",
            ErrorKey.GOOGLE_AUTH_REFUSED to "google_auth_refused",
            ErrorKey.GOOGLE_UNAVAILABLE to "google_unavailable",
            ErrorKey.DRIVE_STORAGE_FULL to "drive_storage_full",
            ErrorKey.DRIVE_MALFORMED_RESPONSE to "drive_malformed_response",
            ErrorKey.DRIVE_HTTP to "drive_http",
            ErrorKey.DRIVE_WRITE_NOT_STORED to "drive_write_not_stored",
            ErrorKey.DRIVE_UNAVAILABLE to "drive_unavailable",
            ErrorKey.TRACKER_SIGN_IN_REFUSED to "tracker_sign_in_refused",
            ErrorKey.TRACKER_AUTH_EXPIRED to "tracker_auth_expired",
            ErrorKey.TRACKER_PERMISSION_MISSING to "tracker_permission_missing",
            ErrorKey.GENERIC to "generic",
        )
    }

    /**
     * Sync's failures live in modules `:domain` cannot see, so each declares its key. The declared
     * key comes first: a WebDAV failure caused by a timeout or an unknown host is still the WebDAV
     * failure, and it never reads as generic.
     */
    @Test
    fun `a failure that declares its key is that key, before any rule that looks at its cause`() {
        ErrorKey.of(Keyed(ErrorKey.WEBDAV_MALFORMED_RESPONSE, cause = UnknownHostException())) shouldBe
            ErrorKey.WEBDAV_MALFORMED_RESPONSE
        ErrorKey.of(Keyed(ErrorKey.SYNC_SIGNED_OUT, cause = HttpException(401))) shouldBe ErrorKey.SYNC_SIGNED_OUT
        ErrorKey.of(Keyed(ErrorKey.SYNC_NEWER_FORMAT)) shouldBe ErrorKey.SYNC_NEWER_FORMAT
        // Only at the top: a declared key as a cause is not the wrapper's key.
        ErrorKey.of(IOException("wrapper", Keyed(ErrorKey.SYNC_SIGNED_OUT))) shouldBe ErrorKey.GENERIC
    }

    @Test
    fun `a stored failure keeps the status code, the class name or the declared detail, never a message`() {
        ErrorKey.detail(HttpException(503)) shouldBe "503"
        ErrorKey.detail(IOException("wrapper", HttpException(429))) shouldBe "429"
        ErrorKey.detail(IllegalStateException("raw text")) shouldBe "IllegalStateException"
        ErrorKey.detail(Keyed(ErrorKey.WEBDAV_HTTP, errorDetail = "507")) shouldBe "507"
        ErrorKey.detail(Keyed(ErrorKey.SYNC_SIGNED_OUT)) shouldBe null
        ErrorKey.detail(UnknownHostException("example.invalid")) shouldBe null
    }

    /** R162 item 8: what both surfaces show of an extension's own explanation. */
    @Test
    fun `a failure's message is its first line that is not blank, trimmed`() {
        ErrorKey.message(IllegalStateException("Log in through the browser view")) shouldBe
            "Log in through the browser view"
        ErrorKey.message(IOException("\n  \r\n  Rate limited, try again in a minute  \nat line 2\nat line 3")) shouldBe
            "Rate limited, try again in a minute"
        ErrorKey.message(Exception("Down for maintenance\r\nBack at noon")) shouldBe "Down for maintenance"
    }

    @Test
    fun `a failure with no message, or a blank one, has none`() {
        ErrorKey.message(IllegalStateException()) shouldBe null
        ErrorKey.message(IllegalStateException(" \n\t\n ")) shouldBe null
        ErrorKey.message(IllegalStateException("")) shouldBe null
    }

    @Test
    fun `a long message is cut to the cap, ending in an ellipsis`() {
        val atCap = "a".repeat(ErrorKey.MESSAGE_MAX_LENGTH)
        ErrorKey.message(Exception(atCap)) shouldBe atCap

        val cut = ErrorKey.message(Exception("<html>" + "b".repeat(5_000)))!!
        cut.length shouldBe ErrorKey.MESSAGE_MAX_LENGTH
        cut shouldBe "<html>" + "b".repeat(ErrorKey.MESSAGE_MAX_LENGTH - 7) + "…"

        // A word boundary at the cut leaves no space before the ellipsis.
        ErrorKey.message(Exception("c".repeat(198) + " " + "d".repeat(10))) shouldBe "c".repeat(198) + "…"
    }

    @Test
    fun `a cut never splits a character outside the basic plane`() {
        // U+1F600 is two chars; placed so the cap falls between its halves.
        val message = "e".repeat(ErrorKey.MESSAGE_MAX_LENGTH - 2) + "😀" + "f".repeat(10)

        ErrorKey.message(Exception(message)) shouldBe "e".repeat(ErrorKey.MESSAGE_MAX_LENGTH - 2) + "…"
    }

    /**
     * The loopback listener's failures live in `:core:common`, below `:domain`, so the resolver
     * names their classes. Each is an `IOException`, and a port in use carries a `BindException`: none
     * may read as a network failure, and the port and the provider's error code are what is stored.
     */
    @Test
    fun `the loopback sign-in's failures have their own keys, the port and the provider's code as detail`() {
        val inUse = LoopbackPortInUseException(18796, BindException("Address already in use"))
        val timedOut = LoopbackTimeoutException(5.minutes)
        val denied = LoopbackDeniedException("access_denied")

        listOf(inUse, timedOut, denied).map(ErrorKey::of) shouldContainExactly
            listOf(ErrorKey.OAUTH_PORT_IN_USE, ErrorKey.OAUTH_TIMED_OUT, ErrorKey.OAUTH_DENIED)
        listOf(inUse, timedOut, denied).map(ErrorKey::detail) shouldContainExactly listOf("18796", null, "access_denied")
        // Wrapped, they are not the sign-in's keys: the listener's callers throw them as they are.
        ErrorKey.of(IOException("wrapper", timedOut)) shouldBe ErrorKey.GENERIC
    }

    private class Keyed(
        override val errorKey: ErrorKey,
        override val errorDetail: String? = null,
        cause: Throwable? = null,
    ) : IOException("keyed text", cause), KeyedFailure

    // Matched only by simple name in ErrorKey, so a plain local subclass is enough to exercise that branch.
    private class CloudflareBypassException : Exception()

    private companion object {
        // What Android's socket says, its errno named in the text; the ErrnoException under it is
        // left out, since the JVM tests cannot build one.
        const val ANDROID_REFUSED = "failed to connect to /192.0.2.1 (port 443) from /192.0.2.9 (port 40000) " +
            "after 10000ms: isConnected failed: ECONNREFUSED (Connection refused)"
        const val UNREACHABLE = "failed to connect to /192.0.2.1 (port 443) from /:: (port 0) " +
            "after 10000ms: connect failed: ENETUNREACH (Network is unreachable)"

        /** OkHttp's own connect failure, which carries the platform's as its cause. */
        fun okHttpConnectFailure(platform: ConnectException) =
            ConnectException("Failed to connect to example.invalid/192.0.2.1:443").apply { initCause(platform) }
    }
}
