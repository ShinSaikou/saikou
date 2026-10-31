package com.shinsaikou.domain.library.search

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * [containsIgnoringCase] answers as `contains(other, ignoreCase = true)` does (phase 5 unit SC4,
 * item 5): on generated pairs over an alphabet that mixes ASCII with the characters whose case rules
 * are not ASCII's (dotted and dotless i, the Kelvin sign, the long s, final sigma, sharp s, accented
 * letters, a surrogate pair), with needles cut from the haystack and their case flipped so about half
 * the pairs match.
 */
class ContainsIgnoringCaseTest {

    @Test
    fun `every generated pair gets the standard answer`() {
        val random = Random(SEED)
        var matched = 0
        val wrong = (1..PAIRS).mapNotNull {
            val haystack = text(random, random.nextInt(0, 40))
            val needle = if (random.nextBoolean() && haystack.isNotEmpty()) {
                val from = random.nextInt(haystack.length)
                flipCase(random, haystack.substring(from, random.nextInt(from, haystack.length) + 1))
            } else {
                text(random, random.nextInt(0, 4))
            }
            val expected = haystack.contains(needle, ignoreCase = true)
            if (expected) matched++
            if (haystack.containsIgnoringCase(needle) == expected) null else "\"$haystack\" / \"$needle\": expected $expected"
        }
        wrong.shouldBeEmpty()
        // Both answers are well represented, so neither side of the comparison went untested.
        (matched in PAIRS / 4..PAIRS * 3 / 4) shouldBe true
    }

    @Test
    fun `the non-ASCII case rules are the platform's`() {
        listOf(
            "Kırmızı" to "KIRMIZI", "Kırmızı" to "kirmizi", "İstanbul" to "istanbul", "Kelvin" to "kelvin",
            "Masſ" to "MASS", "ΟΔΥΣΣΕΥΣ" to "οδυσσευς", "Straße" to "STRASSE", "Café" to "CAFÉ",
            "𐐀" to "𐐨", "" to "", "a" to "", "" to "a",
        ).forEach { (haystack, needle) ->
            haystack.containsIgnoringCase(needle) shouldBe haystack.contains(needle, ignoreCase = true)
        }
    }

    private fun text(random: Random, length: Int): String =
        buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.size)]) } }

    private fun flipCase(random: Random, text: String): String = text.map { char ->
        when {
            !random.nextBoolean() -> char.toString()
            char.isUpperCase() -> char.lowercase()
            else -> char.uppercase()
        }
    }.joinToString("")

    private companion object {
        const val SEED = 20_261_011
        const val PAIRS = 20_000

        val ALPHABET = listOf(
            "a", "b", "e", "i", "k", "s", "z", "A", "B", "E", "I", "K", "S", "Z", "0", "7", " ", "-", "@", "[", "`", "{",
            "ı", "İ", "K", "ſ", "ς", "σ", "Σ", "ß", "é", "É", "𐐀", "𐐨",
        )
    }
}
