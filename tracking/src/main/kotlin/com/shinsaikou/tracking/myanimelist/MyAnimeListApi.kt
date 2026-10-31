// Vendored from upstream v0.20.4: app/src/main/java/eu/kanade/tachiyomi/data/track/myanimelist/MyAnimeListApi.kt (modified: yes)
package com.shinsaikou.tracking.myanimelist

import com.shinsaikou.core.common.oauth.Pkce
import com.shinsaikou.tracking.ListEntry
import com.shinsaikou.tracking.ListRead
import com.shinsaikou.tracking.PkceMethod
import com.shinsaikou.tracking.TrackSearchResult
import com.shinsaikou.tracking.TrackerSignInRefusedException
import com.shinsaikou.tracking.await
import com.shinsaikou.tracking.awaitSuccess
import com.shinsaikou.tracking.myanimelist.dto.MALListItem
import com.shinsaikou.tracking.myanimelist.dto.MALListItemStatus
import com.shinsaikou.tracking.myanimelist.dto.MALListProgressPage
import com.shinsaikou.tracking.myanimelist.dto.MALManga
import com.shinsaikou.tracking.myanimelist.dto.MALOAuth
import com.shinsaikou.tracking.myanimelist.dto.MALSearchResult
import com.shinsaikou.tracking.myanimelist.dto.MALUser
import com.shinsaikou.tracking.parseAs
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.track.model.Track
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Modified (phase 3 unit T2):
 *
 * - **No client id or verifier on the companion.** upstream kept its own client id as a constant and
 *   the PKCE verifier as a companion `var` that every sign-in overwrote. The id is [clientId], read
 *   per call (the build's on MyAnimeList, the fake's on a test base), and the verifier comes in with
 *   each sign-in's [Pkce], which the caller made for that sign-in alone.
 * - **The authorization URL** ([authUrl]) adds `redirect_uri`, `state` and
 *   `code_challenge_method=plain`, which upstream omitted (plain is MyAnimeList's only method and its
 *   default), and [getAccessToken] sends the same `redirect_uri` again.
 * - **A refused code exchange** is [TrackerSignInRefusedException] with the OAuth `error` code, or
 *   `http_<status>`, never the body. [getCurrentUser] takes the new access token itself, so nothing
 *   is stored until the exchange and the user fetch have both succeeded.
 * - **Addresses** come from [endpoints], read on each call, as OkHttp [HttpUrl]s rather than
 *   `android.net.Uri`; the query parameters are upstream's.
 * - **The immutable domain row** in place of upstream's bean: each call returns the row to store.
 *   [findListItem] returns the row with the entry's chapter count applied and says whether the
 *   owner's list has the entry, where upstream mutated the row it was given and returned it or null.
 * - Search answers are [TrackSearchResult], with the entry's mean as its score, as upstream sets it.
 * - [json] is the app's; [nowEpochSeconds] stamps a token's creation from the tracker's clock.
 * - **The list read with progress** (phase 4 unit TC): [readListProgress] pages the owner's list
 *   asking for `list_status`, which upstream never asks for, and follows the offset MyAnimeList's
 *   `paging.next` names without requesting that URL. [findListItems] and its pages stay upstream's.
 */
class MyAnimeListApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    interceptor: MyAnimeListInterceptor,
    private val endpoints: () -> MyAnimeListEndpoints,
    private val clientId: () -> String,
    private val json: Json,
    private val nowEpochSeconds: () -> Long,
) {

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    /** The page the browser opens for one sign-in: upstream's parameters, and the loopback's. */
    fun authUrl(redirectUri: String, state: String, pkce: Pkce): HttpUrl = endpoints().authorization.newBuilder()
        .addQueryParameter("client_id", clientId())
        .addQueryParameter("code_challenge", PkceMethod.PLAIN.challenge(pkce))
        .addQueryParameter("code_challenge_method", PkceMethod.PLAIN.id)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("state", state)
        .build()

    suspend fun getAccessToken(authCode: String, redirectUri: String, pkce: Pkce): MALOAuth {
        return withIOContext {
            val formBody: RequestBody = FormBody.Builder()
                .add("client_id", clientId())
                .add("code", authCode)
                .add("code_verifier", pkce.verifier)
                .add("grant_type", "authorization_code")
                .add("redirect_uri", redirectUri)
                .build()
            client.newCall(POST(endpoints().token.toString(), body = formBody)).await().use { response ->
                if (!response.isSuccessful) throw TrackerSignInRefusedException(trackId, oauthError(response))
                json.decodeFromString<MALOAuth>(response.body.string()).copy(createdAt = nowEpochSeconds())
            }
        }
    }

    /** The signed-in account's name, asked for with [accessToken] before anything is stored. */
    suspend fun getCurrentUser(accessToken: String): String {
        return withIOContext {
            val request = Request.Builder()
                .url(api("users", "@me"))
                .get()
                .header("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(request)
                .awaitSuccess()
                .parseAs<MALUser>(json)
                .name
        }
    }

    suspend fun search(query: String): List<TrackSearchResult> {
        return withIOContext {
            val url = api("manga").newBuilder()
                // MAL API throws a 400 when the query is over 64 characters...
                .addQueryParameter("q", query.take(64))
                .addQueryParameter("nsfw", "true")
                .addQueryParameter("fields", SEARCH_FIELDS)
                .build()
            authClient.newCall(GET(url))
                .awaitSuccess()
                .parseAs<MALSearchResult>(json)
                .data
                .filter { !(it.node.mediaType.contains("novel")) }
                .map { parseSearchItem(it.node) }
        }
    }

    suspend fun getMangaDetails(id: Int): TrackSearchResult {
        return withIOContext {
            val url = api("manga", id.toString()).newBuilder()
                .addQueryParameter("fields", SEARCH_FIELDS)
                .build()
            authClient.newCall(GET(url))
                .awaitSuccess()
                .parseAs<MALManga>(json)
                .let { parseSearchItem(it) }
        }
    }

    suspend fun updateItem(track: Track): Track {
        return withIOContext {
            val formBodyBuilder = FormBody.Builder()
                .add("status", track.toMyAnimeListStatus() ?: "reading")
                .add("is_rereading", (track.status == MyAnimeList.REREADING).toString())
                .add("score", track.score.toString())
                .add("num_chapters_read", track.lastChapterRead.toInt().toString())
            convertToIsoDate(track.startDate)?.let {
                formBodyBuilder.add("start_date", it)
            }
            convertToIsoDate(track.finishDate)?.let {
                formBodyBuilder.add("finish_date", it)
            }

            val request = Request.Builder()
                .url(mangaUrl(track.remoteId))
                .put(formBodyBuilder.build())
                .build()
            val response = authClient
                .newCall(request)
                .await()

            if (!response.isSuccessful) {
                val body = response.use { it.body.string() }
                if (body.contains("invalid_content")) {
                    // MAL returns unapproved titles in search but does not allow adding them to the list
                    // returns 400 with this body: {"message":"Invalid content","error":"invalid_content"}
                    // These unapproved titles cannot be filtered out in search and are also returned by the
                    // endpoint we use for id prefix search
                    throw MALTitleNotApproved()
                } else {
                    throw HttpException(response.code)
                }
            }

            response
                .parseAs<MALListItemStatus>(json)
                .let { parseMangaItem(it, track) }
        }
    }

    suspend fun deleteItem(remoteId: Long) {
        withIOContext {
            authClient
                .newCall(DELETE(mangaUrl(remoteId).toString()))
                .awaitSuccess()
                .close()
        }
    }

    /**
     * The entry of [track] with its chapter count read into the row, and, when the owner's list has
     * the entry, the list's status, chapters, score and dates too ([ListLookup.onList]).
     */
    suspend fun findListItem(track: Track): ListLookup {
        return withIOContext {
            val url = api("manga", track.remoteId.toString()).newBuilder()
                .addQueryParameter("fields", "num_chapters,my_list_status{start_date,finish_date}")
                .build()
            authClient.newCall(GET(url))
                .awaitSuccess()
                .parseAs<MALListItem>(json)
                .let { item ->
                    val counted = track.copy(totalChapters = item.numChapters)
                    item.myListStatus
                        ?.let { ListLookup(parseMangaItem(it, counted), onList = true) }
                        ?: ListLookup(counted, onList = false)
                }
        }
    }

    /** [track] as [findListItem] read it; [onList] is false when the owner's list lacks the entry. */
    class ListLookup(val track: Track, val onList: Boolean)

    suspend fun findListItems(query: String, offset: Int = 0): List<TrackSearchResult> {
        return withIOContext {
            val myListSearchResult = getListPage(offset)

            val matches = myListSearchResult.data
                .filter { it.node.title.contains(query, ignoreCase = true) }
                .map { parseSearchItem(it.node) }

            // Check next page if there's more
            if (!myListSearchResult.paging.next.isNullOrBlank()) {
                matches + findListItems(query, offset + LIST_PAGINATION_AMOUNT)
            } else {
                matches
            }
        }
    }

    private suspend fun getListPage(offset: Int): MALSearchResult {
        return withIOContext {
            val urlBuilder = api("users", "@me", "mangalist").newBuilder()
                .addQueryParameter("fields", SEARCH_FIELDS)
                .addQueryParameter("limit", LIST_PAGINATION_AMOUNT.toString())
            if (offset > 0) {
                urlBuilder.addQueryParameter("offset", offset.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .get()
                .build()
            authClient.newCall(request)
                .awaitSuccess()
                .parseAs(json)
        }
    }

    /**
     * The owner's list entries whose id is in [remoteIds], with their chapters read (phase 4 unit TC).
     *
     * Each page asks for `list_status` and the chapter count, `nsfw=true` as search sends it, and no
     * `status`, so a dropped or planned entry is read too. The next offset is the one `paging.next`
     * names, or this offset plus the entries this page returned when it names none; that URL is never
     * requested, since the token would go to whatever host it names. Upstream's offset plus 250 would skip
     * entries silently on a page capped below the limit. The read ends when `paging.next` is null or
     * blank.
     *
     * [ListRead.Unreadable] when the answer cannot be trusted: a page answering 400 again once asked
     * without `nsfw` (every later page is then asked without it), a page that does not decode, an
     * entry without `list_status`, a next offset not past the current one, or more than
     * [LIST_PROGRESS_MAX_PAGES] pages. Any other failure is thrown, and nothing read is returned.
     */
    suspend fun readListProgress(remoteIds: Set<Long>): ListRead = withIOContext { readListPages(remoteIds) }

    private suspend fun readListPages(remoteIds: Set<Long>): ListRead {
        val kept = mutableMapOf<Long, ListEntry>()
        var offset = 0
        var nsfw = true
        var pages = 0
        while (true) {
            if (pages == LIST_PROGRESS_MAX_PAGES) {
                return ListRead.Unreadable("more than $LIST_PROGRESS_MAX_PAGES pages")
            }
            pages++
            var answer = listProgressPage(offset, nsfw)
            if (answer is ListPageAnswer.BadRequest && nsfw) {
                nsfw = false
                answer = listProgressPage(offset, nsfw)
            }
            val page = when (answer) {
                ListPageAnswer.BadRequest ->
                    return ListRead.Unreadable("the page at offset $offset answered 400 without nsfw too")
                ListPageAnswer.Undecodable ->
                    return ListRead.Unreadable("the page at offset $offset did not decode")
                is ListPageAnswer.Page -> answer.page
            }
            for (item in page.data) {
                val listStatus = item.listStatus
                    ?: return ListRead.Unreadable("an entry on the page at offset $offset had no list_status")
                if (item.node.id in remoteIds) {
                    kept[item.node.id] = ListEntry(
                        remoteId = item.node.id,
                        chaptersRead = listStatus.numChaptersRead.toInt(),
                        totalChapters = item.node.numChapters,
                        status = if (listStatus.isRereading) MyAnimeList.REREADING else getStatus(listStatus.status),
                    )
                }
            }
            val next = page.paging.next
            if (next.isNullOrBlank()) return ListRead.Entries(kept)
            val nextOffset = next.toHttpUrlOrNull()?.queryParameter("offset")?.toIntOrNull()
                ?: (offset + page.data.size)
            if (nextOffset <= offset) {
                return ListRead.Unreadable("the next page's offset $nextOffset is not past $offset")
            }
            offset = nextOffset
        }
    }

    /** One page of [readListProgress], with the two answers it falls back on read apart. */
    private suspend fun listProgressPage(offset: Int, nsfw: Boolean): ListPageAnswer = try {
        ListPageAnswer.Page(getListProgressPage(offset, nsfw))
    } catch (e: HttpException) {
        if (e.code != 400) throw e
        ListPageAnswer.BadRequest
    } catch (_: SerializationException) {
        ListPageAnswer.Undecodable
    }

    private suspend fun getListProgressPage(offset: Int, nsfw: Boolean): MALListProgressPage {
        val urlBuilder = api("users", "@me", "mangalist").newBuilder()
            .addQueryParameter("fields", LIST_PROGRESS_FIELDS)
            .addQueryParameter("limit", LIST_PAGINATION_AMOUNT.toString())
        if (nsfw) {
            urlBuilder.addQueryParameter("nsfw", "true")
        }
        if (offset > 0) {
            urlBuilder.addQueryParameter("offset", offset.toString())
        }
        return authClient.newCall(GET(urlBuilder.build()))
            .awaitSuccess()
            .parseAs(json)
    }

    private sealed interface ListPageAnswer {
        data class Page(val page: MALListProgressPage) : ListPageAnswer
        data object BadRequest : ListPageAnswer
        data object Undecodable : ListPageAnswer
    }

    private fun parseMangaItem(listStatus: MALListItemStatus, track: Track): Track {
        val isRereading = listStatus.isRereading
        return track.copy(
            status = if (isRereading) MyAnimeList.REREADING else getStatus(listStatus.status),
            lastChapterRead = listStatus.numChaptersRead,
            score = listStatus.score.toDouble(),
            startDate = listStatus.startDate?.let { parseDate(it) } ?: track.startDate,
            finishDate = listStatus.finishDate?.let { parseDate(it) } ?: track.finishDate,
        )
    }

    private fun parseSearchItem(searchItem: MALManga): TrackSearchResult {
        return TrackSearchResult(
            trackerId = trackId,
            remoteId = searchItem.id,
            title = searchItem.title,
            summary = searchItem.synopsis,
            totalChapters = searchItem.numChapters,
            coverUrl = searchItem.covers?.large.orEmpty(),
            trackingUrl = "https://myanimelist.net/manga/${searchItem.id}",
            publishingStatus = searchItem.status.replace("_", " "),
            publishingType = searchItem.mediaType.replace("_", " "),
            startDate = searchItem.startDate ?: "",
            artists = searchItem.authors
                .filter { authorNode -> authorNode.role == "Art" }
                .mapNotNull { authorNode -> authorNode.node.getFullName() },
            authors = searchItem.authors
                // count all with "Story" or "Story & Art" as authors, like is done for library entries
                .filter { authorNode -> authorNode.role.contains("Story") }
                .mapNotNull { authorNode -> authorNode.node.getFullName() },
            score = searchItem.mean,
        )
    }

    private fun parseDate(isoDate: String): Long {
        val pattern = when (isoDate.length) {
            10 -> "yyyy-MM-dd"
            7 -> "yyyy-MM"
            4 -> "yyyy"
            else -> throw IllegalArgumentException("Unsupported date format: \"$isoDate\"")
        }
        return SimpleDateFormat(pattern, Locale.US).parse(isoDate)?.time ?: 0L
    }

    private fun convertToIsoDate(epochTime: Long): String? {
        if (epochTime == 0L) {
            return ""
        }
        return try {
            val outputDf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            outputDf.format(epochTime)
        } catch (_: Exception) {
            null
        }
    }

    private fun api(vararg segments: String): HttpUrl =
        endpoints().api.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun mangaUrl(id: Long): HttpUrl = api("manga", id.toString(), "my_list_status")

    /**
     * The OAuth `error` code of a refused token request, or `http_<status>` when the body names
     * none. Only a short lowercase code is taken, so no part of the body that could echo a request
     * value reaches the error list or the screen.
     */
    private fun oauthError(response: Response): String {
        val error = runCatching {
            (json.parseToJsonElement(response.body.string()) as? JsonObject)?.get("error") as? JsonPrimitive
        }.getOrNull()?.takeIf { it.isString }?.content
        return error?.takeIf { OAUTH_ERROR.matches(it) } ?: "http_${response.code}"
    }

    companion object {
        private const val SEARCH_FIELDS =
            "id,title,synopsis,num_chapters,mean,main_picture,status,media_type,start_date,authors{first_name,last_name}"

        private const val LIST_PAGINATION_AMOUNT = 250

        /** What [readListProgress] asks each entry for: its chapter count and the owner's progress. */
        private const val LIST_PROGRESS_FIELDS = "num_chapters,list_status"

        /** 400 pages of 250: a list longer than 100,000 entries is read one entry at a time instead. */
        private const val LIST_PROGRESS_MAX_PAGES = 400

        private val OAUTH_ERROR = Regex("[a-z_]{1,64}")

        /**
         * The refresh grant for [oauth], sent to [tokenUrl] with [clientId]. Upstream's form and its
         * bearer header; the interceptor sends it down its own chain.
         */
        fun refreshTokenRequest(tokenUrl: HttpUrl, clientId: String, oauth: MALOAuth): Request {
            val formBody: RequestBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("refresh_token", oauth.refreshToken)
                .add("grant_type", "refresh_token")
                .build()

            // Add the Authorization header manually as this particular
            // request is called by the interceptor itself so it doesn't reach
            // the part where the token is added automatically.
            val headers = Headers.Builder()
                .add("Authorization", "Bearer ${oauth.accessToken}")
                .build()

            return POST(tokenUrl.toString(), body = formBody, headers = headers)
        }
    }
}
