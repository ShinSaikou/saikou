package com.shinsaikou.domain.chapter

/**
 * A chapter's volume number, read from its name (phase 4 unit VG). Sources send no volume field
 * (`ChapterInfo` has none), so the name is the only input, as it is for the chapter number.
 *
 * The pattern is the volume half of upstream's `ChapterRecognition.unwanted`
 * (`\b(?:v|ver|vol|version|volume|season|s)[^a-z]?[0-9]+`), which upstream only strips before it reads
 * the chapter number: the same tokens and the same word boundary, so a number glued to another
 * token ("rev2", "1v1") is not a volume, now capturing the number. Two changes make the capture
 * right: the gap after the token may not be a digit, or "vol12" would capture "2", and it may be
 * two characters, so "Vol. 2" (a dot and a space, the commonest form) is read. "s" and "season"
 * stay out: a season is not a volume, and "s" takes the possessive in "The King's 2 Swords". The
 * corpus in `VolumeRecognitionTest` is what decided both.
 *
 * `ChapterRecognition.kt` is vendored unmodified and stays so (challenge c2 g3 D item 4). Both
 * devices must run the same pattern: a volume is a synced chapter value, so two patterns would
 * disagree on a value and each library update would write its own back.
 */
object VolumeRecognition {

    private val volume = Regex("""\b(?:volume|version|vol|ver|v)[^a-z0-9]{0,2}([0-9]+)""")

    /**
     * The first volume marker's number in [chapterName], after [mangaTitle] is removed from it as
     * `ChapterRecognition.parseChapterNumber` removes it; null for a name with no marker, which is
     * "not recognised" and not volume 0.
     */
    fun parseVolumeNumber(mangaTitle: String, chapterName: String): Double? {
        val name = chapterName.lowercase().replace(mangaTitle.lowercase(), "").trim()
        return volume.find(name)?.groupValues?.get(1)?.toDouble()
    }
}
