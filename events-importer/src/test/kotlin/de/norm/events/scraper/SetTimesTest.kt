package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class SetTimesTest {
    private val saturday = LocalDate.of(2026, 9, 26)

    private fun at(text: String) = LocalTime.parse(text)

    @Test
    fun `a slot earlier than the one before has crossed midnight`() {
        val clock = RunningOrderClock(saturday)

        clock.slot(at("23:00")).first shouldBe Instant.parse("2026-09-26T21:00:00Z")
        clock.slot(at("01:00")).first shouldBe Instant.parse("2026-09-26T23:00:00Z")
        clock.slot(at("04:30")).first shouldBe Instant.parse("2026-09-27T02:30:00Z")
    }

    @Test
    fun `an end at or before its start is the next day's`() {
        val (start, end) = RunningOrderClock(saturday).slot(at("23:00"), at("02:00"))

        start shouldBe Instant.parse("2026-09-26T21:00:00Z")
        end shouldBe Instant.parse("2026-09-27T00:00:00Z")
    }

    @Test
    fun `a slot without an end keeps it unknown`() {
        RunningOrderClock(saturday).slot(at("22:00")).second.shouldBeNull()
    }

    @Test
    fun `a first slot before the day break is already past midnight`() {
        RunningOrderClock(saturday, dayBreak = NIGHT_ENDS).slot(at("02:00")).first shouldBe Instant.parse("2026-09-27T00:00:00Z")
        RunningOrderClock(saturday, dayBreak = NIGHT_ENDS).slot(at("21:00")).first shouldBe Instant.parse("2026-09-26T19:00:00Z")
    }

    @Test
    fun `without a day break the first slot is on the night's date, however early`() {
        RunningOrderClock(saturday).slot(at("02:00")).first shouldBe Instant.parse("2026-09-26T00:00:00Z")
    }

    @Test
    fun `the clock keeps Berlin's offset across the switch to winter time`() {
        val clock = RunningOrderClock(LocalDate.of(2026, 10, 24))

        clock.slot(at("23:00")).first shouldBe Instant.parse("2026-10-24T21:00:00Z")
        clock.slot(at("04:00")).first shouldBe Instant.parse("2026-10-25T03:00:00Z")
    }
}
