package de.norm.events.scraper

import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class CalendarPropertiesTest {
    @Test
    fun `siteTime reads a VALUE=DATE property as all-day`() {
        CalendarProperty("20261017", mapOf("VALUE" to "DATE")).siteTime() shouldBe SiteTime(LocalDate.of(2026, 10, 17), null)
    }

    @Test
    fun `siteTime reads a bare eight-digit value as all-day without a VALUE parameter`() {
        CalendarProperty("20261017").siteTime() shouldBe SiteTime(LocalDate.of(2026, 10, 17), null)
    }

    @Test
    fun `siteTime moves a UTC time to Berlin, summer and winter`() {
        CalendarProperty("20260715T180000Z").siteTime() shouldBe SiteTime(LocalDate.of(2026, 7, 15), LocalTime.of(20, 0))
        CalendarProperty("20261215T230000Z").siteTime() shouldBe SiteTime(LocalDate.of(2026, 12, 16), LocalTime.of(0, 0))
    }

    @Test
    fun `siteTime moves a UTC time to the zone it is given`() {
        CalendarProperty("20260715T180000Z").siteTime(ZoneOffset.ofHours(1)) shouldBe SiteTime(LocalDate.of(2026, 7, 15), LocalTime.of(19, 0))
    }

    @Test
    fun `siteTime reads a TZID wall-clock time as printed`() {
        CalendarProperty(" 20261017T213000 ", mapOf("TZID" to "Europe/Berlin")).siteTime() shouldBe
            SiteTime(LocalDate.of(2026, 10, 17), LocalTime.of(21, 30))
    }

    @Test
    fun `dateTime is midnight for an all-day value`() {
        SiteTime(LocalDate.of(2026, 10, 17), null).dateTime shouldBe LocalDateTime.of(2026, 10, 17, 0, 0)
        SiteTime(LocalDate.of(2026, 10, 17), LocalTime.of(21, 30)).dateTime shouldBe LocalDateTime.of(2026, 10, 17, 21, 30)
    }

    @Test
    fun `an all-day value sorts before a timed one on the same day`() {
        SiteTime(LocalDate.of(2026, 10, 17), null) shouldBeLessThan SiteTime(LocalDate.of(2026, 10, 17), LocalTime.of(9, 0))
    }
}
