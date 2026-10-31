// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/presentation/more/LogoHeader.kt (modified: yes)
package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.shinsaikou.BuildConfig
import com.shinsaikou.R
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import com.shinsaikou.i18n.R as I18nR

/**
 * Saikou has no About screen, so the version shows here, one line under the mark; [iconPadding]'s
 * bottom goes under that line, so the space under the header is still the caller's.
 */
@Composable
fun LogoHeader(
    iconPadding: PaddingValues = PaddingValues(),
) {
    val layoutDirection = LocalLayoutDirection.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_saikou_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(
                    start = iconPadding.calculateStartPadding(layoutDirection),
                    top = iconPadding.calculateTopPadding(),
                    end = iconPadding.calculateEndPadding(layoutDirection),
                )
                .size(64.dp),
        )

        Text(
            text = stringResource(I18nR.string.more_version_format, BuildConfig.VERSION_NAME),
            modifier = Modifier
                .padding(
                    top = MaterialTheme.padding.small,
                    bottom = iconPadding.calculateBottomPadding(),
                )
                .secondaryItemAlpha(),
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
    }
}
