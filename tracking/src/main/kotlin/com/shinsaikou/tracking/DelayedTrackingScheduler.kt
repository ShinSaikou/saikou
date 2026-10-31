package com.shinsaikou.tracking

/**
 * Asks for a run of the retry worker, which sends what [DelayedTrackingStore] holds. `:tracking`
 * has no WorkManager: `:app` binds the implementation and owns the worker, which it builds through
 * the one worker factory (R8), the way `:app` owns sync's.
 */
fun interface DelayedTrackingScheduler {

    /**
     * Enqueues a run with a network constraint, unless one already waiting will read the store
     * after this call. Call it after the push is in the store; off the main thread.
     */
    suspend fun schedule()
}
