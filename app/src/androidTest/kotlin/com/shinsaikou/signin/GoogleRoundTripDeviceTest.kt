package com.shinsaikou.signin

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.di.AppGraph
import com.shinsaikou.sync.google.GoogleInvalidGrantException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The graph's Google client signs in against the sync fixture from the device (phase 3 unit O, fix
 * round 1 item 6): the `saikou.google.base` runner argument sets the test base in this debug build,
 * and the client the graph builds then sends every call there with the fixture's test client (item
 * 1), through the loopback listener, the fixture's authorization redirect back to it, the code
 * exchange, a refresh, and the revoke after which the refresh token is invalid_grant.
 *
 * Without the argument it logs a `Skipped:` line and returns, reading the argument straight from the
 * runner rather than through `Args`, since a strict gate run has no fixture on 18797 and must not
 * fail for its absence; the tablet step expects the line. The "browser" is the sync client following
 * the fixture's redirect, so the process never leaves the foreground; unit O's spike is the run with
 * a real browser and the app in the background.
 */
@RunWith(AndroidJUnit4::class)
class GoogleRoundTripDeviceTest {

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    @Test
    fun theGraphsGoogleClientSignsInAgainstTheSyncFixtureTheRunnerNames() {
        val base = InstrumentationRegistry.getArguments().getString(GoogleTestBaseSetting.EXTRA_GOOGLE_BASE)
        if (base.isNullOrBlank()) {
            // Spelled out, because gate.sh's tablet step expects this line and a tools test reads it here.
            Log.i(
                LOG_TAG,
                "Skipped: theGraphsGoogleClientSignsInAgainstTheSyncFixtureTheRunnerNames: " +
                    "no saikou.google.base argument",
            )
            return
        }
        val previous = graph.googleTestBase.value
        graph.googleTestBase.value = base
        try {
            val client = graph.googleOAuthClient
            assertFalse("the debug build sent Google's calls to Google with a test base set", client.endpoints.isGoogle)
            assertTrue("a test base has the fixture's test client", client.isConfigured)
            val pkce = Pkce.generate()
            runBlocking(Dispatchers.IO) {
                LoopbackListener.open(0, BrowserSignIn.pages(app)).use { listener ->
                    val callback = coroutineScope {
                        val waiting = async { listener.await() }
                        val url = client.authorizationUrl(listener.redirectUri, listener.state, pkce)
                        graph.syncHttpClient.client.newCall(Request.Builder().url(url).build()).execute().use {
                            assertEquals(200, it.code)
                        }
                        waiting.await()
                    }
                    val tokens = client.exchange(callback.code, pkce, listener.redirectUri)
                    val refreshToken = checkNotNull(tokens.refreshToken) { "no refresh token issued" }
                    client.refresh(refreshToken)
                    assertTrue("the revoke was refused", client.revoke(refreshToken))
                    val refused = runCatching { client.refresh(refreshToken) }.exceptionOrNull()
                    assertTrue(
                        "a revoked refresh token was not invalid_grant: ${refused?.let { it::class.simpleName }}",
                        refused is GoogleInvalidGrantException,
                    )
                }
            }
        } finally {
            graph.googleTestBase.value = previous
        }
    }

    private companion object {
        const val LOG_TAG = "GoogleRoundTripDeviceTest"
    }
}
