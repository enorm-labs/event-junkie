package de.norm.events.scraper.clubost

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for the Django date/time renderings and placeholder handling in
 * `ClubOstFieldMapping.kt`.
 */
class ClubOstFieldMappingTest {
    @Test
    fun `parseClubOstDate reads the abbreviated month names Django shortens`() {
        parseClubOstDate("Jan. 9, 2027") shouldBe LocalDate.of(2027, 1, 9)
        parseClubOstDate("Aug. 7, 2026") shouldBe LocalDate.of(2026, 8, 7)
        parseClubOstDate("Sept. 18, 2026") shouldBe LocalDate.of(2026, 9, 18)
        parseClubOstDate("Nov. 6, 2026") shouldBe LocalDate.of(2026, 11, 6)
        parseClubOstDate("Dec. 31, 2026") shouldBe LocalDate.of(2026, 12, 31)
    }

    @Test
    fun `parseClubOstDate reads the months Django spells out in full`() {
        // Django's `N` format leaves March through July unabbreviated and without a period,
        // so they are a genuinely different shape rather than a stylistic variant.
        parseClubOstDate("March 3, 2027") shouldBe LocalDate.of(2027, 3, 3)
        parseClubOstDate("April 30, 2027") shouldBe LocalDate.of(2027, 4, 30)
        parseClubOstDate("May 1, 2027") shouldBe LocalDate.of(2027, 5, 1)
        parseClubOstDate("June 21, 2027") shouldBe LocalDate.of(2027, 6, 21)
        parseClubOstDate("July 4, 2027") shouldBe LocalDate.of(2027, 7, 4)
    }

    @Test
    fun `parseClubOstDate returns null for blank, foreign and impossible dates`() {
        parseClubOstDate(null) shouldBe null
        parseClubOstDate("  ") shouldBe null
        // The German rendering the site serves under `Accept-Language: de` — deliberately
        // unparsed, because the shared scraper WebClient never asks for it.
        parseClubOstDate("7. August 2026") shouldBe null
        parseClubOstDate("Feb. 30, 2026") shouldBe null
        parseClubOstDate("Smarch 3, 2027") shouldBe null
        parseClubOstDate("2026-08-07") shouldBe null
    }

    @Test
    fun `parseClubOstTime reads whole hours and minute-precision times`() {
        parseClubOstTime("11 p.m.") shouldBe LocalTime.of(23, 0)
        parseClubOstTime("2 p.m.") shouldBe LocalTime.of(14, 0)
        parseClubOstTime("8 a.m.") shouldBe LocalTime.of(8, 0)
        parseClubOstTime("11:55 p.m.") shouldBe LocalTime.of(23, 55)
        parseClubOstTime("1:30 a.m.") shouldBe LocalTime.of(1, 30)
    }

    @Test
    fun `parseClubOstTime reads the words Django substitutes for twelve o'clock`() {
        // Django's `P` never prints "12 a.m." or "12 p.m." — it writes these two words
        // instead, so a club night starting at midnight is unparseable without them.
        parseClubOstTime("midnight") shouldBe LocalTime.MIDNIGHT
        parseClubOstTime("noon") shouldBe LocalTime.NOON
        parseClubOstTime("Midnight") shouldBe LocalTime.MIDNIGHT
    }

    @Test
    fun `parseClubOstTime maps a numeric twelve onto the correct half of the day`() {
        // Not a rendering Django emits, but the 12-hour rule must not yield hour 24.
        parseClubOstTime("12 a.m.") shouldBe LocalTime.MIDNIGHT
        parseClubOstTime("12 p.m.") shouldBe LocalTime.NOON
    }

    @Test
    fun `parseClubOstTime returns null for blank, foreign and unparseable input`() {
        parseClubOstTime(null) shouldBe null
        parseClubOstTime("   ") shouldBe null
        parseClubOstTime("TBA") shouldBe null
        // The German rendering, again deliberately unparsed.
        parseClubOstTime("23:00 Uhr") shouldBe null
        parseClubOstTime("25 p.m.") shouldBe null
    }

    @Test
    fun `withoutPlaceholder drops the sentences the templates print for an empty field`() {
        withoutPlaceholder("No description available") shouldBe null
        withoutPlaceholder("no further information") shouldBe null
        withoutPlaceholder("No logo available") shouldBe null
        withoutPlaceholder("  ") shouldBe null
        withoutPlaceholder(null) shouldBe null
    }

    @Test
    fun `withoutPlaceholder keeps real prose, including text that merely mentions a placeholder`() {
        withoutPlaceholder("  Doors 23:00, RA presale  ") shouldBe "Doors 23:00, RA presale"
        withoutPlaceholder("No description available for the support act, but the headliner plays at 1.") shouldBe
            "No description available for the support act, but the headliner plays at 1."
    }

    @Test
    fun `extractClubOstEventId reads the numeric id out of a detail path`() {
        extractClubOstEventId("/event/231438/") shouldBe "231438"
        extractClubOstEventId("https://clubost.de/event/239128/") shouldBe "239128"
        extractClubOstEventId("/event/215982") shouldBe "215982"
        extractClubOstEventId("/impressum/") shouldBe null
        extractClubOstEventId("/event/blasphemy/") shouldBe null
    }

    @Test
    fun `extractClubOstEventId reads the UUID the site switched to in September 2026`() {
        // Taking only the leading digits skipped every UUID starting with a letter and collided
        // the rest on their first digits (#1131); the whole segment is the id.
        extractClubOstEventId("/event/e9bdde1e-299a-4cc3-ad01-c8d3011aa869/") shouldBe "e9bdde1e-299a-4cc3-ad01-c8d3011aa869"
        extractClubOstEventId("https://clubost.de/event/2fb359f9-da6c-497d-9818-a1e8868d06f4/") shouldBe
            "2fb359f9-da6c-497d-9818-a1e8868d06f4"
        extractClubOstEventId("/event/2fb359f9/") shouldBe null
    }
}
