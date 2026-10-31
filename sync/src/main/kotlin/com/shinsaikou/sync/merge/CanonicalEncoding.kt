package com.shinsaikou.sync.merge

import com.shinsaikou.source.model.UpdateStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one byte encoding of a merged value that breaks every tie (Q6): when two versions or two
 * clocks are equal and the values differ, the value whose encoding sorts higher is kept, on every
 * device, whichever side is local.
 *
 * The encoding is injective and depends on nothing but the value: every component is tagged or
 * length-prefixed, a string is written as its UTF-16 code units (so no charset and no replacement of
 * a lone surrogate enters), a double as its canonical bit pattern, and a map in the order of its
 * keys. Two values encode the same exactly when they are equal, so the order it induces is total.
 * Changing any of it changes which value a tie keeps, and devices on two builds would then disagree;
 * treat it as part of the sync format.
 */
internal class CanonicalWriter {
    private var bytes = ByteArray(64)
    private var size = 0

    fun byte(value: Int): CanonicalWriter = apply {
        if (size == bytes.size) bytes = bytes.copyOf(size * 2)
        bytes[size++] = value.toByte()
    }

    fun long(value: Long): CanonicalWriter = apply {
        for (shift in 56 downTo 0 step 8) byte((value ushr shift).toInt())
    }

    /** `toBits`, not `toRawBits`: every NaN is one value, as it is to a data class's `equals`. */
    fun double(value: Double): CanonicalWriter = long(value.toBits())

    fun boolean(value: Boolean): CanonicalWriter = byte(if (value) 1 else 0)

    fun string(value: String): CanonicalWriter = apply {
        long(value.length.toLong())
        for (char in value) {
            byte(char.code ushr 8)
            byte(char.code)
        }
    }

    fun nullableLong(value: Long?): CanonicalWriter = if (value == null) byte(0) else byte(1).long(value)

    fun nullableDouble(value: Double?): CanonicalWriter = if (value == null) byte(0) else byte(1).double(value)

    fun nullableString(value: String?): CanonicalWriter = if (value == null) byte(0) else byte(1).string(value)

    fun stringList(value: List<String>?): CanonicalWriter = apply {
        if (value == null) {
            byte(0)
        } else {
            byte(1).long(value.size.toLong())
            value.forEach(::string)
        }
    }

    fun updateStrategy(value: UpdateStrategy): CanonicalWriter = string(value.name)

    fun json(value: JsonElement): CanonicalWriter = apply {
        when (value) {
            JsonNull -> byte(0)
            is JsonPrimitive -> byte(1).boolean(value.isString).string(value.content)
            is JsonArray -> {
                byte(2).long(value.size.toLong())
                value.forEach(::json)
            }
            is JsonObject -> {
                byte(3).long(value.size.toLong())
                value.entries.sortedBy { it.key }.forEach { (key, element) -> string(key).json(element) }
            }
        }
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}

/** Unsigned, byte by byte; a proper prefix sorts first. */
internal fun compareEncoded(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val order = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (order != 0) return order
    }
    return a.size.compareTo(b.size)
}

/** The one of two values whose encoding sorts higher; either when they are equal. */
internal inline fun <T> higherEncoding(a: T, b: T, encode: CanonicalWriter.(T) -> Unit): T {
    if (a == b) return a
    val encodedA = CanonicalWriter().apply { encode(a) }.toByteArray()
    val encodedB = CanonicalWriter().apply { encode(b) }.toByteArray()
    return if (compareEncoded(encodedA, encodedB) >= 0) a else b
}
