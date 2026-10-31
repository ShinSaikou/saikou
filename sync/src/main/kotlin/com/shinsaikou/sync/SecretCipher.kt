package com.shinsaikou.sync

import java.security.GeneralSecurityException

/**
 * The encryption under [KeystoreSecretStore]. The application binds [KeystoreSecretCipher]; a JVM
 * test, which has no Android Keystore, hands the store a software cipher instead.
 */
interface SecretCipher {

    /** Seals [plaintext], bound to [associatedData], under an IV the cipher chooses itself. */
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedSecret

    /**
     * Opens [sealed], which must have been sealed with the same [associatedData].
     *
     * @throws GeneralSecurityException when it cannot: a bad tag, a key that is missing or
     *   invalidated, or a Keystore that cannot be loaded or reached.
     */
    fun decrypt(sealed: SealedSecret, associatedData: ByteArray): ByteArray
}

class SealedSecret(val iv: ByteArray, val ciphertext: ByteArray)
