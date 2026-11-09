package com.shinsaikou.backup

import com.shinsaikou.source.model.UpdateStrategy
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCustomCover
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupMergeSettings
import eu.kanade.tachiyomi.data.backup.models.BackupMergedEntry
import eu.kanade.tachiyomi.data.backup.models.BackupMergedGroup
import eu.kanade.tachiyomi.data.backup.models.BackupMergedMember
import eu.kanade.tachiyomi.data.backup.models.BackupPageNote
import eu.kanade.tachiyomi.data.backup.models.BackupScanlatorPriority
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `tools/gate/upstream_backup_fields.json` is every serialized field of upstream v0.20.4's backup models,
 * read from its Kotlin sources by `tools/gate/upstream_backup_fields.py`; Saikou's vendored models are
 * what `:backup` reads and writes a `.tachibk` with. The JVM cannot load upstream's classes beside these
 * (they share every name), so this holds the two together through the file, in both directions: every
 * message reachable from [Backup]'s descriptor is in the file and every message in the file is
 * reachable, and each one's serial name, sealed parent and fields (number, whether the number is
 * annotated or positional, name, type, nullability, default) agree, in declaration order. The
 * defaults are read off the models themselves, by decoding an instance with only its required fields
 * and encoding it with every default written out, so a changed default fails here as well as a
 * changed number. A sealed subclass's class name is on the wire (the type string of a
 * `PreferenceValue`), which is why serial names are compared too.
 *
 * The one allowed difference is [EXTRA]: fields upstream does not have. Some are other readers' of
 * the format, which Saikou reads on import (R162 item 8, R163 item 4) and, for 602 and 800 to 805,
 * writes too; the rest are Saikou's own block (R165 item 3). They are left out of the comparison, and of the
 * walk, so the messages only they reach are not expected in the file, and held by tests of their own
 * to numbers upstream does not use.
 *
 * Phase 3 unit R2, part 1; the `IndexFieldNumbersTest` pattern.
 */
class UpstreamBackupFieldsTest {

    private val document: JsonObject = Json.parseToJsonElement(fieldsFile().readText()).jsonObject
    private val recorded: JsonObject = document["messages"]!!.jsonObject

    /** Every message the `.tachibk` reaches from [Backup], keyed by simple class name. */
    private val reachable: Map<String, Reached> = buildMap { walk(Backup.serializer().descriptor, null, this) }

    private class Reached(val descriptor: SerialDescriptor, val parent: String?)

    private fun walk(descriptor: SerialDescriptor, parent: String?, into: MutableMap<String, Reached>) {
        when (descriptor.kind) {
            StructureKind.CLASS, PolymorphicKind.SEALED -> {
                val name = simpleName(descriptor)
                if (name in into) return
                into[name] = Reached(descriptor, parent)
                if (descriptor.kind == PolymorphicKind.SEALED) {
                    // Element 1 of a sealed descriptor holds one element per subclass.
                    val subclasses = descriptor.getElementDescriptor(1)
                    for (index in 0 until subclasses.elementsCount) {
                        walk(subclasses.getElementDescriptor(index), name, into)
                    }
                } else {
                    for (index in 0 until descriptor.elementsCount) {
                        if (!isExtra(descriptor, index)) walk(descriptor.getElementDescriptor(index), null, into)
                    }
                }
            }
            StructureKind.LIST, StructureKind.MAP -> for (index in 0 until descriptor.elementsCount) {
                walk(descriptor.getElementDescriptor(index), null, into)
            }
            else -> Unit
        }
    }

    @Test
    fun `every message the file records is one a tachibk reaches, and the other way round`() {
        reachable.keys.sorted() shouldBe recorded.keys.sorted()
    }

    @Test
    fun `each message agrees with the file field by field, in declaration order`() {
        for ((name, reached) in reachable) {
            val expected = recorded[name]?.jsonObject ?: continue
            withClue("message $name") {
                describe(name, reached) shouldBe JsonObject(expected - "file")
            }
        }
    }

    @Test
    fun `every default as written is the default the model applies`() {
        for ((name, reached) in reachable) {
            val fields = recorded[name]?.jsonObject?.get("fields")?.jsonArray ?: continue
            val written = fields.map { it.jsonObject }.filter { it["default"] !is JsonNull }
            if (written.isEmpty()) continue
            val applied = defaults(reached.descriptor)
            for (field in written) {
                val fieldName = field["name"]!!.jsonPrimitive.content
                val text = field["default"]!!.jsonPrimitive.content
                withClue("$name.$fieldName = $text") {
                    sameValue(applied[fieldName]!!, writtenDefault(text)) shouldBe true
                }
            }
        }
    }

    @Test
    fun `UpdateStrategy's entries are in the order the file records, on the class and on the wire`() {
        val entries = document["enums"]!!.jsonObject["UpdateStrategy"]!!.jsonObject["entries"]!!.jsonArray
            .map { it.jsonPrimitive.content }

        UpdateStrategy.entries.map { it.name } shouldBe entries
        val wire = BackupManga.serializer().descriptor.let { it.getElementDescriptor(it.getElementIndex("updateStrategy")) }
        wire.elementNames.toList() shouldBe entries
    }

    @Test
    fun `the fields upstream does not have are optional and empty by default, at numbers upstream does not use`() {
        for ((message, fields) in EXTRA) {
            val descriptor = reachable.getValue(message).descriptor
            val upstreamNumbers = recorded.getValue(message).jsonObject["fields"]!!.jsonArray
                .map { it.jsonObject["number"]!!.jsonPrimitive.int }
            val applied = defaults(descriptor)
            for ((number, name) in fields) {
                withClue("$message.$name") {
                    val index = descriptor.getElementIndex(name)
                    val annotated = descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().single()
                    annotated.number shouldBe number
                    descriptor.isElementOptional(index) shouldBe true
                    applied[name] shouldBe when (name) {
                        in EXTRA_LISTS -> JsonArray(emptyList())
                        in EXTRA_ZEROS -> JsonPrimitive(0)
                        else -> JsonNull
                    }
                    upstreamNumbers.contains(number) shouldBe false
                }
            }
        }
        // Every field of a message the walk reaches that upstream lacks is one of them. A sealed
        // message's elements are its type and value, which the file does not record.
        for ((name, reached) in reachable) {
            if (reached.descriptor.kind == PolymorphicKind.SEALED) continue
            val fields = recorded[name]?.jsonObject?.get("fields")?.jsonArray ?: continue
            val upstreamNames = fields.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
            withClue("message $name") {
                (reached.descriptor.elementNames.toSet() - upstreamNames) shouldBe EXTRA[name].orEmpty().values.toSet()
            }
        }
    }

    @Test
    fun `Saikou's own fields are in the block 5000 to 5099, and the other readers' are theirs`() {
        val theirs = mapOf(
            600 to "mergedEntries",
            602 to "customStatus",
            604 to "mergeSettings",
            800 to "customTitle",
            801 to "customArtist",
            802 to "customAuthor",
            804 to "customDescription",
            805 to "customGenre",
        )
        for ((message, fields) in EXTRA) {
            for ((number, name) in fields) {
                val other = message == "BackupManga" && theirs[number] == name
                withClue("$message.$name") { (other || number in 5000..5099) shouldBe true }
            }
        }
    }

    @Test
    fun `Saikou's own messages keep their numbers`() {
        numbers(BackupMergedGroup.serializer().descriptor) shouldBe mapOf(
            "title" to 1,
            "mainSource" to 2,
            "mainUrl" to 3,
            "showAll" to 4,
            "members" to 5,
        )
        numbers(BackupMergedMember.serializer().descriptor) shouldBe
            mapOf("source" to 1, "url" to 2, "checkUpdates" to 3)
        numbers(BackupCustomCover.serializer().descriptor) shouldBe mapOf("source" to 1, "url" to 2, "image" to 3)
        numbers(BackupScanlatorPriority.serializer().descriptor) shouldBe mapOf("scanlator" to 1, "priority" to 2)
        numbers(BackupPageNote.serializer().descriptor) shouldBe mapOf("pageIndex" to 1, "note" to 2, "dateAdded" to 3)
    }

    @Test
    fun `TachiyomiSY's two messages keep the numbers its files write`() {
        numbers(BackupMergedEntry.serializer().descriptor) shouldBe mapOf(
            "isInfoManga" to 1,
            "fetchChapterUpdates" to 2,
            "chapterSortMode" to 3,
            "chapterPriority" to 4,
            "downloadChapters" to 5,
            "mangaUrl" to 7,
            "mangaSourceId" to 8,
        )
        numbers(BackupMergeSettings.serializer().descriptor) shouldBe mapOf(
            "chapterSortMode" to 1,
            "infoMangaUrl" to 2,
            "infoMangaSourceId" to 3,
        )
    }

    private fun numbers(descriptor: SerialDescriptor): Map<String, Int> =
        (0 until descriptor.elementsCount).associate { index ->
            descriptor.getElementName(index) to
                descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().single().number
        }

    private fun isExtra(descriptor: SerialDescriptor, index: Int): Boolean =
        EXTRA[simpleName(descriptor)]?.containsValue(descriptor.getElementName(index)) == true

    /** One message in the file's shape, less its file name, built from the descriptor. */
    private fun describe(name: String, reached: Reached): JsonObject {
        val descriptor = reached.descriptor
        val sealed = descriptor.kind == PolymorphicKind.SEALED
        return buildJsonObject {
            put("serialName", JsonPrimitive(descriptor.serialName.removeSuffix("?")))
            put("sealed", JsonPrimitive(sealed))
            put("extends", reached.parent?.let(::JsonPrimitive) ?: JsonNull)
            val fields = if (sealed) {
                emptyList()
            } else {
                (0 until descriptor.elementsCount)
                    .filterNot { isExtra(descriptor, it) }
                    .map { field(descriptor, it) }
            }
            put("fields", JsonArray(fields))
        }.also { check(name == simpleName(descriptor)) }
    }

    private fun field(descriptor: SerialDescriptor, index: Int): JsonObject {
        val annotated = descriptor.getElementAnnotations(index).filterIsInstance<ProtoNumber>().singleOrNull()
        val element = descriptor.getElementDescriptor(index)
        val recordedDefault = recorded[simpleName(descriptor)]?.jsonObject?.get("fields")?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == descriptor.getElementName(index) }
            ?.get("default")
        return buildJsonObject {
            put("number", JsonPrimitive(annotated?.number ?: (index + 1)))
            put("numberedBy", JsonPrimitive(if (annotated != null) "annotation" else "position"))
            put("name", JsonPrimitive(descriptor.getElementName(index)))
            put("type", JsonPrimitive(typeOf(element, topLevel = true)))
            put("nullable", JsonPrimitive(element.isNullable))
            // The descriptor says whether there is a default; what it is, the defaults test reads off
            // the model. A field the file says has one must be optional, and one it says has none must
            // be required, so the recorded text stands in only when the two agree.
            put(
                "default",
                when {
                    descriptor.isElementOptional(index) && recordedDefault != null && recordedDefault !is JsonNull ->
                        recordedDefault
                    descriptor.isElementOptional(index) -> JsonPrimitive("<optional, with no default in the file>")
                    else -> JsonNull
                },
            )
        }
    }

    private fun typeOf(descriptor: SerialDescriptor, topLevel: Boolean = false): String {
        val name = descriptor.serialName.removeSuffix("?")
        val type = when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> "Boolean"
            PrimitiveKind.BYTE -> "Byte"
            PrimitiveKind.CHAR -> "Char"
            PrimitiveKind.SHORT -> "Short"
            PrimitiveKind.INT -> "Int"
            PrimitiveKind.LONG -> "Long"
            PrimitiveKind.FLOAT -> "Float"
            PrimitiveKind.DOUBLE -> "Double"
            PrimitiveKind.STRING -> "String"
            StructureKind.LIST -> when (name) {
                "kotlin.ByteArray" -> "ByteArray"
                "kotlin.collections.LinkedHashSet", "kotlin.collections.HashSet" ->
                    "Set<${typeOf(descriptor.getElementDescriptor(0))}>"
                else -> "List<${typeOf(descriptor.getElementDescriptor(0))}>"
            }
            StructureKind.MAP ->
                "Map<${typeOf(descriptor.getElementDescriptor(0))}, ${typeOf(descriptor.getElementDescriptor(1))}>"
            else -> name.substringAfterLast('.')
        }
        return if (descriptor.isNullable && !topLevel) "$type?" else type
    }

    /**
     * The model's own default for each optional field: an instance decoded with a placeholder for
     * each required field, encoded again with every default written out.
     */
    @Suppress("UNCHECKED_CAST")
    private fun defaults(descriptor: SerialDescriptor): JsonObject {
        val serializer = serializer(Class.forName(descriptor.serialName)) as KSerializer<Any>
        val required = buildJsonObject {
            for (index in 0 until descriptor.elementsCount) {
                if (!descriptor.isElementOptional(index)) {
                    put(descriptor.getElementName(index), placeholder(descriptor.getElementDescriptor(index)))
                }
            }
        }
        val instance = Json.decodeFromJsonElement(serializer, required)
        return Json { encodeDefaults = true }.encodeToJsonElement(serializer, instance).jsonObject
    }

    private fun placeholder(descriptor: SerialDescriptor): JsonElement = when {
        descriptor.isNullable -> JsonNull
        descriptor.kind == PrimitiveKind.STRING -> JsonPrimitive("")
        descriptor.kind == PrimitiveKind.BOOLEAN -> JsonPrimitive(false)
        descriptor.kind is PrimitiveKind -> JsonPrimitive(0)
        descriptor.kind == StructureKind.LIST -> JsonArray(emptyList())
        else -> error("no placeholder for a required ${descriptor.serialName}; teach this test one")
    }

    /**
     * What a default as the reference tree writes it means, in the JSON the model encodes to. A
     * default this does not know fails the test by name rather than passing unread.
     */
    private fun writtenDefault(text: String): JsonElement = when {
        text == "null" -> JsonNull
        text == "true" || text == "false" -> JsonPrimitive(text.toBoolean())
        text.length >= 2 && text.startsWith('"') && text.endsWith('"') -> JsonPrimitive(text.substring(1, text.length - 1))
        NUMBER.matches(text) -> JsonPrimitive(text.trimEnd('F', 'f', 'L').toDouble())
        text == "emptyList()" || text == "emptySet()" -> JsonArray(emptyList())
        // Upstream's tachiyomi.core.common.extensions.JsonObjectEmptyBytes is byteArrayOf(0x7B, 0x7D), `{}`.
        text == "JsonObjectEmptyBytes" -> JsonArray(listOf(JsonPrimitive(0x7B), JsonPrimitive(0x7D)))
        ENUM_ENTRY.matches(text) -> JsonPrimitive(text.substringAfter('.'))
        else -> error("no reading of the default `$text`; teach this test what it means")
    }

    private fun sameValue(actual: JsonElement, expected: JsonElement): Boolean = when {
        // JsonNull is a JsonPrimitive too, so it is settled before the numeric reading below.
        actual is JsonNull || expected is JsonNull -> actual == expected
        actual is JsonPrimitive && expected is JsonPrimitive && !actual.isString && !expected.isString &&
            actual.booleanOrNull == null && expected.booleanOrNull == null ->
            actual.content.toDouble() == expected.content.toDouble()
        actual is JsonArray && expected is JsonArray ->
            actual.size == expected.size && actual.indices.all { sameValue(actual[it], expected[it]) }
        else -> actual == expected
    }

    private fun simpleName(descriptor: SerialDescriptor): String =
        descriptor.serialName.removeSuffix("?").substringAfterLast('.')

    private fun fieldsFile(): File {
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
        const val RELATIVE_PATH = "tools/gate/upstream_backup_fields.json"
        val NUMBER = Regex("""-?\d+(\.\d+)?[FfL]?""")
        val ENUM_ENTRY = Regex("""[A-Z]\w*\.[A-Z][A-Z0-9_]*""")

        /**
         * By message, the number and name of each field upstream does not have: TachiyomiSY's 600 and
         * 604, read on import only, its custom info (602 and 800 to 805), read and written, and
         * Saikou's own block.
         */
        val EXTRA = mapOf(
            "Backup" to mapOf(
                5000 to "mergedGroups",
                5001 to "customCovers",
            ),
            "BackupManga" to mapOf(
                600 to "mergedEntries",
                602 to "customStatus",
                604 to "mergeSettings",
                800 to "customTitle",
                801 to "customArtist",
                802 to "customAuthor",
                804 to "customDescription",
                805 to "customGenre",
                5000 to "manualPosition",
                5001 to "scanlatorPriority",
                5002 to "statusOverride",
            ),
            "BackupChapter" to mapOf(
                5000 to "volume",
                5001 to "pageNotes",
            ),
            "BackupCategory" to mapOf(
                5000 to "parentOrder",
            ),
        )

        /** The ones of [EXTRA] that default to an empty list. */
        val EXTRA_LISTS = setOf("mergedEntries", "mergedGroups", "customCovers", "scanlatorPriority", "pageNotes")

        /** The ones of [EXTRA] that default to 0; the rest default to null. */
        val EXTRA_ZEROS = setOf("manualPosition", "customStatus")
    }
}
