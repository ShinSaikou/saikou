package com.shinsaikou.reader.di

import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.domain.preferences.ReaderPreferences
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.reader.ReaderViewModel
import com.shinsaikou.reader.state.ChapterWebActions
import com.shinsaikou.reader.viewer.PageImageActions
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.SingleIn

/** The reader session's scope: one graph per open reader, torn down with the activity. */
abstract class ReaderScope private constructor()

/**
 * The slice of the graph the reader needs by hand, outside its own `@Inject` constructors.
 * Ruling R22, whose shape was proven by probe.
 *
 * `:app` writes no reader provider and adds no reader entry to `AppGraph`: [Factory] carries
 * `@ContributesTo(AppScope::class)`, and the accessor Metro generates on the app graph is the whole
 * of the wiring. This supersedes part 2 section 7.2's plain `ReaderGraph` interface, which had
 * `:app`'s `AppGraph` extend it by hand.
 */
@SingleIn(ReaderScope::class)
@GraphExtension(ReaderScope::class)
interface ReaderGraph {

    val readerViewModelFactory: ReaderViewModel.Factory
    val chapterWebActions: ChapterWebActions
    val pageImageActions: PageImageActions
    val uiPreferences: UiPreferences
    val readerPreferences: ReaderPreferences

    /**
     * The app scope's one coordinator, reached from this extension the way every app singleton is.
     * `ReaderScreen` reads its in-flight solves while the reader itself is waiting on the network.
     */
    val cloudflareSolveCoordinator: CloudflareSolveCoordinator

    /**
     * Ruling R22's shape, verbatim. `@ContributesTo(AppScope::class)` merges this factory into
     * `AppGraph`, which makes Metro resolve every accessor above; the app module binds
     * [ChapterWebActions], [PageImageActions], `IncognitoPolicy` and `LocalChapterResolver`, and
     * provides the `Dispatchers`, `PreferenceStore`, `Json` and app `CoroutineScope` that
     * `ReaderViewModel` reaches through.
     */
    @ContributesTo(AppScope::class)
    @GraphExtension.Factory
    fun interface Factory {
        fun createReaderGraph(): ReaderGraph
    }
}

/**
 * Implemented by `:app`'s `Application`, so `ReaderActivity` never imports `com.shinsaikou.SaikouApp`.
 * The merged app graph already implements [ReaderGraph.Factory], so `:app`'s side is one line:
 * `override val readerGraphFactory: ReaderGraph.Factory get() = graph`.
 */
interface ReaderGraphOwner {
    val readerGraphFactory: ReaderGraph.Factory
}
