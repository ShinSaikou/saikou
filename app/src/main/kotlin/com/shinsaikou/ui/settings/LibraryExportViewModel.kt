package com.shinsaikou.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.ui.backup.BackupFileCopy
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFavorites

/**
 * The Data section's Export library list: every library entry, as [libraryCsv] writes it, into the
 * document the system's save dialog made. A view model of its own, as the backup copy's is, so
 * the settings view model takes on none of it.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(LibraryExportViewModel::class)
@Inject
class LibraryExportViewModel(
    private val getFavorites: GetFavorites,
    private val files: BackupFileCopy,
    private val dispatchers: Dispatchers,
) : ViewModel() {

    /**
     * Writes the list into [target]. Not cancelled by leaving the screen, as the Advanced rows'
     * writes are not; [onResult] gets whether the file was written, for the Settings snackbar.
     */
    fun export(target: Uri, columns: LibraryCsvColumns, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val written = try {
                withContext(NonCancellable + dispatchers.io) {
                    files.write(target, libraryCsv(getFavorites.await(), columns))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Could not read the library for its export" }
                false
            }
            onResult(written)
        }
    }
}
