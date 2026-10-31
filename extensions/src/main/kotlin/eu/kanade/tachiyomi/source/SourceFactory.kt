// Vendored from upstream v0.20.4: source-api/src/main/kotlin/eu/kanade/tachiyomi/source/SourceFactory.kt (modified: no)
package eu.kanade.tachiyomi.source

/**
 * A factory for creating sources at runtime.
 */
interface SourceFactory {
    /**
     * Create a new copy of the sources
     * @return The created sources
     */
    fun createSources(): List<Source>
}
