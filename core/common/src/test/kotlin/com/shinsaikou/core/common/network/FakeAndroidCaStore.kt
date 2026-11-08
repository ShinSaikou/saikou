package com.shinsaikou.core.common.network

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration

/**
 * The `AndroidCAStore` KeyStore, which only Android has, registered as a JVM provider for the
 * length of a test. It holds the CAs under the aliases Android gives them: `system:` for the ones
 * the device ships and `user:` for the ones the owner installed. Close it to take it out again.
 */
class FakeAndroidCaStore(user: List<X509Certificate> = emptyList(), system: List<X509Certificate> = emptyList()) :
    AutoCloseable {

    private val certificates: Map<String, X509Certificate> =
        system.mapIndexed { i, cert -> "system:$i" to cert }.toMap() +
            user.mapIndexed { i, cert -> "user:$i" to cert }.toMap()

    private val provider = object : Provider(NAME, 1.0, "Stands in for Android's CA store") {
        init {
            putService(
                object : Service(this, "KeyStore", "AndroidCAStore", Store::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = Store(certificates)
                },
            )
        }
    }

    init {
        Security.addProvider(provider)
    }

    override fun close() {
        Security.removeProvider(NAME)
    }

    private class Store(private val entries: Map<String, X509Certificate>) : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key? = null
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = entries[alias]
        override fun engineGetCreationDate(alias: String?): Date? = if (alias in entries) Date(0) else null
        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String?) = throw UnsupportedOperationException()
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys)
        override fun engineContainsAlias(alias: String?): Boolean = alias in entries
        override fun engineSize(): Int = entries.size
        override fun engineIsKeyEntry(alias: String?): Boolean = false
        override fun engineIsCertificateEntry(alias: String?): Boolean = alias in entries
        override fun engineGetCertificateAlias(cert: Certificate?): String? =
            entries.entries.firstOrNull { it.value == cert }?.key
        override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    private companion object {
        const val NAME = "FakeAndroidCaStore"
    }
}
