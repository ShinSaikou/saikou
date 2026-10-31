package com.shinsaikou.core.common.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * One PKCE pair (RFC 7636) for one sign-in: the [verifier] the app keeps and sends with the code
 * exchange, and the S256 [challenge] the authorization URL carries. A provider that supports only
 * the `plain` method takes the verifier as its own challenge; nothing here computes that.
 *
 * [toString] names neither value: the verifier is what turns an intercepted code into a token.
 */
class Pkce(val verifier: String) {

    init {
        require(verifier.length in 43..128 && verifier.all { it in UNRESERVED }) {
            "A PKCE verifier is 43 to 128 unreserved characters (RFC 7636 section 4.1)"
        }
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))` without padding (RFC 7636 section 4.2). */
    val challenge: String = challengeOf(verifier)

    override fun toString(): String = "Pkce(██)"

    companion object {

        /** The only method this app sends. */
        const val METHOD = "S256"

        private const val VERIFIER_BYTES = 32
        private val UNRESERVED = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')

        /** A verifier of 32 random bytes, base64url without padding: 43 characters. */
        fun generate(random: SecureRandom = SecureRandom()): Pkce = Pkce(randomToken(random, VERIFIER_BYTES))

        fun challengeOf(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        internal fun randomToken(random: SecureRandom, bytes: Int): String =
            base64Url(ByteArray(bytes).also(random::nextBytes))

        // java.util.Base64 is on every API level this app runs on (26 and up).
        private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
