package com.shinsaikou.domain.chapter

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The volume parser (phase 4 unit VG) over `volume-corpus.tsv`, real-looking chapter names in the
 * forms sources send: each form of a volume marker, names with none, a number glued to another token,
 * and seasons. The corpus is what decided that "s" and "season" are not volume tokens.
 */
class VolumeRecognitionTest {

    private data class Row(val title: String, val name: String, val volume: Double?)

    private val corpus: List<Row> = javaClass.getResourceAsStream("volume-corpus.tsv")!!
        .bufferedReader()
        .readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val (title, name, volume) = line.split('\t')
            Row(title, name, volume.takeUnless { it == "-" }?.toDouble())
        }

    @Test
    fun `every corpus name reads the volume the corpus gives it`() {
        val wrong = corpus.mapNotNull { row ->
            val parsed = VolumeRecognition.parseVolumeNumber(row.title, row.name)
            "${row.name}: expected ${row.volume}, parsed $parsed".takeIf { parsed != row.volume }
        }

        wrong.shouldBeEmpty()
    }

    /** The share the 3,000-entry push count in `:sync` takes from this file; a changed corpus says so here. */
    @Test
    fun `the corpus holds 49 names, 27 of them with a volume`() {
        corpus.size shouldBe 49
        corpus.count { it.volume != null } shouldBe 27
    }

    @Test
    fun `a name with no marker is not recognised, which is not volume 0`() {
        VolumeRecognition.parseVolumeNumber("Glass Orchard", "Chapter 12").shouldBeNull()
        VolumeRecognition.parseVolumeNumber("Glass Orchard", "Vol.0 Ch.0: Prologue") shouldBe 0.0
    }

    @Test
    fun `a number glued to another token and a season are not volumes`() {
        listOf("Chapter 7 (rev2)", "Chapter 101: 1v1", "S2 Episode 10", "s2 ep. 11", "Season 2 Episode 12").forEach {
            VolumeRecognition.parseVolumeNumber("Salt Bridge", it).shouldBeNull()
        }
    }

    @Test
    fun `the whole number is captured, after a gap of up to two characters`() {
        VolumeRecognition.parseVolumeNumber("Glass Orchard", "vol12 ch100") shouldBe 12.0
        VolumeRecognition.parseVolumeNumber("Glass Orchard", "Vol. 12 Ch. 100") shouldBe 12.0
        VolumeRecognition.parseVolumeNumber("Glass Orchard", "Vol.012 Ch.100") shouldBe 12.0
    }

    @Test
    fun `the first marker wins and the title's own marker is not one`() {
        VolumeRecognition.parseVolumeNumber("Tin Harbour", "Vol.3 Ch.12 v2") shouldBe 3.0
        VolumeRecognition.parseVolumeNumber("Moon Shelf Vol 9", "Moon Shelf Vol 9 Chapter 3").shouldBeNull()
    }
}
