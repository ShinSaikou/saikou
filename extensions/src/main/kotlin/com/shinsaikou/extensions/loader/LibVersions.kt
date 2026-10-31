package com.shinsaikou.extensions.loader

import com.shinsaikou.domain.preferences.ExtensionsPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** Build constant. Accepting the next ABI major is a one-line change here. */
object LibVersions {
    val SUPPORTED: List<Double> = listOf(1.4, 1.6)
}

/**
 * Resolves the accepted lib versions. The hidden preference wins when it parses to at least one
 * value; anything else falls back to [LibVersions.SUPPORTED], so a typo cannot lock the user out
 * of every extension they have.
 */
@SingleIn(AppScope::class)
@Inject
class LibVersionPolicy(private val preferences: ExtensionsPreferences) {
    fun supported(): List<Double> {
        val override = preferences.libVersionOverride.get().trim()
        if (override.isEmpty()) return LibVersions.SUPPORTED
        val parsed = override.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        return parsed.ifEmpty { LibVersions.SUPPORTED }
    }

    /** Whether an extension built for [libVersion] loads here; an unknown version never does. */
    fun isSupported(libVersion: Double?): Boolean = libVersion != null && libVersion in supported()
}
