package de.norm.events.scraper.elfsight

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The monthly rules of [elfsightOccurrenceDates]; the weekly ones are exercised through Humboldthain's scraper. */
class ElfsightOccurrenceDatesTest {
    private val today = LocalDate.of(2026, 10, 1)

    private fun dates(node: ElfsightEventNode) = elfsightOccurrenceDates(node, LocalDate.parse(node.start!!.date), today, "Test", node.id!!)

    private fun monthly(
        start: String,
        period: String = "nthDayInMonth",
        frequency: String = "daily",
        onDay: String? = "sameDay",
        interval: Int = 1,
        ends: String = "never",
        occurrences: Int = 1
    ) = ElfsightEventNode(
        id = "m",
        start = ElfsightDateTime(date = start),
        repeatPeriod = period,
        repeatFrequency = frequency,
        repeatMonthlyOnDay = onDay,
        repeatInterval = interval,
        repeatEnds = ends,
        repeatEndsOccurrences = occurrences
    )

    @Test
    fun `the nthDayInMonth preset repeats the start date's nth weekday and ignores its stale frequency and day`() {
        // 2026-07-08 is the second Wednesday; "daily" and "sameDay" are what the widget stores beside the preset.
        dates(monthly("2026-07-08")).take(3) shouldContainExactly
            listOf(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 11, 11), LocalDate.of(2026, 12, 9))
    }

    @Test
    fun `a custom monthly rule on the nth day honours its interval and occurrence cap`() {
        // The first Thursday every second month, five slots from June: Jun, Aug, Oct, Dec, Feb — the past two dropped.
        dates(
            monthly("2026-06-04", period = "custom", frequency = "monthly", onDay = "nthDay", interval = 2, ends = "afterOccurrences", occurrences = 5)
        ) shouldContainExactly
            listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 3), LocalDate.of(2027, 2, 4))
    }

    @Test
    fun `a rule no venue publishes keeps its start date only`() {
        // Same day of the month, and a fifth weekday that most months lack: either would be a guess.
        dates(monthly("2026-07-08", period = "custom", frequency = "monthly", onDay = "sameDay")) shouldContainExactly listOf(LocalDate.of(2026, 7, 8))
        dates(monthly("2026-07-29")) shouldContainExactly listOf(LocalDate.of(2026, 7, 29))
    }
}
