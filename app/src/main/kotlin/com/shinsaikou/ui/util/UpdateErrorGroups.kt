package com.shinsaikou.ui.util

import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.libraryupdate.LibraryUpdateError
import com.shinsaikou.source.Source

/**
 * One source's failures from the last library update run.
 *
 * [sourceId] is the group's identity, and the list keys its header on it. [sourceName] is only the
 * label: a display name is not unique, because a multi-language extension installs one source per
 * language under one name, and an outage on that site fails every one of them at once.
 */
data class UpdateErrorGroup(val sourceId: Long, val sourceName: String, val errors: List<LibraryUpdateError>)

/**
 * [source] is called once per distinct source id, not once per row: resolving a stub is a map
 * lookup but a run that failed on one source fails on all of its entries at once.
 *
 * Two groups that would show the same name are each labelled with their language as well, the way
 * `HttpSource.toString` renders a source, so two identical headers never sit in one list. A source
 * with no remembered language keeps its bare name, as `StubSource.toString` does.
 */
fun groupUpdateErrors(
    errors: List<LibraryUpdateError>,
    source: (Long) -> Source,
): List<UpdateErrorGroup> {
    val sources = errors.map { it.sourceId }.distinct().associateWith(source)
    val sharedNames = sources.values.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    val labels = sources.mapValues { (_, src) ->
        if (src.name in sharedNames && src.lang.isNotBlank()) "${src.name} (${src.lang.uppercase()})" else src.name
    }
    return errors
        .groupBy { it.sourceId }
        .map { (sourceId, rows) ->
            UpdateErrorGroup(sourceId, labels.getValue(sourceId), rows.sortedBy { it.title.lowercase() })
        }
        .sortedWith(compareBy<UpdateErrorGroup> { it.sourceName.lowercase() }.thenBy { it.sourceId })
}

/**
 * The entries the run never asked because their site was stopped for the rest of it
 * ([ErrorKey.SITE_STOPPED]), which the Updates error sheet explains above the list with the way to update
 * fewer entries at once (F7). A row stored before keys were has a null key and is not counted.
 */
fun stoppedEntries(errors: List<LibraryUpdateError>): Int = errors.count { it.key == ErrorKey.SITE_STOPPED }
