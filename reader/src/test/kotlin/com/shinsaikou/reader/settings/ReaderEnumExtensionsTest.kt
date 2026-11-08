package com.shinsaikou.reader.settings

import com.shinsaikou.domain.preferences.FlashColor
import com.shinsaikou.domain.preferences.ReaderHideThreshold
import com.shinsaikou.domain.preferences.TappingInvertMode
import com.shinsaikou.domain.preferences.TwoPageLayout
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The payloads `:domain`'s bare enums cannot carry. Nothing here resolves a resource: a `@StringRes`
 * is an `int` and the assertion is only that every case has one.
 */
class ReaderEnumExtensionsTest {

    @Test
    fun `the hide thresholds are upstream's four percentages`() {
        ReaderHideThreshold.HIGHEST.threshold shouldBe 5
        ReaderHideThreshold.HIGH.threshold shouldBe 13
        ReaderHideThreshold.LOW.threshold shouldBe 31
        ReaderHideThreshold.LOWEST.threshold shouldBe 47
    }

    @Test
    fun `horizontal inversion covers HORIZONTAL and BOTH`() {
        TappingInvertMode.NONE.shouldInvertHorizontal shouldBe false
        TappingInvertMode.HORIZONTAL.shouldInvertHorizontal shouldBe true
        TappingInvertMode.VERTICAL.shouldInvertHorizontal shouldBe false
        TappingInvertMode.BOTH.shouldInvertHorizontal shouldBe true
    }

    @Test
    fun `vertical inversion covers VERTICAL and BOTH`() {
        TappingInvertMode.NONE.shouldInvertVertical shouldBe false
        TappingInvertMode.HORIZONTAL.shouldInvertVertical shouldBe false
        TappingInvertMode.VERTICAL.shouldInvertVertical shouldBe true
        TappingInvertMode.BOTH.shouldInvertVertical shouldBe true
    }

    @Test
    fun `every enum case has a label`() {
        TappingInvertMode.entries.forEach { (it.titleRes != 0) shouldBe true }
        ReaderHideThreshold.entries.forEach { (it.titleRes != 0) shouldBe true }
        FlashColor.entries.forEach { (it.titleRes != 0) shouldBe true }
        TwoPageLayout.entries.forEach { (it.titleRes != 0) shouldBe true }
    }

    @Test
    fun `no two cases of one enum share a label`() {
        TappingInvertMode.entries.map { it.titleRes }.distinct().size shouldBe TappingInvertMode.entries.size
        ReaderHideThreshold.entries.map { it.titleRes }.distinct().size shouldBe ReaderHideThreshold.entries.size
        FlashColor.entries.map { it.titleRes }.distinct().size shouldBe FlashColor.entries.size
        TwoPageLayout.entries.map { it.titleRes }.distinct().size shouldBe TwoPageLayout.entries.size
    }
}
