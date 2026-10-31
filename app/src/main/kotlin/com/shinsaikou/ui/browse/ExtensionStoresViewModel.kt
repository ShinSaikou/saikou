package com.shinsaikou.ui.browse

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.extensions.store.AddExtensionStore
import com.shinsaikou.extensions.store.UpdateExtensionStores
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The repository list. Rows come from the table rather than from the add form's result, so a row
 * appears only once the descriptor an address resolved to was actually persisted.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ExtensionStoresViewModel::class)
@Inject
class ExtensionStoresViewModel(
    private val storeRepository: ExtensionStoreRepository,
    private val addExtensionStore: AddExtensionStore,
    private val updateExtensionStores: UpdateExtensionStores,
    private val dispatchers: Dispatchers,
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
    )

    data class StoreRow(val name: String, val indexUrl: String)

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event

        /**
         * A rejected address: the reason the add path gives is more use than a generic line, and
         * ErrorText needs Resources, so the screen is what turns it into text.
         */
        data class SnackbarError(val error: Throwable) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            storeRepository.getAllAsFlow()
                .map { stores -> stores.map { StoreRow(name = it.name, indexUrl = it.indexUrl) } }
                .flowOn(dispatchers.io)
                .collect { rows -> mutableState.update { it.copy(loading = false, stores = rows) } }
        }
    }

    fun setAddress(text: String) {
        mutableState.update { it.copy(address = text) }
    }

    fun add() {
        val address = state.value.address.trim()
        if (address.isEmpty() || state.value.adding) return
        mutableState.update { it.copy(adding = true) }
        viewModelScope.launch {
            val result = withContext(dispatchers.io) { addExtensionStore.await(address) }
            // The field is cleared only on success, so a rejected address stays there to be edited.
            mutableState.update { it.copy(adding = false, address = if (result.isSuccess) "" else it.address) }
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
            withContext(dispatchers.io) { storeRepository.remove(row.indexUrl) }
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
