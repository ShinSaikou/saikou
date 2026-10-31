// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ChapterTransition.kt (modified: yes)
package eu.kanade.tachiyomi.ui.reader.model

/**
 * A gap between two chapters, shown as its own item by both viewers. Contract 07 section 2.2.
 *
 * Modified in one place: [toString] renders chapter ids rather than chapter URLs. A chapter URL
 * comes from a source and the logging rule in `ARCHITECTURE.md` forbids putting one in a log line.
 */
sealed class ChapterTransition {

    abstract val from: ReaderChapter
    abstract val to: ReaderChapter?

    class Prev(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
    ) : ChapterTransition()

    class Next(
        override val from: ReaderChapter,
        override val to: ReaderChapter?,
    ) : ChapterTransition()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterTransition) return false
        if (from == other.from && to == other.to) return true
        if (from == other.to && to == other.from) return true
        return false
    }

    override fun hashCode(): Int {
        var result = from.hashCode()
        result = 31 * result + (to?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "${javaClass.simpleName}(from=${from.chapter.id}, to=${to?.chapter?.id})"
}
