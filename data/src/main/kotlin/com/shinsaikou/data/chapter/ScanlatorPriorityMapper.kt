package com.shinsaikou.data.chapter

import com.shinsaikou.domain.chapter.model.ScanlatorPriority

/**
 * The `scanlator_priority` columns in declaration order. `_id` stays in the table: the ranking is
 * keyed by `(manga_id, scanlator)` and is replaced wholesale, so no caller ever holds a row id.
 */
fun mapScanlatorPriority(
    id: Long,
    mangaId: Long,
    scanlator: String,
    priority: Long,
): ScanlatorPriority = ScanlatorPriority(
    mangaId = mangaId,
    scanlator = scanlator,
    priority = priority,
)
