package com.shinsaikou.data.source

import com.shinsaikou.domain.source.model.SourceAlias

/**
 * The `source_alias` columns in declaration order. `_id` is a surrogate key the domain model does
 * not carry: `old_source_id` is already unique and is what callers resolve by.
 */
fun mapSourceAlias(
    id: Long,
    oldSourceId: Long,
    newSourceId: Long,
    dateAdded: Long,
): SourceAlias = SourceAlias(
    oldSourceId = oldSourceId,
    newSourceId = newSourceId,
    dateAdded = dateAdded,
)
