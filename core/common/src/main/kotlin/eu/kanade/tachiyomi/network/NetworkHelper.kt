// Vendored from upstream v0.20.4: core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt (modified: yes) constructor takes the solve coordinator and the user messages the interceptor needs; verbose logging redacts credentials as upstream v0.21.3 8780f1ca8 does, always rather than behind a setting, with more credential headers and cookies too, which Saikou never logs; adds the custom DNS over HTTPS choice, and the Experimental switch that trusts user-installed CAs for the source client and the challenge WebView
package eu.kanade.tachiyomi.network

import android.content.Context
import com.shinsaikou.core.common.network.CloudflareSolveCoordinator
import com.shinsaikou.core.common.network.NetworkUserMessages
import com.shinsaikou.core.common.network.PREF_DOH_CUSTOM
import com.shinsaikou.core.common.network.dohCustom
import com.shinsaikou.core.common.network.trustUserCertificates
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class NetworkHelper(
    private val context: Context,
    private val preferences: NetworkPreferences,
    private val cloudflareSolveCoordinator: CloudflareSolveCoordinator,
    private val networkUserMessages: NetworkUserMessages,
) {

    val cookieJar = AndroidCookieJar()

    /**
     * Whether this process's source client trusts the CAs the owner installed (Settings >
     * Experimental, amends contract 08 section 6.5), read once like the rest of the client. Every
     * client derived from [client] inherits that trust, so one that carries credentials or installs
     * code takes system trust back when this is true.
     */
    val trustsUserCertificates: Boolean = preferences.trustUserCertificates.get()

    private val clientBuilder: OkHttpClient.Builder = run {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30.seconds)
            .readTimeout(30.seconds)
            .callTimeout(2.minutes)
            .cache(
                Cache(
                    directory = File(context.cacheDir, "network_cache"),
                    maxSize = 5L * 1024 * 1024, // 5 MiB
                ),
            )
            .addInterceptor(UncaughtExceptionInterceptor())
            .addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

        if (preferences.verboseLogging.get()) {
            builder.addNetworkInterceptor(verboseLoggingInterceptor())
        }

        // Ahead of DNS over HTTPS, whose resolver client is built from this builder: a network that
        // inspects secure connections inspects the lookups too.
        if (trustsUserCertificates) {
            builder.trustUserCertificates()
        }

        when (preferences.dohProvider.get()) {
            PREF_DOH_CLOUDFLARE -> builder.dohCloudflare()
            PREF_DOH_GOOGLE -> builder.dohGoogle()
            PREF_DOH_ADGUARD -> builder.dohAdGuard()
            PREF_DOH_QUAD9 -> builder.dohQuad9()
            PREF_DOH_ALIDNS -> builder.dohAliDNS()
            PREF_DOH_DNSPOD -> builder.dohDNSPod()
            PREF_DOH_360 -> builder.doh360()
            PREF_DOH_QUAD101 -> builder.dohQuad101()
            PREF_DOH_MULLVAD -> builder.dohMullvad()
            PREF_DOH_CONTROLD -> builder.dohControlD()
            PREF_DOH_NJALLA -> builder.dohNajalla()
            PREF_DOH_SHECAN -> builder.dohShecan()
            PREF_DOH_CUSTOM -> builder.dohCustom(preferences.dohProviderCustom.get())
            else -> builder
        }
    }

    val client = clientBuilder
        .addInterceptor(
            CloudflareInterceptor(
                context,
                cookieJar,
                ::defaultUserAgentProvider,
                cloudflareSolveCoordinator,
                networkUserMessages,
                trustsUserCertificates,
            ),
        )
        .build()

    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient = client

    fun defaultUserAgentProvider() = preferences.defaultUserAgent.get().trim()
}

/**
 * The header log behind the verbose logging setting. A log is shared with bug reports, so it never
 * carries a credential or a cookie; this sits at the network level, where the cookie jar has
 * already added them.
 */
internal fun verboseLoggingInterceptor(
    logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT,
): HttpLoggingInterceptor = HttpLoggingInterceptor(logger).apply {
    level = HttpLoggingInterceptor.Level.HEADERS
    REDACTED_HEADERS.forEach(::redactHeader)
}

/** Upstream's two, the cookies, and the other credential headers a source may send. */
private val REDACTED_HEADERS = listOf(
    "Authorization",
    "Auth",
    "Cookie",
    "Set-Cookie",
    "Proxy-Authorization",
    "X-Api-Key",
    "X-Auth-Token",
)
