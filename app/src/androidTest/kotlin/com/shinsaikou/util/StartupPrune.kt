package com.shinsaikou.util

import com.shinsaikou.SaikouApp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Waits until the application's start-up change log prune has run ([SaikouApp.changeLogPruned]).
 * With no sync backend configured it empties the log once, right after the process starts, on the
 * application's scope; a test that reads log rows it or the old build wrote waits here first, so the
 * prune never lands inside it (phase 3 unit I, fix round 1 item 10).
 */
suspend fun SaikouApp.awaitStartupPrune() {
    withTimeoutOrNull(STARTUP_PRUNE_TIMEOUT) { changeLogPruned.await() }
        ?: throw AssertionError("the application's start-up change log prune had not run after $STARTUP_PRUNE_TIMEOUT")
}

private val STARTUP_PRUNE_TIMEOUT = 60.seconds
