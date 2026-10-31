package com.shinsaikou.sync.webdav

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.sync.SyncHttpClient
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.io.File

/**
 * What the JVM tests may add to a debug build's URL policy: `http://127.0.0.1`, where the sync
 * fixture listens on the host (plan unit F).
 */
internal val JVM_TEST_POLICY = WebDavUrlPolicy(setOf(WebDavUrlPolicy.EMULATOR_HOST, "127.0.0.1"))

/** The graph's dispatchers as the JVM tests use them: real IO, no main thread. */
internal val TEST_DISPATCHERS = com.shinsaikou.core.common.coroutines.Dispatchers(
    io = Dispatchers.IO,
    default = Dispatchers.Default,
    main = Dispatchers.Default,
)

/**
 * The real sync client (unit S), derived from a real [NetworkHelper] as the app graph derives it,
 * so every request goes through its authorization interceptor.
 */
internal fun syncHttpClient(cacheDir: File): SyncHttpClient {
    val preferences = NetworkPreferences(InMemoryPreferenceStore(), verboseLoggingDefault = false)
    val context = mockk<Context>(relaxed = true)
    every { context.cacheDir } returns cacheDir
    val networkHelper = NetworkHelper(context, preferences, CloudflareSolveCoordinator(), mockk<NetworkUserMessages>(relaxed = true))
    return SyncHttpClient(networkHelper, preferences)
}

/** A backend on the fixture's WebDAV root, with the fixture's credentials unless told otherwise. */
internal fun SyncFixtureProcess.backend(
    client: SyncHttpClient,
    user: String = SyncFixtureProcess.USER,
    password: String = SyncFixtureProcess.PASSWORD,
): WebDavSyncBackend = WebDavSyncBackend(JVM_TEST_POLICY.baseUrl(davUrl), user, password, client.client, Dispatchers.IO)

/** A self-signed certificate for [name], a host name or an IP address. */
internal fun certificate(name: String): HeldCertificate =
    HeldCertificate.Builder().commonName(name).addSubjectAlternativeName(name).build()

/** The sync client with [certificate] trusted as well as the platform's authorities. */
internal fun SyncHttpClient.trusting(certificate: HeldCertificate): OkHttpClient {
    val trust = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .addPlatformTrustedCertificates()
        .build()
    return client.newBuilder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
}
