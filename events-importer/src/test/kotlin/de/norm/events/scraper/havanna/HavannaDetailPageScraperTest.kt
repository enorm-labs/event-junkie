package de.norm.events.scraper.havanna

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [HavannaDetailPageScraper].
 *
 * Covers all three of the venue's weekly night pages, which between them exercise every layout quirk
 * the parser has to survive: a night that states its start time explicitly ("Party: 20.00h" — with a
 * dot, and after an unrelated "Dance Lesson:" line), one that states it as "Start: 22:00", and one
 * that states none at all and must fall back to the footer's opening hours. The Wednesday page also
 * carries the venue's summer-break notice.
 */
class HavannaDetailPageScraperTest {
    private val scraper = HavannaDetailPageScraper()

    private fun night(day: String): HavannaWeeklyNight {
        val url = "https://www.havanna-berlin.de/$day"
        return scraper.scrape(fixtureDocument("havanna/havanna-detail-$day.html", url), url)!!
    }

    private val wednesday by lazy { night("wednesday") }
    private val friday by lazy { night("friday") }
    private val saturday by lazy { night("saturday") }

    @Test
    fun `extracts all fields of a representative night`() {
        friday.dayOfWeek shouldBe DayOfWeek.FRIDAY
        friday.slug shouldBe "friday"
        // The first heading just repeats the weekday; the second names the night.
        friday.title shouldBe "Die große Party von Salsa bis Black Music!"
        friday.subtitle shouldBe "Ladies Night & Reggeaton & noch viel mehr..."
        friday.startTime shouldBe LocalTime.of(22, 0)
        friday.priceBoxOffice shouldBe BigDecimal("10.00")
        friday.imageUrl shouldBe
            "https://images.squarespace-cdn.com/content/v1/568d0402a128e6a4d712eadf/1453721175761-MG116XU2HKD7P0C78IKK/image-asset.jpeg"
        friday.sourceUrl shouldBe "https://www.havanna-berlin.de/friday"
        friday.pauseFrom.shouldBeNull()
    }

    @Test
    fun `keeps the venue's prose as the description, one source paragraph per line`() {
        val lines = friday.description!!.lines()
        lines.first() shouldBe "1st floor Latin Dance Party (Reggaeton & Latin-Pop)"
        // The ladies' free-entry window stays in the description rather than the price note, where
        // `detectFree` would read its standalone "free" and flag the whole night as free entry.
        friday.description!! shouldContain "(Ladies von 22:00 – 23:00 for free!)"
        friday.description!! shouldContain "Salsa & Bachata dance lessons one hour prior to regular opening!"
    }

    @Test
    fun `reads the genres off the dancefloor paragraphs, dropping brackets`() {
        friday.genre shouldBe "Latin Dance Party Reggaeton & Latin-Pop, HipHop & Charts, Salsa, Merengue & Bachata"
    }

    @Test
    fun `takes a floor's genres from the next paragraph when the floor line carries none`() {
        // Saturday's "4th floor 00:30" line lists no genres; they follow in their own paragraph.
        saturday.genre shouldBe "Reggaeton, Latin-Pop, Hip Hop, RnB & Oldschool, Charts, Top40, Discotunes"
        // "2nd floor" is followed by another floor line, so it correctly contributes nothing.
        saturday.genre!! shouldContain "Reggaeton"
    }

    @Test
    fun `falls back to the footer opening hours when a night states no start time`() {
        // Saturday lists per-floor times but no "Start:" line; the footer says "Saturday 22:00 – open end".
        saturday.startTime shouldBe LocalTime.of(22, 0)
        saturday.priceBoxOffice shouldBe BigDecimal("14.00")
        saturday.title shouldBe "Saturdays @ HAVANNA"
        saturday.subtitle shouldBe "Party auf 3 Dancefloors!"
    }

    @Test
    fun `reads a dotted start time and ignores the separately priced dance lesson`() {
        // "Dance Lesson: 19.00h" precedes "Party: 20.00h" — the party time wins.
        wednesday.startTime shouldBe LocalTime.of(20, 0)
        // Both the door and the lesson cost 7,00 €; only the "Entrance Fee" line is the door price.
        wednesday.priceBoxOffice shouldBe BigDecimal("7.00")
        wednesday.title shouldBe "Salsa & bachata AFTER WORK"
        wednesday.genre shouldBe "Salsa, Merengue, Bachata"
    }

    @Test
    fun `captures the announced closure date from the summer-break notice`() {
        // "WIR SIND AB DEM 01.07.2026 IN DER SOMMERPAUSE!" sits in its own block above the programme.
        wednesday.pauseFrom shouldBe LocalDate.of(2026, 7, 1)
        // The notice must not be mistaken for the programme block.
        wednesday.title shouldBe "Salsa & bachata AFTER WORK"
    }

    @Test
    fun `returns null when the URL names no weekday`() {
        val url = "https://www.havanna-berlin.de/events"
        scraper.scrape(Jsoup.parse("<html><body><p>x</p></body></html>", url), url).shouldBeNull()
    }

    @Test
    fun `returns null when the page carries no programme block`() {
        val url = "https://www.havanna-berlin.de/friday"
        val bare = Jsoup.parse("<html><body><div data-content-field=\"main-content\"></div></body></html>", url)
        scraper.scrape(bare, url).shouldBeNull()
    }
}
