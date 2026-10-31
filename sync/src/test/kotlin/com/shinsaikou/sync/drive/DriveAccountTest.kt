package com.shinsaikou.sync.drive

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SoftwareSecretCipher
import com.shinsaikou.sync.SyncSignedOutException
import com.shinsaikou.sync.format.SyncLayout
import com.shinsaikou.sync.google.GoogleAuthRefusedException
import com.shinsaikou.sync.google.GoogleClientCredentials
import com.shinsaikou.sync.google.GoogleEndpoints
import com.shinsaikou.sync.google.GoogleInvalidGrantException
import com.shinsaikou.sync.google.GoogleOAuthClient
import com.shinsaikou.sync.google.GoogleUnavailableException
import com.shinsaikou.sync.mapSecretStore
import com.shinsaikou.sync.webdav.SyncFixtureProcess
import com.shinsaikou.sync.webdav.TEST_DISPATCHERS
import eu.kanade.tachiyomi.util.lang.Hash
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Drive sign-in against unit D's fixture (plan unit H, items 3, 4 and 8): a code from the
 * fixture's authorization endpoint with no browser, the four secrets under their permanent names
 * only after the exchange and `about.get` succeed, the access token in memory and refreshed on the
 * injected clock, one refresh for concurrent calls, the fixture's `early401`, `revoked`,
 * `refreshFailures` and `refreshTokenExpiresIn`, a client the token endpoint refuses, the backend id
 * rule, and a sign-out that revokes. The client secret on every refresh and the revoke are read from
 * the fixture's own log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriveAccountTest {

    private lateinit var dir: File
    private lateinit var process: SyncFixtureProcess
    private lateinit var fixture: DriveFixture

    private val values = mutableMapOf<String, Any>()
    private val secrets = mapSecretStore(values, SoftwareSecretCipher())

    @BeforeAll
    fun start(@TempDir dir: File) {
        this.dir = dir
        process = SyncFixtureProcess(dir)
        fixture = DriveFixture(process, dir)
    }

    @AfterAll
    fun stop() {
        process.close()
    }

    @BeforeEach
    fun reset() {
        fixture.reset()
        values.clear()
        synchronized(fixture.waits) { fixture.waits.clear() }
    }

    private fun stored(): Map<String, String?> = NAMES.associateWith { secrets.get(it) }

    private fun refreshes() = fixture.log("oauth.token").filter { it.string("grant") == "refresh_token" }

    @Test
    fun `a sign-in stores the four secrets under their names, and the backend id is the client and the permissionId`() {
        runBlocking {
            val account = fixture.account(secrets)
            fixture.signIn(account) shouldBe "owner@fixture.test"

            val names = stored()
            names.getValue(DriveAccount.REFRESH_TOKEN).shouldNotBeNull() shouldMatch Regex("1//0.+")
            names.getValue(DriveAccount.EMAIL) shouldBe "owner@fixture.test"
            names.getValue(DriveAccount.ACCOUNT_ID) shouldBe "fixture-permission-1"
            // The fixture's grants do not lapse unless told to, so no expiry is stored.
            names.getValue(DriveAccount.REFRESH_TOKEN_EXPIRES_AT).shouldBeNull()
            account.signedInAs() shouldBe "owner@fixture.test"
            account.refreshTokenExpiresAt().shouldBeNull()

            val expected = "drive-" + Hash.sha256("${GoogleClientCredentials.TEST_BASE.clientId}\nfixture-permission-1").take(16)
            account.backend().shouldNotBeNull().id shouldBe expected
            DriveSyncBackend.idFor(GoogleClientCredentials.TEST_BASE.clientId, "fixture-permission-1") shouldBe expected
            // Another client or another account is another store.
            DriveSyncBackend.idFor("another-client", "fixture-permission-1") shouldNotBe expected
            DriveSyncBackend.idFor(GoogleClientCredentials.TEST_BASE.clientId, "another-permission") shouldNotBe expected
            expected shouldMatch Regex("drive-[0-9a-f]{16}")

            // The access token from the exchange signs the first calls: no refresh yet.
            account.backend()!!.list(SyncLayout.ROOT_PREFIX) shouldBe emptyList()
            refreshes().size shouldBe 0
        }
    }

    @Test
    fun `a backend is built only while the three required secrets read and this build offers Drive`() {
        runBlocking {
            val account = fixture.account(secrets)
            account.backend().shouldBeNull()
            fixture.signIn(account)
            for (name in listOf(DriveAccount.REFRESH_TOKEN, DriveAccount.EMAIL, DriveAccount.ACCOUNT_ID)) {
                val kept = secrets.get(name)!!
                secrets.delete(name)
                account.backend().shouldBeNull()
                secrets.put(name, kept)
            }
            account.backend().shouldNotBeNull()

            // Google's endpoints with no client id: this build does not offer Drive.
            val hidden = DriveAccount(
                secrets, { fixture.oauth() }, fixture.http,
                DriveAvailability({ GoogleEndpoints.GOOGLE }, GoogleClientCredentials("", "")),
                Clock.System, fixture.backoff(), TEST_DISPATCHERS,
            )
            hidden.backend().shouldBeNull()
            hidden.signedInAs() shouldBe "owner@fixture.test"
        }
    }

    @Test
    fun `a grant that lapses stores its expiry, and a later refresh that carries it stores it again`() {
        runBlocking {
            fixture.modes("""{"refreshTokenExpiresIn": 3600}""")
            val account = fixture.account(secrets)
            fixture.signIn(account)
            val expiresAt = account.refreshTokenExpiresAt().shouldNotBeNull()
            ((expiresAt - Clock.System.now().toEpochMilliseconds()) in 3_590_000L..3_600_000L) shouldBe true

            secrets.delete(DriveAccount.REFRESH_TOKEN_EXPIRES_AT)
            // A new process: no access token in memory, so the first call refreshes.
            fixture.account(secrets).backend()!!.list(SyncLayout.ROOT_PREFIX)
            fixture.account(secrets).refreshTokenExpiresAt().shouldNotBeNull()

            // A sign-in whose grant does not lapse removes an expiry an earlier one stored.
            fixture.modes("""{"refreshTokenExpiresIn": null}""")
            fixture.signIn(account)
            account.refreshTokenExpiresAt().shouldBeNull()
        }
    }

    @Test
    fun `the access token is refreshed 60 s before its expiry on the injected clock`() {
        runBlocking {
            val clock = TestClock(Instant.fromEpochMilliseconds(1_000_000_000_000))
            val account = fixture.account(secrets, clock)
            fixture.signIn(account)
            val backend = account.backend()!!
            // The fixture's tokens last 3599 s; the margin is 60 s.
            clock.now += 3538.seconds
            backend.list(SyncLayout.ROOT_PREFIX)
            refreshes().size shouldBe 0
            clock.now += 2.seconds
            backend.list(SyncLayout.ROOT_PREFIX)
            fixture.log("oauth.token") { lines -> lines.count { it.string("grant") == "refresh_token" } >= 1 }
            refreshes().size shouldBe 1
            // The new token's expiry is from the same clock.
            backend.list(SyncLayout.ROOT_PREFIX)
            refreshes().size shouldBe 1
        }
    }

    @Test
    fun `concurrent calls without a token share one refresh`() {
        runBlocking {
            fixture.signIn(fixture.account(secrets))
            // A new process over the stored sign-in.
            val backend = fixture.account(secrets).backend()!!
            coroutineScope {
                (1..6).map { async(TEST_DISPATCHERS.io) { backend.list(SyncLayout.ROOT_PREFIX) } }.awaitAll()
            }
            // Every call has returned, so every token request was answered; the fixture writes a line
            // after its answer, so wait until the six listings' lines are there too. A token request
            // was answered before the listing it signed was sent, so its line is written by then in
            // practice; the fixture does not order lines across threads, so that is the one residue.
            fixture.log("drive.files.list", "oauth.token") { lines ->
                lines.count { it.string("kind") == "drive.files.list" } >= 6 && lines.any { it.string("grant") == "refresh_token" }
            }
            refreshes().size shouldBe 1
        }
    }

    @Test
    fun `a 401 before the expiry refreshes once and repeats the call, and a second 401 is signed out`() {
        runBlocking {
            val account = fixture.account(secrets)
            fixture.signIn(account)
            val backend = account.backend()!!
            fixture.modes("""{"early401": 1}""")
            backend.list(SyncLayout.ROOT_PREFIX) shouldBe emptyList()
            val lines = fixture.log("drive.files.list", "oauth.token") { it.size >= 4 }.drop(1)
            lines.map { listOf(it.string("kind"), it.string("injected")) } shouldBe listOf(
                listOf("drive.files.list", "early401"),
                listOf("oauth.token", null),
                listOf("drive.files.list", null),
            )

            fixture.modes("""{"early401": 2}""")
            shouldThrow<SyncSignedOutException> { backend.list(SyncLayout.ROOT_PREFIX) }
            // Signed out keeps the sign-in: the owner signs in again or out.
            stored().values.filterNotNull().size shouldBe 3
        }
    }

    @Test
    fun `a revoked grant is signed out with Google's invalid_grant as the cause, and the secrets stay`() {
        runBlocking {
            val account = fixture.account(secrets)
            fixture.signIn(account)
            fixture.modes("""{"revoked": true}""")
            val failure = shouldThrow<SyncSignedOutException> { account.backend()!!.list(SyncLayout.ROOT_PREFIX) }
            failure.cause.shouldBeInstanceOf<GoogleInvalidGrantException>()
            ErrorKey.of(failure) shouldBe ErrorKey.SYNC_SIGNED_OUT
            stored().values.filterNotNull().size shouldBe 3
            account.signedInAs() shouldBe "owner@fixture.test"
            // Not a failure to wait out: one refresh, no waits.
            fixture.waits shouldBe emptyList()
        }
    }

    @Test
    fun `a refresh through a dropped connection and a 503 completes inside one call`() {
        runBlocking {
            fixture.signIn(fixture.account(secrets))
            fixture.modes("""{"refreshFailures": ["drop", "503"]}""")
            fixture.account(secrets).backend()!!.list(SyncLayout.ROOT_PREFIX) shouldBe emptyList()

            // OkHttp resends a request whose connection dropped before the answer, so the drop and
            // the 503 are one failed attempt to the client, and the next succeeds after one wait.
            val lines = fixture.log("oauth.token") { lines -> lines.count { it.string("grant") == "refresh_token" } >= 3 }
                .filter { it.string("grant") == "refresh_token" }
            lines.map { it.string("injected") } shouldBe listOf("drop", "503", null)
            fixture.waits shouldBe listOf(1.seconds)

            fixture.modes("""{"refreshFailures": ["503", "503", "503", "503"]}""")
            synchronized(fixture.waits) { fixture.waits.clear() }
            shouldThrow<GoogleUnavailableException> { fixture.account(secrets).backend()!!.list(SyncLayout.ROOT_PREFIX) }
                .status shouldBe 503
            fixture.waits shouldBe listOf(1.seconds, 2.seconds, 4.seconds)
        }
    }

    @Test
    fun `a token endpoint that refuses the client is Google's refusal, at sign-in and at a refresh, and never retried`(
        @TempDir other: File,
    ) {
        val wrong = SyncFixtureProcess(other, listOf("--client-secret", "another-fixture-secret"))
        try {
            runBlocking {
                val otherFixture = DriveFixture(wrong, other)
                val account = otherFixture.account(secrets)
                shouldThrow<GoogleAuthRefusedException> { otherFixture.signIn(account) }.error shouldBe "invalid_client"
                stored().values.filterNotNull().size shouldBe 0

                // A stored grant whose refresh the token endpoint refuses the same way.
                secrets.put(DriveAccount.REFRESH_TOKEN, "1//0a-grant-another-client-issued")
                secrets.put(DriveAccount.EMAIL, "owner@fixture.test")
                secrets.put(DriveAccount.ACCOUNT_ID, "fixture-permission-1")
                shouldThrow<GoogleAuthRefusedException> { account.backend()!!.list(SyncLayout.ROOT_PREFIX) }.error shouldBe "invalid_client"
                otherFixture.waits shouldBe emptyList()
                otherFixture.log("oauth.token") { it.size >= 2 }.map { it.string("client_secret") } shouldBe listOf("wrong", "wrong")
            }
        } finally {
            wrong.close()
        }
    }

    @Test
    fun `a sign-in whose about get fails stores nothing, and a store that fails part way leaves nothing`() {
        runBlocking {
            val account = fixture.account(secrets)
            // The exchange succeeds, then every Drive call and refresh is refused.
            fixture.modes("""{"revoked": true}""")
            shouldThrow<SyncSignedOutException> { fixture.signIn(account) }
            stored().values.filterNotNull().size shouldBe 0
            account.backend().shouldBeNull()

            fixture.modes("""{"revoked": false}""")
            val failing = FailingOn(secrets, DriveAccount.EMAIL)
            val failingAccount = fixture.account(failing)
            shouldThrow<IOException> { fixture.signIn(failingAccount) }.message shouldBe "fixture: the store refused ${DriveAccount.EMAIL}"
            stored().values.filterNotNull().size shouldBe 0
        }
    }

    @Test
    fun `a sign-in cancelled after the refresh token is stored and before the email is leaves nothing, and the caller sees the cancel`() {
        runBlocking {
            lateinit var signIn: Job
            val cancelling = object : SecretStore by secrets {
                override fun put(name: String, value: String) {
                    secrets.put(name, value)
                    if (name == DriveAccount.REFRESH_TOKEN) signIn.cancel()
                }
            }
            val account = fixture.account(cancelling)
            var seen: Throwable? = null
            signIn = launch {
                try {
                    fixture.signIn(account)
                } catch (e: CancellationException) {
                    seen = e
                    throw e
                }
            }
            signIn.join()

            signIn.isCancelled shouldBe true
            seen.shouldBeInstanceOf<CancellationException>()
            stored().values.filterNotNull() shouldBe emptyList()
            account.signedInAs().shouldBeNull()
        }
    }

    @Test
    fun `a code used twice is Google's invalid_grant at sign-in, never signed out`() {
        runBlocking {
            val account = fixture.account(secrets)
            val pkce = Pkce.generate()
            val code = fixture.code(pkce)
            account.signIn(code, pkce, DriveFixture.REDIRECT_URI)
            shouldThrow<GoogleInvalidGrantException> { account.signIn(code, pkce, DriveFixture.REDIRECT_URI) }
            account.signedInAs() shouldBe "owner@fixture.test"
        }
    }

    @Test
    fun `every refresh sends the client secret, and a sign-out revokes the grant and deletes the four secrets`() {
        runBlocking {
            fixture.modes("""{"refreshTokenExpiresIn": 3600}""")
            val account = fixture.account(secrets)
            fixture.signIn(account)
            fixture.account(secrets).backend()!!.list(SyncLayout.ROOT_PREFIX)
            fixture.modes("""{"early401": 1}""")
            account.backend()!!.list(SyncLayout.ROOT_PREFIX)
            val refreshToken = secrets.get(DriveAccount.REFRESH_TOKEN)!!

            account.signOut()
            stored().values.filterNotNull().size shouldBe 0
            account.signedInAs().shouldBeNull()
            account.backend().shouldBeNull()
            // The grant is gone at the fixture: a refresh with the old token is refused.
            shouldThrow<GoogleInvalidGrantException> { fixture.oauth().refresh(refreshToken) }

            // Signed out already: nothing to revoke, and nothing is sent.
            account.signOut()

            val lines = fixture.log("oauth.token", "oauth.revoke") { lines -> lines.count { it.string("kind") == "oauth.token" } >= 4 }
            lines.filter { it.string("kind") == "oauth.revoke" }.map { it["status"].toString() } shouldBe listOf("200")
            val tokens = lines.filter { it.string("kind") == "oauth.token" }
            tokens.map { it.string("grant") } shouldBe listOf("authorization_code", "refresh_token", "refresh_token", "refresh_token")
            tokens.map { it.string("client_secret") }.toSet() shouldBe setOf("present")
        }
    }

    @Test
    fun `a revoke that fails does not stop the sign-out`() {
        runBlocking {
            fixture.signIn(fixture.account(secrets))
            // Google cannot be reached: nothing listens on this port.
            val unreachable = GoogleOAuthClient(
                fixture.http,
                GoogleEndpoints.at("http://127.0.0.1:9/".toHttpUrl()),
                GoogleClientCredentials("", ""),
                Clock.System,
                TEST_DISPATCHERS,
            )
            val account = DriveAccount(
                secrets, { unreachable }, fixture.http, DriveAvailability({ unreachable.endpoints }, GoogleClientCredentials("", "")),
                Clock.System, fixture.backoff(), TEST_DISPATCHERS,
            )
            account.signOut()
            stored().values.filterNotNull().size shouldBe 0
        }
    }

    /** A clock a test moves by hand. */
    private class TestClock(var now: Instant) : Clock {
        override fun now(): Instant = now
    }

    /** [delegate], refusing to store [name]. */
    private class FailingOn(private val delegate: SecretStore, private val name: String) : SecretStore by delegate {
        override fun put(name: String, value: String) {
            if (name == this.name) throw IOException("fixture: the store refused $name")
            delegate.put(name, value)
        }
    }

    private companion object {
        val NAMES = listOf(
            DriveAccount.REFRESH_TOKEN,
            DriveAccount.REFRESH_TOKEN_EXPIRES_AT,
            DriveAccount.EMAIL,
            DriveAccount.ACCOUNT_ID,
        )
    }
}
