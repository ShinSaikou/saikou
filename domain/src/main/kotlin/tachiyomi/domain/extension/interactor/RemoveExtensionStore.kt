// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/extension/interactor/RemoveExtensionStore.kt (modified: yes)
package tachiyomi.domain.extension.interactor

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository

/** Modified: the repository is the fresh interface, and `invoke` is spelled `await` as elsewhere. */
class RemoveExtensionStore(
    private val repository: ExtensionStoreRepository,
) {
    suspend fun await(indexUrl: String) {
        repository.remove(indexUrl)
    }
}
