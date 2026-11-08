package com.shinsaikou.appupdate

import com.shinsaikou.core.common.coroutines.Dispatchers
import com.shinsaikou.core.common.network.trustSystemCertificatesOnly
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The updater's two requests to GitHub: the latest release of [AppUpdateBuild.repository], and the
 * release's APK. Both go through the shared client (its connection pool, DNS over HTTPS choice and
 * user agent) without the browser check, the cookie jar or the disk cache: GitHub needs none of
 * them, and no source's cookies have any business there. When the Experimental switch has the
 * shared client trust the CAs the owner installed, these take system trust back: what they fetch is
 * code the app installs, and whoever holds such a CA could swap it. The clients are built on first
 * use, on the IO dispatcher.
 */
@SingleIn(AppScope::class)
@Inject
class GitHubReleases(
    private val networkHelper: Lazy<NetworkHelper>,
    private val build: AppUpdateBuild,
    private val dispatchers: Dispatchers,
) {

    private val apiClient: OkHttpClient by lazy {
        val helper = networkHelper.value
        helper.client.newBuilder()
            .apply { interceptors().removeAll { it is CloudflareInterceptor } }
            .apply { if (helper.trustsUserCertificates) trustSystemCertificatesOnly() }
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            // A redirect may move between GitHub's https hosts, never down to plain http.
            .followSslRedirects(false)
            .build()
    }

    /**
     * Without the shared two-minute limit on a whole call, which an APK on a slow connection
     * outlasts; the 30-second read timeout still ends a download that stalls.
     */
    private val downloadClient: OkHttpClient by lazy {
        apiClient.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    /**
     * The latest published release, or null when the repository has none (GitHub answers 404).
     * Throws [IOException] when GitHub cannot be reached or answers with anything but a release.
     */
    suspend fun latest(): GitHubRelease? = withContext(dispatchers.io) {
        val request = Request.Builder()
            .url(GitHubRelease.latestUrl(build.repository))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .build()
        apiClient.newCall(request).await().use { response ->
            when {
                response.code == HTTP_NOT_FOUND -> null
                !response.isSuccessful -> throw IOException("GitHub answered ${response.code}")
                else -> GitHubRelease.parse(response.body.string())
                    ?: throw IOException("GitHub's answer is not a release")
            }
        }
    }

    /**
     * Downloads [update]'s APK to [target] through [UpdateFile.save], which hashes it and deletes it
     * on any failure, a digest mismatch included.
     */
    suspend fun download(update: AppUpdate, target: File, onProgress: (done: Long, total: Long) -> Unit) {
        withContext(dispatchers.io) {
            val request = Request.Builder().url(update.assetUrl).header("Accept", "application/octet-stream").build()
            downloadClient.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("The download answered ${response.code}")
                val body = response.body
                UpdateFile.save(body.byteStream(), body.contentLength(), target, AssetDigest.parse(update.assetDigest), onProgress)
            }
        }
    }

    private companion object {
        const val API_VERSION = "2022-11-28"
        const val HTTP_NOT_FOUND = 404
    }
}
