package com.shinsaikou.ui.manga

import com.shinsaikou.domain.preferences.UiPreferences
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.jupiter.api.Test
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.LocalDate as JavaLocalDate

/**
 * The track sheet's start and finish dates are the one place Saikou writes a date alone, and they
 * follow the owner's date format as upstream's track dialog does (phase 4 unit SR).
 */
class TrackDateTextTest {

    /** A stored date is local midnight, as the date picker writes it. */
    private val date = LocalDate(2026, 9, 27).atStartOfDayIn(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    @Test
    fun `the default format is the locale's short date`() {
        trackDateText(date, UiPreferences.dateFormat("")) shouldBe
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).format(JavaLocalDate.of(2026, 9, 27))
    }

    @Test
    fun `a chosen format changes the text the row writes`() {
        trackDateText(date, UiPreferences.dateFormat("yyyy-MM-dd")) shouldBe "2026-09-27"
        trackDateText(date, UiPreferences.dateFormat("dd/MM/yy")) shouldBe "27/09/26"
        trackDateText(date, UiPreferences.dateFormat("MM/dd/yy")) shouldBe "09/27/26"
    }

    @Test
    fun `an unset date writes nothing`() {
        trackDateText(0L, UiPreferences.dateFormat("yyyy-MM-dd")) shouldBe null
    }
}
