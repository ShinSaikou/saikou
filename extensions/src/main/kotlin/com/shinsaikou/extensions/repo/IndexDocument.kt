package com.shinsaikou.extensions.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.extension.model.ExtensionStore

/**
 * The binary index a repository serves. Field numbers are the wire contract and are mirrored in
 * `tools/compat/index_fields.json`, which a unit test checks this file against so the Python and
 * Kotlin parsers cannot drift.
 *
 * Field 6 of `Source` is deliberately absent: it is a per-source content warning the host does not
 * read, and skipping it keeps the numbering honest. Field 501 of `Resources` is the catalog's own
 * addition; upstream's client drops it and this one carries it through.
 */
@Serializable
data class IndexDocument(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(
        @ProtoNumber(1) val extensions: List<Extension> = emptyList(),
    )

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<Source> = emptyList(),
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
        @ProtoNumber(501) val jarUrl: String? = null,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) val homeUrl: String = "",
        @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
        @ProtoNumber(7) val message: String? = null,
    )

    /** Ordinals 0..3 are the proto enum values. */
    @Serializable
    enum class ContentWarning {
        @ProtoNumber(0)
        @JsonNames("CONTENT_WARNING_UNSPECIFIED")
        UNSPECIFIED,

        @ProtoNumber(1)
        @JsonNames("CONTENT_WARNING_SAFE")
        SAFE,

        @ProtoNumber(2)
        @JsonNames("CONTENT_WARNING_MIXED")
        MIXED,

        @ProtoNumber(3)
        @JsonNames("CONTENT_WARNING_NSFW")
        NSFW,
    }

    fun toStore(indexUrl: String): ExtensionStore = ExtensionStore(
        indexUrl = indexUrl,
        name = name,
        badgeLabel = badgeLabel,
        signingKey = signingKey,
        contact = ExtensionStore.Contact(website = contact.website, discord = contact.discord),
        isLegacy = false,
        extensionListUrl = extensionListUrl,
    )
}

/** `repo.json`, the JSON descriptor a legacy or redirecting repository serves. */
@Serializable
data class RepoDocument(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta,
) {
    @Serializable
    data class Meta(
        val name: String,
        val shortName: String? = null,
        val website: String,
        val signingKeyFingerprint: String,
    )

    fun toStore(indexUrl: String): ExtensionStore = ExtensionStore(
        indexUrl = indexUrl,
        name = meta.name,
        badgeLabel = meta.shortName ?: meta.name,
        signingKey = meta.signingKeyFingerprint,
        contact = ExtensionStore.Contact(website = meta.website, discord = null),
        isLegacy = true,
        extensionListUrl = null,
    )
}

/** One entry of a legacy flat `index.min.json`, kept for repositories that never migrated. */
@Serializable
data class LegacyExtensionEntry(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<LegacySource>? = null,
) {
    @Serializable
    data class LegacySource(
        val name: String,
        val lang: String,
        val id: Long,
        val baseUrl: String,
    )
}

/** What one fetch of a repository produced. */
data class FetchedIndex(
    val store: ExtensionStore,
    /** Null when the index only carried an `extensionListUrl`. */
    val extensions: List<IndexDocument.Extension>?,
    val fetchedAtElapsedMs: Long,
)
