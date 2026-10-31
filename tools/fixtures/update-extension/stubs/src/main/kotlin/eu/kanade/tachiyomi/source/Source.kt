// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
package eu.kanade.tachiyomi.source

interface Source {
    val id: Long
    val name: String
    val lang: String
    val supportsLatest: Boolean
}
