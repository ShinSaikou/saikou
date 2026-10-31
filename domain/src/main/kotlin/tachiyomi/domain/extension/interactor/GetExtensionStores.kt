// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/extension/interactor/GetExtensionStores.kt (modified: yes)
package tachiyomi.domain.extension.interactor

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.extension.model.ExtensionStore

class GetExtensionStores(
    private val repository: ExtensionStoreRepository,
) {
    suspend fun get(): List<ExtensionStore> = repository.getAll()

    fun subscribe(): Flow<List<ExtensionStore>> = repository.getAllAsFlow()
}
