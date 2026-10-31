package com.shinsaikou.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import logcat.logcat
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64

/**
 * Sync's secrets, sealed by [cipher] and kept in the ordinary [PreferenceStore] (phase 3 Q8).
 *
 * Every key is a [Preference.appStateKey], as in [SyncPreferences], so `BackupExporter` never
 * writes one into a `.tachibk` and `PreferenceRestorer` never overwrites one, whether or not the
 * owner chose private settings. A ciphertext copied to another device could not be opened there
 * anyway, since its key never leaves this one.
 *
 * A stored value is the IV and the ciphertext, each in Base64, joined by [SEPARATOR]. The secret's
 * name is the associated data, so a value moved to another name fails its tag instead of opening
 * as the wrong secret.
 *
 * Reads go through [preferenceStore]; every write goes through [writer], which has the value on disk
 * before [put] or [delete] returns and throws `IOException` when it could not (fix round 1 item 5).
 * Call them off the main thread.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class KeystoreSecretStore(
    private val preferenceStore: PreferenceStore,
    private val cipher: SecretCipher,
    private val writer: SecretWriter,
) : SecretStore {

    override fun get(name: String): String? {
        val preference = preference(name)
        val stored = preference.get().ifEmpty { return null }
        return try {
            val (iv, ciphertext) = stored.split(SEPARATOR).takeIf { it.size == 2 }
                ?: throw IllegalArgumentException("Not an IV and a ciphertext")
            val sealed = SealedSecret(decoder.decode(iv), decoder.decode(ciphertext))
            cipher.decrypt(sealed, name.encodeToByteArray()).decodeToString()
        } catch (e: GeneralSecurityException) {
            forget(preference, name, e)
        } catch (e: ProviderException) {
            // The Keystore reports some failures of its own as this unchecked exception.
            forget(preference, name, e)
        } catch (e: IllegalArgumentException) {
            forget(preference, name, e)
        }
    }

    /** Seals [value] and has it on disk before returning; throws `IOException` when the write failed. */
    override fun put(name: String, value: String) {
        val sealed = cipher.encrypt(value.encodeToByteArray(), name.encodeToByteArray())
        writer.write(preference(name).key(), encoder.encodeToString(sealed.iv) + SEPARATOR + encoder.encodeToString(sealed.ciphertext))
    }

    /** Has the secret gone from disk before returning; throws `IOException` when the write failed. */
    override fun delete(name: String) {
        writer.write(preference(name).key(), null)
    }

    private fun preference(name: String): Preference<String> {
        require(name.isNotBlank()) { "A secret needs a name" }
        return preferenceStore.getString(Preference.appStateKey("$KEY_PREFIX$name"), "")
    }

    /**
     * The value can never be opened again, so it goes, and the caller sees no secret. A delete that
     * fails leaves a value that still reads as absent, so it is logged, never thrown ([get]'s
     * contract).
     */
    private fun forget(preference: Preference<String>, name: String, error: Exception): String? {
        logcat(LogPriority.WARN) { "Secret $name could not be decrypted and is deleted: ${error.javaClass.name}" }
        try {
            writer.write(preference.key(), null)
        } catch (e: IOException) {
            logcat(LogPriority.WARN) { "Secret $name could not be deleted: ${e.javaClass.name}" }
        }
        return null
    }

    private companion object {
        const val KEY_PREFIX = "sync_secret_"
        const val SEPARATOR = ":"
        val encoder: Base64.Encoder = Base64.getEncoder()
        val decoder: Base64.Decoder = Base64.getDecoder()
    }
}
