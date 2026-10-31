package com.shinsaikou

import android.content.Context
import com.shinsaikou.i18n.R
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * What the version row at the top of Advanced shows (phase 3 unit AD): the build's own name, version
 * name, version code and short commit hash, each read from the build rather than written twice.
 */
class AppInfoTest {

    private val context = mockk<Context> { every { getString(R.string.app_name) } returns "Saikou" }

    @Test
    fun `the version row's four parts are the build's`() {
        val info = AppInfo(context)

        info.name shouldBe "Saikou"
        info.versionName shouldBe BuildConfig.VERSION_NAME
        info.versionCode shouldBe BuildConfig.VERSION_CODE
        info.commitSha shouldBe BuildConfig.COMMIT_SHA
    }
}
