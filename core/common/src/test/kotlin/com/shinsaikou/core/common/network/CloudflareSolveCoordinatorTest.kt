package com.shinsaikou.core.common.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The interceptor itself needs a WebView and a main looper, so what is exercised here is the
 * coordinator it delegates to, driven exactly the way `CloudflareInterceptor.intercept` drives it:
 * the host comes from the request URL, and `alreadySolved` compares the clearance cookie against
 * the one the caller saw before it started waiting.
 */
class CloudflareSolveCoordinatorTest {

    private val clock = AtomicLong(1_000L)
    private val coordinator = CloudflareSolveCoordinator().also { it.elapsedRealtimeMs = clock::get }

    /** Stands in for the cookie jar the WebView and OkHttp share: host to clearance cookie value. */
    private val cookies = ConcurrentHashMap<String, String>()
    private val solves = AtomicInteger()

    private fun solveFor(url: String, waitBudget: kotlin.time.Duration = 5.seconds, inSolve: () -> Unit = {}) {
        val request = Request.Builder().url(url).build()
        val host = request.url.host
        val oldCookie = cookies[host]
        coordinator.coalesce(
            host = host,
            waitBudget = waitBudget,
            alreadySolved = { cookies[host].let { it != null && it != oldCookie } },
        ) {
            solves.incrementAndGet()
            inSolve()
            cookies[host] = "clearance-${solves.get()}"
        }
    }

    @Test
    fun `two concurrent requests to one host trigger one solve`() {
        val solveStarted = CountDownLatch(1)
        val releaseSolve = CountDownLatch(1)

        val first = thread {
            solveFor("https://one.example/a") {
                solveStarted.countDown()
                releaseSolve.await(5, TimeUnit.SECONDS)
            }
        }
        solveStarted.await(5, TimeUnit.SECONDS) shouldBe true

        val second = thread { solveFor("https://one.example/b") }
        // Give the second caller time to reach the lock while the first is still solving.
        Thread.sleep(100)
        releaseSolve.countDown()

        first.join(5_000)
        second.join(5_000)

        solves.get() shouldBe 1
    }

    @Test
    fun `two hosts solve at the same time`() {
        val barrier = CyclicBarrier(2)
        val failure = AtomicReference<Throwable>()
        val bothInside = { runCatching { barrier.await(5, TimeUnit.SECONDS) }.onFailure(failure::set) }

        val first = thread { solveFor("https://one.example/a") { bothInside() } }
        val second = thread { solveFor("https://two.example/a") { bothInside() } }
        first.join(10_000)
        second.join(10_000)

        failure.get() shouldBe null
        solves.get() shouldBe 2
        cookies.keys.sorted() shouldContainExactly listOf("one.example", "two.example")
    }

    @Test
    fun `a waiter that outlives its budget fails instead of solving`() {
        val solveStarted = CountDownLatch(1)
        val releaseSolve = CountDownLatch(1)
        val first = thread {
            solveFor("https://one.example/a") {
                solveStarted.countDown()
                releaseSolve.await(5, TimeUnit.SECONDS)
            }
        }
        solveStarted.await(5, TimeUnit.SECONDS) shouldBe true

        val thrown = shouldThrow<CloudflareBudgetExceededException> {
            solveFor("https://one.example/b", waitBudget = 50.milliseconds)
        }
        thrown.host shouldBe "one.example"

        releaseSolve.countDown()
        first.join(5_000)
        solves.get() shouldBe 1
    }

    @Test
    fun `an in-flight solve is visible while it runs and gone afterwards`() {
        val solveStarted = CountDownLatch(1)
        val releaseSolve = CountDownLatch(1)
        val first = thread {
            solveFor("https://one.example/a") {
                solveStarted.countDown()
                releaseSolve.await(5, TimeUnit.SECONDS)
            }
        }
        solveStarted.await(5, TimeUnit.SECONDS) shouldBe true

        val inFlight = coordinator.inFlight.value
        inFlight.keys shouldBe setOf("one.example")
        inFlight.getValue("one.example").budgetMs shouldBe CloudflareSolveCoordinator.SOLVER_BUDGET.inWholeMilliseconds

        releaseSolve.countDown()
        first.join(5_000)
        coordinator.inFlight.value shouldBe emptyMap()
    }

    @Test
    fun `callers queued behind a failed solve fail at once instead of each solving again`() {
        val solveStarted = CountDownLatch(1)
        val releaseSolve = CountDownLatch(1)
        val first = thread {
            runCatching {
                solveFor("https://one.example/a") {
                    solveStarted.countDown()
                    releaseSolve.await(5, TimeUnit.SECONDS)
                    throw IllegalStateException("the challenge wants a person")
                }
            }
        }
        solveStarted.await(5, TimeUnit.SECONDS) shouldBe true

        val waiterFailures = (1..3).map {
            val failure = AtomicReference<Throwable>()
            failure to thread { runCatching { solveFor("https://one.example/b") }.onFailure(failure::set) }
        }
        Thread.sleep(100)
        releaseSolve.countDown()
        first.join(5_000)
        waiterFailures.forEach { (_, waiter) -> waiter.join(5_000) }

        solves.get() shouldBe 1
        waiterFailures.forEach { (failure, _) ->
            (failure.get() as CloudflareSolveRecentlyFailedException).host shouldBe "one.example"
        }
    }

    @Test
    fun `a failed solve is remembered for a minute, per host`() {
        runCatching { solveFor("https://one.example/a") { throw IllegalStateException("interactive") } }
        solves.get() shouldBe 1

        clock.addAndGet(CloudflareSolveCoordinator.FAILURE_MEMORY.inWholeMilliseconds - 1)
        shouldThrow<CloudflareSolveRecentlyFailedException> { solveFor("https://one.example/a") }
        // Another host is not held back.
        solveFor("https://two.example/a")
        solves.get() shouldBe 2

        clock.addAndGet(1)
        solveFor("https://one.example/a")
        solves.get() shouldBe 3
    }

    @Test
    fun `a clearance earned by hand since the failure lets the caller through`() {
        runCatching { solveFor("https://one.example/a") { throw IllegalStateException("interactive") } }
        // A caller that saw no clearance, and then a person solves the page in the WebView.
        val request = Request.Builder().url("https://one.example/b").build()
        val oldCookie = cookies["one.example"]
        cookies["one.example"] = "by-hand"

        val result = coordinator.coalesce(
            host = request.url.host,
            waitBudget = 5.seconds,
            alreadySolved = { cookies["one.example"].let { it != null && it != oldCookie } },
        ) { solves.incrementAndGet() }

        result shouldBe null
        solves.get() shouldBe 1
    }

    @Test
    fun `a cancelled solve is not remembered as a failure`() {
        runCatching { solveFor("https://one.example/a") { throw InterruptedException() } }

        solveFor("https://one.example/a")

        solves.get() shouldBe 2
    }
}
