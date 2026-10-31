package com.shinsaikou.extensions.source

import com.shinsaikou.source.CatalogueSource
import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.SourceProvider
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.StubSourceNames
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

/**
 * The single registry of live sources. It lives here because the extension host is the provider
 * whose list changes at runtime; the Local source contributes a second provider through the same
 * multibinding and `:data` contributes the name memory.
 *
 * Unknown ids are answered with a stub immediately and a name lookup is started in the background,
 * so a library row whose extension is gone still renders and gets its remembered name a moment
 * later.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultSourceManager(
    private val providers: Set<SourceProvider>,
    private val stubNames: StubSourceNames,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)

    /**
     * True once every provider finished its first load ([SourceProvider.isInitialized]). It is set
     * in the same step that publishes the combined map, after the map, so a reader that sees it true
     * gets the loaded sources from [get] and [getOrStub]. Until then, a registry holding only the
     * Local source may still be loading its extensions.
     */
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _sources = MutableStateFlow<Map<Long, Source>>(emptyMap())
    private val _stubs = MutableStateFlow<Map<Long, StubSource>>(emptyMap())

    /** Ids a lookup is already running for, so a hot list does not start one per frame. */
    private val pendingLookups = ConcurrentHashMap.newKeySet<Long>()

    init {
        if (providers.isEmpty()) {
            _isInitialized.value = true
        }
        scope.launch {
            combine(providers.map { it.published() }) { published -> published.asList() }
                .collect { published ->
                    val byId = LinkedHashMap<Long, Source>()
                    published.flatMap { it.sources }.forEach { source ->
                        val existing = byId.putIfAbsent(source.id, source)
                        if (existing != null) {
                            this@DefaultSourceManager.logcat(LogPriority.WARN) { "Two providers serve source ${source.id}; keeping the first" }
                        }
                    }
                    _sources.value = byId
                    // A provider now serving an id makes its stub obsolete.
                    _stubs.value = _stubs.value.filterKeys { it !in byId }
                    if (published.all { it.initialized }) {
                        _isInitialized.value = true
                    }
                    withContext(ioDispatcher) {
                        byId.values.forEach { stubNames.remember(it.id, it.name, it.lang) }
                    }
                }
        }
    }

    override fun get(id: Long): Source? = _sources.value[id]

    override fun getOrStub(id: Long): Source {
        _sources.value[id]?.let { return it }
        _stubs.value[id]?.let { return it }
        startNameLookup(id)
        return StubSource.unknown(id)
    }

    override fun getCatalogueSources(): Flow<List<CatalogueSource>> =
        _sources.map { it.values.filterIsInstance<CatalogueSource>() }

    override fun getSources(): Flow<List<Source>> = _sources.map { it.values.toList() }

    override fun getStubSources(): Flow<List<StubSource>> = _stubs.map { it.values.toList() }

    /**
     * One provider's list with its flag. The combine only says that one of the two changed; the
     * values are read here, flag first. A provider publishes its list before it sets its flag, so a
     * true flag read here always comes with the list it was set after, whichever of the two
     * collectors inside the combine happened to run first.
     */
    private fun SourceProvider.published(): Flow<Published> =
        combine(isInitialized, sources) { _, _ ->
            val initialized = isInitialized.value
            Published(sources.value, initialized)
        }

    private class Published(val sources: List<Source>, val initialized: Boolean)

    private fun startNameLookup(id: Long) {
        if (!pendingLookups.add(id)) return
        scope.launch {
            try {
                val entry = withContext(ioDispatcher) { stubNames.find(id) } ?: return@launch
                if (_sources.value.containsKey(id)) return@launch
                _stubs.value = _stubs.value + (id to StubSource(entry.id, entry.name, entry.lang))
            } finally {
                pendingLookups.remove(id)
            }
        }
    }
}
