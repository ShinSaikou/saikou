package com.shinsaikou.sync

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.shinsaikou.SaikouApp
import com.shinsaikou.di.AppGraph
import com.shinsaikou.di.ClockOffsetSetting
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.signin.GoogleTestBaseSetting
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveSyncBackend
import com.shinsaikou.util.StagePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Q12's `sync_14_day_no_reconsent` on a device (phase 3 unit H), one step per `am instrument`, named
 * by `saikou.drive.token.step`. `tools/bench/sync_14_day.py` runs the steps against D's sync fixture,
 * whose base address arrives as `saikou.google.base`, sets the fixture's modes between them, stops
 * the process after `sign-in` with `run-as kill -9`, and reads the fixture's log for what the steps
 * cannot see: no authorization request after sign-in's, the `refresh_token` grants, and the client
 * secret on each.
 *
 * With no step named it logs one `Skipped: no drive token step named` line and returns, strict mode
 * or not, as `SyncPairTest` does, so a suite run passes through it. A step it does not know fails.
 *
 * - `sign-in`: the periodic run off, [FixtureGoogleSignIn.signIn] (a code from the fixture's
 *   authorization endpoint with no browser, and [DriveAccount.signIn]), [SyncSession.signedIn] and
 *   the first run it starts, which must succeed.
 *   The refresh token the sign-in stored is copied under [SIGN_IN_COPY] in the same secret store, so
 *   the next process can compare it on the device; neither is ever printed, logged or hashed.
 * - `after-idle`: in a cold process, the graph's clock `saikou.clock.offsetDays` days ahead (the
 *   debug build's [ClockOffsetSetting], which a release build never reads), one Sync now, which must
 *   succeed while the fixture expires the access tokens, drops the first refresh, answers the second
 *   with 503 and one Drive call with 401; then the stored refresh token must equal the copy.
 * - `revoked`: the same with the fixture's revoked mode on: the run must end with `sync_signed_out`
 *   and the backend's probe must report signed out.
 *
 * Each reports one `saikou.drive.token.result` status line of `key=value` pairs (code
 * [STATUS_IN_PROGRESS]), with this process's pid and how long it has lived, so the orchestrator
 * can tell a cold start from the process `sign-in` ran in.
 */
@RunWith(AndroidJUnit4::class)
class DriveTokenDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SaikouApp
    private val graph: AppGraph get() = app.graph
    private val arguments get() = InstrumentationRegistry.getArguments()

    @Test
    fun step() {
        val step = arguments.getString(STEP_ARGUMENT)
        if (step.isNullOrBlank()) {
            Log.i(TAG, "Skipped: no drive token step named")
            return
        }
        graph.googleTestBase.value = FixtureGoogleSignIn.fixtureBase(required(GoogleTestBaseSetting.EXTRA_GOOGLE_BASE))
        val result = linkedMapOf<String, Any?>("step" to step)
        runBlocking(Dispatchers.IO) {
            when (step) {
                "sign-in" -> result += signIn()
                "after-idle" -> result += afterIdle()
                "revoked" -> result += revoked()
                else -> throw AssertionError("$STEP_ARGUMENT names no step this class knows: $step")
            }
        }
        result["pid"] = Process.myPid()
        result["uptimeMs"] = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        // sign-in sets the sync interval, the backend and the sync state with apply() as its run ends,
        // and after-idle reads them in the next process (phase 5 unit TG4).
        StagePreferences.commit()
        report(result)
    }

    private suspend fun signIn(): Map<String, Any?> {
        assertEquals("the device is already signed in", "", SyncPreferences(graph.preferenceStore).backendId.get())
        graph.syncWorkPreferences.intervalHours.set(0)
        graph.syncScheduler.setup()
        val email = FixtureGoogleSignIn.signIn(graph)
        val backend = checkNotNull(graph.driveAccount.backend()) { "the stored Google sign-in builds no backend" }
        val refreshToken = checkNotNull(graph.secretStore.get(DriveAccount.REFRESH_TOKEN)) { "the sign-in stored no refresh token" }
        graph.secretStore.put(SIGN_IN_COPY, refreshToken)
        val started = now()
        graph.syncSession.signedIn(backend)
        val run = awaitRun(started)
        assertEquals("the first run failed: ${run.outcome}", SyncOutcome.Success, run.outcome)
        return linkedMapOf(
            "outcome" to "success",
            "backend" to backend.id.take(DriveSyncBackend.ID_PREFIX.length),
            "account" to (email.isNotEmpty()),
            "exported" to run.exported,
        )
    }

    private suspend fun afterIdle(): Map<String, Any?> {
        val days = offsetDays()
        val run = syncNow()
        assertEquals("the run after $days idle days failed: ${run.outcome}", SyncOutcome.Success, run.outcome)
        val stored = graph.secretStore.get(DriveAccount.REFRESH_TOKEN)
        val copy = checkNotNull(graph.secretStore.get(SIGN_IN_COPY)) { "no copy of the sign-in's refresh token: sign-in did not run" }
        // Compared here and only here; the line says whether they matched, never either value.
        val unchanged = stored == copy
        assertTrue("the refresh token in the secret store is not the one sign-in stored", unchanged)
        graph.secretStore.delete(SIGN_IN_COPY)
        return linkedMapOf("outcome" to "success", "offsetDays" to days, "tokenUnchanged" to unchanged)
    }

    private suspend fun revoked(): Map<String, Any?> {
        val days = offsetDays()
        val run = syncNow()
        val failure = run.outcome as? SyncOutcome.Failure
        assertEquals("the run with the grant revoked did not end signed out: ${run.outcome}", ErrorKey.SYNC_SIGNED_OUT, failure?.key)
        // The secrets stay until the owner signs in again or out, so the backend is still built,
        // and it is the backend that says signed out: Google refused its grant.
        val backend = checkNotNull(graph.driveAccount.backend()) { "the stored Google sign-in builds no backend" }
        val status = backend.probe()
        assertTrue("the backend's probe did not reach the fixture: $status", status.reachable)
        assertFalse("the backend reports signed in with the grant revoked", status.authenticated)
        return linkedMapOf("outcome" to failure?.key?.id, "offsetDays" to days, "probeAuthenticated" to status.authenticated)
    }

    /** The offset the step runs under, applied to the graph's clock before anything reads it. */
    private fun offsetDays(): Int {
        val days = required(ClockOffsetSetting.EXTRA_OFFSET_DAYS).toInt()
        graph.clockOffset.days = days
        // Two readings a moment apart: within a minute of the offset, as ClockBindingsTest allows.
        val ahead = graph.clock.now() - Clock.System.now()
        assertTrue("the graph's clock is $ahead ahead, not $days days", (ahead - days.days).absoluteValue < 1.minutes)
        return days
    }

    /** Sync now, after any run already queued has ended, and the run it starts. */
    private suspend fun syncNow(): Run {
        awaitNoRun()
        val started = now()
        assertTrue("Sync now enqueued nothing: no backend is configured", graph.syncScheduler.syncNow())
        return awaitRun(started)
    }

    private class Run(val outcome: SyncOutcome?, val exported: Boolean?)

    /**
     * Waits until no `sync-once` work is waiting or running and a run has taken the lock since
     * [started], as `SyncPairTest.awaitRun` does, and returns how it ended, success or not. A run
     * WorkManager is backing off to retry ends the wait at once.
     */
    private suspend fun awaitRun(started: Long): Run {
        val record = graph.syncRunRecord
        val ended = withTimeoutOrNull(RUN_TIMEOUT) {
            while (true) {
                val infos = onceWork()
                if (infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 }) break
                val busy = infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                }
                if (!busy && record.lastAttemptAt.get() >= started) break
                delay(POLL_MS)
            }
            true
        }
        assertNotNull("no run since $started ended within $RUN_TIMEOUT", ended)
        val outcome = record.outcome()
        val exported = if (outcome == SyncOutcome.Success && record.lastSuccessAt.get() >= started) {
            record.reports.get().first().exported
        } else {
            null
        }
        return Run(outcome, exported)
    }

    private suspend fun awaitNoRun() {
        val pending = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING)
        val idle = withTimeoutOrNull(RUN_TIMEOUT) {
            while (onceWork().any { it.state in pending }) delay(POLL_MS)
            true
        }
        assertNotNull("a sync-once run was still queued or running after $RUN_TIMEOUT", idle)
    }

    private fun onceWork(): List<WorkInfo> = graph.workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME_ONCE).get()

    private fun report(result: Map<String, Any?>) {
        val line = result.entries.joinToString(" ") { (key, value) -> "$key=$value" }
        Log.i(TAG, "result $line")
        instrumentation.sendStatus(STATUS_IN_PROGRESS, Bundle().apply { putString(RESULT_KEY, line) })
    }

    private fun required(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() } ?: throw AssertionError("instrumentation argument $name is missing")

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val TAG = "DriveToken"
        const val STEP_ARGUMENT = "saikou.drive.token.step"
        const val RESULT_KEY = "saikou.drive.token.result"

        /** `InstrumentationResultParser`'s in-progress code: a status line, not a test's end. */
        const val STATUS_IN_PROGRESS = 2

        /** The sign-in's refresh token, kept in the same secret store for `after-idle` to compare. */
        const val SIGN_IN_COPY = "drive_token_device_test_sign_in_copy"

        const val POLL_MS = 200L
        val RUN_TIMEOUT = 5.minutes
    }
}
