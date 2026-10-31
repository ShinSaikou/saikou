package com.shinsaikou.appupdate

import androidx.work.workDataOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What the Update button hands the worker, and the one unique work name per version. */
class AppUpdateWorkerInputTest {

    private val update = AppUpdate(
        version = "0.0.2",
        notes = "Fixes.",
        assetName = "saikou-0.0.2-universal.apk",
        assetUrl = "https://github.com/example/app/releases/download/v0.0.2/saikou-0.0.2-universal.apk",
        assetDigest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    )

    @Test
    fun `the worker reads back everything it needs, without the notes`() {
        AppUpdateWorker.updateOf(AppUpdateWorker.inputOf(update)) shouldBe update.copy(notes = "")
    }

    @Test
    fun `an asset without a digest stays without one`() {
        val plain = update.copy(assetDigest = null)
        AppUpdateWorker.updateOf(AppUpdateWorker.inputOf(plain)) shouldBe plain.copy(notes = "")
    }

    @Test
    fun `input without a version or an address is no update`() {
        AppUpdateWorker.updateOf(workDataOf("asset_url" to update.assetUrl)).shouldBeNull()
        AppUpdateWorker.updateOf(workDataOf("version" to "0.0.2")).shouldBeNull()
    }

    @Test
    fun `each version has its own unique work`() {
        AppUpdateWorker.workName("0.0.2") shouldBe "app-update-0.0.2"
        (AppUpdateWorker.workName("0.0.2") == AppUpdateWorker.workName("0.0.3")) shouldBe false
    }
}
