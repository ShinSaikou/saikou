package com.shinsaikou.sync.webdav

import com.shinsaikou.sync.KeystoreSecretStore
import com.shinsaikou.sync.SecretStore
import com.shinsaikou.sync.SoftwareSecretCipher
import com.shinsaikou.sync.mapSecretStore
import com.shinsaikou.sync.SyncHttpClient
import com.shinsaikou.sync.webdav.WebDavUrlRefusedException.Reason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executors

/**
 * The WebDAV sign-in over the fixture, with the real [KeystoreSecretStore] on a software cipher:
 * the three secrets are stored only after a probe that answers signed in, under their permanent
 * names; sign-out deletes them; a backend is built only while all three can be read and the
 * address is one this build takes; and every secret store call runs off the calling thread.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebDavAccountTest {

    /** The class's directory: the fixture's log and state, and the client's cache. */
    private lateinit var dir: File

    private lateinit var fixture: SyncFixtureProcess
    private lateinit var client: SyncHttpClient

    private val preferences = mutableMapOf<String, Any>()
    private lateinit var secrets: SecretStore

    @BeforeAll
    fun start(@TempDir dir: File) {
        this.dir = dir
        fixture = SyncFixtureProcess(dir)
        client = syncHttpClient(File(dir, "cache"))
    }

    @AfterAll
    fun stop() {
        fixture.close()
    }

    @BeforeEach
    fun reset() {
        fixture.reset()
        preferences.clear()
        secrets = mapSecretStore(preferences, SoftwareSecretCipher())
    }

    private fun account(policy: WebDavUrlPolicy = JVM_TEST_POLICY): WebDavAccount =
        WebDavAccount(secrets, client, policy, TEST_DISPATCHERS)

    private fun storedNames(): Set<String> = preferences.keys.map { it.substringAfter("sync_secret_") }.toSet()

    @Test
    fun `a sign-in the server accepts stores the three secrets and gives a working backend`() {
        val account = account()

        val status = runBlocking { account.signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }

        status.authenticated shouldBe true
        storedNames() shouldContainOnly setOf("webdav_url", "webdav_user", "webdav_password")
        secrets.get(WebDavAccount.URL) shouldBe fixture.davUrl
        secrets.get(WebDavAccount.USER) shouldBe SyncFixtureProcess.USER
        secrets.get(WebDavAccount.PASSWORD) shouldBe SyncFixtureProcess.PASSWORD
        val backend = runBlocking { account.backend() }.shouldNotBeNull()
        runBlocking { backend.probe() }.authenticated shouldBe true
    }

    @Test
    fun `a sign-in the server refuses stores nothing`() {
        val account = account()

        val status = runBlocking { account.signIn(fixture.davUrl, SyncFixtureProcess.USER, "not-the-password") }

        status.reachable shouldBe true
        status.authenticated shouldBe false
        preferences.keys.shouldBeEmpty()
        runBlocking { account.backend() }.shouldBeNull()
    }

    @Test
    fun `a release build refuses the fixture's cleartext address before any request`() {
        // Were the address checked after the probe, these credentials would sign in.
        val account = account(WebDavUrlPolicy.forBuild(debug = false))

        shouldThrow<WebDavUrlRefusedException> {
            runBlocking { account.signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }
        }.reason shouldBe Reason.CLEARTEXT

        preferences.keys.shouldBeEmpty()
    }

    @Test
    fun `sign-out deletes the three secrets, and the backend is gone`() {
        val account = account()
        runBlocking { account.signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }

        runBlocking { account.signOut() }

        preferences.keys.shouldBeEmpty()
        runBlocking { account.backend() }.shouldBeNull()
    }

    @Test
    fun `the stored address and user name read back without the password, and not after sign-out`() {
        val account = account()
        runBlocking { account.signedInAs() }.shouldBeNull()
        runBlocking { account.signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }

        runBlocking { account.signedInAs() } shouldBe
            WebDavSignIn(url = JVM_TEST_POLICY.baseUrl(fixture.davUrl).toString(), user = SyncFixtureProcess.USER)

        runBlocking { account.signOut() }
        runBlocking { account.signedInAs() }.shouldBeNull()
    }

    @Test
    fun `a secret that is absent or cannot be read is signed out`() {
        runBlocking { account().signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }
        secrets.delete(WebDavAccount.PASSWORD)
        runBlocking { account().backend() }.shouldBeNull()

        runBlocking { account().signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }
        // Another key, as after the Keystore alias was lost: every secret fails its tag.
        secrets = mapSecretStore(preferences, SoftwareSecretCipher())
        runBlocking { account().backend() }.shouldBeNull()
        preferences.keys.shouldBeEmpty()
    }

    @Test
    fun `a stored address this build does not take is signed out`() {
        runBlocking { account().signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }

        runBlocking { account(WebDavUrlPolicy.forBuild(debug = false)).backend() }.shouldBeNull()
        runBlocking { account().backend() }.shouldNotBeNull()
    }

    @Test
    fun `the backend id is webdav and 16 hex characters, one per normalized address and user`() {
        runBlocking { account().signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD) }
        val id = runBlocking { account().backend() }.shouldNotBeNull().id
        id shouldMatch Regex("webdav-[0-9a-f]{16}")

        val base = JVM_TEST_POLICY.baseUrl(fixture.davUrl)
        fun idOf(url: String, user: String) =
            WebDavSyncBackend(JVM_TEST_POLICY.baseUrl(url), user, "any", client.client, TEST_DISPATCHERS.io).id
        idOf("HTTP://127.0.0.1:${fixture.port}/dav", SyncFixtureProcess.USER) shouldBe id
        idOf(base.toString(), "another-user") shouldNotBe id
        idOf("http://127.0.0.1:${fixture.port}/dav/other/", SyncFixtureProcess.USER) shouldNotBe id
        idOf("http://10.0.2.2:${fixture.port}/dav/", SyncFixtureProcess.USER) shouldNotBe id
    }

    @Test
    fun `every secret store call runs on the IO dispatcher, never the caller's thread`() {
        val threads = mutableListOf<String>()
        // Coroutine debug mode appends " @coroutine#n" to a thread's name.
        fun record() {
            threads += Thread.currentThread().name.substringBefore(" @")
        }
        val recording = object : SecretStore by secrets {
            override fun get(name: String): String? = secrets.get(name).also { record() }
            override fun put(name: String, value: String) = secrets.put(name, value).also { record() }
            override fun delete(name: String) = secrets.delete(name).also { record() }
        }
        val io = Executors.newSingleThreadExecutor { Thread(it, "secret-io") }.asCoroutineDispatcher()
        val account = WebDavAccount(recording, client, JVM_TEST_POLICY, TEST_DISPATCHERS.copy(io = io))
        try {
            runBlocking {
                account.signIn(fixture.davUrl, SyncFixtureProcess.USER, SyncFixtureProcess.PASSWORD)
                account.backend().shouldNotBeNull()
                account.signOut()
            }
        } finally {
            io.close()
        }

        threads.size shouldBe 9
        threads.toSet() shouldBe setOf("secret-io")
    }
}
