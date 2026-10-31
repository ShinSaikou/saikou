package com.shinsaikou.data.source

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.domain.source.model.SourceAlias
import com.shinsaikou.domain.source.repository.SourceAliasRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceAliasRepositoryImpl(
    private val handler: DatabaseHandler,
) : SourceAliasRepository {

    override suspend fun getAll(): List<SourceAlias> =
        handler.awaitList { source_aliasQueries.getAll(::mapSourceAlias) }

    /**
     * One query per hop, deliberately outside a transaction: the table is tiny, the walk is a read,
     * and holding a transaction open across eight round trips buys nothing. The hop bound also
     * terminates a cycle, which a badly restored backup could introduce.
     */
    override suspend fun resolve(sourceId: Long): Long {
        var current = sourceId
        repeat(MAX_HOPS) {
            val next = handler.awaitOneOrNull {
                source_aliasQueries.getByOldSourceId(current, ::mapSourceAlias)
            } ?: return current
            current = next.newSourceId
        }
        return current
    }

    override suspend fun upsert(alias: SourceAlias) {
        handler.await {
            source_aliasQueries.upsert(
                oldSourceId = alias.oldSourceId,
                newSourceId = alias.newSourceId,
                now = alias.dateAdded,
            )
        }
    }

    override suspend fun remove(oldSourceId: Long) {
        handler.await { source_aliasQueries.delete(oldSourceId) }
    }

    private companion object {
        const val MAX_HOPS = 8
    }
}
