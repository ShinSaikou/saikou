package com.shinsaikou.tracking

import com.shinsaikou.BuildConfig
import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.myanimelist.MyAnimeList
import com.shinsaikou.tracking.myanimelist.MyAnimeListClient
import com.shinsaikou.tracking.myanimelist.MyAnimeListEndpoints
import com.shinsaikou.tracking.myanimelist.MyAnimeListEndpoints.Companion.MYANIMELIST
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import kotlin.time.Clock

/**
 * MyAnimeList's endpoints and client binding (phase 3 unit T2), on `GoogleBindingsTest`'s pattern:
 * `:app` has unit tests for the debug variant only, so the release build's path is pinned through
 * `MyAnimeListBindings.endpointsFor(debug = false, ...)`, the call the provider makes there, which
 * never reads the test base. Then whether the tracker built over the binding is configured, as
 * `DriveAvailabilityBuildTest` checks Drive's, with the build's id blank or set.
 */
class MyAnimeListBindingsTest {

    private val testBase = MyAnimeListTestBase()

    private fun tracker(clientId: String, endpoints: () -> MyAnimeListEndpoints) = MyAnimeList(
        trackPreferences = TrackPreferences(MapPreferenceStore()),
        trackRepository = mockk(),
        httpClient = mockk { every { client } returns OkHttpClient() },
        clock = Clock.System,
        endpointsProvider = endpoints,
        buildClient = MyAnimeListClient(clientId),
        json = Json,
    )

    @Test
    fun `the debug build sends every MyAnimeList call to the test base once one is set`() {
        BuildConfig.DEBUG shouldBe true
        MyAnimeListBindings.provideMyAnimeListEndpoints(lazyOf(testBase)) shouldBe MYANIMELIST

        testBase.value = "http://10.0.2.2:18801"
        val endpoints = MyAnimeListBindings.provideMyAnimeListEndpoints(lazyOf(testBase))
        endpoints.isMyAnimeList shouldBe false
        endpoints.authorization.toString() shouldBe "http://10.0.2.2:18801/v1/oauth2/authorize"
        endpoints.token.toString() shouldBe "http://10.0.2.2:18801/v1/oauth2/token"
        endpoints.api.toString() shouldBe "http://10.0.2.2:18801/v2"
    }

    @Test
    fun `a blank test base is no test base, and one that is not an address is refused`() {
        testBase.value = "  "
        testBase.value shouldBe null
        MyAnimeListBindings.provideMyAnimeListEndpoints(lazyOf(testBase)) shouldBe MYANIMELIST

        testBase.value = "not an address"
        shouldThrow<IllegalArgumentException> { MyAnimeListBindings.provideMyAnimeListEndpoints(lazyOf(testBase)) }
    }

    @Test
    fun `the release build's endpoints are MyAnimeList's without reading the test base`() {
        var read = 0
        val lazyBase = lazy {
            read++
            testBase.apply { value = "http://10.0.2.2:18801" }
        }

        MyAnimeListBindings.endpointsFor(debug = false) { lazyBase.value.value } shouldBe MYANIMELIST
        MyAnimeListBindings.endpointsFor(debug = false) { throw AssertionError("a release build read the test base") } shouldBe
            MYANIMELIST
        read shouldBe 0
        lazyBase.isInitialized() shouldBe false

        MyAnimeListBindings.endpointsFor(debug = true) { lazyBase.value.value }.isMyAnimeList shouldBe false
        read shouldBe 1
    }

    @Test
    fun `the client is the build's id, blank or set`() {
        MyAnimeListBindings.provideMyAnimeListClient().clientId shouldBe BuildConfig.MAL_CLIENT_ID
    }

    @Test
    fun `a release build is configured only with a client id, whatever a test base says`() {
        val release = { MyAnimeListBindings.endpointsFor(debug = false) { "http://10.0.2.2:18801" } }

        tracker("", release).isConfigured shouldBe false
        tracker("  ", release).isConfigured shouldBe false
        tracker("owner-client-id", release).isConfigured shouldBe true
    }

    @Test
    fun `a debug build is also configured while a test base is set, and reads it on each call`() {
        val blank = tracker("") { MyAnimeListBindings.provideMyAnimeListEndpoints(lazyOf(testBase)) }

        blank.isConfigured shouldBe false
        testBase.value = "http://10.0.2.2:18801"
        blank.isConfigured shouldBe true
        testBase.value = null
        blank.isConfigured shouldBe false

        tracker("owner-client-id") { MyAnimeListBindings.provideMyAnimeListEndpoints(lazyOf(testBase)) }
            .isConfigured shouldBe true
    }

    @Test
    fun `the build's id goes to MyAnimeList alone, and a test base gets the fake's`() {
        val tracker = tracker("owner-client-id") { MyAnimeListBindings.provideMyAnimeListEndpoints(lazyOf(testBase)) }
        val pkce = Pkce.generate()

        tracker.authorizationUrl("http://127.0.0.1:18796/callback", "s", pkce).queryParameter("client_id") shouldBe
            "owner-client-id"
        testBase.value = "http://10.0.2.2:18801"
        val onTestBase = tracker.authorizationUrl("http://127.0.0.1:18796/callback", "s", pkce)
        onTestBase.queryParameter("client_id") shouldBe MyAnimeListClient.TEST_BASE_CLIENT_ID
        onTestBase.toString() shouldNotContain "owner-client-id"
    }
}
