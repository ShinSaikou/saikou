// Vendored from upstream v0.20.4: source-api/src/main/kotlin/eu/kanade/tachiyomi/util/RxExtension.kt (modified: no)
package eu.kanade.tachiyomi.util

import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle

suspend fun <T> Observable<T>.awaitSingle(): T = awaitSingle()
