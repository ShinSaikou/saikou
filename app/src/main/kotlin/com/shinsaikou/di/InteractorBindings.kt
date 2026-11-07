package com.shinsaikou.di

import com.shinsaikou.domain.extension.repository.ExtensionStoreRepository
import com.shinsaikou.domain.merged.interactor.GetMergedChapters
import com.shinsaikou.domain.merged.interactor.GetMergedGroup
import com.shinsaikou.domain.preferences.DownloadPreferences
import com.shinsaikou.domain.preferences.LibraryPreferences
import com.shinsaikou.domain.preferences.SourcePreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import eu.kanade.domain.source.interactor.SetMigrateSorting
import tachiyomi.domain.chapter.interactor.FilterChaptersForDownload
import tachiyomi.domain.extension.interactor.GetExtensionStoreCountAsFlow
import tachiyomi.domain.extension.interactor.GetExtensionStores
import tachiyomi.domain.extension.interactor.RemoveExtensionStore
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.repository.UpdatesRepository

/**
 * Every vendored `:domain` interactor. They keep upstream's packages and upstream's plain constructors,
 * so none of them carries `@Inject` and none can contribute itself; the fresh `com.shinsaikou.domain`
 * interactors do carry it and are absent from this file.
 *
 * None is scoped. Upstream registers them with `addFactory`, so a fresh instance per injection point
 * is the behaviour being preserved; they hold no state.
 */
@BindingContainer
@ContributesTo(AppScope::class)
object InteractorBindings {

    @Provides
    fun provideGetExtensionStoreCountAsFlow(
        repository: ExtensionStoreRepository,
    ): GetExtensionStoreCountAsFlow = GetExtensionStoreCountAsFlow(repository)

    @Provides
    fun provideGetExtensionStores(
        repository: ExtensionStoreRepository,
    ): GetExtensionStores = GetExtensionStores(repository)

    @Provides
    fun provideRemoveExtensionStore(
        repository: ExtensionStoreRepository,
    ): RemoveExtensionStore = RemoveExtensionStore(repository)

    @Provides
    fun provideFilterChaptersForDownload(
        getChaptersByMangaId: GetChaptersByMangaId,
        downloadPreferences: DownloadPreferences,
        getCategories: GetCategories,
    ): FilterChaptersForDownload = FilterChaptersForDownload(getChaptersByMangaId, downloadPreferences, getCategories)

    @Provides
    fun provideCreateCategoryWithName(
        repository: CategoryRepository,
        preferences: LibraryPreferences,
    ): CreateCategoryWithName = CreateCategoryWithName(repository, preferences)

    @Provides
    fun provideDeleteCategory(
        repository: CategoryRepository,
        libraryPreferences: LibraryPreferences,
        downloadPreferences: DownloadPreferences,
    ): DeleteCategory = DeleteCategory(repository, libraryPreferences, downloadPreferences)

    @Provides
    fun provideGetCategories(repository: CategoryRepository): GetCategories = GetCategories(repository)

    @Provides
    fun provideRenameCategory(repository: CategoryRepository): RenameCategory = RenameCategory(repository)

    @Provides
    fun provideReorderCategory(repository: CategoryRepository): ReorderCategory = ReorderCategory(repository)

    @Provides
    fun provideResetCategoryFlags(
        preferences: LibraryPreferences,
        repository: CategoryRepository,
    ): ResetCategoryFlags = ResetCategoryFlags(preferences, repository)

    @Provides
    fun provideSetDisplayMode(preferences: LibraryPreferences): SetDisplayMode = SetDisplayMode(preferences)

    @Provides
    fun provideSetMangaCategories(repository: MangaRepository): SetMangaCategories = SetMangaCategories(repository)

    @Provides
    fun provideSetSortModeForCategory(
        preferences: LibraryPreferences,
        repository: CategoryRepository,
    ): SetSortModeForCategory = SetSortModeForCategory(preferences, repository)

    @Provides
    fun provideGetBookmarkedChaptersByMangaId(
        repository: ChapterRepository,
    ): GetBookmarkedChaptersByMangaId = GetBookmarkedChaptersByMangaId(repository)

    @Provides
    fun provideGetChapterByUrlAndMangaId(
        repository: ChapterRepository,
    ): GetChapterByUrlAndMangaId = GetChapterByUrlAndMangaId(repository)

    @Provides
    fun provideGetChapter(repository: ChapterRepository): GetChapter = GetChapter(repository)

    @Provides
    fun provideGetChaptersByMangaId(
        repository: ChapterRepository,
    ): GetChaptersByMangaId = GetChaptersByMangaId(repository)

    @Provides
    fun provideSetMangaDefaultChapterFlags(
        libraryPreferences: LibraryPreferences,
        setMangaChapterFlags: SetMangaChapterFlags,
        getFavorites: GetFavorites,
    ): SetMangaDefaultChapterFlags = SetMangaDefaultChapterFlags(libraryPreferences, setMangaChapterFlags, getFavorites)

    @Provides
    fun provideShouldUpdateDbChapter(): ShouldUpdateDbChapter = ShouldUpdateDbChapter()

    @Provides
    fun provideUpdateChapter(repository: ChapterRepository): UpdateChapter = UpdateChapter(repository)

    @Provides
    fun provideGetHistory(repository: HistoryRepository): GetHistory = GetHistory(repository)

    @Provides
    fun provideGetNextChapters(
        getChaptersByMangaId: GetChaptersByMangaId,
        getManga: GetManga,
        historyRepository: HistoryRepository,
        getMergedGroup: GetMergedGroup,
        getMergedChapters: GetMergedChapters,
    ): GetNextChapters = GetNextChapters(getChaptersByMangaId, getManga, historyRepository, getMergedGroup, getMergedChapters)

    @Provides
    fun provideGetTotalReadDuration(
        repository: HistoryRepository,
    ): GetTotalReadDuration = GetTotalReadDuration(repository)

    @Provides
    fun provideRemoveHistory(repository: HistoryRepository): RemoveHistory = RemoveHistory(repository)

    @Provides
    fun provideUpsertHistory(repository: HistoryRepository): UpsertHistory = UpsertHistory(repository)

    @Provides
    fun provideFetchInterval(
        getChaptersByMangaId: GetChaptersByMangaId,
    ): FetchInterval = FetchInterval(getChaptersByMangaId)

    @Provides
    fun provideGetDuplicateLibraryManga(
        repository: MangaRepository,
    ): GetDuplicateLibraryManga = GetDuplicateLibraryManga(repository)

    @Provides
    fun provideGetFavorites(repository: MangaRepository): GetFavorites = GetFavorites(repository)

    /** MA's vendored `SetMigrateSorting`, for the Migrate tab's two sort buttons (phase 4 unit MB). */
    @Provides
    fun provideSetMigrateSorting(preferences: SourcePreferences): SetMigrateSorting = SetMigrateSorting(preferences)

    @Provides
    fun provideGetLibraryManga(repository: MangaRepository): GetLibraryManga = GetLibraryManga(repository)

    @Provides
    fun provideGetMangaByUrlAndSourceId(
        repository: MangaRepository,
    ): GetMangaByUrlAndSourceId = GetMangaByUrlAndSourceId(repository)

    @Provides
    fun provideGetManga(repository: MangaRepository): GetManga = GetManga(repository)

    @Provides
    fun provideGetMangaWithChapters(
        mangaRepository: MangaRepository,
        chapterRepository: ChapterRepository,
    ): GetMangaWithChapters = GetMangaWithChapters(mangaRepository, chapterRepository)

    @Provides
    fun provideNetworkToLocalManga(repository: MangaRepository): NetworkToLocalManga = NetworkToLocalManga(repository)

    @Provides
    fun provideResetViewerFlags(repository: MangaRepository): ResetViewerFlags = ResetViewerFlags(repository)

    @Provides
    fun provideSetMangaChapterFlags(
        repository: MangaRepository,
    ): SetMangaChapterFlags = SetMangaChapterFlags(repository)

    @Provides
    fun provideUpdateMangaNotes(repository: MangaRepository): UpdateMangaNotes = UpdateMangaNotes(repository)

    @Provides
    fun provideGetUpdates(repository: UpdatesRepository): GetUpdates = GetUpdates(repository)
}
