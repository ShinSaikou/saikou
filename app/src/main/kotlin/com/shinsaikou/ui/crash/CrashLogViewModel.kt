package com.shinsaikou.ui.crash

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.crash.CrashLog
import com.shinsaikou.i18n.R
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val BYTES_PER_KIB = 1024

/**
 * The local crash files, read from app private storage. State carries paths, sizes and times rather
 * than `File` handles, so nothing composable ever touches the filesystem to render a row.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(CrashLogViewModel::class)
@Inject
class CrashLogViewModel(
    private val crashLog: CrashLog,
    private val dispatchers: Dispatchers,
) : StateViewModel<CrashLogViewModel.State>(State()) {

    data class State(
        val loading: Boolean = true,
        val files: List<LogFile> = emptyList(),
        val selected: String? = null,
        val selectedText: String = "",
    )

    /** [modifiedAt] is the file's timestamp in epoch milliseconds, the time `CrashLog.write` ran. */
    data class LogFile(
        val name: String,
        val sizeKib: Int,
        val path: String,
        val modifiedAt: Long,
    )

    sealed interface Event {
        data class Snackbar(@StringRes val messageRes: Int) : Event

        /** Share the file at [path]; the screen does, since only an activity can start the chooser. */
        data class Share(val path: String) : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val files = withContext(dispatchers.io) { crashLog.list().map(::toRow) }
            mutableState.update { it.copy(loading = false, files = files) }
        }
    }

    fun clear() {
        viewModelScope.launch {
            withContext(dispatchers.io) { crashLog.clear() }
            mutableState.update { it.copy(files = emptyList(), selected = null, selectedText = "") }
        }
    }

    /**
     * One file, the way [open] reads one: the path came out of `crashLog.list()`, and routing a
     * single delete through `CrashLog` would add an API for one caller when `clear()` already
     * covers the whole directory.
     *
     * No confirmation. `clear()` deletes every file without asking, and a crash log is
     * diagnostics rather than anything the owner made.
     */
    fun delete(path: String) {
        viewModelScope.launch {
            withContext(dispatchers.io) { File(path).delete() }
            mutableState.update { state ->
                state.copy(
                    files = state.files.filterNot { it.path == path },
                    selected = state.selected.takeUnless { it == path },
                    selectedText = if (state.selected == path) "" else state.selectedText,
                )
            }
        }
    }

    /**
     * The viewer opens with its text already in hand: a crash file is a few kilobytes, and showing
     * an empty dialog first only flickers. Reading it is also what settles the crash banner, since
     * a log the owner has read is no longer news on the next launch.
     */
    fun open(path: String) {
        viewModelScope.launch {
            val text = withContext(dispatchers.io) { runCatching { File(path).readText() }.getOrNull() }
            if (text == null) {
                eventChannel.send(Event.Snackbar(R.string.crash_read_failed))
                return@launch
            }
            mutableState.update { it.copy(selected = path, selectedText = text) }
            withContext(dispatchers.io) { crashLog.markExported() }
        }
    }

    /**
     * Hands one log to the screen to share through `CrashLogExport`, from its row or from the
     * viewer. This is the only way a log leaves the device once the launch banner is gone. Sharing
     * settles the banner as reading does, whether or not a chooser appears, as the banner's own
     * Export does. A file gone since the list was read says so instead.
     */
    fun share(path: String) {
        viewModelScope.launch {
            if (!withContext(dispatchers.io) { File(path).isFile }) {
                eventChannel.send(Event.Snackbar(R.string.crash_read_failed))
                return@launch
            }
            eventChannel.send(Event.Share(path))
            withContext(dispatchers.io) { crashLog.markExported() }
        }
    }

    fun closeViewer() {
        mutableState.update { it.copy(selected = null, selectedText = "") }
    }

    private fun toRow(file: File) = LogFile(
        name = file.name,
        sizeKib = (file.length() / BYTES_PER_KIB).toInt(),
        path = file.absolutePath,
        modifiedAt = file.lastModified(),
    )
}
