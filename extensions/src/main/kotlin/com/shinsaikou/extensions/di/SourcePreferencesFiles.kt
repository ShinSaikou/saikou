package com.shinsaikou.extensions.di

import android.content.Context
import android.content.SharedPreferences

/**
 * The per-source `SharedPreferences` file an extension writes through
 * `ConfigurableSource.getSourcePreferences()`. The host opens the same file by the same name, so
 * the settings screen and the extension see one set of values, and a later backup can include the
 * file by name.
 */
object SourcePreferencesFiles {
    /** The ABI's fixed naming rule; never change it. */
    fun fileName(sourceId: Long): String = "source_$sourceId"

    fun forSource(context: Context, sourceId: Long): SharedPreferences =
        context.getSharedPreferences(fileName(sourceId), Context.MODE_PRIVATE)
}
