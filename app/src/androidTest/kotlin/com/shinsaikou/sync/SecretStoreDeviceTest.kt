package com.shinsaikou.sync

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.Preference
import java.security.KeyStore
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Phase 3 Q8 on a real Android Keystore, through the process's own graph, so the store and the
 * cipher are the ones the application binds. The JVM tests cover the store's own decisions over a
 * software cipher; what only a device can show is that the Keystore half seals and opens a secret,
 * that its key is the one Q8 describes, and that a deleted alias reads as no secret, not a crash.
 */
@RunWith(AndroidJUnit4::class)
class SecretStoreDeviceTest {

    private val app =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as SaikouApp

    private val graph: AppGraph get() = app.graph

    private val store: SecretStore get() = graph.secretStore

    private val storedKey = Preference.appStateKey("sync_secret_$NAME")

    @After
    fun deleteTheSecret() {
        store.delete(NAME)
    }

    @Test
    fun aSecretRoundTripsThroughTheKeystore() {
        store.put(NAME, VALUE)

        assertEquals(VALUE, store.get(NAME))
        val stored = graph.preferenceStore.getAll()[storedKey] as String?
        assertNotNull("nothing stored under $storedKey", stored)
        assertFalse("the stored value holds the secret in the clear", stored!!.contains(VALUE))

        // The key is the one Q8 asks for: AES-256 for GCM, usable with the screen locked.
        val key = keyStore().getKey(KeystoreSecretCipher.KEY_ALIAS, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), info.blockModes)
        assertFalse("the key requires user authentication", info.isUserAuthenticationRequired)
    }

    @Test
    fun aSecretReadsAbsentAfterTheKeystoreAliasIsDeleted() {
        store.put(NAME, VALUE)
        assertTrue(keyStore().containsAlias(KeystoreSecretCipher.KEY_ALIAS))
        // Readable and stored before the alias goes, so the absence below is the deletion's.
        assertEquals(VALUE, store.get(NAME))
        assertTrue(
            "nothing stored under $storedKey before the alias was deleted",
            graph.preferenceStore.getAll().containsKey(storedKey),
        )

        keyStore().deleteEntry(KeystoreSecretCipher.KEY_ALIAS)

        // Absent, not thrown, and gone from the preferences so no later read tries again.
        assertNull(store.get(NAME))
        assertFalse(graph.preferenceStore.getAll().containsKey(storedKey))

        // The next sign-in writes under a fresh key.
        store.put(NAME, VALUE)
        assertEquals(VALUE, store.get(NAME))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val NAME = "device_test_secret"
        const val VALUE = "correct horse battery staple"
    }
}
