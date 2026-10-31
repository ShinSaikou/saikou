// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/theme/colorscheme/MonetColorScheme.kt (modified: yes)
package eu.kanade.presentation.theme.colorscheme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

internal class MonetColorScheme(context: Context) : BaseColorScheme() {

    // The wallpaper-seeded fallback upstream runs below API 31 needs a third-party palette
    // generator that is not in the catalog, so those API levels use the Saikou palette.
    private val monet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MonetSystemColorScheme(context)
    } else {
        SaikouColorScheme
    }

    override val darkScheme
        get() = monet.darkScheme

    override val lightScheme
        get() = monet.lightScheme
}

@RequiresApi(Build.VERSION_CODES.S)
private class MonetSystemColorScheme(context: Context) : BaseColorScheme() {
    override val lightScheme = dynamicLightColorScheme(context)
    override val darkScheme = dynamicDarkColorScheme(context)
}
