package com.shinsaikou.ui.storage

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hippo.unifile.UniFile
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.StoragePreferences
import com.shinsaikou.domain.storage.StorageLocation
import com.shinsaikou.downloads.export.DownloadExportScheduler
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import com.shinsaikou.storage.StorageGrants
import com.shinsaikou.storage.StorageMove
import com.shinsaikou.storage.StorageMoveScheduler
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.source.local.io.LocalSourceFileSystem

/**
 * Upstream's Storage location, behind the Settings > Data row, the Backup screen's Choose folder and
 * the empty Local listing's Choose folder (unit storage-location). A picked tree with nothing to
 * move (in the app folders, or in the location it replaces) is set at once and its three folders
 * made; otherwise [State.choice] holds it until the owner says Move (set, then the move job) or
 * Cancel (the old setting stays, and the new grant is given back). Unit storage-move-between-folders:
 * the location it replaces is counted too, and kept as the previous location until the move has
 * emptied it.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(StorageLocationViewModel::class)
@Inject
class StorageLocationViewModel(
    private val context: Context,
    private val preferences: StoragePreferences,
    private val storageLocation: StorageLocation,
    private val move: StorageMove,
    private val moveScheduler: StorageMoveScheduler,
    private val grants: StorageGrants,
    private val exportScheduler: DownloadExportScheduler,
    private val localFileSystem: LocalSourceFileSystem,
    private val dispatchers: Dispatchers,
) : StateViewModel<StorageLocationViewModel.State>(State()) {

    data class State(
        val status: StorageLocation.Status? = null,
        val name: String? = null,
        /** What is still in the app folders or the previous location; counted only while a reachable location is set. */
        val left: StorageMove.Pending? = null,
        /** The previous location's name, while some of [left] is in it. */
        val previousName: String? = null,
        val move: StorageMoveScheduler.State = StorageMoveScheduler.State(running = false),
        val choice: Choice? = null,
        /** The folder the Local source reads, as its empty listing names it. */
        val localFolder: String? = null,
    )

    /**
     * A picked folder waiting for Move or Cancel, and what Move would take: [from] names the location
     * it replaces when some of it is there; [needed] is what has to be copied (a move on one storage
     * needs no room) and [free] the new folder's free space, null when it cannot be read.
     */
    data class Choice(
        val uri: String,
        val name: String,
        val pending: StorageMove.Pending,
        val from: String? = null,
        val needed: Long = 0,
        val free: Long? = null,
    )

    /** What to do once a folder is chosen from a row that needs one. */
    enum class Then { NOTHING, EXPORT_DOWNLOADS }

    private var then = Then.NOTHING

    init {
        refresh()
        viewModelScope.launch {
            moveScheduler.state.flowOn(dispatchers.io).collect { moving ->
                val ended = mutableState.value.move.running && !moving.running
                mutableState.update { it.copy(move = moving) }
                if (ended) refresh()
            }
        }
        viewModelScope.launch {
            preferences.storageLocation.changes().collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val read = withContext(dispatchers.io) {
                val status = storageLocation.status()
                val left = if (status == StorageLocation.Status.REACHABLE) move.pending().takeIf { !it.isEmpty } else null
                State(
                    status = status,
                    name = storageLocation.displayName(),
                    left = left,
                    previousName = storageLocation.previousDisplayName().takeIf { left?.fromOldLocation == true },
                    localFolder = localFileSystem.readingFrom(),
                )
            }
            mutableState.update {
                it.copy(
                    status = read.status,
                    name = read.name,
                    left = read.left,
                    previousName = read.previousName,
                    localFolder = read.localFolder,
                )
            }
        }
    }

    /** Remembers what the row that opened the picker wants done once a folder is set. */
    fun pickingFor(then: Then) {
        this.then = then
    }

    /** The tree the picker returned, its grant already taken. */
    fun onPicked(tree: Uri) {
        viewModelScope.launch {
            val choice = withContext(dispatchers.io) {
                // Upstream stores the tree's document URI, as UniFile answers it.
                val uri = (UniFile.fromUri(context, tree)?.uri ?: tree).toString()
                val pending = move.pendingFor(uri)
                if (pending.isEmpty) {
                    Choice(uri, StorageLocation.nameOf(uri), pending)
                } else {
                    val current = preferences.storageLocation.get()
                    val sameVolume = pending.fromOldLocation && StorageLocation.sameVolume(current, uri)
                    Choice(
                        uri = uri,
                        name = StorageLocation.nameOf(uri),
                        pending = pending,
                        from = storageLocation.displayName().takeIf { pending.fromOldLocation },
                        needed = pending.bytes - if (sameVolume) pending.oldLocationBytes else 0L,
                        free = freeBytes(uri),
                    )
                }
            }
            if (choice.pending.isEmpty) apply(choice, startMove = false) else mutableState.update { it.copy(choice = choice) }
        }
    }

    fun confirmMove() {
        val choice = mutableState.value.choice ?: return
        mutableState.update { it.copy(choice = null) }
        viewModelScope.launch { apply(choice, startMove = true) }
    }

    fun cancelChoice() {
        val choice = mutableState.value.choice ?: return
        mutableState.update { it.copy(choice = null) }
        then = Then.NOTHING
        // A folder picked again while it is the location or the previous one keeps the grant it still needs.
        val kept = setOf(preferences.storageLocation.get(), preferences.previousStorageLocation.get())
        if (choice.uri !in kept) grants.release(choice.uri)
    }

    /** Moves what is still in the app folders or the previous location: the row under Storage location. */
    fun moveLeft() = moveScheduler.start()

    private suspend fun apply(choice: Choice, startMove: Boolean) {
        val made = withContext(dispatchers.io) {
            move.setLocation(choice.uri, choice.pending)
            storageLocation.createFolders()
        }
        if (startMove) moveScheduler.start()
        if (then == Then.EXPORT_DOWNLOADS && made) exportScheduler.start()
        then = Then.NOTHING
        refresh()
    }

    /**
     * The free space of the storage [uri] is on, from its file path when UniFile can give one (the
     * system's external storage provider); null when it cannot be read, and the check is skipped.
     */
    private fun freeBytes(uri: String): Long? = runCatching {
        UniFile.fromUri(context, Uri.parse(uri))?.filePath?.let { StatFs(it).availableBytes }
    }.getOrNull()
}
