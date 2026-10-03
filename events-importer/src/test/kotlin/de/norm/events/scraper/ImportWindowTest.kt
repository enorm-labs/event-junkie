package de.norm.events.scraper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

class ImportWindowTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val night = ImportWindow(LocalTime.of(2, 0), LocalTime.of(6, 0), berlin)

    @Test
    fun `contains reads the local time, so the same window is a different UTC hour in summer and winter`() {
        night.contains(Instant.parse("2026-07-01T00:30:00Z")) shouldBe true // 02:30 CEST
        night.contains(Instant.parse("2026-12-01T00:30:00Z")) shouldBe false // 01:30 CET
        night.contains(Instant.parse("2026-12-01T01:30:00Z")) shouldBe true // 02:30 CET
    }

    @Test
    fun `a wrapping window contains both sides of midnight`() {
        val evening = ImportWindow(LocalTime.of(22, 0), LocalTime.of(1, 0), berlin)

        evening.contains(Instant.parse("2026-12-01T22:30:00Z")) shouldBe true // 23:30 CET
        evening.contains(Instant.parse("2026-12-01T23:30:00Z")) shouldBe true // 00:30 CET
        evening.contains(Instant.parse("2026-12-01T00:30:00Z")) shouldBe false // 01:30 CET
        evening.lastOpening(Instant.parse("2026-12-01T23:30:00Z")) shouldBe Instant.parse("2026-12-01T21:00:00Z")
    }

    @Test
    fun `lastOpening before today's start is yesterday's`() {
        night.lastOpening(Instant.parse("2026-12-02T00:00:00Z")) shouldBe Instant.parse("2026-12-01T01:00:00Z")
        night.lastOpening(Instant.parse("2026-12-02T01:00:00Z")) shouldBe Instant.parse("2026-12-02T01:00:00Z")
    }

    @Test
    fun `equal start and end is the whole day`() {
        val allDay = ImportWindow(LocalTime.NOON, LocalTime.NOON, berlin)

        allDay.isWholeDay shouldBe true
        allDay.contains(Instant.parse("2026-12-01T03:00:00Z")) shouldBe true
    }

    @Test
    fun `the properties bind from configuration strings and default to 02 00 to 06 00 in Berlin`() {
        val bound =
            Binder(MapConfigurationPropertySource(mapOf("app.scheduling.import-window.start" to "03:30")))
                .bind("app.scheduling.import-window", ImportWindowProperties::class.java)
                .get()

        bound.window shouldBe ImportWindow(LocalTime.of(3, 30), LocalTime.of(6, 0), berlin)
        ImportWindowProperties().window shouldBe night
    }

    @Test
    fun `a malformed time fails when the properties are built`() {
        shouldThrow<DateTimeParseException> { ImportWindowProperties(start = "2am") }
    }
}
