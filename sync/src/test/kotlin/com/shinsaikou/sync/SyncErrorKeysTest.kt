package com.shinsaikou.sync

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.sync.drive.DriveHttpException
import com.shinsaikou.sync.drive.DriveMalformedResponseException
import com.shinsaikou.sync.drive.DriveStorageFullException
import com.shinsaikou.sync.drive.DriveUnavailableException
import com.shinsaikou.sync.drive.DriveWriteNotStoredException
import com.shinsaikou.sync.format.MalformedSyncFileException
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.webdav.WebDavHostNameMismatchException
import com.shinsaikou.sync.webdav.WebDavHttpException
import com.shinsaikou.sync.webdav.WebDavMalformedResponseException
import com.shinsaikou.sync.webdav.WebDavRedirectException
import com.shinsaikou.sync.webdav.WebDavUntrustedCertificateException
import com.shinsaikou.sync.webdav.WebDavUnreachableException
import com.shinsaikou.sync.webdav.WebDavUrlRefusedException
import com.shinsaikou.sync.webdav.WebDavWriteNotStoredException
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Every failure a sync run, the WebDAV sign-in or the Drive backend names has its own [ErrorKey] (R67,
 * R81 carry-over 6, R88 item 7, unit H item 2), resolved by the one resolver, with the detail its
 * text needs and nothing else.
 */
class SyncErrorKeysTest {

    @Test
    fun `each sync failure resolves to its own key`() {
        val cases = listOf(
            NewerFormatException(found = 2) to ErrorKey.SYNC_NEWER_FORMAT,
            MalformedSyncFileException("fixture: a cut line") to ErrorKey.SYNC_MALFORMED_FILE,
            SyncSignedOutException("fixture: 401") to ErrorKey.SYNC_SIGNED_OUT,
            WebDavUntrustedCertificateException("dav.example.invalid", SSLHandshakeException("fixture")) to
                ErrorKey.WEBDAV_UNTRUSTED_CERTIFICATE,
            WebDavHostNameMismatchException("dav.example.invalid", SSLPeerUnverifiedException("fixture")) to
                ErrorKey.WEBDAV_HOST_NAME_MISMATCH,
            WebDavWriteNotStoredException("saikou-sync/v1/x", "fixture: length 0") to ErrorKey.WEBDAV_WRITE_NOT_STORED,
            WebDavMalformedResponseException("fixture: no multistatus", UnknownHostException()) to
                ErrorKey.WEBDAV_MALFORMED_RESPONSE,
            WebDavHttpException(507, "PUT", "saikou-sync/v1/x") to ErrorKey.WEBDAV_HTTP,
            WebDavRedirectException(308, "GET", "saikou-sync/v1/x", "http", "elsewhere.example.invalid") to
                ErrorKey.WEBDAV_REDIRECT,
            WebDavUrlRefusedException(WebDavUrlRefusedException.Reason.CLEARTEXT, "fixture") to
                ErrorKey.WEBDAV_URL_REFUSED,
            WebDavUnreachableException("dav.example.invalid", ConnectException("fixture")) to
                ErrorKey.WEBDAV_UNREACHABLE,
            DriveStorageFullException("saikou-sync/v1/x") to ErrorKey.DRIVE_STORAGE_FULL,
            DriveMalformedResponseException("fixture: no size") to ErrorKey.DRIVE_MALFORMED_RESPONSE,
            DriveHttpException(400, "badRequest", "files.list") to ErrorKey.DRIVE_HTTP,
            DriveWriteNotStoredException("saikou-sync/v1/x", "fixture: other bytes") to ErrorKey.DRIVE_WRITE_NOT_STORED,
            DriveUnavailableException(503, "backendError") to ErrorKey.DRIVE_UNAVAILABLE,
        )

        cases.map { ErrorKey.of(it.first) } shouldBe cases.map { it.second }
        cases.map { it.second }.toSet().size shouldBe cases.size
    }

    @Test
    fun `a failure's detail is the code, the redirect's scheme and host, or the refusal's reason`() {
        ErrorKey.detail(WebDavHttpException(507, "PUT", "x")) shouldBe "507"
        ErrorKey.detail(WebDavRedirectException(301, "PROPFIND", "x", "https", "moved.example.invalid")) shouldBe
            "https://moved.example.invalid"
        ErrorKey.detail(WebDavRedirectException(302, "GET", "x", null, null)) shouldBe null
        WebDavUrlRefusedException.Reason.entries.map {
            ErrorKey.detail(WebDavUrlRefusedException(it, "fixture"))
        } shouldBe listOf("NOT_A_URL", "NOT_A_BASE_URL", "CLEARTEXT")
        // The certificate's host and the write's path stay out of what is stored.
        ErrorKey.detail(WebDavUntrustedCertificateException("dav.example.invalid", SSLHandshakeException("x"))) shouldBe
            null
        ErrorKey.detail(WebDavWriteNotStoredException("saikou-sync/v1/x", "fixture")) shouldBe null
        ErrorKey.detail(WebDavUnreachableException("dav.example.invalid", UnknownHostException("x"))) shouldBe null
    }

    @Test
    fun `a Drive failure's detail is its status and reason, and never a path`() {
        ErrorKey.detail(DriveHttpException(400, "badRequest", "files.list")) shouldBe "400 badRequest"
        ErrorKey.detail(DriveHttpException(404, null, "files.get")) shouldBe "404"
        ErrorKey.detail(DriveUnavailableException(503, "backendError")) shouldBe "503"
        ErrorKey.detail(DriveUnavailableException(null, null, IOException("fixture"))) shouldBe null
        ErrorKey.detail(DriveStorageFullException("saikou-sync/v1/x")) shouldBe null
        ErrorKey.detail(DriveWriteNotStoredException("saikou-sync/v1/x", "fixture")) shouldBe null
        ErrorKey.detail(DriveMalformedResponseException("fixture")) shouldBe null
    }
}
