package com.shinsaikou.extensions.repo

import io.kotest.matchers.shouldBe
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `tools/compat/index_fields.json` is what the Python compat job parses the index with; this file's
 * `@ProtoNumber` annotations are what the app parses it with. Nothing links the two, so this test
 * does: every message in the JSON must have exactly the fields and numbers the Kotlin model
 * declares, in both directions.
 */
class IndexFieldNumbersTest {

    private val fields: JsonObject = Json.parseToJsonElement(indexFieldsFile().readText())
        .jsonObject["messages"]!!.jsonObject

    private fun protoNumbers(descriptor: SerialDescriptor): Map<String, Int> =
        (0 until descriptor.elementsCount).associate { index ->
            val number = descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().single().number
            descriptor.getElementName(index) to number
        }

    private fun expected(message: String): Map<String, Int> =
        fields[message]!!.jsonObject.entries.associate { (name, value) -> name to value.jsonPrimitive.int }

    private fun assertAgrees(message: String, descriptor: SerialDescriptor) {
        protoNumbers(descriptor) shouldBe expected(message)
    }

    @Test
    fun `the Kotlin index model agrees with the compat job's field numbers`() {
        assertAgrees("Index", IndexDocument.serializer().descriptor)
        assertAgrees("Contact", IndexDocument.Contact.serializer().descriptor)
        assertAgrees("ExtensionList", IndexDocument.ExtensionList.serializer().descriptor)
        assertAgrees("Extension", IndexDocument.Extension.serializer().descriptor)
        assertAgrees("Resources", IndexDocument.Resources.serializer().descriptor)
        assertAgrees("Source", IndexDocument.Source.serializer().descriptor)
    }

    @Test
    fun `the catalog's jar url keeps field 501`() {
        protoNumbers(IndexDocument.Resources.serializer().descriptor)["jarUrl"] shouldBe 501
    }

    @Test
    fun `field 6 of Source stays unused on both sides`() {
        val numbers = protoNumbers(IndexDocument.Source.serializer().descriptor).values
        numbers.contains(6) shouldBe false
        expected("Source").values.contains(6) shouldBe false
    }

    @Test
    fun `the content warning ordinals match the proto enum values`() {
        val declared = Json.parseToJsonElement(indexFieldsFile().readText())
            .jsonObject["enums"]!!.jsonObject["ContentWarning"]!!.jsonObject
            .entries.associate { (name, value) -> name to value.jsonPrimitive.int }

        IndexDocument.ContentWarning.entries.associate { "CONTENT_WARNING_${it.name}" to it.ordinal } shouldBe declared
    }

    private fun indexFieldsFile(): File {
        val workingDir = requireNotNull(System.getProperty("user.dir")) { "no working directory" }
        var dir: File? = File(workingDir).absoluteFile
        while (dir != null) {
            val candidate = File(dir, RELATIVE_PATH)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("$RELATIVE_PATH not found above $workingDir")
    }

    private companion object {
        const val RELATIVE_PATH = "tools/compat/index_fields.json"
    }
}
