// Fresh: written for Saikou. Placed in the vendored package so it can read the internal event handler.
package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * What the challenge page's own messages do to a headless solve: one that wants a person or one that
 * failed ends the wait at once, and only a failure voids a clearance the page may still set.
 */
class ChallengeEventsTest {

    private val latch = CountDownLatch(1)
    private val events = ChallengeEvents(latch)

    @Test
    fun `a failed challenge ends the solve and is remembered as failed`() {
        events.onEvent("fail")

        latch.count shouldBe 0
        events.failed shouldBe true
    }

    @Test
    fun `a challenge that wants a person ends the solve without failing it`() {
        events.onEvent("interactiveBegin")

        latch.count shouldBe 0
        events.failed shouldBe false
    }

    @Test
    fun `any other message leaves the solve running`() {
        events.onEvent("success")
        events.onEvent(null)

        latch.count shouldBe 1
        events.failed shouldBe false
    }

    @Test
    fun `a finished page without a challenge ends the solve`() {
        // The page is not asked for its url: a root that redirects finishes on another one.
        var listening = false
        events.onPageFinished(challengeFound = false) { listening = true }

        latch.count shouldBe 0
        listening shouldBe false
    }

    @Test
    fun `a finished challenge page is listened to and the solve keeps waiting`() {
        var listening = false
        events.onPageFinished(challengeFound = true) { listening = true }

        latch.count shouldBe 1
        listening shouldBe true
    }
}
