package com.shinsaikou.reader.ui

import com.shinsaikou.domain.manga.model.ReadingMode
import eu.kanade.presentation.reader.components.ChapterNavigatorType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChapterNavigatorTypeTest {

    @Test
    fun `a listed mode puts the slider upright on the chosen side`() {
        val modes = setOf(ReadingMode.WEBTOON.name)
        chapterNavigatorType(ReadingMode.WEBTOON, modes, onLeft = false) shouldBe ChapterNavigatorType.VERTICAL_RIGHT
        chapterNavigatorType(ReadingMode.WEBTOON, modes, onLeft = true) shouldBe ChapterNavigatorType.VERTICAL_LEFT
    }

    @Test
    fun `an unlisted mode keeps the slider across, right to left for that mode`() {
        val modes = setOf(ReadingMode.WEBTOON.name)
        chapterNavigatorType(ReadingMode.RIGHT_TO_LEFT, modes, onLeft = false) shouldBe
            ChapterNavigatorType.HORIZONTAL_RTL
        chapterNavigatorType(ReadingMode.LEFT_TO_RIGHT, modes, onLeft = false) shouldBe
            ChapterNavigatorType.HORIZONTAL_LTR
        chapterNavigatorType(ReadingMode.WEBTOON, emptySet(), onLeft = false) shouldBe
            ChapterNavigatorType.HORIZONTAL_LTR
    }
}
