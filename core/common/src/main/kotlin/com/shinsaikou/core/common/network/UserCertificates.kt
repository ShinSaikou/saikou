package com.shinsaikou.core.common.network

import android.net.http.SslError
import android.os.Build
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/*
 * Settings > Experimental's "Trust certificates you installed", off by default (R162 item 2). It
 * amends contract 08 section 6.5, which dropped upstream's user-added trust anchors because a user
 * CA is the shape a man in the middle takes, so only an owner who opts in gets them back. An ad
 * blocker, a DNS filter or a company proxy that inspects secure connections signs every site with
 * such a CA, and without it no source loads behind one.
 *
 * The network security config cannot change at runtime, so the switch works in code: the source
 * client gets a trust manager over the system and user CAs (read once, when `NetworkHelper` builds
 * it), the clients that carry credentials or install code take system trust back explicitly, and the
 * source WebViews accept a leaf a user CA signed. With the switch off none of this runs.
 */

/** The KeyStore Android exposes its CA store through: the system CAs the owner left enabled, and the user's. */
private const val ANDROID_CA_STORE = "AndroidCAStore"

/** The alias prefix `AndroidCAStore` gives a CA the owner installed, as opposed to `system:`. */
private const val USER_ALIAS_PREFIX = "user:"

/**
 * The source client's TLS over the system CAs and the ones the owner installed. Hostname
 * verification stays OkHttp's own. Reads the CA store, so it runs where the client is built, off the
 * main thread.
 */
fun OkHttpClient.Builder.trustUserCertificates(): OkHttpClient.Builder {
    val store = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null) }
    return trust(trustManagerOf(store))
}

/**
 * System trust only, as the network security config grants it, for a client derived from the source
 * client after [trustUserCertificates] widened it: `newBuilder()` copies the socket factory and the
 * trust manager, so a derived client keeps them unless it sets its own.
 */
fun OkHttpClient.Builder.trustSystemCertificatesOnly(): OkHttpClient.Builder = trust(trustManagerOf(null))

private fun OkHttpClient.Builder.trust(trustManager: X509TrustManager): OkHttpClient.Builder {
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return sslSocketFactory(context.socketFactory, trustManager)
}

/**
 * The platform's trust manager over [store]; null is the network security config's anchors, which
 * is what OkHttp's default client uses.
 */
private fun trustManagerOf(store: KeyStore?): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(store) }
        .trustManagers
        .filterIsInstance<X509TrustManager>()
        .first()

/**
 * Whether a source WebView may proceed past [error] because a CA the owner installed signed the
 * page's certificate. The network security config, which a WebView follows, trusts system CAs only,
 * so the WebView reports such a page as untrusted and the switch answers for it here.
 *
 * Only an untrusted certificate qualifies, and only when it is within its dates, matches the page's
 * host (by OkHttp's own verifier, the one the source client uses) and verifies against the key of a
 * user CA whose subject is its issuer. Chromium reports only the most serious of a certificate's
 * errors, and an unknown issuer outranks a wrong host and a stale date, so those two are checked here
 * rather than read off [error].
 *
 * Below API 29 the certificate cannot be read as an X.509 one, so the answer is always no. A proxy
 * that signs through an intermediate is also refused: its leaf's issuer is the intermediate, which
 * the WebView does not hand over, rather than the installed CA.
 */
fun isSignedByUserCertificate(error: SslError): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    if (error.primaryError != SslError.SSL_UNTRUSTED) return false
    val leaf = error.certificate.x509Certificate ?: return false
    val host = error.url?.toHttpUrlOrNull()?.host ?: return false
    return try {
        leaf.checkValidity()
        OkHostnameVerifier.verify(host, leaf) && userCertificates().any { leaf.isSignedBy(it) }
    } catch (_: GeneralSecurityException) {
        false
    } catch (_: IOException) {
        false
    }
}

private fun userCertificates(): List<X509Certificate> {
    val store = KeyStore.getInstance(ANDROID_CA_STORE).apply { load(null) }
    return store.aliases().toList()
        .filter { it.startsWith(USER_ALIAS_PREFIX) }
        .mapNotNull { store.getCertificate(it) as? X509Certificate }
}

private fun X509Certificate.isSignedBy(ca: X509Certificate): Boolean =
    issuerX500Principal == ca.subjectX500Principal &&
        try {
            verify(ca.publicKey)
            true
        } catch (_: GeneralSecurityException) {
            false
        }
