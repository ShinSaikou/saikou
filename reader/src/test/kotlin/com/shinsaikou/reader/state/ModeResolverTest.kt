package com.shinsaikou.reader.state

import android.content.pm.ActivityInfo
import com.shinsaikou.domain.manga.model.ReaderOrientation
import com.shinsaikou.domain.manga.model.ReadingMode
import com.shinsaikou.reader.state.ModeResolver.toActivityOrientation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModeResolverTest {

    // ---- Reading mode ----

    @Test
    fun `the per-manga override wins over the default`() {
        val manga = manga(viewerFlags = ReadingMode.WEBTOON.flagValue.toLong())
        ModeResolver.readingMode(manga, ReadingMode.LEFT_TO_RIGHT.flagValue) shouldBe ReadingMode.WEBTOON
    }

    @Test
    fun `no override falls back to the default preference`() {
        ModeResolver.readingMode(manga(), ReadingMode.VERTICAL.flagValue) shouldBe ReadingMode.VERTICAL
    }

    @Test
    fun `neither an override nor a usable default gives upstream's right to left`() {
        ModeResolver.readingMode(manga(), ReadingMode.DEFAULT.flagValue) shouldBe ReadingMode.RIGHT_TO_LEFT
    }

    @Test
    fun `a restored preference outside the enum falls back to right to left`() {
        ModeResolver.readingMode(manga(), OUT_OF_RANGE) shouldBe ReadingMode.RIGHT_TO_LEFT
    }

    // ---- Orientation ----

    @Test
    fun `the per-manga orientation wins over the default`() {
        val manga = manga(viewerFlags = ReaderOrientation.LOCKED_LANDSCAPE.flagValue.toLong())
        ModeResolver.orientation(manga, ReaderOrientation.PORTRAIT.flagValue) shouldBe
            ReaderOrientation.LOCKED_LANDSCAPE
    }

    @Test
    fun `no orientation override falls back to the default preference`() {
        ModeResolver.orientation(manga(), ReaderOrientation.PORTRAIT.flagValue) shouldBe ReaderOrientation.PORTRAIT
    }

    @Test
    fun `neither an override nor a usable default gives free`() {
        ModeResolver.orientation(manga(), ReaderOrientation.DEFAULT.flagValue) shouldBe ReaderOrientation.FREE
        ModeResolver.orientation(manga(), OUT_OF_RANGE) shouldBe ReaderOrientation.FREE
    }

    @Test
    fun `the two halves of viewer_flags do not interfere`() {
        val flags = (ReadingMode.WEBTOON.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue).toLong()
        val manga = manga(viewerFlags = flags)
        ModeResolver.readingMode(manga, ReadingMode.DEFAULT.flagValue) shouldBe ReadingMode.WEBTOON
        ModeResolver.orientation(manga, ReaderOrientation.DEFAULT.flagValue) shouldBe ReaderOrientation.LOCKED_PORTRAIT
    }

    // ---- The platform mapping ----

    @Test
    fun `every orientation maps to its activity constant`() {
        ReaderOrientation.DEFAULT.toActivityOrientation() shouldBe ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ReaderOrientation.FREE.toActivityOrientation() shouldBe ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ReaderOrientation.PORTRAIT.toActivityOrientation() shouldBe ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        ReaderOrientation.LANDSCAPE.toActivityOrientation() shouldBe ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ReaderOrientation.LOCKED_PORTRAIT.toActivityOrientation() shouldBe ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ReaderOrientation.LOCKED_LANDSCAPE.toActivityOrientation() shouldBe ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        ReaderOrientation.REVERSE_PORTRAIT.toActivityOrientation() shouldBe
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    }

    @Test
    fun `the mapping covers all seven cases`() {
        ReaderOrientation.entries.size shouldBe 7
        ReaderOrientation.entries.forEach { it.toActivityOrientation() }
    }

    private companion object {
        /** No enum case carries this; it stands for a value restored from someone else's backup. */
        const val OUT_OF_RANGE = 0x07
    }
}
