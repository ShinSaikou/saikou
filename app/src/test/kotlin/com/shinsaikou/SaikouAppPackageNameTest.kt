package com.shinsaikou

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * [SaikouApp.getPackageName] hands Chromium a browser's package name for `X-Requested-With`, and
 * every other caller the real one. The stack test below is what decides which caller is which.
 */
class SaikouAppPackageNameTest {

    private fun frame(className: String, methodName: String) = StackTraceElement(className, methodName, null, -1)

    @Test
    fun `Chromium reading its build info is recognised`() {
        isChromiumPackageNameCall(
            arrayOf(
                frame("android.app.ContextImpl", "getPackageName"),
                frame("org.chromium.base.BuildInfo", "<init>"),
                frame("org.chromium.android_webview.AwBrowserProcess", "start"),
            ),
        ) shouldBe true
        isChromiumPackageNameCall(arrayOf(frame("org.chromium.base.ApkInfo", "getPackageName"))) shouldBe true
        isChromiumPackageNameCall(arrayOf(frame("org.chromium.base.BuildInfo", "getAll"))) shouldBe true
    }

    @Test
    fun `every other caller is not`() {
        isChromiumPackageNameCall(
            arrayOf(
                frame("android.app.SharedPreferencesImpl", "<init>"),
                frame("com.shinsaikou.SaikouApp", "onCreate"),
            ),
        ) shouldBe false
        // Another Chromium class, or the build info class doing something else.
        isChromiumPackageNameCall(arrayOf(frame("org.chromium.base.ContextUtils", "getPackageName"))) shouldBe false
        isChromiumPackageNameCall(arrayOf(frame("org.chromium.base.BuildInfo", "isDebugAndroid"))) shouldBe false
        isChromiumPackageNameCall(emptyArray()) shouldBe false
    }
}
