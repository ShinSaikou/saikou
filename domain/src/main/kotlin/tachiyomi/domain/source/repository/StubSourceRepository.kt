// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/source/repository/StubSourceRepository.kt (modified: yes)
package tachiyomi.domain.source.repository

import com.shinsaikou.source.StubSource
import kotlinx.coroutines.flow.Flow

interface StubSourceRepository {
    fun subscribeAll(): Flow<List<StubSource>>

    suspend fun getStubSource(id: Long): StubSource?

    /** ON CONFLICT(_id) DO UPDATE lang, name. Called whenever an installed source is seen. */
    suspend fun upsertStubSource(id: Long, lang: String, name: String)
}
