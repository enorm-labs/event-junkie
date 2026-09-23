package de.norm.events.scraper.colosseum

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ColosseumDetailPageScraper], against four live pages captured on 2026-09-22.
 *
 * Each fixture keeps the page's `wix-warmup-data` payload and drops the rendered Wix markup, which
 * carries nothing this parser reads. The four are the shapes the programme actually holds: a pair
 * written with minutes, a morning matinee whose listing time is the doors, a pair written without
 * them, and a page that states the labels and no clock.
 */
class ColosseumDetailPageScraperTest {
    private val scraper = ColosseumDetailPageScraper()

    private fun scrape(fixture: String) =
        scraper.scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/colosseum/$fixture")!!
                    .bufferedReader()
                    .readText(),
                URL
            ),
            URL
        )

    @Test
    fun `reads the doors and start the event states, not the cloned boilerplate line`() {
        val event = scrape("colosseum-detail-kramer.html").shouldNotBeNull()

        // The page's own text says 18:30 / 19:30; its `about` says the house-wide 19 / 20 Uhr.
        event.doorsTime shouldBe LocalTime.of(18, 30)
        event.startTime shouldBe LocalTime.of(19, 30)
        event.eventDate shouldBe LocalDate.of(2026, 10, 5)
        event.sourceId shouldBe "colosseum:christoph-kramer"
    }

    @Test
    fun `a matinee whose listing time is the doors gets the later start from its own page`() {
        val event = scrape("colosseum-detail-gysi.html").shouldNotBeNull()

        // The Wix `startDate` is 11:00, which is the Einlass — storing it as the start is #1684.
        event.doorsTime shouldBe LocalTime.of(11, 0)
        event.startTime shouldBe LocalTime.of(11, 30)
    }

    @Test
    fun `a pair written without minutes is read like any other`() {
        val event = scrape("colosseum-detail-gene-krupa.html").shouldNotBeNull()

        // "Einlass: 17 Uhr / Beginn: 18 Uhr" — real, and indistinguishable from the boilerplate by
        // formatting alone, which is why the field it sits in decides.
        event.doorsTime shouldBe LocalTime.of(17, 0)
        event.startTime shouldBe LocalTime.of(18, 0)
    }

    @Test
    fun `a bold label and its clock in separate text nodes are read as one line`() {
        // Wix splits a styled line into one node per run, which is how six of the eighteen events
        // kept the listing's time after the first fix (#1684).
        val event = scrape("colosseum-detail-styled-label.html").shouldNotBeNull()

        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `a pair equal to the boilerplate is still the event's own text`() {
        // Peter Sandberg states `Einlass: 19 Uhr / Beginn: 20 Uhr` in its rich content, the same
        // clocks the cloned `about` block carries. The field it sits in is what decides, and the
        // listing's 19:00 is this event's doors.
        val event = scrape("colosseum-detail-sandberg.html").shouldNotBeNull()

        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
    }

    @Test
    fun `a page without the warmup payload yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Wartung</p></body></html>", URL), URL).shouldBeNull()
    }

    @Test
    fun `an event stating no clock at all keeps the listing's time and gets no doors`() {
        // No live event does this today; the path exists because the house edits these lines by hand.
        val page =
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/colosseum/colosseum-detail-kramer.html")!!
                    .bufferedReader()
                    .readText()
                    .replace("Einlass: 18:30 Uhr", "Einlass:")
                    .replace("Beginn: 19:30 Uhr", "Beginn:"),
                URL
            )
        val event = scraper.scrape(page, URL).shouldNotBeNull()

        event.doorsTime.shouldBeNull()
        event.startTime shouldBe LocalTime.of(19, 30)
    }

    private companion object {
        const val URL = "https://www.colosseumberlin.com/details-registrierung/christoph-kramer"
    }
}
