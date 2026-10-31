package com.shinsaikou.appupdate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The fields of GitHub's release object the updater reads, from
 * `GET /repos/<owner>/<repo>/releases/latest`. Everything else in the answer is ignored.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    /** Markdown as the release was written; the prompt shows it as plain text. */
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /** Null when [text] is not a release object: an error answer, a page of HTML, a truncated body. */
        fun parse(text: String): GitHubRelease? = try {
            json.decodeFromString(serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /** The address of [repository]'s latest release, `owner/name` as the build's `UPDATE_REPO` holds it. */
        fun latestUrl(repository: String): HttpUrl = "https://api.github.com/".toHttpUrl().newBuilder()
            .addPathSegment("repos")
            .addPathSegments(repository)
            .addPathSegments("releases/latest")
            .build()
    }
}

/** One file attached to a release. */
@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    /** `sha256:<hex>` on assets GitHub has hashed; absent on older ones. */
    val digest: String? = null,
)
