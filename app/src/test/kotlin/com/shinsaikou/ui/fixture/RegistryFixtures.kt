package com.shinsaikou.ui.fixture

import com.shinsaikou.source.Source
import com.shinsaikou.source.SourceManager
import com.shinsaikou.source.StubSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A source registry as `DefaultSourceManager` answers before and after its first load, for the
 * screens a restore after process death brings back as the process starts (ruling R69 item 4).
 *
 * Until [finishLoad] the flag is false and an extension source is not loaded: [SourceManager.get]
 * answers null and [SourceManager.getOrStub] a stub, which is what the real registry answers for an
 * installed extension whose first load has not ended. Afterwards both answer from [loaded].
 */
internal class ColdSourceRegistry(private val loaded: Map<Long, Source>) {

    private val initialized = MutableStateFlow(false)

    val manager: SourceManager = mockk()

    init {
        every { manager.isInitialized } returns initialized
        every { manager.get(any()) } answers { if (initialized.value) loaded[firstArg()] else null }
        every { manager.getOrStub(any()) } answers {
            val id = firstArg<Long>()
            (if (initialized.value) loaded[id] else null) ?: StubSource.unknown(id)
        }
    }

    /** The extension host's first load ends: the lists are in place, then the flag turns. */
    fun finishLoad() {
        initialized.value = true
    }
}

/**
 * A state flow caught mid-publish: [value] already holds the new list while a collector that
 * has not resumed yet still holds the old one, until [deliver]. That is the moment `combine` can
 * hand over a true flag with the list from before a load (ruling R69 item 3). A collector that
 * starts during the lag sees the new value first, as a fresh collector of a `StateFlow` does.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class LaggingStateFlow<T>(initial: T) : StateFlow<T> {
    private val published = MutableStateFlow(initial)
    private val delivered = MutableStateFlow(initial)

    override val value: T get() = published.value
    override val replayCache: List<T> get() = listOf(value)

    fun publish(next: T) {
        published.value = next
    }

    fun deliver() {
        delivered.value = published.value
    }

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        var last = published.value
        collector.emit(last)
        delivered.collect { next ->
            if (next != last) {
                last = next
                collector.emit(next)
            }
        }
    }
}
