// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
package eu.kanade.tachiyomi.source

interface SourceFactory {
    fun createSources(): List<Source>
}
