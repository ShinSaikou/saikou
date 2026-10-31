package com.shinsaikou.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM under one Android Keystore key, generated the first time a secret is written and
 * kept under [KEY_ALIAS] from then on (phase 3 Q8).
 *
 * The key asks for no user authentication and is not bound to an unlocked device, because the sync
 * worker runs with the screen locked: either setting would refuse the decrypt then, and the first
 * is invalidated for good when the lock screen is removed. The IV is the one the Keystore
 * generates on every encrypt. The key survives an in-place update; an uninstall deletes it.
 *
 * The key is looked up on every call rather than held, so a key deleted or invalidated under a
 * running process surfaces as a failed decrypt, which [KeystoreSecretStore] reads as absent.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KeystoreSecretCipher : SecretCipher {

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generateKey())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return SealedSecret(iv = cipher.iv, ciphertext = ciphertext)
    }

    override fun decrypt(sealed: SealedSecret, associatedData: ByteArray): ByteArray {
        val key = existingKey() ?: throw UnrecoverableKeyException("No key under $KEY_ALIAS")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed.ciphertext)
    }

    /**
     * The key, or null when there is none. A Keystore that cannot be loaded or reached is thrown as
     * a [KeyStoreException], the [java.security.GeneralSecurityException] [SecretCipher] promises,
     * so [KeystoreSecretStore] reads it as absent instead of passing it to the caller. `load`
     * declares [IOException], and `getKey` throws [IllegalStateException] when the keystore2
     * service is not running.
     */
    private fun existingKey(): SecretKey? = try {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (e: IOException) {
        throw KeyStoreException("The Keystore could not be loaded", e)
    } catch (e: IllegalStateException) {
        throw KeyStoreException("The Keystore service could not be reached", e)
    }

    /** Two first writes at once must not each generate a key, or the loser's ciphertext is lost. */
    @Synchronized
    private fun generateKey(): SecretKey {
        existingKey()?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        /** The one key sync's secrets are sealed under. Never renamed: a new alias is a sign-out. */
        const val KEY_ALIAS = "saikou_sync_secrets"

        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
