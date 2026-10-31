package saikou.gradle.signing

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Two throwaway keys and APKs signed with them, made once per test JVM in a temporary directory with
 * the JDK's `keytool` and the SDK's `apksigner`, the tools the task itself runs.
 *
 * The APKs are signed four ways, so each API range can be made to match or not on its own: key A
 * alone, key B alone, and A rotated to B once from API 28 and once from API 33 (apksigner's default).
 * Read per range, a rotated APK reports A where the platform still verifies the older block and B
 * where it reads the rotated one (challenge 6 of the phase 3 audit measured the same table).
 *
 * No key here signs anything outside this directory, and none is printed.
 */
object SigningFixtures {

    const val PASSWORD = "throwaway"
    const val ALIAS_A = "throwaway-a"
    const val ALIAS_B = "throwaway-b"

    val dir: File by lazy { Files.createTempDirectory("saikou-release-signing").toFile().also { it.deleteOnExit() } }

    /** The SDK's newest apksigner, from `saikou.sdk.dir` (the build passes ANDROID_SDK_ROOT or ANDROID_HOME). */
    val apksigner: File by lazy {
        val sdk = System.getProperty("saikou.sdk.dir")?.takeIf { it.isNotBlank() }
            ?: error("no SDK: set ANDROID_SDK_ROOT or ANDROID_HOME for the build-logic tests")
        File(sdk, "build-tools").listFiles().orEmpty()
            .map { File(it, "apksigner") }
            .filter { it.canExecute() }
            .maxByOrNull { it.parentFile.name.split('.').map { part -> part.toIntOrNull() ?: 0 }.fold(0L) { acc, n -> acc * 1000 + n } }
            ?: error("no apksigner under $sdk/build-tools")
    }

    val storeA: File by lazy { key("a.jks", ALIAS_A, "CN=Throwaway A", "JKS") }
    val storeB: File by lazy { key("b.p12", ALIAS_B, "CN=Throwaway B", "PKCS12") }

    private val unsigned: File by lazy {
        File(dir, "unsigned.apk").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write("not a real manifest; the signer is told the API levels".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(16))
                zip.closeEntry()
            }
        }
    }

    val signedA: File by lazy { sign("a.apk", signer(storeA, ALIAS_A)) }
    val signedB: File by lazy { sign("b.apk", signer(storeB, ALIAS_B)) }
    val rotatedFrom28: File by lazy { rotated("rotated-28.apk", "--rotation-min-sdk-version", "28") }
    val rotatedFrom33: File by lazy { rotated("rotated-33.apk") }

    private val lineage: File by lazy {
        File(dir, "lineage").also { out ->
            run(
                listOf(apksigner.path, "rotate", "--out", out.path, "--old-signer") + signer(storeA, ALIAS_A) +
                    listOf("--new-signer") + signer(storeB, ALIAS_B),
            )
        }
    }

    private fun rotated(name: String, vararg extra: String): File =
        sign(name, signer(storeA, ALIAS_A) + listOf("--next-signer") + signer(storeB, ALIAS_B) + listOf("--lineage", lineage.path) + extra)

    private fun signer(store: File, alias: String) = listOf("--ks", store.path, "--ks-pass", "pass:$PASSWORD", "--ks-key-alias", alias)

    private fun sign(name: String, signerArgs: List<String>): File = File(dir, name).also { out ->
        run(listOf(apksigner.path, "sign") + signerArgs + listOf("--min-sdk-version", "26", "--out", out.path, unsigned.path))
    }

    private fun key(name: String, alias: String, dname: String, type: String): File = File(dir, name).also { store ->
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        run(
            listOf(
                keytool.path, "-genkeypair", "-keystore", store.path, "-storetype", type, "-storepass", PASSWORD,
                "-keypass", PASSWORD, "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
                "-dname", dname,
            ),
        )
    }

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(2, TimeUnit.MINUTES) && process.exitValue() == 0) {
            "${File(command.first()).name} ${command.getOrNull(1)} failed: ${output.lines().take(5).joinToString(" | ")}"
        }
    }
}
