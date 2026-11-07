package de.norm.events.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class TimetableClockTest {
    private val night = LocalDate.of(2026, 11, 7)

    @Test
    fun `a start before the day break is the next day, and an end before its start the day after that start`() {
        val clock = TimetableClock(night, dayBreak = LocalTime.of(23, 0))

        clock.instants(LocalTime.of(23, 0), LocalTime.of(2, 0)) shouldBe
            (Instant.parse("2026-11-07T22:00:00Z") to Instant.parse("2026-11-08T01:00:00Z"))
        clock.instants(LocalTime.of(4, 0), LocalTime.of(7, 0)) shouldBe
            (Instant.parse("2026-11-08T03:00:00Z") to Instant.parse("2026-11-08T06:00:00Z"))
        clock.instants(LocalTime.of(10, 0), null) shouldBe (Instant.parse("2026-11-08T09:00:00Z") to null)
    }

    @Test
    fun `a daytime event keeps a late set on its own date`() {
        val clock = TimetableClock(night, dayBreak = LocalTime.of(14, 0))

        clock.instants(LocalTime.of(14, 0), LocalTime.of(22, 30)) shouldBe
            (Instant.parse("2026-11-07T13:00:00Z") to Instant.parse("2026-11-07T21:30:00Z"))
    }

    @Test
    fun `the clock follows the summer offset`() {
        val clock = TimetableClock(LocalDate.of(2026, 7, 4), dayBreak = LocalTime.of(6, 0))

        clock.instants(LocalTime.of(5, 0), null) shouldBe (Instant.parse("2026-07-05T03:00:00Z") to null)
    }
}
