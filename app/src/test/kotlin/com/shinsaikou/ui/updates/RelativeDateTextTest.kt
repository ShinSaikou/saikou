package com.shinsaikou.ui.updates

import android.content.Context
import android.content.res.Resources
import com.shinsaikou.domain.preferences.UiPreferences
import com.shinsaikou.i18n.R
import eu.kanade.tachiyomi.util.lang.toRelativeString
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import org.junit.jupiter.api.Test
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Upstream's relative date, ported for the Updates day headers (phase 4 unit UG): the words within a
 * week either side of today, the date in the stored format beyond it or with Relative timestamps
 * off. The context answers each string by its name, so a test reads which string and count Android
 * would be asked for; the plural's choice of "Yesterday" for one is Android's.
 */
class RelativeDateTextTest {

    private val resources = mockk<Resources> {
        every { getQuantityString(any(), any(), *anyVararg()) } answers {
            val name = when (firstArg<Int>()) {
                R.plurals.relative_time -> "relative_time"
                R.plurals.upcoming_relative_time -> "upcoming_relative_time"
                else -> "unknown"
            }
            "$name(${secondArg<Int>()})"
        }
    }
    private val context = mockk<Context> {
        every { getString(R.string.relative_time_today) } returns "Today"
        every { resources } returns this@RelativeDateTextTest.resources
    }

    private val today = LocalDate(2026, 9, 27)
    private val short = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

    private fun LocalDate.text(relative: Boolean = true, format: String = ""): String =
        toRelativeString(context, relative, UiPreferences.dateFormat(format), today)

    @Test
    fun `today is Today`() {
        today.text() shouldBe "Today"
    }

    @Test
    fun `yesterday asks for the relative plural with one`() {
        LocalDate(2026, 9, 26).text() shouldBe "relative_time(1)"
    }

    @Test
    fun `a few days ago asks for the relative plural with the count`() {
        LocalDate(2026, 9, 24).text() shouldBe "relative_time(3)"
        LocalDate(2026, 9, 21).text() shouldBe "relative_time(6)"
    }

    @Test
    fun `seven days and more ago is the date`() {
        val weekAgo = LocalDate(2026, 9, 20)
        weekAgo.text() shouldBe short.format(weekAgo.toJavaLocalDate())
        LocalDate(2026, 1, 2).text() shouldBe short.format(LocalDate(2026, 1, 2).toJavaLocalDate())
    }

    @Test
    fun `a day ahead of today is upcoming and more than a week ahead is the date`() {
        LocalDate(2026, 9, 28).text() shouldBe "upcoming_relative_time(1)"
        LocalDate(2026, 10, 4).text() shouldBe "upcoming_relative_time(7)"
        LocalDate(2026, 10, 5).text() shouldBe short.format(LocalDate(2026, 10, 5).toJavaLocalDate())
    }

    @Test
    fun `with relative timestamps off even today is the date`() {
        today.text(relative = false) shouldBe short.format(today.toJavaLocalDate())
        LocalDate(2026, 9, 26).text(relative = false) shouldBe short.format(LocalDate(2026, 9, 26).toJavaLocalDate())
    }

    @Test
    fun `a custom date format writes the date beyond the window and with relative off`() {
        LocalDate(2026, 9, 1).text(format = "yyyy-MM-dd") shouldBe "2026-09-01"
        today.text(relative = false, format = "dd/MM/yy") shouldBe "27/09/26"
        // Inside the window the format does not show.
        today.text(format = "yyyy-MM-dd") shouldBe "Today"
    }
}
