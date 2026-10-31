package com.shinsaikou.signin

import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The foreground hold while the browser is open for a sign-in (phase 3 unit O, Q1). Without it the
 * app is an ordinary background process for as long as the owner stays in the browser: frozen by the
 * cached-apps freezer from API 31 or killed for memory, so the loopback listener never answers.
 *
 * [start] enqueues [SignInHoldWorker] as expedited work under [WORK_NAME] and waits until the worker
 * has asked for the foreground, which WorkManager grants through its existing `dataSync` foreground
 * service; no manifest entry is added. It must be called while the activity is visible, since from
 * API 31 a foreground service cannot start from the background. It returns a [Handle] for that
 * sign-in's hold; [end] with the handle releases that hold and no other, and the worker then posts
 * "Signed in, tap to return" when the sign-in came back with a code.
 *
 * One hold at a time: a second [start] ends the first as not signed in, and a late [end] for the
 * first never touches the second (fix round 1 item 2). The hold lives in this process only; a worker
 * that WorkManager starts in a new process finds none and ends at once.
 */
@SingleIn(AppScope::class)
@Inject
class SignInHold(private val workManager: Lazy<WorkManager>) {

    private val current = AtomicReference<Hold?>(null)

    /**
     * Holds the foreground until [end] is called with the returned [Handle]; [returnIntent] is the
     * activity the notifications bring the owner back to. [Handle.inForeground] says whether the
     * worker entered the foreground within [ENTER_TIMEOUT]: false when the platform refused it or
     * WorkManager did not start the worker in time, in which case the sign-in goes on without the
     * hold. A start cancelled before it returns ends its own hold.
     */
    suspend fun start(returnIntent: Intent): Handle {
        val hold = Hold(returnIntent)
        current.getAndSet(hold)?.ended?.complete(false)
        var handle: Handle? = null
        try {
            val request = OneTimeWorkRequestBuilder<SignInHoldWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .build()
            workManager.value.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
            val entered = withTimeoutOrNull(ENTER_TIMEOUT) { hold.entered.await() } ?: false
            return Handle(hold, entered).also { handle = it }
        } finally {
            if (handle == null) release(hold, signedIn = false)
        }
    }

    /**
     * Ends [handle]'s hold, whichever way its sign-in ended; [signedIn] is true when a code came
     * back. A hold a later [start] already replaced stays ended as not signed in, and the later one
     * is untouched.
     */
    fun end(handle: Handle, signedIn: Boolean) {
        release(handle.hold, signedIn)
    }

    private fun release(hold: Hold, signedIn: Boolean) {
        hold.ended.complete(signedIn)
        current.compareAndSet(hold, null)
    }

    /** The hold the worker serves, or null when none is open in this process. */
    internal fun active(): Hold? = current.get()?.takeUnless { it.ended.isCompleted }

    /** One sign-in's hold, from [start], for its [end]. */
    class Handle internal constructor(internal val hold: Hold, val inForeground: Boolean)

    internal class Hold(val returnIntent: Intent) {
        /** Completed by the worker with whether it entered the foreground. */
        val entered = CompletableDeferred<Boolean>()

        /** Completed by [end] with whether the sign-in came back with a code, or by a later [start] with false. */
        val ended = CompletableDeferred<Boolean>()
    }

    companion object {
        const val WORK_NAME = "sign-in-hold"
        const val TAG = "sign-in"

        /** How long [start] waits for the worker before the browser opens regardless. */
        val ENTER_TIMEOUT: Duration = 5.seconds
    }
}
