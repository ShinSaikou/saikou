package com.shinsaikou.sync

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.Security
import java.security.cert.Certificate
import java.util.Collections
import java.util.Date
import java.util.Enumeration

/**
 * The real [KeystoreSecretCipher] behind the store, over a JVM stand-in for the `AndroidKeyStore`
 * provider that fails the way a device's Keystore can before any key is read: `load` throwing the
 * [IOException] it declares, or `getKey` throwing the [IllegalStateException] keystore2 raises when
 * its service is not running. Either must reach the caller as no secret, as Q8 says, never as an
 * exception out of `get`. A deleted alias is `SecretStoreDeviceTest`'s, on a device.
 */
class KeystoreSecretCipherTest {

    private val preferences = MapPreferenceStore()

    @AfterEach
    fun removeProvider() {
        Security.removeProvider(ANDROID_KEYSTORE)
    }

    @Test
    fun `a Keystore that cannot be loaded reads absent and deletes the value`() {
        readThroughKeystore { FailingKeyStoreSpi(onLoad = { throw IOException("keystore unavailable") }) }
    }

    @Test
    fun `a Keystore service that is not running reads absent and deletes the value`() {
        readThroughKeystore {
            FailingKeyStoreSpi(onGetKey = { throw IllegalStateException("Could not connect to Keystore service") })
        }
    }

    private fun readThroughKeystore(spi: () -> KeyStoreSpi) {
        KeystoreSecretStore(preferences, SoftwareSecretCipher(), MapSecretWriter(preferences)).put("drive_refresh_token", "a refresh token")
        preferences.values shouldContainKey Preference.appStateKey("sync_secret_drive_refresh_token")
        Security.insertProviderAt(StandInKeystoreProvider(spi), 1)

        KeystoreSecretStore(preferences, KeystoreSecretCipher(), MapSecretWriter(preferences)).get("drive_refresh_token").shouldBeNull()
        preferences.values.shouldBeEmpty()
    }

    private class StandInKeystoreProvider(spi: () -> KeyStoreSpi) :
        Provider(ANDROID_KEYSTORE, 1.0, "A JVM stand-in for the Android Keystore") {
        init {
            putService(
                object : Service(this, "KeyStore", ANDROID_KEYSTORE, FailingKeyStoreSpi::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = spi()
                },
            )
        }
    }

    /** A key store holding nothing, whose `load` and `getKey` do what the test says. */
    private class FailingKeyStoreSpi(
        private val onLoad: () -> Unit = {},
        private val onGetKey: () -> Key? = { null },
    ) : KeyStoreSpi() {
        override fun engineLoad(stream: InputStream?, password: CharArray?) = onLoad()
        override fun engineGetKey(alias: String?, password: CharArray?): Key? = onGetKey()
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = null
        override fun engineSetKeyEntry(
            alias: String?,
            key: Key?,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) = unsupported()
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
            unsupported()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = unsupported()
        override fun engineDeleteEntry(alias: String?) = unsupported()
        override fun engineAliases(): Enumeration<String> = Collections.emptyEnumeration()
        override fun engineContainsAlias(alias: String?): Boolean = false
        override fun engineSize(): Int = 0
        override fun engineIsKeyEntry(alias: String?): Boolean = false
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = unsupported()

        private fun unsupported(): Nothing = throw UnsupportedOperationException("read only")
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
