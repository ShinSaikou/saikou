package com.shinsaikou.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import tachiyomi.domain.manga.model.MangaCover

/**
 * The one cover renderer. [data] is a `Manga` or a [MangaCover]: the image loader has a keyer and a
 * fetcher for both, so a caller hands over the domain object rather than a URL and the cover cache
 * stays in the picture.
 *
 * The placeholder and the corner are upstream's `MangaCover`'s: a flat translucent grey, which reads
 * on every theme's background in light and dark, and `shapes.extraSmall`. [alpha] fades the image
 * alone, so a dimmed cover still sits on its placeholder.
 */
@Composable
fun MangaCover(
    data: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    ratio: Float = BOOK_RATIO,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    alpha: Float = 1f,
) {
    AsyncImage(
        model = data,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        alpha = alpha,
        modifier = modifier
            .aspectRatio(ratio)
            .clip(shape)
            .background(CoverPlaceholderColor),
    )
}

/** Upstream's cover ratio: every grid cell and list thumbnail uses it so rows line up. */
const val BOOK_RATIO = 2f / 3f

/** Upstream's `MangaCover` placeholder, `Color(0x1F888888)`. */
internal val CoverPlaceholderColor = Color(0x1F888888)
