// Vendored from upstream v0.20.4: domain/src/main/java/tachiyomi/domain/manga/model/MangaWithChapterCount.kt (modified: no)
package tachiyomi.domain.manga.model

data class MangaWithChapterCount(
    val manga: Manga,
    val chapterCount: Long,
)
