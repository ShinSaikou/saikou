package com.shinsaikou.extensions.loader

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import eu.kanade.tachiyomi.util.lang.Hash

/**
 * SHA-256 of each signing certificate's DER bytes, lowercase hex with no separators. This is the
 * exact value an index carries as its signing key, so trust can be compared byte for byte.
 */
object ExtensionSignatures {
    /**
     * Upstream's `getSignatures`, kept branch for branch: a single-signer APK yields its certificate
     * history, which survives key rotation, while a multi-signer APK yields the current signer set.
     * Null when the APK is unsigned.
     */
    fun fingerprints(packageInfo: PackageInfo): List<String>? {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        return signatures?.map { Hash.sha256(it.toByteArray()) }
    }
}
