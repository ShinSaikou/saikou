package com.shinsaikou.ui.appupdate

import android.content.res.Resources
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.appupdate.AppUpdate
import com.shinsaikou.appupdate.AppUpdateBuild
import com.shinsaikou.appupdate.AppUpdateChecker
import com.shinsaikou.appupdate.AppUpdateInstaller
import com.shinsaikou.appupdate.AppUpdatePreferences
import com.shinsaikou.appupdate.AppUpdateScheduler
import com.shinsaikou.i18n.R
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The line a check the owner asked for ends with, on the snackbar of the screen it was asked from
 * (Settings > Advanced, More > About); null for a newer release, which raises the prompt instead.
 */
internal fun AppUpdateChecker.Result.message(resources: Resources, appName: String): String? = when (this) {
    is AppUpdateChecker.Result.Available -> null
    AppUpdateChecker.Result.UpToDate -> resources.getString(R.string.app_update_up_to_date)
    AppUpdateChecker.Result.Failed -> resources.getString(R.string.app_update_check_failed)
    is AppUpdateChecker.Result.NoApk -> resources.getString(R.string.app_update_no_apk, appName, version)
}

/**
 * The prompt over the main screen, the two rows in Settings > Advanced and the one in More > About. Each holds its own
 * instance; the offer and the check in progress are [AppUpdateChecker]'s, one per process, so both
 * see the same.
 *
 * "Update" is the one click: it starts the download at once when the owner already lets this app
 * install apps, and otherwise first asks the screen to open Android's "Install unknown apps" page
 * ([State.askInstallPermission]) and starts it when the owner comes back with the permission.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(AppUpdateViewModel::class)
@Inject
class AppUpdateViewModel(
    private val checker: AppUpdateChecker,
    private val scheduler: AppUpdateScheduler,
    private val installer: AppUpdateInstaller,
    /** The Settings row's "Check for updates automatically". */
    val preferences: AppUpdatePreferences,
    /** Whether this build checks by itself at all, which the Settings row says when it does not. */
    val build: AppUpdateBuild,
) : ViewModel() {

    /** A line the screen shows once and then reports with [noticeShown]. */
    sealed interface Notice {
        /** The download of [version] started; Android asks to install it when it is done. */
        data class Downloading(val version: String) : Notice

        /** The owner came back from Android's page without allowing installs. */
        data object InstallNotAllowed : Notice
    }

    data class State(
        /** The release the prompt shows, or null for no prompt. */
        val offer: AppUpdate? = null,
        val checking: Boolean = false,
        /** The screen should open Android's "Install unknown apps" page for this app, then call [onInstallPermissionResult]. */
        val askInstallPermission: Boolean = false,
        val notice: Notice? = null,
    )

    private val local = MutableStateFlow(LocalState())

    /** The update waiting on the owner's answer on Android's page. */
    private var awaitingPermission: AppUpdate? = null

    val state: StateFlow<State> = combine(checker.offer, checker.checking, local) { offer, checking, local ->
        State(offer = offer, checking = checking, askInstallPermission = local.askInstallPermission, notice = local.notice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), State())

    /** "Check for updates" in Settings and in About; [onResult] says how it went, and a newer release also raises the prompt. */
    fun checkNow(onResult: (AppUpdateChecker.Result) -> Unit) {
        viewModelScope.launch { onResult(checker.checkNow()) }
    }

    fun notNow() = checker.dismiss()

    fun skip() {
        checker.offer.value?.let(checker::skip)
    }

    fun update() {
        val update = checker.offer.value ?: return
        checker.dismiss()
        viewModelScope.launch {
            if (installer.canRequestInstalls()) {
                start(update)
            } else {
                awaitingPermission = update
                local.update { it.copy(askInstallPermission = true) }
            }
        }
    }

    /** The screen opened Android's page (or could not); the request is not repeated. */
    fun installPermissionAsked() {
        local.update { it.copy(askInstallPermission = false) }
    }

    /**
     * The owner is back from Android's page. With the permission the download starts; without it
     * nothing does, and the screen says why. [pageOpened] is false when the page could not be opened
     * at all (a device without it), and then the download starts anyway: Android's own install
     * prompt offers the same switch.
     */
    fun onInstallPermissionResult(pageOpened: Boolean = true) {
        val update = awaitingPermission ?: return
        awaitingPermission = null
        viewModelScope.launch {
            if (!pageOpened || installer.canRequestInstalls()) {
                start(update)
            } else {
                local.update { it.copy(notice = Notice.InstallNotAllowed) }
            }
        }
    }

    fun noticeShown() {
        local.update { it.copy(notice = null) }
    }

    private fun start(update: AppUpdate) {
        scheduler.start(update)
        local.update { it.copy(notice = Notice.Downloading(update.version)) }
    }

    private data class LocalState(val askInstallPermission: Boolean = false, val notice: Notice? = null)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
