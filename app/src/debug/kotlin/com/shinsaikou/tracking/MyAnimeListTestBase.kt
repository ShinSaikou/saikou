package com.shinsaikou.tracking

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The address the debug and androidTest variants send every MyAnimeList call to instead of
 * MyAnimeList: the authorization page, the token endpoint and the API (phase 3 unit T2), such as
 * `http://10.0.2.2:<port>` for the host's MyAnimeList fake on an emulator. `GoogleTestBase`'s pattern
 * (R91 item 8): an instrumentation test sets it from the runner argument
 * [MyAnimeListTestBaseSetting.EXTRA_MAL_BASE], and nothing else does. No intent sets it and no screen
 * offers it. Blank is absent (R29). Nothing persists it.
 *
 * Only a debug build has it: it replaces [NoMyAnimeListTestBase] there, and no other build compiles
 * it (phase 4 unit GH, with `GoogleTestBase`). And with any test base in effect, MyAnimeList sends
 * the fake's client id, never the build's.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoMyAnimeListTestBase::class])
@Inject
class MyAnimeListTestBase : MyAnimeListTestBaseSetting {

    @Volatile
    override var value: String? = null
        set(value) {
            field = value?.takeIf { it.isNotBlank() }
        }
}
