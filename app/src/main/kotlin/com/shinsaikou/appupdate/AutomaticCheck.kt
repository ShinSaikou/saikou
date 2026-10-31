package com.shinsaikou.appupdate

import kotlin.time.Duration.Companion.hours

/** When the app coming to the foreground asks GitHub for the latest release. */
object AutomaticCheck {

    /** At most one check, automatic or asked for, in this long. */
    val INTERVAL = 24.hours

    /**
     * Only in a build that checks by itself ([buildChecks], false in a debug build), only while the
     * owner's setting is on, and only once [INTERVAL] has passed since [lastCheckAt] (epoch millis,
     * 0 when never). A last check dated after [now] means the clock was set back, and checks rather
     * than waiting for the clock to catch up.
     */
    fun due(buildChecks: Boolean, enabled: Boolean, lastCheckAt: Long, now: Long): Boolean {
        if (!buildChecks || !enabled) return false
        if (lastCheckAt <= 0L || now < lastCheckAt) return true
        return now - lastCheckAt >= INTERVAL.inWholeMilliseconds
    }
}
