package com.shinsaikou.sync

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/**
 * Q8's store over a fake cipher. Everything the store itself decides is here: where a secret is
 * kept, what it looks like there, and that a value the cipher cannot open is gone and reads as no
 * secret rather than as an exception. The Keystore half is `SecretStoreDeviceTest`, on a device.
 */
class KeystoreSecretStoreTest {

    private val preferences = MapPreferenceStore()
    private val cipher = SoftwareSecretCipher()
    private val store = KeystoreSecretStore(preferences, cipher, MapSecretWriter(preferences))

    @Test
    fun `a secret round trips, sealed under one app state key`() {
        store.put("webdav_password", "correct horse battery staple")

        store.get("webdav_password") shouldBe "correct horse battery staple"
        preferences.values.keys.toList() shouldHaveSize 1
        val (key, stored) = preferences.values.entries.single()
        Preference.isAppState(key) shouldBe true
        key shouldStartWith Preference.appStateKey("sync_secret_")
        stored as String shouldNotContain "correct horse"
    }

    @Test
    fun `every write is sealed under a fresh IV`() {
        store.put("drive_refresh_token", "same value")
        val first = preferences.values.values.single()
        store.put("drive_refresh_token", "same value")

        preferences.values.values.single() shouldNotBe first
        store.get("drive_refresh_token") shouldBe "same value"
    }

    @Test
    fun `a secret sealed under a key that is gone reads absent and is deleted`() {
        store.put("webdav_password", "hunter2")

        // The same preferences behind a cipher with another key: the Keystore key was deleted or
        // regenerated, so the stored tag no longer verifies.
        val afterKeyLoss = KeystoreSecretStore(preferences, SoftwareSecretCipher(), MapSecretWriter(preferences))

        afterKeyLoss.get("webdav_password").shouldBeNull()
        preferences.values.shouldBeEmpty()
    }

    @Test
    fun `a value moved to another name fails its tag instead of opening as that secret`() {
        store.put("webdav_password", "hunter2")
        val sealed = preferences.values.values.single()
        preferences.values[Preference.appStateKey("sync_secret_webdav_user")] = sealed

        store.get("webdav_user").shouldBeNull()
        store.get("webdav_password") shouldBe "hunter2"
        preferences.values.keys shouldBe setOf(Preference.appStateKey("sync_secret_webdav_password"))
    }

    @Test
    fun `a value that is not an IV and a ciphertext reads absent and is deleted`() {
        val key = Preference.appStateKey("sync_secret_webdav_url")
        for (garbage in listOf("no separator", "a:b:c", "!!!:???", ":")) {
            preferences.values[key] = garbage

            store.get("webdav_url").shouldBeNull()
            preferences.values.shouldBeEmpty()
        }
    }

    @Test
    fun `each way the Keystore refuses a decrypt reads absent and deletes the value`() {
        val failures = listOf<() -> Exception>(
            { UnrecoverableKeyException("No key under ${KeystoreSecretCipher.KEY_ALIAS}") },
            { KeyStoreException("keystore unavailable") },
            { ProviderException("Keystore operation failed") },
        )
        for (failure in failures) {
            store.put("webdav_password", "hunter2")
            preferences.values shouldContainKey Preference.appStateKey("sync_secret_webdav_password")

            KeystoreSecretStore(preferences, FailingSecretCipher(failure), MapSecretWriter(preferences)).get("webdav_password").shouldBeNull()
            preferences.values.shouldBeEmpty()
        }
    }

    @Test
    fun `an absent secret reads null and delete removes a stored one`() {
        store.get("webdav_password").shouldBeNull()

        store.put("webdav_password", "hunter2")
        store.delete("webdav_password")

        store.get("webdav_password").shouldBeNull()
        preferences.values.shouldBeEmpty()
    }
}
