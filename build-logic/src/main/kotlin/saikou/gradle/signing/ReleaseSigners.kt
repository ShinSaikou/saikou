package saikou.gradle.signing

import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * The comparison behind `verifyReleaseSigning` (phase 3 unit R1, Q18): the certificate of the alias
 * `keystore.properties` names against every signer `apksigner` reports for the release APK in each
 * API range where the platform reads a different signature block.
 *
 * Only whether they match leaves this file. A certificate is compared by its encoded bytes, read from
 * `apksigner verify --print-certs-pem`, and neither it nor any digest of it is printed or kept.
 */
object ReleaseSigners {

    /**
     * API 26 and 27 read the v2 block, 28 to 32 the v3.0 block, and 33 and up the v3.1 block
     * (challenge 6 of the phase 3 audit), so a key that differs in one block shows in its range only.
     */
    val RANGES = listOf(ApiRange(26, 27), ApiRange(28, 32), ApiRange(33, null))

    private val PEM = Regex("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)

    /** The certificate stored under [alias] in [storeFile], whatever the store's type. */
    fun certificate(storeFile: File, storePassword: String, alias: String): X509Certificate {
        val store = KeyStore.getInstance(storeFile, storePassword.toCharArray())
        val certificate = store.getCertificate(alias)
            ?: throw IllegalStateException("the keystore ${storeFile.path} holds no certificate under the alias $alias")
        return certificate as? X509Certificate
            ?: throw IllegalStateException("the certificate under the alias $alias is not an X.509 certificate")
    }

    /** Every certificate in [text], in order, from the PEM blocks `--print-certs-pem` prints. */
    fun pemCertificates(text: String): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        return PEM.findAll(text).map { block ->
            val der = Base64.getMimeDecoder().decode(block.groupValues[1].trim())
            factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.toList()
    }

    /** A match needs at least one signer, and every signer the range reports is [expected]. */
    fun matches(expected: X509Certificate, signers: List<X509Certificate>): Boolean =
        signers.isNotEmpty() && signers.all { it.encoded.contentEquals(expected.encoded) }

    /**
     * Runs `apksigner verify --print-certs-pem` on [apk] for each of [RANGES] and compares. A range
     * `apksigner` cannot verify (a non-zero exit) is a [RangeResult] with no match and the
     * exit status as its reason; apksigner's own output is not passed on, since it prints digests.
     */
    fun check(apksigner: File, apk: File, expected: X509Certificate): List<RangeResult> = RANGES.map { range ->
        val command = buildList {
            add(apksigner.path)
            add("verify")
            add("--print-certs-pem")
            add("--min-sdk-version")
            add(range.min.toString())
            range.max?.let {
                add("--max-sdk-version")
                add(it.toString())
            }
            add(apk.path)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) return@map RangeResult(range, match = false, reason = "apksigner could not verify it (exit $exit)")
        val signers = pemCertificates(output)
        when {
            signers.isEmpty() -> RangeResult(range, match = false, reason = "apksigner reported no signer")
            matches(expected, signers) -> RangeResult(range, match = true, reason = null)
            else -> RangeResult(range, match = false, reason = null)
        }
    }
}

/** An inclusive API range; [max] null is open ended. */
data class ApiRange(val min: Int, val max: Int?) {
    override fun toString(): String = if (max == null) "API $min and up" else "API $min to $max"

    /** The form the report file uses: `26-27`, `33-`. */
    val key: String get() = "$min-${max ?: ""}"
}

/** One range's answer. [reason] says why a range could not be compared at all. */
data class RangeResult(val range: ApiRange, val match: Boolean, val reason: String?) {
    val verdict: String get() = if (match) "match" else "no match"
}
