package com.shinsaikou.reader

import com.shinsaikou.core.common.preferences.BasePreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import com.shinsaikou.extensions.ExtensionManager
import com.shinsaikou.reader.state.IncognitoPolicy
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Incognito is either the global switch or the extension the source came from being on the
 * per-extension list. The reader cannot see the extension host, which is why this lives here.
 *
 * The lookup reads the manager's current snapshot rather than suspending: the reader asks once per
 * committed page and a source whose extension has just been unloaded is not incognito anyway.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AppIncognitoPolicy(
    private val basePreferences: BasePreferences,
    private val sourcePreferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) : IncognitoPolicy {

    override fun isIncognito(sourceId: Long): Boolean {
        if (basePreferences.incognitoMode.get()) return true
        val incognitoPackages = sourcePreferences.incognitoExtensions.get()
        if (incognitoPackages.isEmpty()) return false
        return extensionManager.installedExtensions.value.any { extension ->
            extension.packageName in incognitoPackages && extension.sources.any { it.id == sourceId }
        }
    }
}
