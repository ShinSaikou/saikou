package com.shinsaikou.tracking.myanimelist

import com.shinsaikou.tracking.myanimelist.MyAnimeListFixture.Companion.json
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The fixture's server is reached at the address it is bound to (phase 5 unit TG1). `localhost`
 * resolves to `::1` first on the gate's host and OkHttp tries IPv6 first, so while the fixture wrote
 * `localhost`, a process listening on `[::1]` at the port the kernel gave the server (an emulator, a
 * local dev server) took the tracker's calls: `ApplyTrackChoiceTest` and `MyAnimeListRefreshTest`
 * failed under the staging gate's load, when other units' emulators were up.
 */
class MyAnimeListFixtureAddressTest {

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `a listener on the IPv6 loopback at the server's port never takes the tracker's call`() {
        MyAnimeListFixture(cacheDir).use { fixture ->
            ServerSocket().use { other ->
                val bound = try {
                    other.bind(InetSocketAddress(InetAddress.getByName("::1"), fixture.server.port))
                    true
                } catch (e: IOException) {
                    false
                }
                assumeTrue(bound, "this host has no IPv6 loopback, where the call could not go astray")
                val taken = AtomicInteger()
                thread(isDaemon = true) {
                    while (true) {
                        val socket = try {
                            other.accept()
                        } catch (e: IOException) {
                            break
                        }
                        taken.incrementAndGet()
                        // Answers as another local HTTP server would.
                        socket.use {
                            val request = it.getInputStream().bufferedReader()
                            while (request.readLine()?.isNotEmpty() == true) Unit
                            it.getOutputStream().write(NOT_FOUND.toByteArray())
                        }
                    }
                }
                fixture.signedIn()
                fixture.server.enqueue(json(200, """{"data":[],"paging":{}}"""))

                runBlocking { fixture.tracker.search("fixture") }.shouldBeEmpty()

                fixture.server.requestCount shouldBe 1
                taken.get() shouldBe 0
            }
        }
    }

    private companion object {
        const val NOT_FOUND = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    }
}
