package com.shinsaikou.sync.merge

import com.shinsaikou.sync.MergeArbs
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * The tie-break encoding is part of the sync format: two builds that encode one value differently
 * keep different values on a tie. These cases pin its bytes and check it agrees with equality.
 */
class CanonicalEncodingTest {

    private fun bytes(write: CanonicalWriter.() -> Unit) = CanonicalWriter().apply(write).toByteArray()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `the bytes of each component are fixed`() {
        hex(bytes { long(1L) }) shouldBe "0000000000000001"
        hex(bytes { long(-1L) }) shouldBe "ffffffffffffffff"
        hex(bytes { boolean(true).boolean(false) }) shouldBe "0100"
        hex(bytes { string("ab") }) shouldBe "000000000000000200610062"
        hex(bytes { nullableString(null).nullableLong(2L) }) shouldBe "00010000000000000002"
        hex(bytes { double(1.0) }) shouldBe "3ff0000000000000"
        hex(bytes { double(-0.0) }) shouldBe "8000000000000000"
        hex(bytes { json(JsonObject(mapOf("b" to JsonPrimitive(1), "a" to JsonPrimitive("x")))) }) shouldBe
            "030000000000000002" + "0000000000000001" + "0061" + "01" + "01" + "0000000000000001" + "0078" +
            "0000000000000001" + "0062" + "01" + "00" + "0000000000000001" + "0031"
    }

    @Test
    fun `a string is written as its code units, so no charset merges two strings`() {
        val loneHigh = "\uD800"
        val loneLow = "\uDC00"

        (compareEncoded(bytes { string(loneHigh) }, bytes { string(loneLow) }) == 0) shouldBe false
    }

    @Test
    fun `two strings encode alike exactly when they are equal`() = runTest {
        checkAll(PropTestConfig(iterations = 2_000), Arb.string(0..4), Arb.string(0..4)) { a, b ->
            (compareEncoded(bytes { string(a) }, bytes { string(b) }) == 0) shouldBe (a == b)
        }
    }

    @Test
    fun `two memos encode alike exactly when they are equal, whatever their key order`() = runTest {
        checkAll(PropTestConfig(iterations = 500), MergeArbs.memo, MergeArbs.memo) { a, b ->
            (compareEncoded(bytes { json(a) }, bytes { json(b) }) == 0) shouldBe (a == b)
        }
        val one = JsonObject(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
        val other = JsonObject(mapOf("b" to JsonPrimitive(2), "a" to JsonPrimitive(1)))
        hex(bytes { json(one) }) shouldBe hex(bytes { json(other) })
    }

    @Test
    fun `the higher encoding is kept whichever value comes first`() {
        higherEncoding("a", "b") { string(it) } shouldBe "b"
        higherEncoding("b", "a") { string(it) } shouldBe "b"
        higherEncoding(1.0, -0.0) { double(it) } shouldBe -0.0
    }

    @Test
    fun `a proper prefix sorts first`() {
        (compareEncoded(byteArrayOf(1), byteArrayOf(1, 0)) < 0) shouldBe true
        (compareEncoded(byteArrayOf(0x7f), byteArrayOf(0x80.toByte())) < 0) shouldBe true
    }
}
