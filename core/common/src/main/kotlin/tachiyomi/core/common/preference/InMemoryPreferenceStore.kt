// Vendored from upstream v0.20.4: core/common/src/main/kotlin/tachiyomi/core/common/preference/InMemoryPreferenceStore.kt (modified: yes)
package tachiyomi.core.common.preference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Local-copy implementation of PreferenceStore mostly for test and preview purposes
 */
class InMemoryPreferenceStore(
    initialPreferences: Sequence<InMemoryPreference<*>> = sequenceOf(),
) : PreferenceStore {

    private val preferences: Map<String, Preference<*>> =
        initialPreferences.toList().associateBy { it.key() }

    override fun getString(key: String, defaultValue: String): Preference<String> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: String? = preferences[key]?.get() as? String
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    override fun getLong(key: String, defaultValue: Long): Preference<Long> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: Long? = preferences[key]?.get() as? Long
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    override fun getInt(key: String, defaultValue: Int): Preference<Int> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: Int? = preferences[key]?.get() as? Int
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: Float? = preferences[key]?.get() as? Float
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: Boolean? = preferences[key]?.get() as? Boolean
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
        val default = InMemoryPreference(key, null, defaultValue)

        @Suppress("UNCHECKED_CAST")
        val data: Set<String>? = preferences[key]?.get() as? Set<String>
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: T? = preferences[key]?.get() as? T
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: T? = preferences[key]?.get() as? T
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> {
        val default = InMemoryPreference(key, null, defaultValue)
        val data: Set<T>? = preferences[key]?.get() as? Set<T>
        return if (data == null) default else InMemoryPreference(key, data, defaultValue)
    }

    override fun getAll(): Map<String, *> {
        return preferences
    }

    /**
     * Modified from upstream: `changes()` was `flow { data }`, which evaluates `data` as a statement
     * and emits nothing, so the flow completed empty and a write was never observed. Anything under
     * test that reacts to a preference change silently did nothing. It now emits on every write, as
     * the Android store does, and like that store it does not replay the current value: callers
     * that need one start with `changes().withInitial(get())`.
     */
    class InMemoryPreference<T>(
        private val key: String,
        private var data: T?,
        private val defaultValue: T,
    ) : Preference<T> {

        private val changes = MutableSharedFlow<T>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        override fun key(): String = key

        override fun get(): T = data ?: defaultValue()

        override fun isSet(): Boolean = data != null

        override fun delete() {
            data = null
            changes.tryEmit(defaultValue)
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = changes.asSharedFlow()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> {
            return changes().stateIn(scope, SharingStarted.Eagerly, get())
        }

        override fun set(value: T) {
            data = value
            changes.tryEmit(value)
        }
    }
}
