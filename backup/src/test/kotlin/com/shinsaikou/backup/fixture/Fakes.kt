package com.shinsaikou.backup.fixture

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import com.shinsaikou.backup.CustomCoverFiles
import com.shinsaikou.domain.extension.SourceExtensionInstaller
import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.ConfigurableSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceIds
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.StubSourceNames
import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** A source that answers nothing; only its id, name and language ever matter to `:backup`. */
class FakeSource(
    override val id: Long,
    override val name: String,
    override val lang: String = "en",
) : Source {
    override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = error("not used")
    override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = error("not used")
    override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = error("not used")
    override suspend fun getImage(page: PageInfo): ImageResult = error("not used")
    override fun toString(): String = if (lang.isBlank()) name else "$name (${lang.uppercase()})"
}

/** A source with settings, so the exporter reads its `source_<id>` file; nothing else about it is used. */
class ConfigurableFakeSource(
    override val id: Long,
    override val name: String,
    override val lang: String = "",
) : ConfigurableSource {
    override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = error("not used")
    override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = error("not used")
    override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = error("not used")
    override suspend fun getImage(page: PageInfo): ImageResult = error("not used")
    override fun setupPreferenceScreen(screen: PreferenceScreen) = error("not used")
    override fun toString(): String = if (lang.isBlank()) name else "$name (${lang.uppercase()})"
}

/**
 * Answers the way the app's registry does. Ready with every source from the start, unless a test
 * calls [startCold]: then only the Local source is loaded and [isInitialized] is false, as in a
 * process a worker started before the extension host has loaded, until [finishLoad].
 */
class FakeSourceManager(private val sources: List<Source>) : SourceManager {
    private val loaded = MutableStateFlow(sources)

    override val isInitialized = MutableStateFlow(true)

    fun startCold() {
        isInitialized.value = false
        loaded.value = sources.filter { it.id == SourceIds.LOCAL }
    }

    /**
     * Ends the extension host's first load. A failed load ends it with nothing from it loaded. The
     * list is published before the flag, as `SourceProvider` requires.
     */
    fun finishLoad(succeeded: Boolean = true) {
        if (succeeded) loaded.value = sources
        isInitialized.value = true
    }

    override fun get(id: Long): Source? = loaded.value.firstOrNull { it.id == id }
    override fun getOrStub(id: Long): Source = get(id) ?: StubSource.unknown(id)
    override fun getCatalogueSources(): Flow<List<CatalogueSource>> = flowOf(emptyList())
    override fun getSources(): Flow<List<Source>> = loaded
    override fun getStubSources(): Flow<List<StubSource>> = flowOf(emptyList())
}

class FakeStubSourceNames : StubSourceNames {
    val remembered = mutableMapOf<Long, StubSourceNames.Entry>()

    /** Runs before each [remember] stores its entry, so a test can cancel a restore in its step 3. */
    @Volatile
    var beforeRemember: (suspend () -> Unit)? = null

    override suspend fun find(id: Long): StubSourceNames.Entry? = remembered[id]

    override suspend fun remember(id: Long, name: String, lang: String) {
        beforeRemember?.invoke()
        remembered[id] = StubSourceNames.Entry(id, name, lang)
    }
}

/**
 * Answers Install extensions with [answer], by default every source not found, as a device whose
 * repositories list none of the fixture's sources; [requests] is each call's sources, in order, and
 * [storesBefore] each call's stores from before the restore.
 */
class FakeSourceExtensionInstaller : SourceExtensionInstaller {
    val requests = mutableListOf<List<SourceExtensionInstaller.WantedSource>>()
    val storesBefore = mutableListOf<List<SourceExtensionInstaller.KnownStore>>()

    var answer: suspend (List<SourceExtensionInstaller.WantedSource>, (SourceExtensionInstaller.Progress) -> Unit) -> SourceExtensionInstaller.Outcome =
        { sources, _ -> SourceExtensionInstaller.Outcome(notFound = sources) }

    override suspend fun install(
        sources: List<SourceExtensionInstaller.WantedSource>,
        storesBefore: List<SourceExtensionInstaller.KnownStore>,
        onProgress: (SourceExtensionInstaller.Progress) -> Unit,
    ): SourceExtensionInstaller.Outcome {
        requests += sources
        this.storesBefore += storesBefore
        return answer(sources, onProgress)
    }
}

/** The app's custom cover files, in memory, by entry id. */
class FakeCustomCoverFiles : CustomCoverFiles {
    val files = mutableMapOf<Long, ByteArray>()

    override fun exists(mangaId: Long): Boolean = mangaId in files

    override fun read(mangaId: Long): ByteArray? = files[mangaId]

    override fun write(mangaId: Long, bytes: ByteArray) {
        files[mangaId] = bytes
    }
}

/**
 * An in-memory [PreferenceStore] whose `getAll` returns raw values, the way `AndroidPreferenceStore`
 * does. The vendored `InMemoryPreferenceStore` returns its [Preference] wrappers instead, which the
 * restorer's type gate cannot read.
 */
class FakePreferenceStore(initial: Map<String, Any> = emptyMap()) : PreferenceStore {

    val values: MutableMap<String, Any> = initial.toMutableMap()

    override fun getString(key: String, defaultValue: String): Preference<String> = preference(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long): Preference<Long> = preference(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int): Preference<Int> = preference(key, defaultValue)
    override fun getFloat(key: String, defaultValue: Float): Preference<Float> = preference(key, defaultValue)
    override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> = preference(key, defaultValue)
    override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> = preference(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = ObjectPreference(this, key, defaultValue, { serializer(it) }, { deserializer(it as String) })

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = ObjectPreference(this, key, defaultValue, { serializer(it) }, { deserializer(it as Int) })

    @Suppress("UNCHECKED_CAST")
    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = ObjectPreference(
        store = this,
        key = key,
        defaultValue = defaultValue,
        encode = { set -> set.map(serializer).toSet() },
        decode = { raw -> (raw as Set<String>).mapNotNull(deserializer).toSet() },
    )

    override fun getAll(): Map<String, *> = values.toMap()

    private fun <T : Any> preference(key: String, defaultValue: T) =
        ObjectPreference<T>(this, key, defaultValue, { it }, { @Suppress("UNCHECKED_CAST") (it as T) })

    class ObjectPreference<T>(
        private val store: FakePreferenceStore,
        private val key: String,
        private val defaultValue: T,
        private val encode: (T) -> Any,
        private val decode: (Any) -> T,
    ) : Preference<T> {
        override fun key(): String = key
        override fun get(): T = store.values[key]?.let(decode) ?: defaultValue
        override fun set(value: T) {
            store.values[key] = encode(value) as Any
        }
        override fun isSet(): Boolean = key in store.values
        override fun delete() {
            store.values.remove(key)
        }
        override fun defaultValue(): T = defaultValue
        override fun changes(): Flow<T> = flowOf(get())
        override fun stateIn(scope: kotlinx.coroutines.CoroutineScope): StateFlow<T> = MutableStateFlow(get())
    }
}

/** Enough of [SharedPreferences] for `AndroidPreferenceStore` to read and write a source file. */
class FakeSharedPreferences : SharedPreferences {

    val values: MutableMap<String, Any?> = mutableMapOf()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun edit(): SharedPreferences.Editor = Editor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class Editor(private val target: FakeSharedPreferences) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) target.values.clear()
            removed.forEach(target.values::remove)
            target.values.putAll(pending)
        }
    }
}
