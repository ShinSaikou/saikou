package com.shinsaikou.sync

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.di.AppGraph
import com.shinsaikou.signin.GoogleTestBaseSetting
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveSyncBackend
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.junit.Assert.assertEquals
import java.util.UUID

/**
 * A Google sign-in against D's sync fixture with no browser (phase 3 unit H), for the device tests
 * that sign in to Drive: `DriveTokenDeviceTest` and `SyncPairTest` over Drive. The fixture's
 * authorization endpoint is the consent screen already approved: a plain GET of the authorization
 * URL answers 302 to the redirect with the code, which [DriveAccount.signIn] exchanges as the
 * browser's round trip would. The code is never printed or logged.
 *
 * The graph's [GoogleTestBaseSetting] must already name the fixture, in this process: the Drive backend is
 * built only while it does. [fixtureBase] is the only way the device tests set it, and [code]
 * checks the URL it is about to request again, so neither ever sends a request to a Google host,
 * whether the build carries a real client or none.
 */
internal object FixtureGoogleSignIn {

    /** Where the fixture's authorization endpoint sends the code; nothing listens there. */
    const val REDIRECT_URI = "http://127.0.0.1:9/callback"

    /**
     * The hosts D's fixture is reached at: the host's loopback as an emulator sees it, and the
     * loopback itself. Plain HTTP only; the fixture serves no TLS.
     */
    val FIXTURE_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")

    /** [base] when it is an `http` address on one of [FIXTURE_HOSTS]; otherwise a failure. */
    fun fixtureBase(base: String): String {
        val url = base.toHttpUrlOrNull()
        if (url == null || url.scheme != "http" || url.host !in FIXTURE_HOSTS) {
            throw AssertionError("saikou.google.base must be the sync fixture's address (http on ${FIXTURE_HOSTS.joinToString()}), not $base")
        }
        return base
    }

    /** [DriveAccount.signIn] with a code from the fixture; returns the account's address. */
    suspend fun signIn(graph: AppGraph): String {
        val account = graph.driveAccount
        val pkce = Pkce.generate()
        return account.signIn(code(graph, pkce), pkce, REDIRECT_URI)
    }

    /** [signIn], then the backend the stored sign-in builds. */
    suspend fun signInBackend(graph: AppGraph): DriveSyncBackend {
        signIn(graph)
        return checkNotNull(graph.driveAccount.backend()) { "the stored Google sign-in builds no backend" }
    }

    /** A GET of the authorization URL, redirects not followed; the redirect's code. */
    private fun code(graph: AppGraph, pkce: Pkce): String {
        val url = graph.driveAccount.authorizationUrl(REDIRECT_URI, UUID.randomUUID().toString(), pkce).toString()
        fixtureBase(url)
        val client = graph.syncHttpClient.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            assertEquals("the authorization endpoint did not redirect", 302, response.code)
            val location = checkNotNull(response.header("Location")) { "the redirect names no location" }
            return checkNotNull(location.toHttpUrl().queryParameter("code")) { "the redirect carries no code" }
        }
    }
}
