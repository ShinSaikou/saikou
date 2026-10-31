package com.shinsaikou.extensions.di

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.xmlStreaming
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class InjektBootstrapTest {

    @Serializable
    private class Inner(val value: String)

    @Serializable
    private class Doc(val inner: Inner)

    private val application = mockk<Application>(relaxed = true)
    private val networkHelper = mockk<NetworkHelper>(relaxed = true)
    private val json = Json {}
    private val protoBuf = ProtoBuf
    private val javaScriptEngine = mockk<JavaScriptEngine>(relaxed = true)
    private val preferenceStore = mockk<PreferenceStore>(relaxed = true)

    private fun bootstrap() = InjektBootstrap(
        application = application,
        networkHelper = networkHelper,
        json = json,
        protoBuf = protoBuf,
        javaScriptEngine = javaScriptEngine,
        preferenceStore = preferenceStore,
    )

    private fun installed(): InjektBootstrap = bootstrap().also { it.install() }

    @Test
    fun `install registers the injected ProtoBuf instance`() {
        installed()

        (Injekt.get<ProtoBuf>() === protoBuf) shouldBe true
    }

    @Test
    fun `install registers XML as a singleton factory`() {
        installed()

        val first = Injekt.get<XML>()
        val second = Injekt.get<XML>()

        (first === second) shouldBe true
    }

    @Test
    fun `XML ignores unknown child elements`() {
        installed()
        val xml = Injekt.get<XML>()

        // The generic reader avoids the Android pull parser, which android.jar stubs out on the JVM.
        val reader = xmlStreaming.newGenericReader("<Doc><Unknown a=\"b\" /><Inner value=\"a\" /></Doc>")
        val doc = xml.decodeFromReader(Doc.serializer(), reader)

        doc.inner.value shouldBe "a"
    }

    @Test
    fun `XML writes a UTF-8 version 1_0 declaration and indents nested elements by two spaces`() {
        installed()
        val xml = Injekt.get<XML>()

        val out = xml.encodeToString(Doc.serializer(), Doc(Inner("a"))).lines()

        out[0] shouldBe "<?xml version='1.0' encoding='UTF-8' ?>"
        out[1] shouldBe "<Doc>"
        out[2] shouldBe "  <Inner value=\"a\" />"
    }

    @Test
    fun `install registers every entry with the instances passed in`() {
        installed()

        (Injekt.get<Context>() === application) shouldBe true
        (Injekt.get<Application>() === application) shouldBe true
        (Injekt.get<NetworkHelper>() === networkHelper) shouldBe true
        (Injekt.get<Json>() === json) shouldBe true
        (Injekt.get<JavaScriptEngine>() === javaScriptEngine) shouldBe true
        (Injekt.get<PreferenceStore>() === preferenceStore) shouldBe true
    }

    @Test
    fun `registered log line names each of the eight types`() {
        val names = listOf(
            "Context", "Application", "NetworkHelper", "Json",
            "ProtoBuf", "XML", "JavaScriptEngine", "PreferenceStore",
        )

        InjektBootstrap.REGISTERED_LOG_LINE.substringAfter(": ").split(", ") shouldBe names
    }

    @Test
    fun `second install is a no-op that keeps ProtoBuf and XML registered`() {
        val bootstrap = installed()
        val xmlBefore = Injekt.get<XML>()

        bootstrap.install()

        bootstrap.isInstalled shouldBe true
        (Injekt.get<ProtoBuf>() === protoBuf) shouldBe true
        (Injekt.get<XML>() === xmlBefore) shouldBe true
    }
}
