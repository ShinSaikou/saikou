package com.shinsaikou.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.shinsaikou.core.common.oauth.LoopbackCallback
import com.shinsaikou.core.common.oauth.LoopbackDeniedException
import com.shinsaikou.core.common.oauth.LoopbackListener
import com.shinsaikou.core.common.oauth.LoopbackPages
import com.shinsaikou.core.common.oauth.LoopbackPortInUseException
import com.shinsaikou.core.common.oauth.LoopbackTimeoutException
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.domain.sync.model.StoredCategory
import com.shinsaikou.domain.sync.model.SyncLibraryCounts
import com.shinsaikou.domain.sync.model.SyncPushRequest
import com.shinsaikou.domain.sync.model.SyncPushRows
import com.shinsaikou.domain.sync.repository.SyncApplyScope
import com.shinsaikou.domain.sync.repository.SyncStore
import com.shinsaikou.domain.sync.repository.SyncStoreSnapshot
import com.shinsaikou.sync.BackendStatus
import com.shinsaikou.sync.MapPreferenceStore
import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SyncConflict
import com.shinsaikou.sync.SyncHttpClient
import com.shinsaikou.sync.SyncPreferences
import com.shinsaikou.sync.SyncRunRecord
import com.shinsaikou.sync.SyncRunReport
import com.shinsaikou.sync.SyncRunResult
import com.shinsaikou.sync.SyncScheduler
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.SyncSession
import com.shinsaikou.sync.SyncWorkPreferences
import com.shinsaikou.sync.drive.DriveAccount
import com.shinsaikou.sync.drive.DriveAvailability
import com.shinsaikou.sync.drive.DriveBackoff
import com.shinsaikou.sync.drive.DriveSyncBackend
import com.shinsaikou.sync.format.NewerFormatException
import com.shinsaikou.sync.google.GoogleClientCredentials
import com.shinsaikou.sync.google.GoogleEndpoints
import com.shinsaikou.sync.google.GoogleOAuthClient
import com.shinsaikou.sync.format.RowKind
import com.shinsaikou.sync.webdav.WebDavAccount
import com.shinsaikou.sync.webdav.WebDavSignIn
import com.shinsaikou.sync.webdav.WebDavUnreachableException
import com.shinsaikou.sync.webdav.WebDavUrlPolicy
import com.shinsaikou.sync.webdav.WebDavUrlRefusedException
import com.shinsaikou.signin.BrowserSignIn
import com.shinsaikou.signin.SignInCancelledException
import com.shinsaikou.ui.main.BatteryExemption
import com.shinsaikou.ui.settings.SyncSettingsViewModel.Fingerprint
import com.shinsaikou.ui.settings.SyncSettingsViewModel.SignInProblem
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.math.BigInteger
import java.net.ConnectException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import com.shinsaikou.core.common.coroutines.Dispatchers as AppDispatchers
import kotlinx.coroutines.Dispatchers as KotlinxDispatchers

/**
 * Sync settings' view model over the real WebDAV sign-in and the real preference stores: the secret
 * store is an in-memory map and the server is an OkHttp interceptor answering as the fixture would,
 * so what a sign-in leaves stored is read back rather than assumed. The session and the scheduler
 * are stand-ins; `SyncSessionTest` covers what they do.
 */
class SyncSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = AppDispatchers(io = testDispatcher, default = testDispatcher, main = testDispatcher)

    private val values = mutableMapOf<String, Any>()
    private val syncPreferences = SyncPreferences(MapPreferenceStore(values))
    private val workPreferences = SyncWorkPreferences(MapPreferenceStore(values))
    private val record = SyncRunRecord(MapPreferenceStore(values))

    private val secrets = object : SecretStore {
        val stored = mutableMapOf<String, String>()
        override fun get(name: String): String? = stored[name]
        override fun put(name: String, value: String) {
            stored[name] = value
        }
        override fun delete(name: String) {
            stored.remove(name)
        }
    }

    /**
     * What the fake server answers: Google's token, about and revoke endpoints under [GOOGLE_BASE] as
     * [google] says, and anything else a PROPFIND on the base address, as signed in.
     */
    private var answer: (Request) -> Response = { request ->
        if (request.url.toString().startsWith(GOOGLE_BASE)) google.answer(request) else multistatus(request)
    }
    private val requests = mutableListOf<Request>()
    private val okHttp = OkHttpClient.Builder()
        // Each call runs on the thread that sends it. A sign-out's revoke is bounded by a timeout on
        // the test dispatcher's virtual time, so a call left to OkHttp's own thread could lose to it:
        // advanceUntilIdle jumped the ten seconds before that thread reached the fake, the revoke was
        // cancelled unsent, and "every failed google sign-in" failed under a loaded full suite.
        .dispatcher(okhttp3.Dispatcher(CallingThreadExecutor()))
        .addInterceptor { chain ->
            requests += chain.request()
            answer(chain.request())
        }
        .build()
    private val httpClient = mockk<SyncHttpClient> { every { client } returns okHttp }

    private val account = WebDavAccount(secrets, httpClient, WebDavUrlPolicy.forBuild(debug = true), dispatchers)

    /** Google's endpoints as the debug build sees them: the test base, or Google's own with no client id. */
    private var googleEndpoints = GoogleEndpoints.forBuild(debug = true, testBase = GOOGLE_BASE)
    private val google = FakeGoogle()
    private val noClient = GoogleClientCredentials(clientId = "", clientSecret = "")
    private val driveAvailability = DriveAvailability({ googleEndpoints }, noClient)
    private val driveAccount = DriveAccount(
        secrets = secrets,
        oauthClient = { GoogleOAuthClient(httpClient, googleEndpoints, noClient, Clock.System, dispatchers) },
        httpClient = httpClient,
        availability = driveAvailability,
        clock = Clock.System,
        // No retry: a failing Google call fails the sign-in at once.
        backoff = DriveBackoff(emptyList()) {},
        dispatchers = dispatchers,
    )

    /** O's browser sign-in, answering with [browserAnswer] after building the authorization URL it would open. */
    private val browserSignIn = mockk<BrowserSignIn>()
    private var browserAnswer: () -> BrowserSignIn.Result = { BrowserSignIn.Result(LoopbackCallback(CODE, mapOf("code" to CODE)), REDIRECT_URI, true) }
    private val authorizationUrls = mutableListOf<HttpUrl>()
    private val session = mockk<SyncSession>(relaxed = true)
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val once = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val periodic = MutableStateFlow<List<WorkInfo>>(emptyList())
    private val batteryExemption = RecordingBatteryExemption()
    private val snapshots = RecordingSnapshot()
    private val fingerprintStore = snapshots.store
    private val workManager = mockk<WorkManager> {
        every { getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NAME_ONCE) } returns once
        every { getWorkInfosForUniqueWorkFlow(SyncScheduler.WORK_NAME_PERIODIC) } returns periodic
    }

    @BeforeEach
    fun setUp() {
        KotlinxDispatchers.setMain(testDispatcher)
        coEvery { browserSignIn.signIn(0, any(), any(), any()) } coAnswers {
            val url = arg<(LoopbackListener) -> HttpUrl>(3)
            LoopbackListener.open(0, LoopbackPages("Saikou", "in", "out")).use { authorizationUrls += url(it) }
            browserAnswer()
        }
    }

    @AfterEach
    fun tearDown() {
        KotlinxDispatchers.resetMain()
    }

    private fun viewModel(account: WebDavAccount = this.account) = SyncSettingsViewModel(
        syncPreferences = syncPreferences,
        webDavAccount = account,
        session = session,
        scheduler = scheduler,
        workPreferences = workPreferences,
        record = record,
        workManager = workManager,
        dispatchers = dispatchers,
        batteryExemption = batteryExemption,
        driveAccount = driveAccount,
        driveAvailability = driveAvailability,
        browserSignIn = browserSignIn,
        snapshot = snapshots,
    )

    /** Fills the form and signs in, then waits for the attempt to end, the server's answer included. */
    private suspend fun TestScope.signIn(
        viewModel: SyncSettingsViewModel,
        url: String = URL,
        user: String = USER,
        password: String = PASSWORD,
    ): SyncSettingsViewModel.State {
        viewModel.choose(SyncSettingsViewModel.Service.WEBDAV)
        viewModel.setUrl(url)
        viewModel.setUser(user)
        viewModel.setPassword(password)
        viewModel.signIn()
        advanceUntilIdle()
        return viewModel.state.first { !it.signingIn }
    }

    @Test
    fun `sign-in needs an address, a user name without a colon and a non-empty password`() = runTest(testDispatcher) {
        SyncSettingsViewModel.canSignIn(URL, USER, PASSWORD) shouldBe true
        SyncSettingsViewModel.canSignIn(" ", USER, PASSWORD) shouldBe false
        SyncSettingsViewModel.canSignIn(URL, " ", PASSWORD) shouldBe false
        SyncSettingsViewModel.canSignIn(URL, "a:b", PASSWORD) shouldBe false
        SyncSettingsViewModel.canSignIn(URL, USER, "") shouldBe false
        // A password of spaces is a password; the store reads only an empty one as absent.
        SyncSettingsViewModel.canSignIn(URL, USER, " ") shouldBe true

        // The button's rule is the one the tap obeys: a form it refuses reaches no server.
        val viewModel = viewModel()
        val state = signIn(viewModel, user = "a:b")
        state.form.canSignIn shouldBe false
        state.signInProblem.shouldBeNull()
        requests.shouldBeEmpty()
        secrets.stored.keys.shouldBeEmpty()
    }

    @Test
    fun `a sign-in the server accepts configures the backend once and clears the form`() = runTest(testDispatcher) {
        val backend = slot<com.shinsaikou.sync.SyncBackend>()
        coEvery { session.signedIn(capture(backend)) } returns Unit

        val state = signIn(viewModel())

        coVerify(exactly = 1) { session.signedIn(any()) }
        backend.captured.id shouldStartWith "webdav-"
        state.signInProblem.shouldBeNull()
        state.form shouldBe SyncSettingsViewModel.Form()
        state.form.password shouldBe ""
        secrets.stored.keys shouldBe setOf(WebDavAccount.URL, WebDavAccount.USER, WebDavAccount.PASSWORD)
    }

    @Test
    fun `a refused user name or password configures nothing, stores nothing and drops the password`() = runTest(testDispatcher) {
        answer = { request -> response(request, 401) }

        val state = signIn(viewModel())

        state.signInProblem shouldBe SignInProblem.NotAuthenticated("HTTP 401")
        assertNothingKept(state)
    }

    @Test
    fun `a server that is not reached configures nothing, stores nothing and drops the password`() = runTest(testDispatcher) {
        // The WebDAV probe throws when nothing answers; a backend may instead report it unreachable.
        val unreachable = spyk(account)
        coEvery { unreachable.signIn(any(), any(), any()) } returns
            BackendStatus(reachable = false, authenticated = false, message = "no route")

        val state = signIn(viewModel(unreachable))

        state.signInProblem shouldBe SignInProblem.Unreachable("no route")
        assertNothingKept(state)
    }

    @Test
    fun `a sign-in that throws configures nothing, stores nothing and drops the password`() = runTest(testDispatcher) {
        answer = { throw ConnectException("Connection refused") }

        val state = signIn(viewModel())

        // A refused connection is the server not answering, never "no network" (fix round 1 item 6).
        val failed = state.signInProblem.shouldBeInstanceOf<SignInProblem.Failed>()
        failed.error.shouldBeInstanceOf<WebDavUnreachableException>().cause.shouldBeInstanceOf<ConnectException>()
        ErrorKey.of(failed.error) shouldBe ErrorKey.WEBDAV_UNREACHABLE
        assertNothingKept(state)
    }

    @Test
    fun `an address this build refuses fails before any request, with its own key`() = runTest(testDispatcher) {
        val state = signIn(viewModel(), url = "http://example.invalid/dav/")

        val failed = state.signInProblem.shouldBeInstanceOf<SignInProblem.Failed>()
        failed.error.shouldBeInstanceOf<WebDavUrlRefusedException>()
        ErrorKey.of(failed.error) shouldBe ErrorKey.WEBDAV_URL_REFUSED
        requests.shouldBeEmpty()
        assertNothingKept(state, url = "http://example.invalid/dav/")
    }

    @Test
    fun `secrets stored by a sign-in the session then fails are deleted`() = runTest(testDispatcher) {
        coEvery { session.signedIn(any()) } throws IllegalStateException("could not configure")

        val state = signIn(viewModel())

        state.signInProblem.shouldBeInstanceOf<SignInProblem.Failed>()
        // The session was asked, and threw: the secrets the account had stored are gone again.
        assertNothingKept(state, signedInCalls = 1)
    }

    /** Stores the fixture's sign-in as a past sign-in would have, configured, and returns its backend id. */
    private suspend fun storedSignIn(password: String = PASSWORD): String {
        secrets.stored[WebDavAccount.URL] = URL
        secrets.stored[WebDavAccount.USER] = USER
        secrets.stored[WebDavAccount.PASSWORD] = password
        val id = checkNotNull(account.backend()).id
        syncPreferences.backendId.set(id)
        return id
    }

    @Test
    fun `sign in again shows for a lost sign-in or a signed-out run, and for nothing else`() = runTest(testDispatcher) {
        suspend fun signInAgain(): SyncSettingsViewModel.SignInAgain? {
            val viewModel = viewModel()
            advanceUntilIdle()
            return viewModel.state.value.signInAgain
        }

        signInAgain().shouldBeNull()

        // Signed out as the last outcome, with no backend configured: the plain form, not this state.
        record.failed(SyncSignedOutException("fixture: 401"))
        signInAgain().shouldBeNull()

        // Configured, and the secrets are gone: the whole form, whatever the last outcome.
        syncPreferences.backendId.set("webdav-0123456789abcdef")
        signInAgain() shouldBe SyncSettingsViewModel.SignInAgain.SIGN_IN_LOST
        record.lastOutcome.set(SyncRunRecord.OUTCOME_SUCCESS)
        signInAgain() shouldBe SyncSettingsViewModel.SignInAgain.SIGN_IN_LOST

        // Configured with the sign-in stored: only a signed-out run asks for the password again.
        storedSignIn()
        signInAgain().shouldBeNull()
        record.failed(WebDavUnreachableException("10.0.2.2", ConnectException("fixture")))
        signInAgain().shouldBeNull()
        record.failed(SyncSignedOutException("fixture: 401"))
        signInAgain() shouldBe SyncSettingsViewModel.SignInAgain.PASSWORD_REFUSED
    }

    @Test
    fun `signing in again with the password sends the saved server and user and keeps the backend`() = runTest(testDispatcher) {
        val id = storedSignIn(password = "the-old-password")
        record.failed(SyncSignedOutException("fixture: 401"))
        val backend = slot<com.shinsaikou.sync.SyncBackend>()
        coEvery { session.signedIn(capture(backend)) } answers { record.clearSignedOut() }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.signInAgain shouldBe SyncSettingsViewModel.SignInAgain.PASSWORD_REFUSED
        viewModel.state.value.signInForm.canSignIn shouldBe false

        viewModel.setPassword("the-new-password")
        viewModel.state.value.signInForm.canSignIn shouldBe true
        viewModel.signIn()
        advanceUntilIdle()
        val state = viewModel.state.first { !it.signingIn }

        requests.single().url.toString() shouldBe URL
        backend.captured.id shouldBe id
        syncPreferences.backendId.get() shouldBe id
        secrets.stored shouldBe mapOf(
            WebDavAccount.URL to URL,
            WebDavAccount.USER to USER,
            WebDavAccount.PASSWORD to "the-new-password",
        )
        state.signInProblem.shouldBeNull()
        state.form shouldBe SyncSettingsViewModel.Form()
        // The session cleared the signed-out outcome. This test store sends no changes, so a view
        // model reading the record afresh shows the section signed in again.
        record.outcome().shouldBeNull()
        viewModel().also { advanceUntilIdle() }.state.value.signInAgain.shouldBeNull()
    }

    @Test
    fun `a failed attempt to sign in again keeps the stored secrets`() = runTest(testDispatcher) {
        val id = storedSignIn(password = "the-old-password")
        record.failed(SyncSignedOutException("fixture: 401"))
        val kept = secrets.stored.toMap()
        val viewModel = viewModel()
        advanceUntilIdle()

        // Refused, not answered, and a session that fails after the probe accepted.
        for (attempt in 0 until 3) {
            when (attempt) {
                0 -> answer = { request -> response(request, 401) }
                1 -> answer = { throw ConnectException("Connection refused") }
                2 -> {
                    answer = { request -> multistatus(request) }
                    coEvery { session.signedIn(any()) } throws IllegalStateException("could not configure")
                }
            }
            viewModel.setPassword("the-new-password")
            viewModel.signIn()
            advanceUntilIdle()

            val state = viewModel.state.first { !it.signingIn }
            state.signInProblem.shouldNotBeNull()
            state.form.password shouldBe ""
            syncPreferences.backendId.get() shouldBe id
            secrets.stored.keys shouldBe kept.keys
            if (attempt < 2) secrets.stored shouldBe kept
            state.signInAgain shouldBe SyncSettingsViewModel.SignInAgain.PASSWORD_REFUSED
        }
    }

    @Test
    fun `with the sign-in lost, the whole form signs in and the account is read again`() = runTest(testDispatcher) {
        syncPreferences.backendId.set("webdav-0123456789abcdef")
        val backend = slot<com.shinsaikou.sync.SyncBackend>()
        coEvery { session.signedIn(capture(backend)) } answers { syncPreferences.backendId.set(backend.captured.id) }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.signInAgain shouldBe SyncSettingsViewModel.SignInAgain.SIGN_IN_LOST

        val state = signIn(viewModel)

        coVerify(exactly = 1) { session.signedIn(any()) }
        state.signInProblem.shouldBeNull()
        state.account shouldBe WebDavSignIn(URL, USER)
        state.signInAgain.shouldBeNull()
        syncPreferences.backendId.get() shouldBe backend.captured.id
    }

    @Test
    fun `while the stored sign-in works, a sign-in is not offered and reaches no server`() = runTest(testDispatcher) {
        storedSignIn()
        record.failed(WebDavUnreachableException("10.0.2.2", ConnectException("fixture")))
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setUrl("http://10.0.2.2:18795/other/")
        viewModel.setUser(USER)
        viewModel.setPassword(PASSWORD)
        viewModel.signIn()
        advanceUntilIdle()
        // A sign-in that did start answers on OkHttp's own threads; wait for any attempt to end.
        viewModel.state.first { !it.signingIn }

        requests.shouldBeEmpty()
        coVerify(exactly = 0) { session.signedIn(any()) }
    }

    @Test
    fun `the battery row shows only while Saikou is not exempt, is read again on resume, and opens the system request`() =
        runTest(testDispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            // Unread, the row stays hidden, so an exempt device never sees it flash.
            viewModel.state.value.batteryExempt shouldBe true

            batteryExemption.exempt = false
            viewModel.refreshBatteryExemption()
            advanceUntilIdle()
            viewModel.state.value.batteryExempt shouldBe false

            val activity = mockk<Activity>()
            viewModel.requestBatteryExemption(activity)
            batteryExemption.requests shouldContainExactly listOf(activity)

            // The owner allowed it in the system screen; coming back reads it again and the row goes.
            batteryExemption.exempt = true
            viewModel.refreshBatteryExemption()
            advanceUntilIdle()
            viewModel.state.value.batteryExempt shouldBe true
        }

    @Test
    fun `the form never prints its password`() {
        val form = SyncSettingsViewModel.Form(URL, USER, PASSWORD)

        form.toString() shouldNotContain PASSWORD
    }

    @Test
    fun `sign-out goes through the session`() = runTest(testDispatcher) {
        syncPreferences.backendId.set("webdav-0123456789abcdef")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.signOut()
        advanceUntilIdle()

        coVerify(exactly = 1) { session.signOut() }
        viewModel.state.value.signingOut shouldBe false
    }

    @Test
    fun `sync now asks the scheduler`() = runTest(testDispatcher) {
        val viewModel = viewModel()

        viewModel.syncNow()
        advanceUntilIdle()

        coVerify(exactly = 1) { scheduler.syncNow() }
    }

    @Test
    fun `the interval is written before the schedule is re-armed from it`() = runTest(testDispatcher) {
        every { scheduler.setup() } answers { workPreferences.intervalHours.get() shouldBe 12 }
        val viewModel = viewModel()

        viewModel.setInterval(12)
        advanceUntilIdle()

        workPreferences.intervalHours.get() shouldBe 12
        verify(exactly = 1) { scheduler.setup() }
    }

    @Test
    fun `Wi-Fi only is read into the state, and written before the schedule is re-armed from it`() = runTest(testDispatcher) {
        workPreferences.wifiOnly.set(true)
        every { scheduler.setup() } answers { workPreferences.wifiOnly.get() shouldBe false }
        coEvery { scheduler.rearm() } answers { workPreferences.wifiOnly.get() shouldBe false }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.wifiOnly shouldBe true

        viewModel.setWifiOnly(false)
        advanceUntilIdle()

        workPreferences.wifiOnly.get() shouldBe false
        verify(exactly = 1) { scheduler.setup() }
        coVerify(exactly = 1) { scheduler.rearm() }
    }

    @Test
    fun `a newer format stopping the last run is what the banner reads, and no other failure is`() = runTest(testDispatcher) {
        record.failed(NewerFormatException(found = 99))
        val newer = viewModel()
        advanceUntilIdle()
        newer.state.value.newerFormat shouldBe true

        record.failed(ConnectException("Connection refused"))
        val other = viewModel()
        advanceUntilIdle()
        other.state.value.newerFormat shouldBe false
        other.state.value.outcome shouldBe com.shinsaikou.sync.SyncOutcome.Failure(ErrorKey.CONNECTION_REFUSED, null)
    }

    @Test
    fun `a run in progress under either name shows as running`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.running shouldBe false

        periodic.value = listOf(WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, setOf(SyncScheduler.TAG)))
        advanceUntilIdle()
        viewModel.state.value.running shouldBe true

        periodic.value = emptyList()
        once.value = listOf(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, setOf(SyncScheduler.TAG)))
        advanceUntilIdle()
        viewModel.state.value.running shouldBe false
    }

    /**
     * The digest of [FingerprintStore]'s library worked out here, from the wire lines its two
     * categories make, rather than through `SyncDigest`: each line's SHA-256 read as an unsigned
     * number, the numbers summed modulo 2^256, and the sum's 32 big-endian bytes hashed.
     */
    private fun expectedFingerprint(): Fingerprint.Computed {
        val lines = listOf(
            """{"entity":"category","key":"key-reading","name":"Reading","order":1,"flags":0,"parent":null,"modifiedAt":1750000000000,"deletedAt":null,"restoredAt":null}""",
            """{"entity":"category","key":"key-done","name":"Done","order":2,"flags":4,"parent":"key-reading","modifiedAt":null,"deletedAt":null,"restoredAt":1750000000500}""",
        )
        val modulus = BigInteger.ONE.shiftLeft(256)
        val sum = lines.fold(BigInteger.ZERO) { sum, line ->
            sum.add(BigInteger(1, MessageDigest.getInstance("SHA-256").digest(line.toByteArray()))).mod(modulus)
        }
        val bytes = sum.toByteArray().takeLast(32).toByteArray()
        val hex = MessageDigest.getInstance("SHA-256").digest(ByteArray(32 - bytes.size) + bytes)
            .joinToString("") { "%02x".format(it) }
        return Fingerprint.Computed(groups = hex.substring(0, 16).chunked(4).joinToString(" "), rows = lines.size)
    }

    private fun running() = listOf(WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, setOf(SyncScheduler.TAG)))

    @Test
    fun `the fingerprint waits for a tap, then shows the digest's first 16 hex characters and its rows, writing nothing`() =
        runTest(testDispatcher) {
            syncPreferences.backendId.set("webdav-0123456789abcdef")
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.fingerprint shouldBe Fingerprint.Idle
            fingerprintStore.reads shouldBe 0
            val stored = values.toMap()

            viewModel.computeFingerprint()
            viewModel.state.value.fingerprint shouldBe Fingerprint.Computing
            advanceUntilIdle()

            val computed = viewModel.state.value.fingerprint.shouldBeInstanceOf<Fingerprint.Computed>()
            computed shouldBe expectedFingerprint()
            computed.groups shouldMatch Regex("[0-9a-f]{4}( [0-9a-f]{4}){3}")
            fingerprintStore.reads shouldBe 1
            values shouldBe stored
            // Both of the digest's reads (the ids, then the page) in the one snapshot.
            snapshots.opened shouldBe 1
            fingerprintStore.readsOutsideSnapshot shouldBe 0

            // A later tap computes it again.
            viewModel.computeFingerprint()
            advanceUntilIdle()
            viewModel.state.value.fingerprint shouldBe expectedFingerprint()
            fingerprintStore.reads shouldBe 2
        }

    @Test
    fun `a second tap while the fingerprint computes does nothing`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>().also { fingerprintStore.gate = it }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.computeFingerprint()
        advanceUntilIdle()
        viewModel.state.value.fingerprint shouldBe Fingerprint.Computing
        viewModel.computeFingerprint()
        advanceUntilIdle()
        fingerprintStore.reads shouldBe 1

        gate.complete(Unit)
        advanceUntilIdle()
        viewModel.state.value.fingerprint shouldBe expectedFingerprint()
        fingerprintStore.reads shouldBe 1
    }

    @Test
    fun `a tap while a sync runs computes nothing and says so`() = runTest(testDispatcher) {
        val viewModel = viewModel()
        periodic.value = running()
        advanceUntilIdle()

        viewModel.computeFingerprint()
        advanceUntilIdle()
        viewModel.state.value.fingerprint shouldBe Fingerprint.SyncRunning
        fingerprintStore.reads shouldBe 0

        periodic.value = emptyList()
        advanceUntilIdle()
        viewModel.computeFingerprint()
        advanceUntilIdle()
        viewModel.state.value.fingerprint shouldBe expectedFingerprint()
    }

    @Test
    fun `a sync that starts while the fingerprint computes throws its result away`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>().also { fingerprintStore.gate = it }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.computeFingerprint()
        advanceUntilIdle()

        // The run starts and ends before the digest is in: only its start was ever seen.
        once.value = running()
        advanceUntilIdle()
        once.value = emptyList()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        viewModel.state.value.fingerprint shouldBe Fingerprint.SyncRanMeanwhile
        viewModel.state.value.running shouldBe false
        // Stopped rather than left to finish in the background.
        fingerprintStore.cancelled shouldBe true

        fingerprintStore.gate = null
        viewModel.computeFingerprint()
        advanceUntilIdle()
        viewModel.state.value.fingerprint shouldBe expectedFingerprint()
    }

    @Test
    fun `a run that took the session's lock while the fingerprint computed throws its result away`() = runTest(testDispatcher) {
        var runs = 2L
        every { session.runs } answers { runs }
        val gate = CompletableDeferred<Unit>().also { fingerprintStore.gate = it }
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.computeFingerprint()
        advanceUntilIdle()

        // WorkManager never reported it running; the session counted its start and its end.
        runs += 2
        gate.complete(Unit)
        advanceUntilIdle()

        viewModel.state.value.fingerprint shouldBe Fingerprint.SyncRanMeanwhile
    }

    /**
     * Verifier finding 2: a run already holding the session's lock when the tap comes, which
     * WorkManager has not reported and never reports while the digest would be read.
     */
    @Test
    fun `a tap while a run holds the session's lock, before WorkManager reports it, computes nothing and says so`() =
        runTest(testDispatcher) {
            var runs = 1L
            every { session.runs } answers { runs }
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.running shouldBe false

            viewModel.computeFingerprint()
            advanceUntilIdle()
            viewModel.state.value.fingerprint shouldBe Fingerprint.SyncRunning
            fingerprintStore.reads shouldBe 0
            snapshots.opened shouldBe 0

            // The run lets go of the lock: a tap now computes.
            runs = 2L
            viewModel.computeFingerprint()
            advanceUntilIdle()
            viewModel.state.value.fingerprint shouldBe expectedFingerprint()
        }

    /**
     * Verifier finding 1: the digest pages the library, and a restore or a library update writes
     * between two pages unless the whole read is one transaction. Since phase 5 unit SC6 that is a
     * read snapshot, which no writer waits for (`SyncStoreSnapshotImpl`, proven on the device).
     */
    @Test
    fun `the fingerprint reads the whole library in one snapshot`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>().also { fingerprintStore.gate = it }
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.computeFingerprint()
        advanceUntilIdle()
        // Held on its first read: the snapshot is open, and stays open until the digest is in.
        snapshots.open shouldBe true
        gate.complete(Unit)
        advanceUntilIdle()

        viewModel.state.value.fingerprint shouldBe expectedFingerprint()
        snapshots.opened shouldBe 1
        snapshots.open shouldBe false
        fingerprintStore.readsInSnapshot shouldBe 2
        fingerprintStore.readsOutsideSnapshot shouldBe 0
    }

    @Test
    fun `a library that cannot be read shows the failure`() = runTest(testDispatcher) {
        fingerprintStore.failure = IOException("fixture: disk")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.computeFingerprint()
        advanceUntilIdle()

        viewModel.state.value.fingerprint.shouldBeInstanceOf<Fingerprint.Failed>().error.shouldBeInstanceOf<IOException>()
    }

    /**
     * R131 carry-over 1: a library too large for the heap threw [OutOfMemoryError] in the digest, which
     * the catch of [Exception] let through, so the row stayed on Computing until the process died.
     */
    @Test
    fun `a library that runs out of memory shows the failure`() = runTest(testDispatcher) {
        fingerprintStore.failure = OutOfMemoryError("fixture: heap")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.computeFingerprint()
        advanceUntilIdle()

        val failed = viewModel.state.value.fingerprint.shouldBeInstanceOf<Fingerprint.Failed>()
        failed.error.shouldBeInstanceOf<OutOfMemoryError>()
        snapshots.open shouldBe false

        // A later tap computes again.
        fingerprintStore.failure = null
        viewModel.computeFingerprint()
        advanceUntilIdle()
        viewModel.state.value.fingerprint shouldBe expectedFingerprint()
    }

    @Test
    fun `leaving the screen cancels the fingerprint`() = runTest(testDispatcher) {
        fingerprintStore.gate = CompletableDeferred()
        val store = ViewModelStore()
        val built = viewModel()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = built as T
            },
        )[SyncSettingsViewModel::class.java]
        advanceUntilIdle()
        viewModel.computeFingerprint()
        advanceUntilIdle()
        fingerprintStore.reads shouldBe 1

        store.clear()
        advanceUntilIdle()

        fingerprintStore.cancelled shouldBe true
    }

    @Test
    fun `reports become the rows shown, newest first, with the conflicts not kept as more`() = runTest(testDispatcher) {
        record.succeeded(1_000L, result(conflicts = 3, exported = true))
        record.succeeded(2_000L, result(conflicts = SyncRunReport.MAX_CONFLICTS + 5, exported = false))
        val viewModel = viewModel()
        advanceUntilIdle()

        val rows = viewModel.state.value.reports
        rows.map { it.at } shouldContainExactly listOf(2_000L, 1_000L)

        val newest = rows[0]
        newest.conflictCount shouldBe SyncRunReport.MAX_CONFLICTS + 5
        newest.conflicts.size shouldBe SyncRunReport.MAX_CONFLICTS
        newest.more shouldBe 5
        newest.exported shouldBe false
        newest.routineMerges shouldBe 7
        newest.before shouldBe SyncRunReport.Counts(1, 2, 3, 4)
        newest.after shouldBe SyncRunReport.Counts(5, 6, 7, 8)

        val older = rows[1]
        older.more shouldBe 0
        older.exported shouldBe true
        older.conflicts.map { it.title } shouldContainExactly listOf("Title 0", "Title 1", "Title 2")
        older.conflicts[1] shouldBe SyncRunReport.Conflict(RowKind.CHAPTER, "Title 1", "Chapter 1", listOf("bookmark", "read"))
    }


    /** Chooses Google Drive, signs in through the stand-in browser, and waits for the attempt to end. */
    private suspend fun TestScope.signInWithGoogle(viewModel: SyncSettingsViewModel): SyncSettingsViewModel.State {
        advanceUntilIdle()
        if (!viewModel.state.value.configured) viewModel.choose(SyncSettingsViewModel.Service.GOOGLE_DRIVE)
        viewModel.signInWithGoogle(mockk<Intent>())
        advanceUntilIdle()
        return viewModel.state.first { !it.signingIn }
    }

    @Test
    fun `google drive is chosen and signed in to only while this build offers it`() = runTest(testDispatcher) {
        // Google's own endpoints and no client id, as the gate's build: no chip, and no sign-in.
        googleEndpoints = GoogleEndpoints.GOOGLE
        val unoffered = viewModel()
        advanceUntilIdle()
        unoffered.state.value.driveOffered shouldBe false
        unoffered.choose(SyncSettingsViewModel.Service.GOOGLE_DRIVE)
        unoffered.state.value.chosen shouldBe SyncSettingsViewModel.Service.NONE
        unoffered.state.value.canSignInWithGoogle shouldBe false
        unoffered.signInWithGoogle(mockk<Intent>())
        advanceUntilIdle()
        coVerify(exactly = 0) { browserSignIn.signIn(any(), any(), any(), any()) }

        // A test base in the debug build offers it; the button waits for the chip.
        googleEndpoints = GoogleEndpoints.forBuild(debug = true, testBase = GOOGLE_BASE)
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.state.value.driveOffered shouldBe true
        viewModel.state.value.canSignInWithGoogle shouldBe false
        viewModel.choose(SyncSettingsViewModel.Service.GOOGLE_DRIVE)
        viewModel.state.value.canSignInWithGoogle shouldBe true
        // Leaving WebDAV for Drive drops what the form held.
        viewModel.choose(SyncSettingsViewModel.Service.WEBDAV)
        viewModel.setPassword(PASSWORD)
        viewModel.choose(SyncSettingsViewModel.Service.GOOGLE_DRIVE)
        viewModel.state.value.form shouldBe SyncSettingsViewModel.Form()
        // WebDAV configured: no Google sign-in, and Drive's chip is not what it shows.
        storedSignIn()
        val webDav = viewModel()
        advanceUntilIdle()
        webDav.state.value.configuredService shouldBe SyncSettingsViewModel.Service.WEBDAV
        webDav.state.value.canSignInWithGoogle shouldBe false
    }

    @Test
    fun `a google sign-in configures the drive backend once and keeps no code, verifier or token in the state`() =
        runTest(testDispatcher) {
            val backend = slot<com.shinsaikou.sync.SyncBackend>()
            coEvery { session.signedIn(capture(backend)) } answers { syncPreferences.backendId.set(backend.captured.id) }

            val state = signInWithGoogle(viewModel())

            coVerify(exactly = 1) { session.signedIn(any()) }
            backend.captured.id shouldStartWith DriveSyncBackend.ID_PREFIX
            state.signInProblem.shouldBeNull()
            state.chosen shouldBe SyncSettingsViewModel.Service.NONE
            state.configuredService shouldBe SyncSettingsViewModel.Service.GOOGLE_DRIVE
            state.googleAccount shouldBe EMAIL
            state.googleExpiresAt.shouldBeNull()
            state.signInAgain.shouldBeNull()
            secrets.stored.keys shouldBe setOf(DriveAccount.REFRESH_TOKEN, DriveAccount.EMAIL, DriveAccount.ACCOUNT_ID)
            // The page the browser opened names the listener's redirect and a challenge, never the verifier.
            val url = authorizationUrls.single()
            url.queryParameter("redirect_uri") shouldStartWith "http://127.0.0.1:"
            url.queryParameter("code_challenge_method") shouldBe "S256"
            val verifier = google.tokenForms.single().getValue("code_verifier")
            google.tokenForms.single().getValue("code") shouldBe CODE
            for (secret in listOf(CODE, verifier, google.refreshToken, ACCESS_TOKEN)) {
                state.toString() shouldNotContain secret
            }
        }

    @Test
    fun `the date a google sign-in lapses is read with the account, and signing in again is offered before it`() =
        runTest(testDispatcher) {
            google.refreshTokenExpiresIn = 7 * 24 * 3600L
            coEvery { session.signedIn(any()) } answers { syncPreferences.backendId.set(firstArg<com.shinsaikou.sync.SyncBackend>().id) }
            val started = System.currentTimeMillis()

            val state = signInWithGoogle(viewModel())

            val expiresAt = state.googleExpiresAt.shouldNotBeNull()
            (expiresAt - started) shouldBeGreaterThanOrEqual 7 * 24 * 3600_000L - 60_000L
            state.signInAgain.shouldBeNull()
            state.canSignInWithGoogle shouldBe true
        }

    @Test
    fun `every failed google sign-in leaves no backend and no secret`() = runTest(testDispatcher) {
        data class Case(val name: String, val key: ErrorKey?, val arrange: () -> Unit)
        val cases = listOf(
            // Another sign-in started (a cancel): nothing shown.
            Case("cancelled", null) { browserAnswer = { throw SignInCancelledException() } },
            Case("timed out", ErrorKey.OAUTH_TIMED_OUT) { browserAnswer = { throw LoopbackTimeoutException(5.minutes) } },
            Case("denied", ErrorKey.OAUTH_DENIED) { browserAnswer = { throw LoopbackDeniedException("access_denied") } },
            Case("port in use", ErrorKey.OAUTH_PORT_IN_USE) {
                browserAnswer = { throw LoopbackPortInUseException(0, java.net.BindException("in use")) }
            },
            // Q1's fallback: Google refused this app's client.
            Case("client refused", ErrorKey.GOOGLE_AUTH_REFUSED) {
                google.tokenStatus = 401
                google.tokenBody = """{"error":"invalid_client"}"""
            },
            Case("code refused", ErrorKey.GOOGLE_INVALID_GRANT) {
                google.tokenStatus = 400
                google.tokenBody = """{"error":"invalid_grant"}"""
            },
            Case("token endpoint down", ErrorKey.GOOGLE_UNAVAILABLE) { google.tokenStatus = 503 },
            Case("drive down", ErrorKey.DRIVE_UNAVAILABLE) { google.aboutStatus = 503 },
            // Stored, then the session could not take the backend: the grant is revoked and deleted.
            Case("session failed", ErrorKey.GENERIC) {
                coEvery { session.signedIn(any()) } throws IllegalStateException("could not configure")
            },
        )
        for (case in cases) {
            google.reset()
            browserAnswer = { BrowserSignIn.Result(LoopbackCallback(CODE, mapOf("code" to CODE)), REDIRECT_URI, true) }
            coEvery { session.signedIn(any()) } returns Unit
            case.arrange()

            val state = signInWithGoogle(viewModel())

            withClue(case.name) {
                syncPreferences.backendId.get() shouldBe ""
                secrets.stored.keys.shouldBeEmpty()
                state.chosen shouldBe SyncSettingsViewModel.Service.GOOGLE_DRIVE
                state.googleAccount.shouldBeNull()
                if (case.key == null) {
                    state.signInProblem.shouldBeNull()
                } else {
                    val failed = state.signInProblem.shouldBeInstanceOf<SignInProblem.Failed>()
                    ErrorKey.of(failed.error) shouldBe case.key
                }
                // Only a grant that was stored is revoked, and it is the one Google issued.
                google.revoked shouldBe if (case.name == "session failed") listOf(google.refreshToken) else emptyList()
            }
        }
    }

    /** Signs in to Drive as a past sign-in would have, configured, and returns its backend id. */
    private suspend fun TestScope.signedInToDrive(): String {
        coEvery { session.signedIn(any()) } answers { syncPreferences.backendId.set(firstArg<com.shinsaikou.sync.SyncBackend>().id) }
        signInWithGoogle(viewModel())
        return syncPreferences.backendId.get().also { it shouldStartWith DriveSyncBackend.ID_PREFIX }
    }

    @Test
    fun `signing in with google again with the same account keeps the backend, and another account is a new one`() =
        runTest(testDispatcher) {
            val id = signedInToDrive()
            record.failed(SyncSignedOutException("fixture: invalid_grant"))
            val backends = mutableListOf<String>()
            coEvery { session.signedIn(any()) } answers {
                val backend = firstArg<com.shinsaikou.sync.SyncBackend>()
                backends += backend.id
                syncPreferences.backendId.set(backend.id)
                record.clearSignedOut()
            }
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.state.value.signInAgain shouldBe SyncSettingsViewModel.SignInAgain.PASSWORD_REFUSED
            viewModel.state.value.googleAccount shouldBe EMAIL
            viewModel.state.value.canSignInWithGoogle shouldBe true

            google.refreshToken = "refresh-2"
            val again = signInWithGoogle(viewModel)

            backends shouldBe listOf(id)
            again.signInProblem.shouldBeNull()
            secrets.stored[DriveAccount.REFRESH_TOKEN] shouldBe "refresh-2"
            viewModel().also { advanceUntilIdle() }.state.value.signInAgain.shouldBeNull()

            // Another Google account: another backend id, which the session takes as a new device.
            record.failed(SyncSignedOutException("fixture: invalid_grant"))
            google.email = "other@example.test"
            google.permissionId = "other-permission"
            val other = viewModel()
            val switched = signInWithGoogle(other)

            backends.size shouldBe 2
            backends[1] shouldStartWith DriveSyncBackend.ID_PREFIX
            (backends[1] == id) shouldBe false
            switched.googleAccount shouldBe "other@example.test"
        }

    @Test
    fun `a failed attempt to sign in with google again keeps the stored grant`() = runTest(testDispatcher) {
        val id = signedInToDrive()
        record.failed(SyncSignedOutException("fixture: invalid_grant"))
        val kept = secrets.stored.toMap()
        val viewModel = viewModel()
        advanceUntilIdle()

        for (arrange in listOf<() -> Unit>(
            { browserAnswer = { throw LoopbackTimeoutException(5.minutes) } },
            { google.tokenStatus = 503 },
        )) {
            google.reset()
            browserAnswer = { BrowserSignIn.Result(LoopbackCallback(CODE, mapOf("code" to CODE)), REDIRECT_URI, true) }
            arrange()
            val state = signInWithGoogle(viewModel)

            state.signInProblem.shouldBeInstanceOf<SignInProblem.Failed>()
            syncPreferences.backendId.get() shouldBe id
            secrets.stored shouldBe kept
            google.revoked.shouldBeEmpty()
            state.signInAgain shouldBe SyncSettingsViewModel.SignInAgain.PASSWORD_REFUSED
        }
    }

    @Test
    fun `a drive backend whose account cannot be read asks for the whole sign-in, and a webdav sign-in is not taken`() =
        runTest(testDispatcher) {
            syncPreferences.backendId.set(DriveSyncBackend.ID_PREFIX + "0123456789abcdef")
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.state.value.signInAgain shouldBe SyncSettingsViewModel.SignInAgain.SIGN_IN_LOST
            viewModel.state.value.canSignInWithGoogle shouldBe true
            // The WebDAV form is not what this state shows, and a sign-in through it reaches no server.
            viewModel.setUrl(URL)
            viewModel.setUser(USER)
            viewModel.setPassword(PASSWORD)
            viewModel.signIn()
            advanceUntilIdle()
            requests.shouldBeEmpty()
        }

    private fun assertNothingKept(state: SyncSettingsViewModel.State, url: String = URL, signedInCalls: Int = 0) {
        coVerify(exactly = signedInCalls) { session.signedIn(any()) }
        syncPreferences.backendId.get() shouldBe ""
        secrets.stored.keys.shouldBeEmpty()
        state.form.password shouldBe ""
        // What the owner typed apart from the password stays, to correct and try again.
        state.form.url shouldBe url
        state.form.user shouldBe USER
        state.chosen shouldBe SyncSettingsViewModel.Service.WEBDAV
    }

    private fun result(conflicts: Int, exported: Boolean) = SyncRunResult(
        segmentsApplied = 1,
        exportsApplied = 0,
        snapshotApplied = false,
        // The routine merges are what was written apart from the conflicts: seven here.
        rowsWritten = conflicts + 7,
        rowsSkipped = 0,
        stoppedAtGap = false,
        segmentsPushed = 1,
        exported = exported,
        compacted = false,
        filesDeleted = 0,
        conflicts = (0 until conflicts).map { index ->
            if (index % 2 == 1) {
                SyncConflict(RowKind.CHAPTER, "Title $index", "Chapter $index", setOf("read", "bookmark"))
            } else {
                SyncConflict(RowKind.MANGA, "Title $index", null, setOf("favorite"))
            }
        },
        before = SyncLibraryCounts(libraryEntries = 1, categories = 2, readChapters = 3, bookmarkedChapters = 4),
        after = SyncLibraryCounts(libraryEntries = 5, categories = 6, readChapters = 7, bookmarkedChapters = 8),
    )

    /** The fixture's answer to a PROPFIND at depth 0 on the base address: one collection. */
    private fun multistatus(request: Request): Response = response(
        request,
        207,
        """<?xml version="1.0" encoding="utf-8"?><D:multistatus xmlns:D="DAV:"><D:response>""" +
            "<D:href>${request.url.encodedPath}</D:href><D:propstat><D:prop><D:resourcetype><D:collection/>" +
            "</D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>",
    )

    private fun response(request: Request, code: Int, body: String = "") = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake")
        .body(body.toResponseBody())
        .build()


    /** Google's token, about and revoke endpoints as the sync fixture answers them, with what each was sent. */
    private inner class FakeGoogle {
        var email = EMAIL
        var permissionId = PERMISSION_ID
        var refreshToken = "refresh-1"
        var refreshTokenExpiresIn: Long? = null
        var tokenStatus = 200
        var tokenBody: String? = null
        var aboutStatus = 200
        val tokenForms = mutableListOf<Map<String, String>>()
        val revoked = mutableListOf<String>()

        fun reset() {
            tokenStatus = 200
            tokenBody = null
            aboutStatus = 200
            tokenForms.clear()
            revoked.clear()
        }

        fun answer(request: Request): Response = when (request.url.encodedPath) {
            "/token" -> {
                tokenForms += form(request)
                val expires = refreshTokenExpiresIn?.let { ""","refresh_token_expires_in":$it""" }.orEmpty()
                response(
                    request,
                    tokenStatus,
                    tokenBody ?: """{"access_token":"$ACCESS_TOKEN","expires_in":3599,"refresh_token":"$refreshToken",""" +
                        """"token_type":"Bearer"$expires}""",
                )
            }
            "/drive/v3/about" -> response(
                request,
                aboutStatus,
                """{"user":{"emailAddress":"$email","permissionId":"$permissionId"}}""",
            )
            "/revoke" -> {
                revoked += form(request).getValue("token")
                response(request, 200)
            }
            else -> response(request, 404)
        }

        private fun form(request: Request): Map<String, String> {
            val body = request.body as FormBody
            return (0 until body.size).associate { body.name(it) to body.value(it) }
        }
    }

    private companion object {
        const val URL = "http://10.0.2.2:18795/dav/"
        const val USER = "fixture-user"
        const val PASSWORD = "fixture-password-1"
        const val GOOGLE_BASE = "http://10.0.2.2:18797/"
        const val REDIRECT_URI = "http://127.0.0.1:43210/callback"
        const val CODE = "fixture-code-1"
        const val ACCESS_TOKEN = "fixture-access-1"
        const val EMAIL = "reader@example.test"
        const val PERMISSION_ID = "fixture-permission"
    }
}

/** Runs each task at once on the thread that submits it, for [okhttp3.Dispatcher]. */
private class CallingThreadExecutor : AbstractExecutorService() {
    @Volatile private var shutdown = false

    override fun execute(command: Runnable) = command.run()

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): List<Runnable> = emptyList<Runnable>().also { shutdown = true }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

/**
 * A library of two categories and no entries, the digest's whole read, for the fingerprint's tests.
 * While [gate] is set, the digest's first read waits on it, so a test can act mid computation;
 * [cancelled] says whether that wait was cancelled. Nothing else of the store is read.
 */
private class FingerprintStore(private val snapshots: RecordingSnapshot) : SyncStore {
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    var reads = 0
    var cancelled = false

    /** The store's reads made inside and outside [snapshots]' open snapshot. */
    var readsInSnapshot = 0
    var readsOutsideSnapshot = 0

    private fun counted() {
        if (snapshots.open) readsInSnapshot++ else readsOutsideSnapshot++
    }

    private val categories = listOf(
        StoredCategory(
            id = 1L, syncKey = "key-reading", name = "Reading", order = 1L, flags = 0L, parentKey = null,
            modifiedAt = 1_750_000_000_000L, deletedAt = null, restoredAt = null,
        ),
        StoredCategory(
            id = 2L, syncKey = "key-done", name = "Done", order = 2L, flags = 4L, parentKey = "key-reading",
            modifiedAt = null, deletedAt = null, restoredAt = 1_750_000_000_500L,
        ),
    ).associateBy { it.id }

    override suspend fun libraryMangaIds(afterId: Long, limit: Long): List<Long> {
        reads++
        counted()
        failure?.let { throw it }
        try {
            gate?.await()
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        }
        return emptyList()
    }

    override suspend fun readLibrary(mangaIds: Collection<Long>): SyncPushRows {
        counted()
        return SyncPushRows(
            mangas = emptyMap(), categoryKeys = emptyMap(), excludedScanlators = emptyMap(), scanlatorPriority = emptyMap(),
            tracks = emptyMap(), chapters = emptyMap(), notes = emptyMap(), history = emptyMap(), categories = categories,
        )
    }

    override suspend fun readForPush(request: SyncPushRequest): SyncPushRows = error("the fingerprint only reads the library")

    override suspend fun chapterMangaIds(chapterIds: Collection<Long>): Map<Long, Long> =
        error("the fingerprint only reads the library")

    override suspend fun beginExport(pushedSeq: Long): Long = error("the fingerprint only reads the library")

    override suspend fun libraryCounts(): SyncLibraryCounts = error("the fingerprint only reads the library")

    override suspend fun <T> apply(block: suspend SyncApplyScope.() -> T): T = error("the fingerprint only reads the library")
}

/** A read snapshot over [store] that counts the snapshots opened and knows whether one is open now. */
private class RecordingSnapshot : SyncStoreSnapshot {
    var opened = 0
    var open = false
    val store = FingerprintStore(this)

    override suspend fun <T> read(block: suspend (SyncStore) -> T): T {
        opened++
        open = true
        try {
            return block(store)
        } finally {
            open = false
        }
    }
}

/** Records what the view model asked the platform for; `SettingsViewModelTest` has its own in this package. */
private class RecordingBatteryExemption(var exempt: Boolean = false) : BatteryExemption {

    val requests = mutableListOf<Activity>()

    override fun isExempt(): Boolean = exempt

    override fun request(activity: Activity) {
        requests += activity
    }
}
