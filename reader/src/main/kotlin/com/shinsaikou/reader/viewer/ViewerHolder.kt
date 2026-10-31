package com.shinsaikou.reader.viewer

import eu.kanade.tachiyomi.ui.reader.viewer.Viewer

/**
 * The one live [Viewer]. Contract 07 part 2 section 5.3.
 *
 * Written only by `ReaderViewerContainer`; read by `ReaderActivity`, which needs the current viewer
 * from `dispatchKeyEvent` and lives outside the composition.
 */
class ViewerHolder {
    var viewer: Viewer? = null
}
