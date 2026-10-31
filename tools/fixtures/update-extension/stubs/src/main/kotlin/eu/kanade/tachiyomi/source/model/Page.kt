// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
package eu.kanade.tachiyomi.source.model

import android.net.Uri

// The fixture passes all four arguments, so it links against the host's full constructor and never
// against the synthetic one Kotlin generates for the defaults.
@Suppress("UNUSED_PARAMETER")
open class Page(index: Int, url: String, imageUrl: String?, uri: Uri?)
