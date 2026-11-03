package com.shinsaikou.ui.browse

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.extensions.store.AddExtensionStore
import com.shinsaikou.extensions.store.UpdateExtensionStores
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The repository list. Rows come from the table rather than from the add form's result, so a row
 * appears only once the descriptor an address resolved to was actually persisted.
 *
 * A repository added here has its index read at once and one removed here has its entries dropped,
 * both through [ExtensionManager], so the Extensions page shows what the list now offers without a
 * refresh. The add runs in the app's scope rather than this screen's, so Back pressed while it runs
 * does not cancel it.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ExtensionStoresViewModel::class)
@Inject
class ExtensionStoresViewModel(
    private val storeRepository: ExtensionStoreRepository,
    private val addExtensionStore: AddExtensionStore,
    private val updateExtensionStores: UpdateExtensionStores,
    private val extensionManager: ExtensionManager,
    private val dispatchers: Dispatchers,
    private val appScope: CoroutineScope,
) : StateViewModel<ExtensionStoresViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val stores: List<StoreRow> = emptyList(),
        val address: String = "",
        val adding: Boolean = false,
        /**
         * The store the remove confirmation names while it is open (owner-delegated decision 4.15,
         * upstream's `ExtensionStoreDeleteDialog`); the remove acts on this row only. Null while closed.
         */
        val removing: StoreRow? = null,
        /** The index URLs whose extensions are being read, a repository just added; each row shows it. */
        val refreshing: Set<String> = emptySet(),
    )

    data class StoreRow(val name: String, val indexUrl: String)

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event

        /**
         * A rejected address: the reason the add path gives is more use than a generic line, and
         * ErrorText needs Resources, so the screen is what turns it into text.
         */
        data class SnackbarError(val error: Throwable) : Event

        /** An added repository whose extensions could not be read, worded as a failed refresh is. */
        data class RefreshFailed(val error: Throwable) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                storeRepository.getAllAsFlow()
                    .map { stores -> stores.map { StoreRow(name = it.name, indexUrl = it.indexUrl) } },
                extensionManager.refreshingStores,
                ::Pair,
            )
                .flowOn(dispatchers.io)
                .collect { (rows, refreshing) ->
                    mutableState.update { it.copy(loading = false, stores = rows, refreshing = refreshing) }
                }
        }
    }

    fun setAddress(text: String) {
        mutableState.update { it.copy(address = text) }
    }

    fun add() {
        val address = state.value.address.trim()
        if (address.isEmpty() || state.value.adding) return
        mutableState.update { it.copy(adding = true) }
        // The app's scope: an add the owner left the screen during still completes, and the
        // Extensions page lists what the repository offers.
        appScope.launch {
            val result = withContext(dispatchers.io) { addExtensionStore.await(address) }
            // The field is cleared only on success, so a rejected address stays there to be edited.
            mutableState.update { it.copy(adding = false, address = if (result.isSuccess) "" else it.address) }
            if (!viewModelScope.isActive) {
                // The screen has closed and no snackbar would be seen; the Extensions page's banner
                // says why the repository never appeared.
                result.exceptionOrNull()?.let(extensionManager::reportStoreFailure)
            } else {
                eventChannel.send(
                    result.fold(
                        onSuccess = { Event.Snackbar(R.string.ext_stores_added) },
                        onFailure = { error ->
                            if (error.message.isNullOrBlank()) {
                                Event.Snackbar(R.string.ext_stores_add_failed)
                            } else {
                                Event.SnackbarError(error)
                            }
                        },
                    ),
                )
            }
            val store = result.getOrNull() ?: return@launch
            // The manager's own job, and its failure reaches the Extensions page's banner whatever
            // this screen does; the snackbar is only for a screen still open to show it.
            extensionManager.refreshStore(store.indexUrl).await()
                .onFailure { if (viewModelScope.isActive) eventChannel.send(Event.RefreshFailed(it)) }
        }
    }

    /** Opens the confirmation naming [row]; nothing is removed until it is confirmed. */
    fun askRemove(row: StoreRow) {
        mutableState.update { it.copy(removing = row) }
    }

    fun dismissRemove() {
        mutableState.update { it.copy(removing = null) }
    }

    /** Removes the store the confirmation named and closes it. */
    fun confirmRemove() {
        val row = state.value.removing ?: return
        mutableState.update { it.copy(removing = null) }
        viewModelScope.launch {
            withContext(dispatchers.io) {
                storeRepository.remove(row.indexUrl)
                extensionManager.forgetStore(row.indexUrl)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            // A refetch that throws must still say so. Without the catch the coroutine dies on the
            // scope's SupervisorJob and the screen shows nothing at all.
            val outcome = runCatching { withContext(dispatchers.io) { updateExtensionStores.await() } }
            eventChannel.send(
                outcome.fold(
                    onSuccess = { Event.Snackbar(R.string.ext_stores_refreshed) },
                    onFailure = { Event.SnackbarError(it) },
                ),
            )
        }
    }
}
