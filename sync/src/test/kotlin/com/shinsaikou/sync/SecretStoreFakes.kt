package com.shinsaikou.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The fake cipher Q8's JVM tests write through: real AES-256-GCM from the JVM's own provider, under
 * a key held in memory. A second instance holds a different key, which is what a device whose
 * Keystore key was deleted or regenerated looks like to [KeystoreSecretStore].
 */
class SoftwareSecretCipher : SecretCipher {

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedSecret {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(associatedData)
        return SealedSecret(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(sealed: SealedSecret, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(sealed.ciphertext)
    }
}

/** A cipher that fails the way [thrown] says, on every call. */
class FailingSecretCipher(private val thrown: () -> Exception) : SecretCipher {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): SealedSecret = throw thrown()
    override fun decrypt(sealed: SealedSecret, associatedData: ByteArray): ByteArray = throw thrown()
}

/**
 * The secret store's writer over [store]'s map, as a commit leaves it: the next read sees it. The
 * commit itself is `CommittingSecretWriterTest`'s.
 */
class MapSecretWriter(private val store: MapPreferenceStore) : SecretWriter {
    override fun write(key: String, value: String?) {
        if (value == null) store.values.remove(key) else store.values[key] = value
    }
}

/** [KeystoreSecretStore] over [values], reading and writing the same map. */
fun mapSecretStore(values: MutableMap<String, Any>, cipher: SecretCipher): KeystoreSecretStore =
    MapPreferenceStore(values).let { KeystoreSecretStore(it, cipher, MapSecretWriter(it)) }

/**
 * A preference store over one map, so a write is seen by the next read and by [getAll], which the
 * vendored `InMemoryPreferenceStore` does not do for a key it was not seeded with. Strings, longs
 * and objects kept as strings: what [KeystoreSecretStore] and [SyncPreferences] ask for. A second
 * store over the same [values] is what a process that starts again reads.
 */
class MapPreferenceStore(val values: MutableMap<String, Any> = mutableMapOf()) : PreferenceStore {

    override fun getString(key: String, defaultValue: String): Preference<String> =
        MapPreference(key, defaultValue, { it as String }, { it })

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        MapPreference(key, defaultValue, { it as Long }, { it })
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = unsupported()
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = unsupported()
    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        MapPreference(key, defaultValue, { it as Boolean }, { it })
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = unsupported()

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = MapPreference(key, defaultValue, { deserializer(it as String) }, serializer)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = unsupported()

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = unsupported()

    override fun getAll(): Map<String, *> = values.toMap()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("strings only")

    private inner class MapPreference<T>(
        private val key: String,
        private val defaultValue: T,
        private val read: (Any) -> T,
        private val write: (T) -> Any,
    ) : Preference<T> {
        override fun key(): String = key
        override fun get(): T = values[key]?.let(read) ?: defaultValue
        override fun set(value: T) {
            values[key] = write(value)
        }
        override fun isSet(): Boolean = key in values
        override fun delete() {
            values.remove(key)
        }
        override fun defaultValue(): T = defaultValue
        override fun changes(): Flow<T> = emptyFlow()
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = MutableStateFlow(get())
    }
}
