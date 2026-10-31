package com.shinsaikou.domain.download

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Which chapters are downloaded, answered against the manifest and the disk together: a manifest
 * row whose folder or archive is gone counts as not downloaded (phase 2 decision Q4).
 *
 * `:reader` and the chapter list need that answer, and `:reader` may not depend on `:downloads`,
 * which is where the files are found. So the question is asked here and `:downloads` binds the
 * answer, the way [com.shinsaikou.domain.database.TransactionRunner] is asked here and bound by `:data`.
 * The type is [File], the one `:downloads` already hands out for a chapter's folder: downloads live
 * under the app's own files directory, so no content URI is ever involved.
 */
interface DownloadedChapters {

    /**
     * The chapter's folder, or its archive for an adopted chapter, when the chapter has a manifest
     * row and the file is on disk. Null otherwise, and the caller treats the chapter as online.
     */
    suspend fun find(chapterId: Long): File?

    /**
     * The ids of [mangaId]'s chapters that [find] would answer, again on every manifest change. A
     * folder removed by hand is noticed at the next change, or when the sweep removes its row.
     */
    fun subscribeByMangaId(mangaId: Long): Flow<Set<Long>>

    /**
     * The ids among [chapterIds] that [find] would answer, again on every manifest change: the
     * Updates tab's download controls, which check only the folders of the chapters it shows. At
     * most 999 ids, the bound of
     * [com.shinsaikou.domain.download.repository.DownloadManifestRepository.subscribeByChapterIds].
     */
    fun subscribeByChapterIds(chapterIds: Set<Long>): Flow<Set<Long>>

    /**
     * The ids of every chapter [find] would answer, again on every manifest change: the Updates
     * tab's downloaded filter, which spans every entry. It checks every row's folder on each
     * change, so a caller subscribes only while it needs the answer.
     */
    fun subscribeAll(): Flow<Set<Long>>
}
