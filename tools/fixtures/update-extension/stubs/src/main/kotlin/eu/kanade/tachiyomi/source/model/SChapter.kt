// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

interface SChapter {
    var url: String
    var name: String
    var chapter_number: Float
    var date_upload: Long

    companion object {
        fun create(): SChapter = throw RuntimeException("Stub!")
    }
}
