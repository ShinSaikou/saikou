package com.shinsaikou.appupdate

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import kotlin.time.Clock

/**
 * Asks GitHub for the latest release and holds the one the prompt offers ([offer]), for the whole
 * process: the prompt over the main screen and the Settings row read the same instance.
 *
 * Two ways in. [checkAutomatically] is the foreground arrival's: only when [AutomaticCheck.due]
 * says so, silent when GitHub cannot be reached, and a skipped version stays skipped.
 * [checkNow] is Settings' "Check for updates": always asks, says how it went, and offers a skipped
 * version too, since the owner asked. Either records the time it asked, so GitHub hears from the
 * app at most once a day unless the owner asks again. One check at a time.
 */
@SingleIn(AppScope::class)
@Inject
class AppUpdateChecker(
    private val releases: GitHubReleases,
    private val preferences: AppUpdatePreferences,
    private val build: AppUpdateBuild,
    private val clock: Clock,
) {

    /** How a check the owner asked for went, for Settings to say. */
    sealed interface Result {
        /** A newer release is in [offer], and the prompt is up. */
        data class Available(val update: AppUpdate) : Result

        /** The installed build is the newest there is, as far as the latest release says. */
        data object UpToDate : Result

        /** [version] is out, but has no APK for this device. */
        data class NoApk(val version: String) : Result

        /** GitHub could not be reached, or did not answer with a release. */
        data object Failed : Result
    }

    private val mutex = Mutex()
    private val mutableOffer = MutableStateFlow<AppUpdate?>(null)
    private val mutableChecking = MutableStateFlow(false)

    /** The release the prompt shows; null while there is none or the owner answered it. */
    val offer: StateFlow<AppUpdate?> = mutableOffer.asStateFlow()

    /** True while a check the owner asked for runs, for the Settings row to say so. */
    val checking: StateFlow<Boolean> = mutableChecking.asStateFlow()

    suspend fun checkAutomatically() {
        mutex.withLock {
            val now = clock.now().toEpochMilliseconds()
            if (!AutomaticCheck.due(build.automaticChecks, preferences.autoCheck.get(), preferences.lastCheckAt.get(), now)) return
            preferences.lastCheckAt.set(now)
            val release = try {
                releases.latest()
            } catch (e: IOException) {
                logcat(LogPriority.INFO, e) { "The automatic update check could not reach GitHub" }
                return
            } ?: return
            val verdict = judge(release, honourSkip = true)
            if (verdict is ReleaseVerdict.Offer) mutableOffer.value = verdict.update
        }
    }

    suspend fun checkNow(): Result {
        mutex.withLock {
            mutableChecking.value = true
            try {
                preferences.lastCheckAt.set(clock.now().toEpochMilliseconds())
                val release = try {
                    releases.latest()
                } catch (e: IOException) {
                    logcat(LogPriority.INFO, e) { "The update check could not reach GitHub" }
                    return Result.Failed
                } ?: return Result.UpToDate
                return when (val verdict = judge(release, honourSkip = false)) {
                    is ReleaseVerdict.Offer -> {
                        mutableOffer.value = verdict.update
                        Result.Available(verdict.update)
                    }
                    is ReleaseVerdict.NoApk -> Result.NoApk(verdict.version)
                    is ReleaseVerdict.Skipped, ReleaseVerdict.UpToDate -> Result.UpToDate
                }
            } finally {
                mutableChecking.value = false
            }
        }
    }

    /** "Not now", or the prompt dismissed: gone until the next check. */
    fun dismiss() {
        mutableOffer.value = null
    }

    /** "Skip this version": [update] and anything older is not offered again by the automatic check. */
    fun skip(update: AppUpdate) {
        preferences.skippedVersion.set(update.version)
        dismiss()
    }

    private fun judge(release: GitHubRelease, honourSkip: Boolean): ReleaseVerdict = ReleaseRules.judge(
        release = release,
        versionName = build.versionName,
        skippedVersion = preferences.skippedVersion.get(),
        primaryAbi = build.primaryAbi,
        honourSkip = honourSkip,
        kind = build.apkKind,
    )
}
