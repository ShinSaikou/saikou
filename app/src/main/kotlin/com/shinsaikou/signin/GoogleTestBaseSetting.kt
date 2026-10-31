package com.shinsaikou.signin

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * The test base Google's calls go to instead of Google, as every variant sees it (phase 3 unit O,
 * R83 item 1). A debug build binds `GoogleTestBase`, the holder in `src/debug` that a test or the
 * debug round trip screen sets; every other build binds [NoGoogleTestBase], so the holder is not in
 * a release APK (phase 4 unit GH, R91 carry-over 3). The runner argument's name stays here, where
 * the androidTest source set, which R90 also compiles against release, can read it.
 */
interface GoogleTestBaseSetting {

    /** The fixture's base address, or null for Google's own. Blank is absent (R29). */
    var value: String?

    companion object {
        /** The instrumentation runner argument that carries it. */
        const val EXTRA_GOOGLE_BASE = "saikou.google.base"
    }
}

/**
 * The binding of every build but debug: no test base, and setting one keeps nothing. A release
 * build's endpoints never ask for it anyway (`GoogleBindings.endpointsFor(debug = false, ...)`).
 */
@ContributesBinding(AppScope::class)
@Inject
class NoGoogleTestBase : GoogleTestBaseSetting {

    override var value: String?
        get() = null
        set(_) = Unit
}
