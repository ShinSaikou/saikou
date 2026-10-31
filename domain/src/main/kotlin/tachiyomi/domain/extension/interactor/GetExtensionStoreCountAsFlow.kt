// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/extension/interactor/GetExtensionStoreCountAsFlow.kt (modified: yes)
package tachiyomi.domain.extension.interactor

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository

class GetExtensionStoreCountAsFlow(
    private val repository: ExtensionStoreRepository,
) {
    operator fun invoke() = repository.getCountAsFlow()
}
