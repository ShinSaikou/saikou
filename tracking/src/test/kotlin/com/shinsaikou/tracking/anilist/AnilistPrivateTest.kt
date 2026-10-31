package com.shinsaikou.tracking.anilist

import com.shinsaikou.tracking.AddTracks
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.EditTrack
import com.shinsaikou.tracking.MANGA_ID
import com.shinsaikou.tracking.RecordingTransactionRunner
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.graphql
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.json
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.listBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.savedBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.searchBody
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.media
import com.shinsaikou.tracking.anilist.AnilistFixture.Companion.track
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.repository.HistoryRepository
import java.io.File

/**
 * Private entries, which AniList is the first tracker here to offer: the track sheet's "track
 * privately" binds through [AddTracks] and its toggle through [EditTrack], each sent to AniList and
 * stored as AniList answered.
 */
class AnilistPrivateTest {

    @TempDir
    lateinit var cacheDir: File

    private val fixture by lazy { AnilistFixture(cacheDir) }
    private val chapterRepository = mockk<ChapterRepository>()
    private val historyRepository = mockk<HistoryRepository>()

    @AfterEach
    fun close() {
        fixture.close()
    }

    @Test
    fun `a result tracked privately is added to the list as private and stored so`() {
        runBlocking {
            fixture.signedIn()
            coEvery { chapterRepository.getChapterByMangaId(MANGA_ID, any()) } returns emptyList()
            fixture.server.enqueue(json(200, searchBody(media())))
            fixture.server.enqueue(json(200, listBody()))
            fixture.server.enqueue(json(200, savedBody(libraryId = 5088L, status = "PLANNING")))
            val result = fixture.tracker.search("fixture").single()
            val addTracks = AddTracks(
                fixture.repository,
                GetChaptersByMangaId(chapterRepository),
                GetHistory(historyRepository),
                RecordingTransactionRunner(),
            )

            val bound = addTracks.bind(fixture.tracker, result, MANGA_ID, private = true)

            fixture.server.takeRequest()
            fixture.server.takeRequest()
            val added = fixture.server.takeRequest().graphql().second
            added["private"] shouldBe JsonPrimitive(true)
            added["status"] shouldBe JsonPrimitive("PLANNING")
            bound.private shouldBe true
            bound.libraryId shouldBe 5088L
            fixture.repository.rows.values.single().private shouldBe true
        }
    }

    @Test
    fun `the sheet's toggle sends the flipped privacy and stores it`() {
        runBlocking {
            fixture.signedIn()
            fixture.repository.insert(track(private = false))
            fixture.server.enqueue(json(200, savedBody()))
            val editTrack = EditTrack(fixture.repository, DelayedTrackingStore(fixture.preferences, fixture.preferences::write))

            editTrack.setPrivate(fixture.tracker, MANGA_ID, true)

            fixture.server.takeRequest().graphql().second["private"] shouldBe JsonPrimitive(true)
            fixture.repository.rows.getValue(10L).private shouldBe true
        }
    }
}
