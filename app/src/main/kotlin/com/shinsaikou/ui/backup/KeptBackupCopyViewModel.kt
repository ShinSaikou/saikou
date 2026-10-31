package com.shinsaikou.ui.backup

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.backup.export.AutomaticBackupFiles
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The sync safety backup notice's Save a copy (F11, unit storage-location): finds the kept file by
 * the name the notice shows, wherever it is now (the storage location or the app folder), and
 * copies it into the document the system's save dialog made. [State.result] says how it went, in
 * the notice, until the next copy.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(KeptBackupCopyViewModel::class)
@Inject
class KeptBackupCopyViewModel(
    private val files: AutomaticBackupFiles,
    private val copy: BackupFileCopy,
    private val dispatchers: Dispatchers,
) : StateViewModel<KeptBackupCopyViewModel.State>(State()) {

    /** [available] is whether the file the notice names is still kept, so the button has something to copy. */
    data class State(val name: String = "", val available: Boolean = false, @StringRes val result: Int? = null)

    /** Looks [name] up; called whenever the notice's file name changes. */
    fun watch(name: String) {
        if (name == mutableState.value.name && mutableState.value.available) return
        viewModelScope.launch {
            val found = name.isNotEmpty() && withContext(dispatchers.io) { files.find(name) } != null
            mutableState.update { it.copy(name = name, available = found) }
        }
    }

    fun saveCopy(target: Uri) {
        val name = mutableState.value.name
        viewModelScope.launch {
            val saved = withContext(dispatchers.io) {
                val kept = files.find(name) ?: return@withContext false
                copy.copy(kept.file.uri, target)
            }
            mutableState.update { it.copy(result = if (saved) R.string.backup_copy_saved else R.string.backup_copy_failed) }
        }
    }
}
