package com.shinsaikou.extensions.repo

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test

/**
 * The index is protobuf on the wire, so what matters is that an unknown field is skipped, that the
 * catalog's own field 501 survives, and that a JSON-encoded index decodes through the same model.
 */
class IndexDocumentTest {

    private val protoBuf = ProtoBuf { encodeDefaults = false }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun index(jarUrl: String?) = IndexDocument(
        name = "Fixture Index",
        badgeLabel = "FI",
        signingKey = "0".repeat(64),
        contact = IndexDocument.Contact(website = "https://example.invalid", discord = null),
        extensionList = IndexDocument.ExtensionList(
            listOf(
                IndexDocument.Extension(
                    name = "Fixture Extension A",
                    packageName = "com.shinsaikou.fixture.a",
                    resources = IndexDocument.Resources(
                        apkUrl = "https://example.invalid/apk/a.apk",
                        iconUrl = "https://example.invalid/icon/a.png",
                        jarUrl = jarUrl,
                    ),
                    extensionLib = "1.6",
                    versionCode = 12,
                    versionName = "1.6.12",
                    contentWarning = IndexDocument.ContentWarning.SAFE,
                    sources = listOf(
                        IndexDocument.Source(
                            id = 1234567890L,
                            name = "Fixture Source A",
                            language = "en",
                            homeUrl = "https://example.invalid",
                        ),
                    ),
                ),
            ),
        ),
        extensionListUrl = null,
    )

    @Test
    fun `an index carrying field 501 round trips through protobuf`() {
        val encoded = protoBuf.encodeToByteArray(index("https://example.invalid/jar/a.jar"))

        val decoded = protoBuf.decodeFromByteArray<IndexDocument>(encoded)

        decoded.extensionList.shouldNotBeNull()
            .extensions.single().resources.jarUrl shouldBe "https://example.invalid/jar/a.jar"
    }

    @Test
    fun `an index without field 501 decodes with a null jar url`() {
        val encoded = protoBuf.encodeToByteArray(index(jarUrl = null))

        val decoded = protoBuf.decodeFromByteArray<IndexDocument>(encoded)

        decoded.extensionList.shouldNotBeNull().extensions.single().resources.jarUrl shouldBe null
        decoded.signingKey shouldBe "0".repeat(64)
    }

    @Test
    fun `a JSON encoded index decodes through the same model`() {
        val encoded = json.encodeToString(IndexDocument.serializer(), index(null))

        json.decodeFromString(IndexDocument.serializer(), encoded) shouldBe index(null)
    }

    @Test
    fun `the proto enum names a JSON index uses are accepted`() {
        val body = """
            {"name":"Fixture Index","badgeLabel":"FI","signingKey":"${"0".repeat(64)}",
             "contact":{"website":"https://example.invalid"},
             "extensionList":{"extensions":[{"name":"Fixture Extension B","packageName":"com.shinsaikou.fixture.b",
               "resources":{"apkUrl":"https://example.invalid/apk/b.apk","iconUrl":"https://example.invalid/icon/b.png"},
               "extensionLib":"1.4","versionCode":3,"versionName":"1.4.3",
               "contentWarning":"CONTENT_WARNING_NSFW","sources":[]}]}}
        """.trimIndent()

        val decoded = json.decodeFromString(IndexDocument.serializer(), body)

        decoded.extensionList.shouldNotBeNull()
            .extensions.single().contentWarning shouldBe IndexDocument.ContentWarning.NSFW
    }

    @Test
    fun `a repo descriptor becomes a legacy store row`() {
        val body = """
            {"meta":{"name":"Fixture Index","shortName":"FI","website":"https://example.invalid",
             "signingKeyFingerprint":"${"0".repeat(64)}"}}
        """.trimIndent()

        val store = json.decodeFromString(RepoDocument.serializer(), body).toStore("https://example.invalid/repo.json")

        store.isLegacy shouldBe true
        store.badgeLabel shouldBe "FI"
        store.signingKey shouldBe "0".repeat(64)
        store.extensionListUrl shouldBe null
    }

    @Test
    fun `a descriptor with index_v2 exposes the URL the client follows`() {
        val body = """
            {"index_v2":"https://example.invalid/index.pb",
             "meta":{"name":"Fixture Index","website":"https://example.invalid",
             "signingKeyFingerprint":"${"0".repeat(64)}"}}
        """.trimIndent()

        val descriptor = json.decodeFromString(RepoDocument.serializer(), body)

        descriptor.indexV2 shouldBe "https://example.invalid/index.pb"
        descriptor.toStore("https://example.invalid/repo.json").badgeLabel shouldBe "Fixture Index"
    }

    @Test
    fun `an index entry becomes an available extension with resolved relative urls`() {
        val store = index(null).toStore("https://example.invalid/sub/index.pb")

        val available = index("jar/a.jar").extensionList!!.extensions.single()
            .copy(
                resources = IndexDocument.Resources(
                    apkUrl = "apk/a.apk",
                    iconUrl = "/icon/a.png",
                    jarUrl = "jar/a.jar",
                ),
            )
            .toAvailable(store)

        available.apkUrl shouldBe "https://example.invalid/sub/apk/a.apk"
        available.iconUrl shouldBe "https://example.invalid/icon/a.png"
        available.jarUrl shouldBe "https://example.invalid/sub/jar/a.jar"
        available.libVersion shouldBe 1.6
        available.lang shouldBe "en"
        available.isNsfw shouldBe false
    }

    @Test
    fun `a multi language entry reports all`() {
        val store = index(null).toStore("https://example.invalid/index.pb")
        val entry = index(null).extensionList!!.extensions.single().copy(
            sources = listOf(
                IndexDocument.Source(1L, "Fixture Source A", "en"),
                IndexDocument.Source(2L, "Fixture Source B", "ja"),
            ),
        )

        entry.toAvailable(store).lang shouldBe "all"
    }

    @Test
    fun `a MIXED content warning flags the entry as adult, as NSFW does and SAFE does not`() {
        // Ruling R55: the index path agrees with the installed path, where the manifest's mixed
        // value is already above zero and so already adult.
        val store = index(null).toStore("https://example.invalid/index.pb")
        val entry = index(null).extensionList!!.extensions.single()

        entry.copy(contentWarning = IndexDocument.ContentWarning.MIXED).toAvailable(store).isNsfw shouldBe true
        entry.copy(contentWarning = IndexDocument.ContentWarning.NSFW).toAvailable(store).isNsfw shouldBe true
        entry.copy(contentWarning = IndexDocument.ContentWarning.SAFE).toAvailable(store).isNsfw shouldBe false
        entry.copy(contentWarning = IndexDocument.ContentWarning.UNSPECIFIED).toAvailable(store).isNsfw shouldBe false
    }
}
