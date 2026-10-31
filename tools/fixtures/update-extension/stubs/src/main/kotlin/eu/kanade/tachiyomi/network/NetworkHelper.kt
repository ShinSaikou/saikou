// Fresh: a compile-only stub of the host extension ABI for the library update fixture, never packaged.
package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

class NetworkHelper private constructor() {
    val client: OkHttpClient get() = throw RuntimeException("Stub!")
}
