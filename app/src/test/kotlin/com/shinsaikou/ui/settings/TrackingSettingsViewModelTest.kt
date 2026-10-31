package com.shinsaikou.ui.settings

import android.content.Intent
import com.shinsaikou.core.common.oauth.LoopbackCallback
import com.shinsaikou.core.common.oauth.LoopbackDeniedException
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.LoopbackPages
import com.shinsaikou.core.common.oauth.LoopbackPortInUseException
import com.shinsaikou.core.common.oauth.LoopbackResponse
import com.shinsaikou.core.common.oauth.LoopbackTimeoutException
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.preferences.AutoTrackState
import com.shinsaikou.domain.preferences.TrackPreferences
import com.shinsaikou.signin.BrowserSignIn
import com.shinsaikou.signin.SignInCancelledException
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.tracking.DelayedTrackingScheduler
import com.shinsaikou.tracking.DelayedTrackingStore
import com.shinsaikou.tracking.FakeComparingTracker
import com.shinsaikou.tracking.FakeOAuthTracker
import com.shinsaikou.tracking.ResumeQueuedPushes
import com.shinsaikou.tracking.Tracker
import com.shinsaikou.tracking.TrackerManager
import com.shinsaikou.tracking.TrackerSignInNotifier
import com.shinsaikou.tracking.TrackerSignInRefusedException
import com.shinsaikou.ui.settings.TrackingSettingsViewModel.CompareRow
import com.shinsaikou.ui.settings.TrackingSettingsViewModel.Problem
import com.shinsaikou.ui.settings.TrackingSettingsViewModel.Status
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.BindException
import kotlin.time.Duration.Companion.minutes
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * The Tracking section's view model (phase 3 unit T1 stage 2) over fake OAuth trackers and a stand-in
 * for O's [BrowserSignIn]. The stand-in keeps O's rule that a new sign-in cancels the one in flight
 * with [SignInCancelledException], builds the authorization URL against a real loopback listener on
 * a port the system picks (never the tracker port on this host), and counts every session whose wait
 * was cancelled: in the real class that is what closes the listener and ends the hold
 * (`BrowserSignInTest` covers that half).
 */
class TrackingSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)
    private val trackPreferences = TrackPreferences(MapPreferenceStore())

    private val browserSignIn = mockk<BrowserSignIn>()
    private val ports = mutableListOf<Int>()
    private val responses = mutableListOf<LoopbackResponse>()
    private val authorizationUrls = mutableListOf<HttpUrl>()
    private var redirectUri = ""

    /** The session waiting on the browser: complete it with [callback], or fail it with [fail]. */
    private var waiting: CompletableDeferred<BrowserSignIn.Result>? = null
    private var cancelledSessions = 0

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { browserSignIn.signIn(any(), any(), any(), any()) } coAnswers {
            ports += firstArg<Int>()
            responses += thirdArg<LoopbackResponse>()
            val authorizationUrl = arg<(LoopbackListener) -> HttpUrl>(3)
            LoopbackListener.open(0, LoopbackPages("Saikou", "in", "out")).use {
                redirectUri = it.redirectUri
                authorizationUrls += authorizationUrl(it)
            }
            val session = CompletableDeferred<BrowserSignIn.Result>()
            waiting?.completeExceptionally(SignInCancelledException())
            waiting = session
            try {
                session.await()
            } catch (e: CancellationException) {
                cancelledSessions++
                throw e
            }
        }
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private val queuePreferences = MapPreferenceStore()
    private val store = DelayedTrackingStore(queuePreferences) { key, value ->
        if (value == null) queuePreferences.values.remove(key) else queuePreferences.values[key] = value
    }
    private var scheduled = 0
    private val signedInNotices = mutableListOf<Long>()
    private val notifier = object : TrackerSignInNotifier {
        override fun expired(tracker: Tracker) = Unit
        override fun signedIn(tracker: Tracker) {
            signedInNotices += tracker.id
        }
    }

    private fun viewModel(vararg trackers: Tracker) = TrackingSettingsViewModel(
        trackerManager = TrackerManager(trackers.toSet()),
        trackPreferences = trackPreferences,
        browserSignIn = browserSignIn,
        dispatchers = dispatchers,
        resumeQueuedPushes = ResumeQueuedPushes(store, DelayedTrackingScheduler { scheduled++ }, notifier),
    )

    private fun callback() {
        checkNotNull(waiting).complete(BrowserSignIn.Result(LoopbackCallback(CODE, mapOf("code" to CODE)), redirectUri, true))
    }

    private fun fail(error: Throwable) {
        checkNotNull(waiting).completeExceptionally(error)
    }

    private fun TrackingSettingsViewModel.row(id: Long) = state.value.trackers.single { it.id == id }

    @Test
    fun `one row per configured tracker in id order, each in the state its sign-in is in`() = runTest(testDispatcher) {
        val signedOut = FakeOAuthTracker(id = 3L)
        val signedIn = FakeOAuthTracker(id = 1L, loggedIn = true, displayName = "Owner")
        val expired = FakeOAuthTracker(id = 2L, loggedIn = true, authExpired = true)
        val unconfigured = FakeOAuthTracker(id = 4L, isConfigured = false, loggedIn = true)

        val viewModel = viewModel(signedOut, signedIn, expired, unconfigured)
        advanceUntilIdle()

        viewModel.state.value.trackers.map { it.id } shouldContainExactly listOf(1L, 2L, 3L)
        viewModel.row(1L).status shouldBe Status.SIGNED_IN
        viewModel.row(1L).displayName shouldBe "Owner"
        viewModel.row(2L).status shouldBe Status.SIGN_IN_AGAIN
        viewModel.row(3L).status shouldBe Status.SIGNED_OUT
        viewModel.row(3L).displayName shouldBe ""
        viewModel.state.value.trackers.map { it.canSignIn } shouldContainExactly listOf(true, true, true)
    }

    /**
     * The "Compare with" row (phase 4 unit TC, test 51): shown and enabled while signed in, dimmed
     * while the service wants a new sign-in, absent while signed out or waiting for the browser, and
     * absent for a tracker that cannot compare.
     */
    @Test
    fun `the compare row shows while signed in, dims for sign in again, and hides while signed out or waiting`() =
        runTest(testDispatcher) {
            val comparing = FakeComparingTracker(FakeOAuthTracker(id = 1L, loggedIn = true))
            val plain = FakeOAuthTracker(id = 2L, loggedIn = true)
            val viewModel = viewModel(comparing, plain)
            advanceUntilIdle()

            viewModel.row(1L).compare shouldBe CompareRow.ENABLED
            viewModel.row(2L).compare shouldBe CompareRow.HIDDEN

            comparing.base.authExpiredState.value = true
            advanceUntilIdle()
            viewModel.row(1L).compare shouldBe CompareRow.DIMMED

            comparing.base.logout()
            advanceUntilIdle()
            viewModel.row(1L).status shouldBe Status.SIGNED_OUT
            viewModel.row(1L).compare shouldBe CompareRow.HIDDEN

            viewModel.signIn(1L, mockk<Intent>())
            advanceUntilIdle()
            viewModel.row(1L).status shouldBe Status.WAITING_FOR_BROWSER
            viewModel.row(1L).compare shouldBe CompareRow.HIDDEN
            viewModel.cancelSignIn(1L)
            advanceUntilIdle()
        }

    @Test
    fun `no configured tracker gives no rows`() = runTest(testDispatcher) {
        viewModel().state.value.trackers.shouldBeEmpty()
        viewModel(FakeOAuthTracker(id = 1L, isConfigured = false)).state.value.trackers.shouldBeEmpty()
    }

    @Test
    fun `a sign-in waits for the browser on the tracker port, then completes with the same redirect and pkce`() =
        runTest(testDispatcher) {
            val tracker = FakeOAuthTracker(id = 1L, displayName = "Owner")
            val viewModel = viewModel(tracker)
            advanceUntilIdle()

            viewModel.signIn(1L, mockk<Intent>())
            advanceUntilIdle()

            viewModel.row(1L).status shouldBe Status.WAITING_FOR_BROWSER
            ports shouldContainExactly listOf(LoopbackListener.TRACKER_PORT)
            responses shouldContainExactly listOf(LoopbackResponse.CODE)
            val (redirect, state, pkce) = tracker.authorizationRequests.single()
            redirect shouldBe redirectUri
            val url = authorizationUrls.single()
            url.queryParameter("redirect_uri") shouldBe redirect
            url.queryParameter("state") shouldBe state
            // MyAnimeList's method: the challenge is the verifier itself.
            url.queryParameter("code_challenge_method") shouldBe "plain"
            url.queryParameter("code_challenge") shouldBe pkce.verifier
            tracker.completions.shouldBeEmpty()

            callback()
            advanceUntilIdle()

            val (code, completedRedirect, completedPkce) = tracker.completions.single()
            code shouldBe CODE
            completedRedirect shouldBe redirect
            (completedPkce === pkce) shouldBe true
            viewModel.row(1L).status shouldBe Status.SIGNED_IN
            viewModel.row(1L).displayName shouldBe "Owner"
            viewModel.row(1L).problem.shouldBeNull()
            // The state holds no code, state or verifier.
            for (secret in listOf(CODE, state, pkce.verifier)) viewModel.state.value.toString() shouldNotContain secret
        }

    /**
     * AniList's implicit grant (R162 item 8): the listener waits for a token in the redirect's
     * fragment, on the same port, and the token the redirect carried completes the sign-in where a
     * code would.
     */
    @Test
    fun `an implicit grant's sign-in waits for a token on the tracker port, and the token completes it`() =
        runTest(testDispatcher) {
            val code = FakeOAuthTracker(id = 1L)
            val token = FakeOAuthTracker(id = 2L, loopbackResponse = LoopbackResponse.TOKEN)
            val viewModel = viewModel(code, token)
            advanceUntilIdle()

            viewModel.signIn(2L, mockk<Intent>())
            advanceUntilIdle()

            ports shouldContainExactly listOf(LoopbackListener.TRACKER_PORT)
            responses shouldContainExactly listOf(LoopbackResponse.TOKEN)
            callback()
            advanceUntilIdle()

            token.completions.single().first shouldBe CODE
            viewModel.row(2L).status shouldBe Status.SIGNED_IN
            code.completions.shouldBeEmpty()
        }

    /**
     * The pushes the retry worker kept while the tracker was expired or signed out go out once a
     * sign-in stores its grant, and the "sign in again" notice goes; a failed sign-in does neither.
     */
    @Test
    fun `a completed sign-in sends the pushes kept for the tracker and removes its notice`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(id = 1L, loggedIn = true, authExpired = true)
        store.add(42L, 1L, 1001L, 7.0)
        val viewModel = viewModel(tracker)
        advanceUntilIdle()

        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        fail(LoopbackTimeoutException(5.minutes))
        advanceUntilIdle()
        scheduled shouldBe 0
        signedInNotices.shouldBeEmpty()

        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        callback()
        advanceUntilIdle()

        scheduled shouldBe 1
        signedInNotices shouldContainExactly listOf(1L)
    }

    @Test
    fun `every pkce pair is new for its sign-in`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(id = 1L)
        val viewModel = viewModel(tracker)
        advanceUntilIdle()

        repeat(2) {
            viewModel.signIn(1L, mockk<Intent>())
            advanceUntilIdle()
            fail(LoopbackTimeoutException(5.minutes))
            advanceUntilIdle()
        }

        val verifiers = tracker.authorizationRequests.map { it.third.verifier }
        verifiers.toSet().size shouldBe 2
    }

    @Test
    fun `cancel ends the browser's wait, and the row goes back to signed out with nothing to show`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(id = 1L)
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        viewModel.row(1L).status shouldBe Status.WAITING_FOR_BROWSER

        viewModel.cancelSignIn(1L)
        advanceUntilIdle()

        cancelledSessions shouldBe 1
        viewModel.row(1L).status shouldBe Status.SIGNED_OUT
        viewModel.row(1L).problem.shouldBeNull()
        tracker.completions.shouldBeEmpty()

        // Signing in again after a cancel starts a new sign-in.
        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        viewModel.row(1L).status shouldBe Status.WAITING_FOR_BROWSER
        ports.size shouldBe 2
    }

    @Test
    fun `a sign-in replaced by another ends quietly and the other one completes`() = runTest(testDispatcher) {
        val first = FakeOAuthTracker(id = 1L)
        val second = FakeOAuthTracker(id = 2L)
        val viewModel = viewModel(first, second)
        advanceUntilIdle()

        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        viewModel.signIn(2L, mockk<Intent>())
        advanceUntilIdle()

        cancelledSessions shouldBe 1
        viewModel.row(1L).status shouldBe Status.SIGNED_OUT
        viewModel.row(1L).problem.shouldBeNull()
        viewModel.row(2L).status shouldBe Status.WAITING_FOR_BROWSER

        callback()
        advanceUntilIdle()

        first.completions.shouldBeEmpty()
        second.completions.single().first shouldBe CODE
        viewModel.row(2L).status shouldBe Status.SIGNED_IN
    }

    @Test
    fun `a second tap while one is waiting starts nothing`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeOAuthTracker(id = 1L))
        advanceUntilIdle()

        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()

        ports.size shouldBe 1
        cancelledSessions shouldBe 0
    }

    @Test
    fun `each failed sign-in shows its key and leaves the row as it was`() = runTest(testDispatcher) {
        data class Case(val name: String, val problem: Problem, val arrange: (FakeOAuthTracker) -> Unit)
        val cases = listOf(
            Case("port in use", Problem(ErrorKey.OAUTH_PORT_IN_USE, "18796")) {
                fail(LoopbackPortInUseException(LoopbackListener.TRACKER_PORT, BindException("fixture: in use")))
            },
            Case("timed out", Problem(ErrorKey.OAUTH_TIMED_OUT, null)) { fail(LoopbackTimeoutException(5.minutes)) },
            Case("denied", Problem(ErrorKey.OAUTH_DENIED, "access_denied")) { fail(LoopbackDeniedException("access_denied")) },
            Case("refused by the tracker", Problem(ErrorKey.TRACKER_SIGN_IN_REFUSED, "invalid_grant")) { tracker ->
                tracker.exchange = { throw TrackerSignInRefusedException(tracker.id, "invalid_grant") }
                callback()
            },
            Case("tracker unreachable", Problem(ErrorKey.GENERIC, "IOException")) { tracker ->
                tracker.exchange = { throw IOException("fixture: dropped") }
                callback()
            },
        )
        for (expired in listOf(false, true)) {
            for (case in cases) {
                val tracker = FakeOAuthTracker(id = 1L, loggedIn = expired, authExpired = expired)
                val viewModel = viewModel(tracker)
                advanceUntilIdle()

                viewModel.signIn(1L, mockk<Intent>())
                advanceUntilIdle()
                case.arrange(tracker)
                advanceUntilIdle()

                withClue("${case.name}, expired=$expired") {
                    viewModel.row(1L).problem shouldBe case.problem
                    viewModel.row(1L).status shouldBe if (expired) Status.SIGN_IN_AGAIN else Status.SIGNED_OUT
                    viewModel.row(1L).signingIn shouldBe false
                }
            }
        }
    }

    @Test
    fun `a new attempt clears the last problem`() = runTest(testDispatcher) {
        val viewModel = viewModel(FakeOAuthTracker(id = 1L))
        advanceUntilIdle()
        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        fail(LoopbackTimeoutException(5.minutes))
        advanceUntilIdle()
        viewModel.row(1L).problem shouldBe Problem(ErrorKey.OAUTH_TIMED_OUT, null)

        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()

        viewModel.row(1L).problem.shouldBeNull()
        viewModel.row(1L).status shouldBe Status.WAITING_FOR_BROWSER
    }

    @Test
    fun `an expired sign-in turns the row to sign in again while it is open, and signing in again clears it`() =
        runTest(testDispatcher) {
            val tracker = FakeOAuthTracker(id = 1L, loggedIn = true)
            val viewModel = viewModel(tracker)
            advanceUntilIdle()
            viewModel.row(1L).status shouldBe Status.SIGNED_IN

            // The retry worker's refresh was refused: the tracker set trackAuthExpired.
            tracker.authExpiredState.value = true
            advanceUntilIdle()
            viewModel.row(1L).status shouldBe Status.SIGN_IN_AGAIN

            viewModel.signIn(1L, mockk<Intent>())
            advanceUntilIdle()
            callback()
            advanceUntilIdle()

            viewModel.row(1L).status shouldBe Status.SIGNED_IN
            tracker.isAuthExpired shouldBe false
        }

    @Test
    fun `sign-out clears the sign-in and the last problem`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(id = 1L, loggedIn = true, authExpired = true)
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        fail(LoopbackDeniedException("access_denied"))
        advanceUntilIdle()
        viewModel.row(1L).problem shouldBe Problem(ErrorKey.OAUTH_DENIED, "access_denied")

        viewModel.signOut(1L)
        advanceUntilIdle()

        tracker.logouts shouldBe 1
        viewModel.row(1L).status shouldBe Status.SIGNED_OUT
        viewModel.row(1L).problem.shouldBeNull()
    }

    @Test
    fun `signing out while a sign-in waits cancels it first`() = runTest(testDispatcher) {
        val tracker = FakeOAuthTracker(id = 1L, loggedIn = true, authExpired = true)
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()

        viewModel.signOut(1L)
        advanceUntilIdle()

        cancelledSessions shouldBe 1
        tracker.logouts shouldBe 1
        tracker.completions.shouldBeEmpty()
        viewModel.row(1L).status shouldBe Status.SIGNED_OUT
    }

    @Test
    fun `a sign-out that lands while the sign-in is storing waits for it, and ends signed out with no grant`() =
        runTest(testDispatcher) {
            val storing = CompletableDeferred<Unit>()
            val tracker = FakeOAuthTracker(id = 1L).apply { betweenWrites = { storing.await() } }
            val viewModel = viewModel(tracker)
            advanceUntilIdle()
            viewModel.signIn(1L, mockk<Intent>())
            advanceUntilIdle()
            callback()
            advanceUntilIdle()
            // The grant is stored and the credentials are not: the sign-in is between its writes.
            tracker.token shouldBe "fixture-grant-1"
            tracker.isLoggedIn shouldBe false

            viewModel.signOut(1L)
            advanceUntilIdle()

            // The sign-out waits for the sign-in to finish storing before it signs out.
            tracker.logouts shouldBe 0
            storing.complete(Unit)
            advanceUntilIdle()

            tracker.logouts shouldBe 1
            tracker.isLoggedIn shouldBe false
            tracker.token shouldBe null
            viewModel.row(1L).status shouldBe Status.SIGNED_OUT
            viewModel.row(1L).signingIn shouldBe false
        }

    @Test
    fun `a cancel that lands while the sign-in is storing lets it finish, and the row shows it signed in`() =
        runTest(testDispatcher) {
            val storing = CompletableDeferred<Unit>()
            val tracker = FakeOAuthTracker(id = 1L, displayName = "Owner").apply { betweenWrites = { storing.await() } }
            store.add(42L, 1L, 1001L, 7.0)
            val viewModel = viewModel(tracker)
            advanceUntilIdle()
            viewModel.signIn(1L, mockk<Intent>())
            advanceUntilIdle()
            callback()
            advanceUntilIdle()

            viewModel.cancelSignIn(1L)
            advanceUntilIdle()
            storing.complete(Unit)
            advanceUntilIdle()

            tracker.token shouldBe "fixture-grant-1"
            tracker.isLoggedIn shouldBe true
            viewModel.row(1L).status shouldBe Status.SIGNED_IN
            viewModel.row(1L).displayName shouldBe "Owner"
            // The sign-in stands, so what a sign-in sets going goes too.
            scheduled shouldBe 1
            signedInNotices shouldContainExactly listOf(1L)
        }

    @Test
    fun `the row shows signed in once the credentials land, not waiting for the browser`() = runTest(testDispatcher) {
        val settled = CompletableDeferred<Unit>()
        val tracker = FakeOAuthTracker(id = 1L, displayName = "Owner").apply { afterWrites = { settled.await() } }
        val viewModel = viewModel(tracker)
        advanceUntilIdle()
        viewModel.signIn(1L, mockk<Intent>())
        advanceUntilIdle()
        callback()
        advanceUntilIdle()

        // Every write has landed and the sign-in's coroutine has not returned yet.
        tracker.isLoggedIn shouldBe true
        viewModel.row(1L).status shouldBe Status.SIGNED_IN
        viewModel.row(1L).displayName shouldBe "Owner"

        settled.complete(Unit)
        advanceUntilIdle()
        viewModel.row(1L).status shouldBe Status.SIGNED_IN
    }

    @Test
    fun `a tracker that is not a browser sign-in tracker offers no sign-in`() = runTest(testDispatcher) {
        val password = mockk<Tracker> {
            every { id } returns 7L
            every { name } returns "Password tracker"
            every { isConfigured } returns true
            every { isLoggedIn } returns false
            every { isAuthExpired } returns false
            every { isLoggedInFlow } returns flowOf(false)
            every { isAuthExpiredFlow } returns flowOf(false)
        }
        val viewModel = viewModel(password)
        advanceUntilIdle()

        viewModel.row(7L).canSignIn shouldBe false
        viewModel.signIn(7L, mockk<Intent>())
        advanceUntilIdle()

        ports.shouldBeEmpty()
        viewModel.row(7L).status shouldBe Status.SIGNED_OUT
    }

    @Test
    fun `the two behaviour rows show the stored values and write what is picked`() = runTest(testDispatcher) {
        trackPreferences.autoUpdateTrack.set(false)
        trackPreferences.autoUpdateTrackOnMarkRead.set(AutoTrackState.ASK)
        val viewModel = viewModel(FakeOAuthTracker(id = 1L))
        advanceUntilIdle()
        viewModel.state.value.autoUpdateTrack shouldBe false
        viewModel.state.value.autoUpdateTrackOnMarkRead shouldBe AutoTrackState.ASK

        viewModel.setAutoUpdateTrack(true)
        viewModel.setAutoUpdateTrackOnMarkRead(AutoTrackState.NEVER)
        advanceUntilIdle()

        trackPreferences.autoUpdateTrack.get() shouldBe true
        trackPreferences.autoUpdateTrackOnMarkRead.get() shouldBe AutoTrackState.NEVER
        viewModel.state.value.autoUpdateTrack shouldBe true
        viewModel.state.value.autoUpdateTrackOnMarkRead shouldBe AutoTrackState.NEVER

        viewModel.setAutoUpdateTrackOnMarkRead(AutoTrackState.ALWAYS)
        advanceUntilIdle()
        trackPreferences.autoUpdateTrackOnMarkRead.get() shouldBe AutoTrackState.ALWAYS
    }

    private companion object {
        const val CODE = "fixture-authorization-code"
    }
}
