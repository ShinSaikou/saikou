package com.shinsaikou.tracking

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.domain.error.ErrorKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The OAuth seam's two pieces of logic (phase 3 unit T1 stage 2): the challenge each PKCE method
 * sends, since MyAnimeList accepts only `plain` and the seam must not force S256 on it, and the keys
 * a tracker's own failures declare (R67, R91 item 7).
 */
class OAuthTrackerTest {

    private val pkce = Pkce("a".repeat(20) + "B".repeat(20) + "-._~")

    @Test
    fun `plain sends the verifier itself as the challenge`() {
        PkceMethod.PLAIN.id shouldBe "plain"
        PkceMethod.PLAIN.challenge(pkce) shouldBe pkce.verifier
    }

    @Test
    fun `s256 sends the hash of the verifier, never the verifier`() {
        PkceMethod.S256.id shouldBe "S256"
        PkceMethod.S256.challenge(pkce) shouldBe Pkce.challengeOf(pkce.verifier)
        PkceMethod.S256.challenge(pkce) shouldNotBe pkce.verifier
    }

    @Test
    fun `a refused sign-in declares its key with the service's error code as the detail`() {
        val refused = TrackerSignInRefusedException(TrackerIds.MYANIMELIST, "invalid_grant")

        ErrorKey.of(refused) shouldBe ErrorKey.TRACKER_SIGN_IN_REFUSED
        ErrorKey.detail(refused) shouldBe "invalid_grant"
        ErrorKey.TRACKER_SIGN_IN_REFUSED.id shouldBe "tracker_sign_in_refused"
    }

    @Test
    fun `an expired sign-in declares its key and no detail`() {
        val expired = TrackerAuthExpiredException(TrackerIds.MYANIMELIST)

        ErrorKey.of(expired) shouldBe ErrorKey.TRACKER_AUTH_EXPIRED
        ErrorKey.detail(expired) shouldBe null
        ErrorKey.TRACKER_AUTH_EXPIRED.id shouldBe "tracker_auth_expired"
        expired.message.orEmpty() shouldNotContain "token"
    }
}
