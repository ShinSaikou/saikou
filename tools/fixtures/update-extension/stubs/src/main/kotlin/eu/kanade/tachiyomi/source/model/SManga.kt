// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

interface SManga {
    var url: String
    var title: String
    var description: String?
    var status: Int
    var initialized: Boolean

    companion object {
        const val ONGOING = 1

        fun create(): SManga = throw RuntimeException("Stub!")
    }
}
