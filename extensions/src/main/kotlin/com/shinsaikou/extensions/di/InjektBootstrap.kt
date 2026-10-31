package com.shinsaikou.extensions.di

import android.app.Application
import android.content.Context
import dev.mihon.injekt.patchInjekt
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.logcat
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Registers the singletons extension bytecode resolves through Injekt.
 *
 * This is the one place in the tree where `Injekt.get()` survives: the vendored `HttpSource` and
 * `ConfigurableSource` bodies and roughly five sixths of published extensions call it by name, so
 * a real registry has to exist for the life of the process. Saikou's own code never reads from it;
 * it only writes these eight entries, which are the eight upstream gives extensions. Seven are the
 * instances the Metro graph hands out; [XML] is built here (see [install]).
 *
 * Called once from the application's `onCreate`, on the main thread, before any extension loads.
 * It only stores references, so it does no I/O.
 */
@SingleIn(AppScope::class)
@Inject
class InjektBootstrap(
    private val application: Application,
    private val networkHelper: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
    private val javaScriptEngine: JavaScriptEngine,
    private val preferenceStore: PreferenceStore,
) {
    private val installed = AtomicBoolean(false)

    /** Idempotent: the second call is a no-op, so a restarted activity cannot double register. */
    fun install() {
        if (!installed.compareAndSet(false, true)) return
        patchInjekt()
        // Context and Application both resolve to the application: ConfigurableSource asks for
        // Application, AppInfo and some extensions ask for Context.
        Injekt.addSingleton<Context>(application)
        Injekt.addSingleton<Application>(application)
        Injekt.addSingleton<NetworkHelper>(networkHelper)
        Injekt.addSingleton<Json>(json)
        // The graph's ProtoBuf is the companion default, which is what upstream registers. Extensions
        // that read it do so in a file-level initializer, so a missing entry fails the class on its
        // first use, long after the load succeeded.
        Injekt.addSingleton<ProtoBuf>(protoBuf)
        // A factory, as upstream's is: no extension in the compatibility sample asks for XML, so the
        // xmlutil classes are not loaded until one does.
        Injekt.addSingletonFactory<XML> { extensionXml() }
        Injekt.addSingleton<JavaScriptEngine>(javaScriptEngine)
        Injekt.addSingleton<PreferenceStore>(preferenceStore)
        logcat { REGISTERED_LOG_LINE }
    }

    /** The loader refuses to load anything until this is true. */
    val isInstalled: Boolean get() = installed.get()

    companion object {
        /** Logged once [install] has registered every entry; names each registered type. */
        const val REGISTERED_LOG_LINE =
            "Injekt singletons registered: Context, Application, NetworkHelper, Json, ProtoBuf, XML, " +
                "JavaScriptEngine, PreferenceStore"

        /**
         * Upstream v0.20.4's `AppModule` configuration, field for field. `:core:metadata`'s
         * `ComicInfoXml` builds its own instance the same way; it is not shared because the module
         * graph (contract 01, section 1.2) keeps `:core:metadata` out of `:extensions`.
         */
        private fun extensionXml(): XML = XML.v1 {
            policy {
                ignoreUnknownChildren()
                autoPolymorphic = true
            }
            xmlDeclMode = XmlDeclMode.Charset
            xmlVersion = XmlVersion.XML10
            setIndent(2)
        }
    }
}
