package com.shinsaikou.sync

import com.shinsaikou.di.AppGraph

/**
 * R118 carry-over 1's device half (phase 4 device coverage unit DC2, item 2): a reorder made on
 * one device of the two-device suite's shared library ([SyncPairLibrary]) comes out in the dropped
 * order on the other, once both have synced.
 *
 * [drag] writes [SyncPairLibrary.READING]'s entries in the reverse of the seed's index order,
 * through the one call [com.shinsaikou.domain.library.interactor.SetManualLibraryOrder.await] makes,
 * `mangaRepository.setManualPositions`, never a UI drag: the order a drag would have handed it is
 * picked here instead, since what this scenario proves is the sync of `manual_position`, not the
 * tab-diffing `LibraryViewModel.reorder` already covered by `LibraryViewModelTest`'s JVM suite
 * (R118 item 2). [check] reads the same category back by its ranked order (position, ties by id,
 * [com.shinsaikou.ui.library.LibraryViewModel]'s own comparator) and reports it the same way [drag]
 * does, by the index [SyncPairLibrary.entryUrl] gave each entry rather than its database id, so the
 * orchestrator's expectation is one fixed string regardless of which ids the seed assigned this run.
 */
object SyncPairReorder {

    /** How many of [SyncPairLibrary.ENTRIES] fall in [SyncPairLibrary.READING] by the seed's turn. */
    private val READING_COUNT = SyncPairLibrary.ENTRIES / SyncPairLibrary.CATEGORIES.size

    /** [SyncPairLibrary.READING]'s share of the seed, by the index [SyncPairLibrary.entryUrl] gave it. */
    private fun readingIndex(index: Int): Boolean =
        index % SyncPairLibrary.CATEGORIES.size == SyncPairLibrary.CATEGORIES.indexOf(SyncPairLibrary.READING)

    private suspend fun readingEntries(graph: AppGraph): List<Pair<Long, Int>> {
        val entries = graph.mangaRepository.getFavorites()
            .filter { it.source == SyncPairLibrary.SOURCE_ID }
            .map { it.id to it.url.substringAfterLast('/').toInt() }
            .filter { (_, index) -> readingIndex(index) }
        check(entries.size == READING_COUNT) {
            "${SyncPairLibrary.READING} holds ${entries.size} entries, not $READING_COUNT"
        }
        return entries
    }

    /** The dropped order, by entry index, descending: the reverse of the seed's own order. */
    private fun droppedOrder(entries: List<Pair<Long, Int>>): List<Pair<Long, Int>> =
        entries.sortedByDescending { (_, index) -> index }

    suspend fun drag(graph: AppGraph): Map<String, Any?> {
        val dropped = droppedOrder(readingEntries(graph))
        graph.mangaRepository.setManualPositions(dropped.map { (id, _) -> id })
        return linkedMapOf(
            "moved" to dropped.size,
            "order" to dropped.joinToString(",") { (_, index) -> index.toString() },
        )
    }

    /** Reads [SyncPairLibrary.READING] back by its ranked position, the same shape [drag] reports. */
    suspend fun check(graph: AppGraph): Map<String, Any?> {
        val byId = readingEntries(graph).toMap()
        val positions = graph.mangaRepository.getFavorites()
            .filter { it.id in byId }
            .associate { it.id to it.manualPosition }
        val ranked = byId.keys
            .filter { id -> (positions[id] ?: 0L) > 0L }
            .sortedWith(compareBy({ id -> positions.getValue(id) }, { id -> id }))
        return linkedMapOf(
            "ranked" to ranked.size,
            "order" to ranked.joinToString(",") { id -> byId.getValue(id).toString() },
        )
    }
}
