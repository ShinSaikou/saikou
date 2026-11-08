package com.shinsaikou.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.ui.util.withCurrent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Keeps the manifest's search alias enabled exactly while the Experimental switch is on
 * (`SourcePreferences.searchLinks`). The alias carries the share and search intent filters, so with
 * it disabled Android offers the app to no other app for them, which is what off promises.
 *
 * It follows the preference from the start of the process rather than only from the settings row:
 * the first value reconciles the alias with the switch, so a component state left behind (app data
 * cleared, a switch turned on before a reinstall) is put right on the next start. Off sets the
 * alias back to its manifest default, disabled, rather than to an explicit disabled state, so the
 * manifest stays the one place that default is written.
 */
@SingleIn(AppScope::class)
@Inject
class SearchLinksAlias(
    private val context: Context,
    private val sourcePreferences: SourcePreferences,
    private val appScope: CoroutineScope,
    private val dispatchers: Dispatchers,
) {

    /** Once per process, from `SaikouApp.onCreate`; the `PackageManager` calls run on IO. */
    fun install() {
        appScope.launch(dispatchers.io) {
            sourcePreferences.searchLinks.withCurrent().distinctUntilChanged().collect { on ->
                // DONT_KILL_APP: the change is made from the settings screen of this very process.
                runCatching {
                    context.packageManager.setComponentEnabledSetting(
                        ComponentName(context, ALIAS_CLASS),
                        if (on) {
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        } else {
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        },
                        PackageManager.DONT_KILL_APP,
                    )
                }.onFailure { logcat(LogPriority.ERROR, it) { "Could not set the search alias to $on" } }
            }
        }
    }

    companion object {
        /** The alias's `android:name` in the manifest, in full: an alias has no class to name it by. */
        const val ALIAS_CLASS = "com.shinsaikou.ui.main.SearchLinks"
    }
}
