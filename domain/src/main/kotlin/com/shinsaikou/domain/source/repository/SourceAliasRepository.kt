package com.shinsaikou.domain.source.repository

import com.shinsaikou.domain.source.model.SourceAlias

interface SourceAliasRepository {
    suspend fun getAll(): List<SourceAlias>

    /** Follows the chain old to new to newer up to 8 hops; returns the input when no alias exists. */
    suspend fun resolve(sourceId: Long): Long

    suspend fun upsert(alias: SourceAlias)

    suspend fun remove(oldSourceId: Long)
}
