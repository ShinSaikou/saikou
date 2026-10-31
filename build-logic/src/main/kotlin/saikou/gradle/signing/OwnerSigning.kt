package saikou.gradle.signing

import org.gradle.api.GradleException
import java.io.File
import java.util.Properties

/**
 * Which key signs the release build type (phase 3 unit R1, contract 01 decision 15, Q15).
 *
 * Without `-Psaikou.ownerSigning=true` the release keeps the debug key, even when `keystore.properties`
 * exists, so a fresh clone and every phase 0 to 2 run build exactly as before. With it, the release
 * is signed with the key `keystore.properties` names, and a build that cannot have that key fails
 * rather than falling back: a release that quietly carried the debug key would pass for the owner's.
 *
 * Nothing here reads a value into a log or an exception. A missing or blank key is named by its key,
 * never by what it holds.
 */
object OwnerSigning {

    /** The Gradle property. `-Psaikou.ownerSigning=true` turns it on; nothing else does. */
    const val PROPERTY = "saikou.ownerSigning"

    /** The file beside the root build script, gitignored (contract 01 section 10). */
    const val KEYSTORE_PROPERTIES = "keystore.properties"

    /** The four keys, in the order `README.md` lists them. */
    val KEYS = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

    /**
     * Whether the property asks for owner signing: absent or `false` is off, `true` is on. A blank
     * value is refused (ruling R29: no property reaches the build blank), which covers a bare
     * `-Psaikou.ownerSigning`, since Gradle cannot tell it from `=` followed by a variable that
     * expanded to nothing. Any other value is refused too, so a typo cannot quietly leave the release
     * on the debug key. The message is fixed text and the property's name, never the value.
     */
    fun requested(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        null, "false" -> false
        "true" -> true
        else -> throw GradleException(REFUSED)
    }

    /** Why a blank or unknown value was refused. */
    val REFUSED = "-P$PROPERTY takes true or false; pass -P$PROPERTY=true to sign the release with $KEYSTORE_PROPERTIES"

    /**
     * The release key for this build. [keystoreProperties] is read only when [requested]; a
     * `storeFile` is resolved against [rootDir] unless it is absolute.
     */
    fun releaseKey(requested: Boolean, keystoreProperties: File, rootDir: File): ReleaseKey {
        if (!requested) return ReleaseKey.Debug
        if (!keystoreProperties.isFile) {
            return ReleaseKey.Unavailable("-P$PROPERTY=true was given and there is no ${keystoreProperties.path}")
        }
        val properties = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
        val missing = KEYS.filter { properties.getProperty(it).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return ReleaseKey.Unavailable("${keystoreProperties.path} has no value for ${missing.joinToString()}")
        }
        val storeFile = File(properties.getProperty("storeFile").trim()).let { if (it.isAbsolute) it else File(rootDir, it.path) }
        if (!storeFile.isFile) {
            return ReleaseKey.Unavailable("the storeFile ${keystoreProperties.path} names is not a file: ${storeFile.path}")
        }
        return ReleaseKey.Owner(
            storeFile = storeFile,
            storePassword = properties.getProperty("storePassword"),
            keyAlias = properties.getProperty("keyAlias").trim(),
            keyPassword = properties.getProperty("keyPassword"),
        )
    }
}

/** What [OwnerSigning.releaseKey] decided. */
sealed interface ReleaseKey {

    /** No flag: the debug key, as contract 01 decision 15 has it. */
    data object Debug : ReleaseKey

    /** The flag and a usable `keystore.properties`. */
    class Owner(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String) : ReleaseKey {
        // The passwords stay out of every string Gradle might print.
        override fun toString(): String = "Owner(storeFile=$storeFile, keyAlias=$keyAlias)"
    }

    /** The flag without a usable key. Any release task fails with [reason]. */
    data class Unavailable(val reason: String) : ReleaseKey
}
