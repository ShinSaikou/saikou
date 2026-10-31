package com.shinsaikou.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * A preference store over one map, as the platform's is over one file: every preference with a key
 * reads what any other wrote under it, and an object preference stores its serialized string, so a
 * second reader over [values] decodes what the first encoded. `InMemoryPreferenceStore` keeps each
 * preference's value in the preference itself and never serializes, so it can show neither.
 */
class MapPreferenceStore(val values: MutableMap<String, Any> = mutableMapOf()) : PreferenceStore {

    override fun getString(key: String, defaultValue: String): Preference<String> =
        MapPreference(key, defaultValue, { it as String }, { it })

    override fun getLong(key: String, defaultValue: Long): Preference<Long> =
        MapPreference(key, defaultValue, { it as Long }, { it })

    override fun getInt(key: String, defaultValue: Int): Preference<Int> =
        MapPreference(key, defaultValue, { it as Int }, { it })

    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = unsupported()

    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> =
        MapPreference(key, defaultValue, { it as Boolean }, { it })

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> =
        MapPreference(key, defaultValue, { it as Set<String> }, { it })

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
    ): Preference<T> = MapPreference(key, defaultValue, { deserializer(it as Int) }, serializer)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = unsupported()

    override fun getAll(): Map<String, *> = values.toMap()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by the tests over this store")

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
