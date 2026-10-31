package com.shinsaikou.appupdate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Which release file a device installs: its ABI's, else the universal one, else the only APK. */
class ApkAssetsTest {

    private fun asset(name: String, url: String = "https://github.com/example/app/releases/download/v1/$name") =
        GitHubAsset(name = name, downloadUrl = url)

    private val arm64 = asset("saikou-1.0.0-arm64-v8a.apk")
    private val x8664 = asset("saikou-1.0.0-x86_64.apk")
    private val universal = asset("saikou-1.0.0-universal.apk")
    private val notes = asset("notes.txt")
    private val mapping = asset("mapping.zip")

    @Test
    fun `the device's own ABI wins over the universal file`() {
        ApkAssets.choose(listOf(universal, x8664, arm64, notes), "arm64-v8a") shouldBe arm64
        ApkAssets.choose(listOf(universal, x8664, arm64, notes), "x86_64") shouldBe x8664
    }

    @Test
    fun `without a file for the ABI the universal one is chosen`() {
        ApkAssets.choose(listOf(x8664, universal, arm64), "armeabi-v7a") shouldBe universal
        ApkAssets.choose(listOf(arm64, universal), null) shouldBe universal
    }

    @Test
    fun `an ABI must be a word of its own in the name`() {
        // x86 is inside x86_64, and an x86 device cannot run that file.
        ApkAssets.choose(listOf(x8664, universal), "x86") shouldBe universal
    }

    @Test
    fun `with neither, the only APK is chosen`() {
        val single = asset("app-release.apk")
        ApkAssets.choose(listOf(notes, single, mapping), "arm64-v8a") shouldBe single
    }

    @Test
    fun `several APKs none of which fits, or none at all, choose nothing`() {
        ApkAssets.choose(listOf(arm64, x8664), "armeabi-v7a").shouldBeNull()
        ApkAssets.choose(listOf(notes, mapping), "arm64-v8a").shouldBeNull()
        ApkAssets.choose(emptyList(), "arm64-v8a").shouldBeNull()
    }

    @Test
    fun `the suffix is matched without regard to case, and only an https address counts`() {
        val upper = asset("SAIKOU-ARM64-V8A.APK")
        ApkAssets.choose(listOf(upper), "arm64-v8a") shouldBe upper
        ApkAssets.choose(listOf(asset("app.apk", url = "http://example.org/app.apk")), "arm64-v8a").shouldBeNull()
    }
}
