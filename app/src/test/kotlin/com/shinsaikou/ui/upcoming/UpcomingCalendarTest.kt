package com.shinsaikou.ui.upcoming

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.jupiter.api.Test

class UpcomingCalendarTest {

    private val november2026 = YearMonth(2026, 11)
    private val february2026 = YearMonth(2026, 2)
    private val august2026 = YearMonth(2026, 8)

    private fun leadingNulls(week: List<LocalDate?>) = week.takeWhile { it == null }.size

    @Test
    fun `every row has seven cells`() {
        listOf(november2026, february2026, august2026).forEach { month ->
            DayOfWeek.entries.forEach { first ->
                calendarWeeks(month, first).forEach { it.size shouldBe 7 }
            }
        }
    }

    @Test
    fun `the days in the cells are exactly day 1 to the last of the month in order`() {
        val days = calendarWeeks(november2026, DayOfWeek.MONDAY).flatten().filterNotNull()

        days shouldContainExactly (1..30).map { LocalDate(2026, 11, it) }
    }

    @Test
    fun `a leap year February keeps its 29th`() {
        val days = calendarWeeks(YearMonth(2028, 2), DayOfWeek.MONDAY).flatten().filterNotNull()

        days.last() shouldBe LocalDate(2028, 2, 29)
        days.size shouldBe 29
    }

    @Test
    fun `a month starting on the first day of the week has no leading blanks`() {
        // 1 November 2026 is a Sunday.
        leadingNulls(calendarWeeks(november2026, DayOfWeek.SUNDAY).first()) shouldBe 0
    }

    @Test
    fun `a Sunday start with Monday first has six leading blanks`() {
        val first = calendarWeeks(november2026, DayOfWeek.MONDAY).first()

        leadingNulls(first) shouldBe 6
        first[6] shouldBe LocalDate(2026, 11, 1)
    }

    @Test
    fun `leading blanks follow the first day of the week`() {
        // Sunday the 1st: with Saturday first it is the second column.
        leadingNulls(calendarWeeks(november2026, DayOfWeek.SATURDAY).first()) shouldBe 1
        leadingNulls(calendarWeeks(november2026, DayOfWeek.WEDNESDAY).first()) shouldBe 4
    }

    @Test
    fun `a 28 day month starting on the first day of the week is four full rows`() {
        val weeks = calendarWeeks(february2026, DayOfWeek.SUNDAY)

        weeks.size shouldBe 4
        weeks.flatten().none { it == null } shouldBe true
    }

    @Test
    fun `a month that spills over five rows takes six`() {
        // 1 August 2026 is a Saturday: five leading blanks with Monday first, so 36 cells.
        val weeks = calendarWeeks(august2026, DayOfWeek.MONDAY)

        weeks.size shouldBe 6
        weeks.first()[5] shouldBe LocalDate(2026, 8, 1)
    }

    @Test
    fun `the cells after the last day of the month are blank`() {
        val last = calendarWeeks(november2026, DayOfWeek.MONDAY).last()

        // 30 November is a Monday, so it is the first cell of the last row.
        last.first() shouldBe LocalDate(2026, 11, 30)
        last.drop(1).all { it == null } shouldBe true
    }
}
