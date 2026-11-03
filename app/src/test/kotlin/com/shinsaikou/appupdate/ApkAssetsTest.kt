package com.shinsaikou.appupdate

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which release file a device installs: one of its own build's kind, and of those its ABI's, else the
 * universal one, else the only APK.
 */
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
        ApkAssets.choose(listOf(universal, x8664, arm64, notes), "arm64-v8a", ApkKind.RELEASE) shouldBe arm64
        ApkAssets.choose(listOf(universal, x8664, arm64, notes), "x86_64", ApkKind.RELEASE) shouldBe x8664
    }

    @Test
    fun `without a file for the ABI the universal one is chosen`() {
        ApkAssets.choose(listOf(x8664, universal, arm64), "armeabi-v7a", ApkKind.RELEASE) shouldBe universal
        ApkAssets.choose(listOf(arm64, universal), null, ApkKind.RELEASE) shouldBe universal
    }

    @Test
    fun `an ABI must be a word of its own in the name`() {
        // x86 is inside x86_64, and an x86 device cannot run that file.
        ApkAssets.choose(listOf(x8664, universal), "x86", ApkKind.RELEASE) shouldBe universal
    }

    @Test
    fun `with neither, the only APK is chosen`() {
        val single = asset("app-release.apk")
        ApkAssets.choose(listOf(notes, single, mapping), "arm64-v8a", ApkKind.RELEASE) shouldBe single
    }

    @Test
    fun `several APKs none of which fits, or none at all, choose nothing`() {
        ApkAssets.choose(listOf(arm64, x8664), "armeabi-v7a", ApkKind.RELEASE).shouldBeNull()
        ApkAssets.choose(listOf(notes, mapping), "arm64-v8a", ApkKind.RELEASE).shouldBeNull()
        ApkAssets.choose(emptyList(), "arm64-v8a", ApkKind.RELEASE).shouldBeNull()
    }

    @Test
    fun `the suffix is matched without regard to case, and only an https address counts`() {
        val upper = asset("SAIKOU-ARM64-V8A.APK")
        ApkAssets.choose(listOf(upper), "arm64-v8a", ApkKind.RELEASE) shouldBe upper
        ApkAssets.choose(listOf(asset("app.apk", url = "http://example.org/app.apk")), "arm64-v8a", ApkKind.RELEASE).shouldBeNull()
    }

    @Test
    fun `a release takes app-release apk and the debug APK takes app-debug apk, with both attached`() {
        val release = asset("app-release.apk")
        val debug = asset("app-debug.apk")
        ApkAssets.choose(listOf(release, debug), "arm64-v8a", ApkKind.RELEASE) shouldBe release
        ApkAssets.choose(listOf(debug, release), "arm64-v8a", ApkKind.RELEASE) shouldBe release
        ApkAssets.choose(listOf(release, debug), "arm64-v8a", ApkKind.DEBUG) shouldBe debug
        ApkAssets.choose(listOf(debug, release), null, ApkKind.DEBUG) shouldBe debug
    }

    @Test
    fun `neither build takes the other's APK when its own is missing`() {
        ApkAssets.choose(listOf(asset("app-release.apk"), notes), "arm64-v8a", ApkKind.DEBUG).shouldBeNull()
        ApkAssets.choose(listOf(asset("app-debug.apk"), notes), "arm64-v8a", ApkKind.RELEASE).shouldBeNull()
    }

    @Test
    fun `the ABI and universal rules apply within each build's own APKs`() {
        val releaseArm = asset("saikou-1.0.0-arm64-v8a.apk")
        val debugArm = asset("saikou-1.0.0-arm64-v8a-debug.apk")
        val debugUniversal = asset("saikou-1.0.0-universal-debug.apk")
        val assets = listOf(debugArm, debugUniversal, releaseArm, universal)
        ApkAssets.choose(assets, "arm64-v8a", ApkKind.RELEASE) shouldBe releaseArm
        ApkAssets.choose(assets, "arm64-v8a", ApkKind.DEBUG) shouldBe debugArm
        ApkAssets.choose(assets, "x86_64", ApkKind.RELEASE) shouldBe universal
        ApkAssets.choose(assets, "x86_64", ApkKind.DEBUG) shouldBe debugUniversal
    }

    @Test
    fun `debug must be a word of its own, in any case, and a file that is no APK has no kind`() {
        ApkAssets.kindOf(asset("app-debug.apk")) shouldBe ApkKind.DEBUG
        ApkAssets.kindOf(asset("Saikou_DEBUG_x86_64.APK")) shouldBe ApkKind.DEBUG
        ApkAssets.kindOf(asset("app-release.apk")) shouldBe ApkKind.RELEASE
        ApkAssets.kindOf(asset("debugger-tools.apk")) shouldBe ApkKind.RELEASE
        ApkAssets.kindOf(asset("nodebug.apk")) shouldBe ApkKind.RELEASE
        ApkAssets.kindOf(asset("debug.apk.sha256")).shouldBeNull()
        ApkAssets.kindOf(asset("app-debug.apk", url = "http://example.org/app-debug.apk")).shouldBeNull()
    }
}
