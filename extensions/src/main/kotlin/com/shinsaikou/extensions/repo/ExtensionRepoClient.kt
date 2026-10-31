package com.shinsaikou.extensions.repo

import android.os.SystemClock
import com.shinsaikou.extensions.model.AvailableSource
import com.shinsaikou.extensions.model.Extension
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.domain.extension.model.ExtensionStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches and decodes a repository index.
 *
 * Dispatch is by first byte after gzip detection, which is how one URL can serve three formats:
 * `[` is a legacy flat `index.min.json` and is only accepted at that path, `{` is either a
 * `repo.json` descriptor or a JSON-encoded index, and anything else is the protobuf index. A
 * descriptor carrying `index_v2` recurses once into the URL it names.
 *
 * Nothing here throws except cancellation; every failure comes back as a [RepoFetchException]
 * inside a failed [Result].
 */
@SingleIn(AppScope::class)
@Inject
class ExtensionRepoClient(
    private val networkHelper: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache = ConcurrentHashMap<String, FetchedIndex>()

    /** The last successful fetch of [indexUrl], for the browse screen and for refreshes. */
    fun cached(indexUrl: String): FetchedIndex? = cache[indexUrl]

    /** Fetches [url] and resolves it to a store row plus, when the index carried one, its list. */
    suspend fun fetch(url: String): Result<FetchedIndex> = withContext(ioDispatcher) {
        try {
            Result.success(fetchIndex(url, followedRedirect = false))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RepoFetchException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(RepoFetchException(url, RepoFetchException.Kind.DECODE, e))
        }
    }

    /**
     * The extensions of a fetched store: from the index body when it carried one, else one fetch of
     * `extensionListUrl`, else the legacy flat list. The index URL is never fetched twice.
     */
    suspend fun extensions(fetched: FetchedIndex): Result<List<Extension.Available>> = withContext(ioDispatcher) {
        val store = fetched.store
        try {
            val entries = when {
                fetched.extensions != null -> fetched.extensions
                store.extensionListUrl != null -> {
                    val url = store.extensionListUrl!!
                    readBody(url) { source ->
                        when (source.peek().readByte()) {
                            JSON_OBJECT -> json.decodeFromBufferedSource<IndexDocument.ExtensionList>(source)
                            else -> protoBuf.decodeFromByteArray<IndexDocument.ExtensionList>(source.readByteArray())
                        }.extensions
                    }
                }
                store.isLegacy -> return@withContext Result.success(legacyExtensions(store))
                else -> emptyList()
            }
            Result.success(entries.map { it.toAvailable(store) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: RepoFetchException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(RepoFetchException(store.indexUrl, RepoFetchException.Kind.DECODE, e))
        }
    }

    /** Streams an APK to [target] with progress. The caller verifies what it receives. */
    suspend fun downloadApk(
        url: String,
        target: File,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): Result<File> = withContext(ioDispatcher) {
        val listener = object : ProgressListener {
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                onProgress(bytesRead, contentLength)
            }
        }
        try {
            networkHelper.client.newCachelessCallWithProgress(GET(url), listener).awaitSuccess().use { response ->
                target.parentFile?.mkdirs()
                response.body.source().use { source ->
                    target.sink().buffer().use { it.writeAll(source) }
                }
            }
            Result.success(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            target.delete()
            Result.failure(RepoFetchException(url, e.toKind(), e))
        }
    }

    private suspend fun fetchIndex(url: String, followedRedirect: Boolean): FetchedIndex {
        var resolvedUrl = url
        val decoded = readBody(url) { source ->
            when (source.peek().readByte()) {
                JSON_ARRAY -> {
                    if (!url.endsWith(LEGACY_INDEX_SUFFIX)) {
                        throw RepoFetchException(url, RepoFetchException.Kind.LEGACY_URL_MISMATCH)
                    }
                    resolvedUrl = url.removeSuffix(LEGACY_INDEX_SUFFIX) + REPO_DESCRIPTOR_SUFFIX
                    readBody(resolvedUrl) { json.decodeFromBufferedSource<RepoDocument>(it) }
                }
                JSON_OBJECT -> try {
                    json.decodeFromBufferedSource<RepoDocument>(source.peek())
                } catch (_: IllegalArgumentException) {
                    json.decodeFromBufferedSource<IndexDocument>(source)
                }
                // A JSON-encoded index that did not start with '{' cannot exist, but a protobuf
                // body that fails to decode is retried as JSON before the fetch is called a loss.
                else -> {
                    val bytes = source.readByteArray()
                    try {
                        protoBuf.decodeFromByteArray<IndexDocument>(bytes)
                    } catch (_: Exception) {
                        json.decodeFromString<IndexDocument>(bytes.decodeToString())
                    }
                }
            }
        }

        if (decoded is RepoDocument && decoded.indexV2 != null) {
            if (followedRedirect) {
                throw RepoFetchException(resolvedUrl, RepoFetchException.Kind.DECODE)
            }
            return fetchIndex(decoded.indexV2, followedRedirect = true)
        }

        return when (decoded) {
            is IndexDocument -> FetchedIndex(
                store = decoded.toStore(resolvedUrl),
                extensions = decoded.extensionList?.extensions,
                fetchedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            is RepoDocument -> FetchedIndex(
                store = decoded.toStore(resolvedUrl),
                extensions = null,
                fetchedAtElapsedMs = SystemClock.elapsedRealtime(),
            )
            else -> throw RepoFetchException(resolvedUrl, RepoFetchException.Kind.DECODE)
        }.also { cache[it.store.indexUrl] = it }
    }

    private suspend fun legacyExtensions(store: ExtensionStore): List<Extension.Available> {
        val baseUrl = store.indexUrl.removeSuffix(REPO_DESCRIPTOR_SUFFIX)
        val url = baseUrl + LEGACY_INDEX_SUFFIX
        val entries = readBody(url) { json.decodeFromBufferedSource<List<LegacyExtensionEntry>>(it) }
        return entries.map { it.toAvailable(store, baseUrl) }
    }

    private suspend fun <T> readBody(url: String, decode: suspend (BufferedSource) -> T): T {
        val response = try {
            networkHelper.client.newCall(GET(url)).awaitSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw RepoFetchException(url, e.toKind(), e)
        }
        return response.use {
            it.body.source().decompressIfGzipped().use { source -> decode(source) }
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() and 0xffff == GZIP_MAGIC
            } catch (_: Exception) {
                false
            }
        }
        return if (isGzip) gzip().buffer() else this
    }

    private companion object {
        const val JSON_ARRAY = '['.code.toByte()
        const val JSON_OBJECT = '{'.code.toByte()
        const val GZIP_MAGIC = 0x1f8b
        const val LEGACY_INDEX_SUFFIX = "/index.min.json"
        const val REPO_DESCRIPTOR_SUFFIX = "/repo.json"
    }
}

private fun Throwable.toKind(): RepoFetchException.Kind = when (this) {
    is HttpException -> RepoFetchException.Kind.HTTP
    is RepoFetchException -> kind
    is IOException -> RepoFetchException.Kind.NETWORK
    else -> RepoFetchException.Kind.DECODE
}

/** Relative resource URLs are resolved against the index URL, as a browser would. */
internal fun IndexDocument.Extension.toAvailable(store: ExtensionStore): Extension.Available {
    val languages = sources.mapTo(mutableSetOf()) { it.language }
    return Extension.Available(
        packageName = packageName,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        libVersion = extensionLib.toDoubleOrNull() ?: 0.0,
        lang = when (languages.size) {
            0 -> ""
            1 -> languages.first()
            else -> "all"
        },
        isNsfw = contentWarning in ADULT_CONTENT_WARNINGS,
        sources = sources.map { AvailableSource(it.id, it.name, it.language, it.homeUrl) },
        apkUrl = resolveAgainst(store.indexUrl, resources.apkUrl),
        iconUrl = resolveAgainst(store.indexUrl, resources.iconUrl),
        jarUrl = resources.jarUrl?.let { resolveAgainst(store.indexUrl, it) },
        store = store,
    )
}

internal fun LegacyExtensionEntry.toAvailable(store: ExtensionStore, baseUrl: String): Extension.Available =
    Extension.Available(
        packageName = pkg,
        name = name.substringAfter("Tachiyomi: "),
        versionName = version,
        versionCode = code,
        libVersion = version.substringBeforeLast('.').toDoubleOrNull() ?: 0.0,
        lang = lang,
        isNsfw = nsfw == 1,
        sources = sources.orEmpty().map { AvailableSource(it.id, it.name, it.lang, it.baseUrl) },
        apkUrl = "$baseUrl/apk/$apk",
        iconUrl = "$baseUrl/icon/$pkg.png",
        jarUrl = null,
        store = store,
    )

/**
 * Ruling R55: MIXED counts as adult as well as NSFW, which is what the installed path already does
 * with the manifest's own 0, 1, 2 encoding (anything above zero) and what the mature sources switch
 * says it covers. Written as a set rather than an ordinal comparison so a value added to the enum
 * later is not swept in by its position. This supersedes contract 03 section 8's NSFW-only reading.
 */
private val ADULT_CONTENT_WARNINGS = setOf(IndexDocument.ContentWarning.MIXED, IndexDocument.ContentWarning.NSFW)

private fun resolveAgainst(indexUrl: String, candidate: String): String =
    indexUrl.toHttpUrlOrNull()?.resolve(candidate)?.toString() ?: candidate
