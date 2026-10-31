package com.shinsaikou.domain.source.model

/** Records that a source changed its id, so library rows can be carried over. */
data class SourceAlias(val oldSourceId: Long, val newSourceId: Long, val dateAdded: Long)
