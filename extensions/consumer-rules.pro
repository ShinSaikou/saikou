-keep class eu.kanade.tachiyomi.** { *; }
-keep class tachiyomi.** { *; }
-keep class rx.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep class dev.mihon.injekt.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
# okhttp-zstd is in the extension-facing bundle, and its entry point is neither okhttp3 nor okio:
# `okhttp3.zstd.Zstd` delegates to `com.squareup.zstd.okio.OkioZstd`, which nothing the application
# itself calls, so R8 stripped it out of the shipped APK while keeping the two packages above. It
# was found by indexing the APK that ships rather than a Gradle classpath: 110 of the 320 sampled
# extensions name that class, and every one of them would have failed on its first zstd response.
-keep class com.squareup.zstd.** { *; }
-keep class org.jsoup.** { *; }
-keep class kotlinx.serialization.** { *; }
# InjektBootstrap registers xmlutil's XML for extensions, as upstream does. Extension bytecode names it
# by its full name in the Injekt type it asks for, so an obfuscated name would never match, and it
# calls whichever XML members it likes, which the application itself may not. ProtoBuf, the other
# serializer registered there, is covered by the kotlinx.serialization rule above.
-keep class nl.adaptivity.xmlutil.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlin.** { *; }
-keep class androidx.preference.** { *; }
-keep class app.cash.quickjs.** { *; }
-keep class com.github.zhanghai.quickjs.** { *; }
-dontwarn rx.internal.util.unsafe.**
