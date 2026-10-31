// Vendored from upstream v0.20.4: data/src/main/java/tachiyomi/data/source/StubSourceRepositoryImpl.kt (modified: yes)
package tachiyomi.data.source

import com.shinsaikou.data.DatabaseHandler
import com.shinsaikou.source.StubSource
import com.shinsaikou.source.StubSourceNames
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.repository.StubSourceRepository

/**
 * Modified: the constructor takes [DatabaseHandler], and the stub type is
 * `com.shinsaikou.source.StubSource` rather than upstream's `tachiyomi.domain.source.model.StubSource`.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class StubSourceRepositoryImpl(
    private val handler: DatabaseHandler,
) : StubSourceRepository {

    override fun subscribeAll(): Flow<List<StubSource>> {
        return handler.subscribeToList { sourcesQueries.findAll(::mapStubSource) }
    }

    override suspend fun getStubSource(id: Long): StubSource? {
        return handler.awaitOneOrNull { sourcesQueries.findOne(id, ::mapStubSource) }
    }

    override suspend fun upsertStubSource(id: Long, lang: String, name: String) {
        handler.await { sourcesQueries.upsert(id, lang, name) }
    }

    private fun mapStubSource(
        id: Long,
        lang: String,
        name: String,
    ): StubSource = StubSource(id = id, lang = lang, name = name)
}

/**
 * Ruling R2: `:data` backs `:source-contract`'s [StubSourceNames] with the same `sources` table
 * the stub repository reads, so an uninstalled source still renders with the last name and
 * language it reported.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DatabaseStubSourceNames(
    private val handler: DatabaseHandler,
) : StubSourceNames {

    override suspend fun find(id: Long): StubSourceNames.Entry? {
        return handler.awaitOneOrNull {
            sourcesQueries.findOne(id) { rowId, lang, name ->
                StubSourceNames.Entry(id = rowId, name = name, lang = lang)
            }
        }
    }

    override suspend fun remember(id: Long, name: String, lang: String) {
        handler.await { sourcesQueries.upsert(id, lang, name) }
    }
}
