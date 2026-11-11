package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.comick.Comick
import com.shinsaikou.tracking.comick.ComickClient
import com.shinsaikou.tracking.comick.ComickEndpoints
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import kotlin.time.Clock

/**
 * Comick's endpoints and client binding, on `MangaBakaBindingsTest`'s pattern: the endpoints are
 * Comick's own in every build, the client is the build's id, and the tracker built over the bindings
 * is configured exactly when that id is set.
 */
class ComickBindingsTest {

    private fun tracker(clientId: String) = Comick(
        trackPreferences = TrackPreferences(MapPreferenceStore()),
        trackRepository = mockk(),
        httpClient = mockk { every { client } returns OkHttpClient() },
        clock = Clock.System,
        endpointsProvider = { ComickBindings.provideComickEndpoints() },
        buildClient = ComickClient(clientId),
        json = Json,
    )

    @Test
    fun `every build sends Comick's calls to Comick`() {
        val endpoints = ComickBindings.provideComickEndpoints()

        endpoints shouldBe ComickEndpoints.COMICK
        endpoints.isComick shouldBe true
        endpoints.authorization.toString() shouldBe "https://comick.dev/api/auth/oauth2/authorize"
        endpoints.token.toString() shouldBe "https://comick.dev/api/auth/oauth2/token"
        endpoints.api.toString() shouldBe "https://api.comick.dev/integrations/v1/"
    }

    @Test
    fun `the client is the build's id, blank or set`() {
        ComickBindings.provideComickClient().clientId shouldBe BuildConfig.COMICK_CLIENT_ID
    }

    @Test
    fun `Comick is configured only with a client id, and stays in the graph under its id without one`() {
        tracker("").isConfigured shouldBe false
        tracker("  ").isConfigured shouldBe false
        tracker("").id shouldBe TrackerIds.COMICK
        tracker("owner-client-id").isConfigured shouldBe true
        tracker(BuildConfig.COMICK_CLIENT_ID).isConfigured shouldBe BuildConfig.COMICK_CLIENT_ID.isNotBlank()
    }
}
