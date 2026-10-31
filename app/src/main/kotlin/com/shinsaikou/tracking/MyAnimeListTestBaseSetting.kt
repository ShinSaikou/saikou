package com.shinsaikou.tracking

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * The address MyAnimeList's calls go to instead of MyAnimeList, as every variant sees it (phase 3
 * unit T2). A debug build binds `MyAnimeListTestBase`, the holder in `src/debug` that an
 * instrumentation test sets; every other build binds [NoMyAnimeListTestBase], so the holder is not
 * in a release APK (phase 4 unit GH, with `GoogleTestBase`). The runner argument's name stays here,
 * where the androidTest source set, which R90 also compiles against release, can read it.
 */
interface MyAnimeListTestBaseSetting {

    /** The fake's base address, or null for MyAnimeList's own. Blank is absent (R29). */
    var value: String?

    companion object {
        /** The instrumentation runner argument that carries it. */
        const val EXTRA_MAL_BASE = "saikou.mal.base"
    }
}

/**
 * The binding of every build but debug: no test base, and setting one keeps nothing. A release
 * build's endpoints never ask for it anyway (`MyAnimeListBindings.endpointsFor(debug = false, ...)`).
 */
@ContributesBinding(AppScope::class)
@Inject
class NoMyAnimeListTestBase : MyAnimeListTestBaseSetting {

    override var value: String?
        get() = null
        set(_) = Unit
}
