package com.shinsaikou.signin

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.work.WorkerInstanceFactory
import com.shinsaikou.core.common.work.setForegroundSafely
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Holds the process in the foreground while a browser sign-in waits (phase 3 unit O, Q1): it enters
 * the foreground on WorkManager's `dataSync` service with [SignInNotifier]'s waiting notification,
 * says whether it got there, and waits for [SignInHold.end]. When the sign-in came back with a code
 * it posts "Signed in, tap to return" before it ends, since the foreground notification goes with
 * the worker. Built by `SaikouWorkerFactory` (R8).
 *
 * It never outlives the listener: it ends when the hold ends, whichever way, and in any case
 * [MAX_HOLD] after it started, a minute past the listener's own five minutes.
 */
class SignInHoldWorker @AssistedInject constructor(
    context: Context,
    @Assisted params: WorkerParameters,
    private val hold: SignInHold,
    private val notifier: SignInNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.waitingNotification(hold.active()?.returnIntent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(SignInNotifier.ID_WAITING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(SignInNotifier.ID_WAITING, notification)
        }
    }

    override suspend fun doWork(): Result {
        val current = hold.active() ?: return Result.success()
        current.entered.complete(setForegroundSafely())
        val signedIn = withTimeoutOrNull(MAX_HOLD) { current.ended.await() } ?: false
        if (signedIn) notifier.signedIn(current.returnIntent)
        return Result.success()
    }

    @AssistedFactory
    interface Factory : WorkerInstanceFactory<SignInHoldWorker>

    companion object {
        val MAX_HOLD: Duration = LoopbackListener.DEFAULT_TIMEOUT + 1.minutes
    }
}
