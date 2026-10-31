package com.shinsaikou.signin

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The test argument the debug and androidTest variants send Google's calls to instead of Google:
 * the sync fixture's base address, such as `http://10.0.2.2:18797` on an emulator (phase 3 unit O,
 * R83 item 1). An instrumentation test sets it from the runner argument
 * [GoogleTestBaseSetting.EXTRA_GOOGLE_BASE]; the debug build's round trip screen sets it from a
 * field the person holding the device types before Run. No intent sets it (fix round 1 item 1): the
 * screen is exported, and another app could name its own listener. Blank is absent (R29). Nothing
 * persists it.
 *
 * Only a debug build has it: it replaces [NoGoogleTestBase] there, and no other build compiles it
 * (phase 4 unit GH, R91 carry-over 3). And with any test base in effect, `GoogleOAuthClient` sends
 * the fixture's test client, never the build's.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, replaces = [NoGoogleTestBase::class])
@Inject
class GoogleTestBase : GoogleTestBaseSetting {

    @Volatile
    override var value: String? = null
        set(value) {
            field = value?.takeIf { it.isNotBlank() }
        }
}
