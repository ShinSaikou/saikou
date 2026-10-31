package com.shinsaikou.reader.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.bench.ReaderBenchHooks
import com.shinsaikou.reader.viewer.ViewerFactory
import com.shinsaikou.reader.viewer.ViewerHolder
import com.shinsaikou.reader.viewer.ViewerHost
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer

/**
 * The `AndroidView` that owns the vendored viewer's lifetime. Contract 07 part 2 section 5.3.
 *
 * `key(generation)` is the rebuild mechanism: `viewerGeneration` changes only on an effective
 * reading-mode change, so a mode switch disposes the old viewer through `onRelease` and builds the
 * new one in `factory`. There is no other path that constructs a `Viewer`.
 */
@Composable
fun ReaderViewerContainer(
    generation: Int,
    readingMode: ReadingMode,
    host: ViewerHost,
    holder: ViewerHolder,
    grayscale: Boolean,
    invertedColors: Boolean,
    fullscreen: Boolean,
    drawUnderCutout: Boolean,
    onViewerBuilt: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Upstream's three-way rule from applyInsetsPadding, as a modifier. Compose re-reads the insets on
    // every change on its own, so upstream's setOnApplyWindowInsetsListener is dropped.
    val insets = when {
        !fullscreen -> WindowInsets.systemBars
        !drawUnderCutout -> WindowInsets.displayCutout
        else -> WindowInsets(0)
    }
    key(generation) {
        // Each generation releases the viewer it built, never whatever the holder holds. Compose
        // dispatches the old view's onRelease after the new view's factory has run, so releasing
        // through the holder destroyed the viewer just built and left the reader black after every
        // mode switch (found by ReaderModeSwitchTest).
        val built = remember { BuiltViewer() }
        AndroidView(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(insets),
            factory = {
                ReaderBenchHooks.trace(ReaderBenchHooks.SECTION_BUILD_VIEWER) {
                    ViewerFactory.create(readingMode, host)
                        .also {
                            built.viewer = it
                            holder.viewer = it
                        }
                        .getView()
                }
            },
            update = { view ->
                val paint = readerLayerPaint(grayscale, invertedColors)
                view.setLayerType(if (paint != null) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, paint)
            },
            onRelease = {
                val viewer = built.viewer
                viewer?.destroy()
                if (holder.viewer === viewer) holder.viewer = null
                built.viewer = null
            },
        )
        LaunchedEffect(generation) {
            // The factory runs during the apply phase and LaunchedEffect after it, so the viewer is
            // already set. The null check is defensive, not load-bearing.
            if (holder.viewer != null) onViewerBuilt(generation)
        }
    }
}

/** The viewer one generation's factory built, so that generation's release finds it. */
private class BuiltViewer {
    var viewer: Viewer? = null
}

/**
 * Upstream's two colour matrices, verbatim, and null when neither filter is on. A pure function of two
 * booleans, unit tested by `ReaderLayerPaintTest`.
 *
 * The filter stays a hardware layer on the viewer's own View rather than becoming a Compose
 * `ColorFilter` over the `AndroidView`, because the two do not composite the same way.
 */
internal fun readerLayerPaint(grayscale: Boolean, invertedColors: Boolean): Paint? {
    if (!grayscale && !invertedColors) return null
    val matrix = ColorMatrix()
    if (grayscale) {
        matrix.setSaturation(0f)
    }
    if (invertedColors) {
        matrix.postConcat(ColorMatrix(INVERSION_MATRIX.copyOf()))
    }
    return Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
}

/**
 * Upstream's hand-written negate-and-offset matrix, as data rather than as a literal inside the
 * function, because `android.graphics.ColorMatrix` is a stub on the unit-test JVM and its array
 * cannot be read back there. `ReaderLayerPaintTest` asserts on this.
 */
internal val INVERSION_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 0f, 255f,
    0f, -1f, 0f, 0f, 255f,
    0f, 0f, -1f, 0f, 255f,
    0f, 0f, 0f, 1f, 0f,
)
