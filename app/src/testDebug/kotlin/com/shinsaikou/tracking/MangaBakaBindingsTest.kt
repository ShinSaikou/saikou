package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.mangabaka.MangaBaka
import com.shinsaikou.tracking.mangabaka.MangaBakaClient
import com.shinsaikou.tracking.mangabaka.MangaBakaEndpoints
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers as KotlinDispatchers

/**
 * MangaBaka's endpoints and client binding, on `AnilistBindingsTest`'s pattern: the endpoints are
 * MangaBaka's own in every build, the client is the build's id, and the tracker built over the
 * bindings is configured exactly when that id is set.
 */
class MangaBakaBindingsTest {

    private fun tracker(clientId: String) = MangaBaka(
        trackPreferences = TrackPreferences(MapPreferenceStore()),
        trackRepository = mockk(),
        httpClient = mockk { every { client } returns OkHttpClient() },
        clock = Clock.System,
        endpointsProvider = { MangaBakaBindings.provideMangaBakaEndpoints() },
        buildClient = MangaBakaClient(clientId),
        json = Json,
        appScope = CoroutineScope(KotlinDispatchers.Unconfined),
        dispatchers = Dispatchers(KotlinDispatchers.Unconfined, KotlinDispatchers.Unconfined, KotlinDispatchers.Unconfined),
    )

    @Test
    fun `every build sends MangaBaka's calls to MangaBaka`() {
        val endpoints = MangaBakaBindings.provideMangaBakaEndpoints()

        endpoints shouldBe MangaBakaEndpoints.MANGABAKA
        endpoints.isMangaBaka shouldBe true
        endpoints.authorization.toString() shouldBe "https://mangabaka.org/auth/oauth2/authorize"
        endpoints.token.toString() shouldBe "https://mangabaka.org/auth/oauth2/token"
        endpoints.revocation.toString() shouldBe "https://mangabaka.org/auth/oauth2/revoke"
        endpoints.api.toString() shouldBe "https://api.mangabaka.org/"
    }

    @Test
    fun `the client is the build's id, blank or set`() {
        MangaBakaBindings.provideMangaBakaClient().clientId shouldBe BuildConfig.MANGABAKA_CLIENT_ID
    }

    @Test
    fun `MangaBaka is configured only with a client id, and stays in the graph under its id without one`() {
        tracker("").isConfigured shouldBe false
        tracker("  ").isConfigured shouldBe false
        tracker("").id shouldBe TrackerIds.MANGABAKA
        tracker("owner-client-id").isConfigured shouldBe true
        tracker(BuildConfig.MANGABAKA_CLIENT_ID).isConfigured shouldBe BuildConfig.MANGABAKA_CLIENT_ID.isNotBlank()
    }
}
