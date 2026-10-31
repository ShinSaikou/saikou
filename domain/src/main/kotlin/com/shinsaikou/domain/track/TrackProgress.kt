package com.shinsaikou.domain.track

/**
 * The progress push to trackers, as the reader and the chapter list reach it (phase 3 unit T1).
 * `:reader` cannot see `:tracking`, so the seam is this one interface, bound once by `:tracking`
 * with `@ContributesBinding`. It is not a multibinding set: a set may be empty and still satisfy
 * the graph, while this binding either resolves on the reader's path or fails the graph (R26,
 * challenge 5 T1 item 3). It replaces the `ChapterFinishedListener` set R25 deleted.
 *
 * Nothing here waits on the network. A push is recorded in the retry store, on disk before
 * [record] returns, and a worker sends it when a network is available.
 */
interface TrackProgress {

    /**
     * Whether [record] would record anything for [chapterNumber]: some logged-in tracker is bound
     * to [mangaId] with its `lastChapterRead` below the number. One whose sign-in has expired counts:
     * its push waits for the next sign-in. Reads the database; call it off the main thread.
     */
    suspend fun wouldRecord(mangaId: Long, chapterNumber: Double): Boolean

    /**
     * Records a push of [chapterNumber] for each track [wouldRecord] describes, then asks for the
     * retry worker unless every one waits for a sign-in. Returns how many tracks it recorded; zero
     * records nothing and enqueues nothing.
     * Off the main thread. A failure to store the record is thrown to the caller, which decides
     * whether its own work fails with it (the reader's progress write never does).
     */
    suspend fun record(mangaId: Long, chapterNumber: Double): Int
}
