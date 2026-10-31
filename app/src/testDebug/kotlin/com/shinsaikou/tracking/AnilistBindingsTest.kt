package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.anilist.Anilist
import com.shinsaikou.tracking.anilist.AnilistClient
import com.shinsaikou.tracking.anilist.AnilistEndpoints
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import kotlin.time.Clock

/**
 * AniList's endpoints and client binding, on `MyAnimeListBindingsTest`'s pattern: the endpoints are
 * AniList's own in every build, the client is the build's id, and the tracker built over the
 * bindings is configured exactly when that id is set, as MyAnimeList is with no test base.
 */
class AnilistBindingsTest {

    private fun tracker(clientId: String) = Anilist(
        trackPreferences = TrackPreferences(MapPreferenceStore()),
        trackRepository = mockk(),
        httpClient = mockk { every { client } returns OkHttpClient() },
        clock = Clock.System,
        endpointsProvider = { AnilistBindings.provideAnilistEndpoints() },
        buildClient = AnilistClient(clientId),
        json = Json,
    )

    @Test
    fun `every build sends AniList's calls to AniList`() {
        val endpoints = AnilistBindings.provideAnilistEndpoints()

        endpoints shouldBe AnilistEndpoints.ANILIST
        endpoints.isAniList shouldBe true
        endpoints.authorization.toString() shouldBe "https://anilist.co/api/v2/oauth/authorize"
        endpoints.api.toString() shouldBe "https://graphql.anilist.co/"
    }

    @Test
    fun `the client is the build's id, blank or set`() {
        AnilistBindings.provideAnilistClient().clientId shouldBe BuildConfig.ANILIST_CLIENT_ID
    }

    @Test
    fun `AniList is configured only with a client id, and stays in the graph under its id without one`() {
        tracker("").isConfigured shouldBe false
        tracker("  ").isConfigured shouldBe false
        tracker("").id shouldBe TrackerIds.ANILIST
        tracker("owner-client-id").isConfigured shouldBe true
        tracker(BuildConfig.ANILIST_CLIENT_ID).isConfigured shouldBe BuildConfig.ANILIST_CLIENT_ID.isNotBlank()
    }
}
