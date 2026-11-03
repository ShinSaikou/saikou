package com.shinsaikou.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinsaikou.i18n.R
import com.shinsaikou.navigation.LocalNavigator
import com.shinsaikou.ui.components.InlineNotice
import com.shinsaikou.ui.components.ListRow
import com.shinsaikou.ui.components.ScreenTopBar
import com.shinsaikou.ui.components.SectionLabel
import com.shinsaikou.ui.components.SaikouSnackbarHost
import dev.zacsweers.metrox.viewmodel.metroViewModel
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * [pendingUrl] is an index address that arrived as a `saikou://extension-store?url=...` intent. It
 * is only ever placed in the add field: a link cannot make this app trust a repository, so the
 * owner still has to read the address and tap Add. Anything else would let a page the owner merely
 * opened decide where extensions come from.
 */
@Composable
fun ExtensionStoresScreen(pendingUrl: String? = null) {
    val viewModel: ExtensionStoresViewModel = metroViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(pendingUrl) {
        if (!pendingUrl.isNullOrBlank()) viewModel.setAddress(pendingUrl)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is ExtensionStoresViewModel.Event.Snackbar -> resources.getString(event.messageRes)
                is ExtensionStoresViewModel.Event.SnackbarError -> RepoErrorText.of(resources, event.error)
                is ExtensionStoresViewModel.Event.RefreshFailed ->
                    resources.getString(R.string.ext_refresh_failed, RepoErrorText.of(resources, event.error))
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = { scrollBehavior ->
            ScreenTopBar(
                title = stringResource(R.string.ext_stores_title),
                onBack = navigator::back,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                        )
                    }
                },
            )
        },
        snackbarHost = { SaikouSnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.loading) {
            LoadingScreen(Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                item {
                    AddStoreForm(
                        address = state.address,
                        adding = state.adding,
                        onAddressChange = viewModel::setAddress,
                        onAdd = viewModel::add,
                    )
                }
                if (state.stores.isEmpty()) {
                    item { InlineNotice(stringResource(R.string.ext_stores_empty)) }
                }
                items(state.stores, key = { it.indexUrl }) { row ->
                    ListRow(
                        title = row.name,
                        detail = row.indexUrl,
                        trailing = stringResource(R.string.action_remove),
                        onTrailing = { viewModel.askRemove(row) },
                        // While a repository just added has its extensions read.
                        trailingIcon = if (row.indexUrl in state.refreshing) {
                            { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    state.removing?.let { row ->
        RemoveStoreDialog(
            storeName = row.name,
            storeIndexUrl = row.indexUrl,
            onConfirm = viewModel::confirmRemove,
            onDismiss = viewModel::dismissRemove,
        )
    }
}

/**
 * Upstream's `ExtensionStoreDeleteDialog`, its title, body and buttons: the store named with its index
 * address, since two stores can share a name (owner-delegated decision 4.15).
 */
@Composable
private fun RemoveStoreDialog(
    storeName: String,
    storeIndexUrl: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ext_stores_remove_title)) },
        text = { Text(stringResource(R.string.ext_stores_remove_body, storeName, storeIndexUrl)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun AddStoreForm(
    address: String,
    adding: Boolean,
    onAddressChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.ext_stores_add))
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text(stringResource(R.string.ext_stores_hint)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            enabled = !adding,
            singleLine = true,
            // An address is typed, not written: autocorrect turns a host into a word.
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            enabled = address.isNotBlank() && !adding,
        ) {
            Text(stringResource(R.string.action_add))
        }
    }
}
