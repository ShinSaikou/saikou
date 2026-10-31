package com.shinsaikou.source

import com.shinsaikou.source.model.ChapterInfo
import com.shinsaikou.source.model.ImageResult
import com.shinsaikou.source.model.MangaInfo
import com.shinsaikou.source.model.PageInfo

/**
 * Placeholder for a source id that no provider currently serves. Every fetch throws
 * [SourceNotInstalledException]; the UI catches it and offers the install flow. [name] and
 * [lang] come from [StubSourceNames] when known, otherwise [name] is the id rendered as text
 * and [lang] is "".
 */
class StubSource(
    override val id: Long,
    override val name: String,
    override val lang: String,
) : Source {
    override suspend fun getMangaDetails(manga: MangaInfo): MangaInfo = throw SourceNotInstalledException(id)
    override suspend fun getChapterList(manga: MangaInfo): List<ChapterInfo> = throw SourceNotInstalledException(id)
    override suspend fun getPageList(chapter: ChapterInfo): List<PageInfo> = throw SourceNotInstalledException(id)
    override suspend fun getImage(page: PageInfo): ImageResult = throw SourceNotInstalledException(id)

    override fun toString(): String = when {
        name.isBlank() -> id.toString()
        lang.isBlank() -> name
        else -> "$name (${lang.uppercase()})"
    }

    override fun equals(other: Any?): Boolean = other is StubSource && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** Stub with no remembered name: [name] is the id as text so lists stay readable. */
        fun unknown(id: Long): StubSource = StubSource(id = id, name = id.toString(), lang = "")
    }
}
