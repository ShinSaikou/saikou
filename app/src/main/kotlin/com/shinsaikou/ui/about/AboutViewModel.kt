package com.shinsaikou.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shinsaikou.appupdate.AppUpdateBuild
import com.shinsaikou.appupdate.GitHubReleases
import com.shinsaikou.presentation.core.viewmodel.StateViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException

/**
 * More > About: the latest release's APKs, read once when the screen opens and again on Retry,
 * and the bug report. Check for updates is `AppUpdateViewModel`'s, as in Settings, so there is one
 * update check and one prompt. Reading the release list is not an update check: it records no
 * check time and offers nothing.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(AboutViewModel::class)
@Inject
class AboutViewModel(
    private val releases: GitHubReleases,
    private val build: AppUpdateBuild,
    private val reports: BugReportWriter,
) : StateViewModel<AboutViewModel.State>(State()) {

    sealed interface Builds {
        data object Loading : Builds

        /** GitHub could not be reached, or did not answer with a release. */
        data object Failed : Builds

        /** Empty when the repository has no release, or the latest one has no APK. */
        data class Loaded(val assets: List<BuildAsset>) : Builds
    }

    data class State(
        val builds: Builds = Builds.Loading,
        /** True while the report is written, so a second tap does not write a second one. */
        val writingReport: Boolean = false,
    )

    sealed interface Event {
        /** Share the report at [path]; the screen does, since only an activity can start the chooser. */
        data class ShareReport(val path: String) : Event

        data object ReportFailed : Event
    }

    private val eventChannel = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = eventChannel.receiveAsFlow()

    /** The header of the report, which Copy device info copies alone. */
    val deviceInfo: DeviceInfo by lazy { reports.deviceInfo() }

    init {
        loadBuilds()
    }

    fun loadBuilds() {
        mutableState.update { it.copy(builds = Builds.Loading) }
        viewModelScope.launch {
            val builds = try {
                Builds.Loaded(releases.latest()?.let { AboutBuilds.of(it, build) }.orEmpty())
            } catch (e: IOException) {
                logcat(LogPriority.INFO, e) { "The About screen could not read the latest release" }
                Builds.Failed
            }
            mutableState.update { it.copy(builds = builds) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun shareBugReport() {
        if (state.value.writingReport) return
        mutableState.update { it.copy(writingReport = true) }
        viewModelScope.launch {
            val event = try {
                Event.ShareReport(reports.write().absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "The bug report could not be written" }
                Event.ReportFailed
            } finally {
                mutableState.update { it.copy(writingReport = false) }
            }
            eventChannel.send(event)
        }
    }
}
