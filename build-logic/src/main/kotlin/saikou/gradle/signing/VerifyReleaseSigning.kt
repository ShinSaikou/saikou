package saikou.gradle.signing

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/**
 * `verifyReleaseSigning` (phase 3 unit R1, Q18): whether the release APK carries the owner's key in
 * every API range, printed as match or no match per range and written to [report] for the gate's
 * row `release_build_owner_signed`.
 *
 * The expected certificate is loaded with the values the release signing config already holds, so
 * the task checks the key the build signed with and nothing read a second time. Built without
 * `-Psaikou.ownerSigning=true`, the release carries the debug key by design and the task fails
 * without comparing anything: the row is about a release built with the flag. It runs every time it
 * is asked to (a verification served as up to date would say nothing about this build), and it
 * fails when any range does not match, after writing the report.
 */
@DisableCachingByDefault(because = "a check whose answer is only worth anything when it ran")
abstract class VerifyReleaseSigning : DefaultTask() {

    /** The directory AGP writes the release APK to, with its `output-metadata.json`. */
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val ownerSigning: Property<Boolean>

    /** The signing config's values. Internal: a password is no task input to fingerprint or log. */
    @get:Internal
    abstract val storeFile: RegularFileProperty

    @get:Internal
    abstract val storePassword: Property<String>

    @get:Internal
    abstract val keyAlias: Property<String>

    @get:Internal
    abstract val apksigner: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val out = report.get().asFile
        out.parentFile.mkdirs()
        out.delete()
        if (!ownerSigning.get()) {
            out.writeText(reportJson(apk = null, ownerSigning = false, results = emptyList()))
            throw GradleException(
                "the release was built without -P${OwnerSigning.PROPERTY}=true, so it carries the debug key by design; " +
                    "run this task with the flag",
            )
        }
        val apk = releaseApk(apkDirectory.get().asFile)
        val expected = ReleaseSigners.certificate(storeFile.get().asFile, storePassword.get(), keyAlias.get())
        val results = ReleaseSigners.check(apksigner.get().asFile, apk, expected)
        results.forEach { logger.lifecycle("${it.range}: ${it.verdict}${it.reason?.let { reason -> " ($reason)" } ?: ""}") }
        out.writeText(reportJson(apk, ownerSigning = true, results))
        val failed = results.filterNot { it.match }
        if (failed.isNotEmpty()) {
            throw GradleException("the release APK does not carry the key keystore.properties names in ${failed.joinToString { it.range.toString() }}")
        }
    }

    private fun releaseApk(directory: File): File {
        val apks = directory.listFiles { file -> file.isFile && file.name.endsWith(".apk") }.orEmpty()
        return apks.singleOrNull()
            ?: throw GradleException("expected one APK in ${directory.path}, found ${apks.map { it.name }}")
    }

    /** A small JSON object, written by hand: build-logic carries no JSON library. */
    private fun reportJson(apk: File?, ownerSigning: Boolean, results: List<RangeResult>): String {
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val ranges = results.joinToString(",\n") { result ->
            "    {\"range\": ${quote(result.range.key)}, \"result\": ${quote(result.verdict)}" +
                (result.reason?.let { ", \"reason\": ${quote(it)}" } ?: "") + "}"
        }
        return buildString {
            append("{\n")
            append("  \"apk\": ${apk?.let { quote(it.path) } ?: "null"},\n")
            append("  \"ownerSigning\": $ownerSigning,\n")
            append("  \"ranges\": [")
            if (ranges.isNotEmpty()) append("\n").append(ranges).append("\n  ")
            append("]\n}\n")
        }
    }
}
