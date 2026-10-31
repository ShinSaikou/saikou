package com.shinsaikou.ui.browse

import android.content.res.Resources
import com.shinsaikou.extensions.loader.InstallRejection
import com.shinsaikou.extensions.model.InstallFailedException
import com.shinsaikou.extensions.repo.RepoFetchException
import com.shinsaikou.i18n.R
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.UnknownHostException

/** The reason an Extensions row shows beside Retry after a failed install or update. */
class InstallFailureTextTest {

    private val resources = mockk<Resources> {
        every { getString(R.string.ext_install_rejected_signer_mismatch) } returns "signer mismatch"
        every { getString(R.string.error_no_network) } returns "no network"
    }

    @Test
    fun `a refusal reads as the installer's reason`() {
        installFailureText(resources, InstallFailedException(InstallRejection.SIGNER_MISMATCH)) shouldBe
            "signer mismatch"
    }

    @Test
    fun `a failed download reads as its own failure, not as a copy failure`() {
        val download = RepoFetchException(
            "https://example.invalid/fixture.apk",
            RepoFetchException.Kind.NETWORK,
            UnknownHostException("example.invalid"),
        )

        installFailureText(resources, download) shouldBe "no network"
    }
}
