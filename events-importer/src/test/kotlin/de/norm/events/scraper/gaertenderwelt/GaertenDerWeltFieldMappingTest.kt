package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.mapEventType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for the Gärten der Welt field mapping — the park's category vocabulary and the
 * breadth filter built on it, the badges it writes into event titles, the date, start time and
 * identity all read out of the detail URL, and an exhibition's run.
 */
class GaertenDerWeltFieldMappingTest {
    @Test
    fun `maps the park's own category labels onto event types`() {
        mapEventType("Konzerte", GAERTEN_DER_WELT_CATEGORY_SYNONYMS) shouldBe EventType.CONCERT.name
        mapEventType("Open-Air Kino", GAERTEN_DER_WELT_CATEGORY_SYNONYMS) shouldBe EventType.SCREENING.name
        mapEventType("Ausstellungen", GAERTEN_DER_WELT_CATEGORY_SYNONYMS) shouldBe EventType.EXHIBITION.name
        mapEventType("Parkfeste", GAERTEN_DER_WELT_CATEGORY_SYNONYMS) shouldBe EventType.FESTIVAL.name
        mapEventType("Bühne/Theater", GAERTEN_DER_WELT_CATEGORY_SYNONYMS) shouldBe EventType.SHOW.name
    }

    @Test
    fun `leaves the editorial highlight tag unmapped so the title decides`() {
        mapEventType("Unser Tipp", GAERTEN_DER_WELT_CATEGORY_SYNONYMS).shouldBeNull()
    }

    @Test
    fun `keeps the park's staged programme in scope`() {
        isProgrammeCategory("Konzerte") shouldBe true
        isProgrammeCategory("Open-Air Kino") shouldBe true
        isProgrammeCategory("Parkfeste") shouldBe true
        isProgrammeCategory("Ausstellungen") shouldBe true
    }

    @Test
    fun `keeps an uncategorised row, which is how the park files its evening events`() {
        isProgrammeCategory(null) shouldBe true
        isProgrammeCategory("  ") shouldBe true
    }

    @Test
    fun `drops the park's participation formats`() {
        isProgrammeCategory("Führungen") shouldBe false
        isProgrammeCategory("Workshop") shouldBe false
        isProgrammeCategory("Sport/Tanz") shouldBe false
        isProgrammeCategory("Sport & Tanz") shouldBe false
        isProgrammeCategory("Umweltbildung") shouldBe false
        isProgrammeCategory("Infoveranstaltungen") shouldBe false
        isProgrammeCategory("Mitmachaktion") shouldBe false
    }

    @Test
    fun `drops a row that is a participation format alongside another category`() {
        isProgrammeCategory("Unser Tipp Führungen") shouldBe false
    }

    @Test
    fun `reads the sold-out badge and strips it off the title`() {
        isSoldOutTitle("AUSGEBUCHT: Sonderführungen Japan") shouldBe true
        cleanGaertenDerWeltTitle("AUSGEBUCHT: Sonderführungen Japan") shouldBe "Sonderführungen Japan"
        // Sold-out is a flag, not a status.
        gaertenDerWeltStatus("AUSGEBUCHT: Sonderführungen Japan") shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `strips the new-date badge without changing the status`() {
        cleanGaertenDerWeltTitle("NEUER TERMIN! Tobi Krell live in Berlin - Open Air") shouldBe "Tobi Krell live in Berlin - Open Air"
        // The event is already listed under its new date, so it is not postponed.
        gaertenDerWeltStatus("NEUER TERMIN! Tobi Krell live in Berlin - Open Air") shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `reads a cancellation badge as the event status`() {
        gaertenDerWeltStatus("ABGESAGT: Mondfest") shouldBe EventStatus.CANCELLED.name
        cleanGaertenDerWeltTitle("ABGESAGT: Mondfest") shouldBe "Mondfest"
    }

    @Test
    fun `leaves an unbadged title alone`() {
        cleanGaertenDerWeltTitle("Agnes Obel") shouldBe "Agnes Obel"
        isSoldOutTitle("Agnes Obel") shouldBe false
        gaertenDerWeltStatus("Agnes Obel") shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `reads the date, start time and identity out of a detail URL`() {
        val path = parseEventPath("$DETAIL_BASE/2026-08-15_1900/agnes-obel/")

        path?.date shouldBe LocalDate.of(2026, 8, 15)
        path?.startTime shouldBe LocalTime.of(19, 0)
        path?.identity shouldBe "2026-08-15_1900/agnes-obel"
    }

    @Test
    fun `separates the dates of a recurring event that reuses one slug`() {
        val september = parseEventPath("$DETAIL_BASE/2026-09-05_1100/fuehrung-durch-die-gaerten-der-welt/")
        val october = parseEventPath("$DETAIL_BASE/2026-10-03_1100/fuehrung-durch-die-gaerten-der-welt/")

        (september?.identity == october?.identity) shouldBe false
    }

    @Test
    fun `parses a morning stamp without losing its leading zero`() {
        parseEventPath("$DETAIL_BASE/2026-09-01_0900/zwischen-himmel-und-erde-ausstellung/")?.startTime shouldBe LocalTime.of(9, 0)
    }

    @Test
    fun `returns null for a path that carries no stamp`() {
        parseEventPath("https://www.gaertenderwelt.de/events/veranstaltungen/").shouldBeNull()
        parseEventPath("$DETAIL_BASE/agnes-obel/").shouldBeNull()
    }

    private companion object {
        private const val DETAIL_BASE = "https://www.gaertenderwelt.de/events/veranstaltungen/detail"
    }

    @Test
    fun `reads a date cell that spans days as a run`() {
        parseDateRange(" 01.09.2026 - 01.11.2026 ") shouldBe LocalDate.of(2026, 9, 1)..LocalDate.of(2026, 11, 1)
        parseDateRange("22.10.2026 – 24.10.2026") shouldBe LocalDate.of(2026, 10, 22)..LocalDate.of(2026, 10, 24)
    }

    @Test
    fun `reads a single day, a reversed range or no date as no run`() {
        parseDateRange("08.10.2026").shouldBeNull()
        parseDateRange("01.11.2026 - 01.09.2026").shouldBeNull()
        parseDateRange("01.11.2026 - 01.11.2026").shouldBeNull()
        parseDateRange(null).shouldBeNull()
    }

    @Test
    fun `keys a run on the slug, without the day`() {
        val path = parseEventPath("https://www.gaertenderwelt.de/events/veranstaltungen/detail/2026-10-04_0900/zwischen-himmel-und-erde-ausstellung/")

        path?.let(::gaertenDerWeltRunId) shouldBe "gaerten_der_welt:zwischen-himmel-und-erde-ausstellung"
    }
}
