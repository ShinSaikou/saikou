package com.shinsaikou.core.common.oauth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class PkceTest {

    /** RFC 7636 appendix B: the S256 challenge of the specification's own verifier. */
    @Test
    fun `the S256 challenge matches RFC 7636's test vector`() {
        val pkce = Pkce("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")

        pkce.challenge shouldBe "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        Pkce.METHOD shouldBe "S256"
    }

    @Test
    fun `a generated verifier is 43 unreserved characters and differs each time`() {
        val first = Pkce.generate()
        val second = Pkce.generate()

        first.verifier shouldMatch Regex("[A-Za-z0-9_-]{43}")
        first.challenge shouldMatch Regex("[A-Za-z0-9_-]{43}")
        first.verifier shouldNotBe second.verifier
        first.challenge shouldBe Pkce.challengeOf(first.verifier)
    }

    @Test
    fun `a verifier outside RFC 7636's alphabet or length is refused, and neither value is printed`() {
        shouldThrow<IllegalArgumentException> { Pkce("too-short") }
        shouldThrow<IllegalArgumentException> { Pkce("a".repeat(42) + "+") }
        shouldThrow<IllegalArgumentException> { Pkce("a".repeat(129)) }

        val pkce = Pkce.generate()
        pkce.toString() shouldNotContain pkce.verifier
        pkce.toString() shouldNotContain pkce.challenge
    }
}
