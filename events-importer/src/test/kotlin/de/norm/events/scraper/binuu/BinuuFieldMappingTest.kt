package de.norm.events.scraper.binuu

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for Bi Nuu's field-mapping helpers ([mapBinuuStatus], [parseBinuuDate],
 * [parseBinuuTime]), which translate the raw SvelteKit payload values into domain types.
 */
class BinuuFieldMappingTest {
    @Test
    fun `maps the observed status codes and defaults the rest`() {
        mapBinuuStatus(null) shouldBe "SCHEDULED"
        mapBinuuStatus("") shouldBe "SCHEDULED"
        mapBinuuStatus("r") shouldBe "RELOCATED"
        // "p" (Verschoben / postponed) is a real on-site code, seen only on detail pages.
        mapBinuuStatus("p") shouldBe "POSTPONED"
        // An unknown code degrades to SCHEDULED rather than being mismapped.
        mapBinuuStatus("x") shouldBe "SCHEDULED"
    }

    @Test
    fun `reads the Berlin date of a UTC timestamp, rolling over a late start`() {
        parseBinuuDate("2026-07-19 19:00:00.000Z") shouldBe LocalDate.of(2026, 7, 19)
        // 23:30 UTC is already the next day in Berlin, and the night belongs to that date (#1675).
        parseBinuuDate("2026-07-19 23:30:00.000Z") shouldBe LocalDate.of(2026, 7, 20)
        parseBinuuDate(null).shouldBeNull()
        parseBinuuDate("").shouldBeNull()
        parseBinuuDate("not-a-date").shouldBeNull()
    }

    @Test
    fun `reads the Berlin time of a UTC timestamp, on both sides of the DST boundary`() {
        // The payload's instants are UTC: the venue's own page renders this one as 21:00 (#1675).
        parseBinuuTime("2026-07-19 19:00:00.000Z") shouldBe LocalTime.of(21, 0)
        // Summer is +2, winter +1, which is the step every row took on 2026-10-25.
        parseBinuuTime("2026-09-22 18:00:00.000Z") shouldBe LocalTime.of(20, 0)
        parseBinuuTime("2026-11-22 19:00:00.000Z") shouldBe LocalTime.of(20, 0)
        // A date-only value (no time component) yields null.
        parseBinuuTime("2026-07-19").shouldBeNull()
        parseBinuuTime(null).shouldBeNull()
    }

    @Test
    fun `infers the event type from the title and subtitle, defaulting to CONCERT`() {
        // Live-music venue default: a plain band name is a CONCERT.
        inferBinuuEventType("Arch Enemy", "Back To The Root Of All Evil") shouldBe "CONCERT"
        // A pub quiz, detected in the name.
        inferBinuuEventType("Music Quiz", null) shouldBe "QUIZ"
        // Known recurring party/DJ series, matched by curated name (edition number ignored).
        inferBinuuEventType("GrooveJet Berlin", "") shouldBe "PARTY"
        inferBinuuEventType("Ultra Night", "Depeche Mode Special") shouldBe "PARTY"
        inferBinuuEventType("Boheme Sauvage N°141", null) shouldBe "PARTY"
        // Party/DJ-night phrasing in the title/subtitle.
        inferBinuuEventType("Karaoke Night", null) shouldBe "PARTY"
        inferBinuuEventType("Some Night", "DJ Set till late") shouldBe "PARTY"
        // Regression: a genre word in a band's tour name must NOT flip a concert to PARTY
        // (Gutalax is death metal; "Shit On The Dancefloor" is the tour name).
        inferBinuuEventType("Gutalax", "Shit On The Dancefloor Mini-Tour 2026") shouldBe "CONCERT"
    }
}
