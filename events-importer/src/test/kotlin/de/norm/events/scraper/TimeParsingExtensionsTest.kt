package de.norm.events.scraper

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime

class TimeParsingExtensionsTest {
    @Test
    fun `labelledClock reads every clock spelling the venues print`() {
        labelledClock("Einlass: 19:00 Beginn: 20:00", DOORS_LABELS) shouldBe LocalTime.of(19, 0)
        labelledClock("Einlass: 19:00 Beginn: 20:00", START_LABELS) shouldBe LocalTime.of(20, 0)
        labelledClock("Einlass: 19.00", DOORS_LABELS) shouldBe LocalTime.of(19, 0)
        labelledClock("Beginn 20,00 Uhr", START_LABELS) shouldBe LocalTime.of(20, 0)
        labelledClock("Einlass: 19 Uhr", DOORS_LABELS) shouldBe LocalTime.of(19, 0)
        labelledClock("Doors: 19:00h", DOORS_LABELS) shouldBe LocalTime.of(19, 0)
        labelledClock("Einlass ab 9:00 Uhr", DOORS_LABELS) shouldBe LocalTime.of(9, 0)
        labelledClock("Start: 11:30 pm", START_LABELS) shouldBe LocalTime.of(23, 30)
        labelledClock("Doors 7pm", DOORS_LABELS) shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `labelledClock takes a qualifier, a label suffix and a compound label`() {
        labelledClock("Einlass ab ca. 18:45 (Saal)", DOORS_LABELS) shouldBe LocalTime.of(18, 45)
        labelledClock("Doors open 19:30", DOORS_LABELS) shouldBe LocalTime.of(19, 30)
        labelledClock("Doors 19:30 / Quiz starts 20:00", START_LABELS) shouldBe LocalTime.of(20, 0)
        labelledClock("Konzertbeginn: 20 Uhr", START_LABELS) shouldBe LocalTime.of(20, 0)
        labelledClock("fr 03.10. tür 19:00 beginn 20:00", DOORS_LABELS) shouldBe LocalTime.of(19, 0)
        labelledClock("Show: 20.30", START_LABELS) shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `labelledClock reads no date and no bare hour as a clock`() {
        labelledClock("Beginn 1. Oktober", START_LABELS).shouldBeNull()
        labelledClock("Beginn: 19.10.2026", START_LABELS).shouldBeNull()
        labelledClock("Einlass 19", DOORS_LABELS).shouldBeNull()
        labelledClock("Startseite", START_LABELS).shouldBeNull()
        labelledClock(null, DOORS_LABELS).shouldBeNull()
    }
}
