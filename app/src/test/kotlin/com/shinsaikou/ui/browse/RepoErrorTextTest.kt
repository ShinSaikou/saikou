package com.shinsaikou.ui.browse

import android.content.res.Resources
import com.shinsaikou.extensions.repo.RepoFetchException
import com.shinsaikou.extensions.repo.RepoRefreshException
import com.shinsaikou.extensions.store.InvalidStoreAddressException
import com.shinsaikou.extensions.store.StoreAlreadyConfiguredException
import com.shinsaikou.i18n.R
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.domain.extension.model.ExtensionStore
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** Repository failures read as sentences, and a partial refresh names each repository that failed. */
class RepoErrorTextTest {

    private val resources = mockk<Resources> {
        every { getString(R.string.error_no_network) } returns "no network"
        every { getString(R.string.ext_repo_error_unreachable) } returns "unreachable"
        every { getString(R.string.ext_repo_error_decode) } returns "not an index"
        every { getString(R.string.ext_repo_error_legacy_url) } returns "a bare list"
        every { getString(R.string.ext_stores_error_address) } returns "not http"
        every { getString(R.string.ext_stores_error_duplicate) } returns "already there"
        every { getString(R.string.ext_repo_failed_format, any(), any()) } answers {
            val args = secondArg<Array<Any>>()
            "${args[0]} (${args[1]})"
        }
    }

    private fun fetch(kind: RepoFetchException.Kind, cause: Throwable? = null) =
        RepoFetchException("https://example.invalid/index.pb", kind, cause)

    private fun store(name: String) = ExtensionStore(
        indexUrl = "https://example.invalid/$name.pb",
        name = name,
        badgeLabel = "FI",
        signingKey = "a".repeat(64),
        contact = ExtensionStore.Contact("https://example.invalid/contact", null),
        isLegacy = false,
        extensionListUrl = null,
    )

    @Test
    fun `each kind of fetch failure reads as a sentence, never as the client's English`() {
        RepoErrorText.of(resources, fetch(RepoFetchException.Kind.DECODE)) shouldBe "not an index"
        RepoErrorText.of(resources, fetch(RepoFetchException.Kind.LEGACY_URL_MISMATCH)) shouldBe "a bare list"
        RepoErrorText.of(resources, fetch(RepoFetchException.Kind.NETWORK, UnknownHostException())) shouldBe "no network"
        RepoErrorText.of(resources, fetch(RepoFetchException.Kind.NETWORK, SSLHandshakeException("x"))) shouldBe
            "unreachable"
    }

    @Test
    fun `an address refused when adding reads as the reason`() {
        RepoErrorText.of(resources, InvalidStoreAddressException()) shouldBe "not http"
        RepoErrorText.of(resources, StoreAlreadyConfiguredException()) shouldBe "already there"
    }

    @Test
    fun `a partial refresh names every repository that failed, with its reason`() {
        val error = RepoRefreshException(
            listOf(
                RepoRefreshException.Failure(store("Fixture Index"), fetch(RepoFetchException.Kind.DECODE)),
                RepoRefreshException.Failure(
                    store("Other Index"),
                    fetch(RepoFetchException.Kind.NETWORK, UnknownHostException()),
                ),
            ),
        )

        RepoErrorText.of(resources, error) shouldBe "Fixture Index (not an index), Other Index (no network)"
    }
}
