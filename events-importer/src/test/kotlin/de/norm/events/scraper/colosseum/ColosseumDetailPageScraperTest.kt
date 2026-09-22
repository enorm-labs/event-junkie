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
    fun `a page stating the labels without a clock keeps the listing's time and gets no doors`() {
        val event = scrape("colosseum-detail-no-times.html").shouldNotBeNull()

        event.doorsTime.shouldBeNull()
        // The payload's own start, which the importer then keeps.
        event.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `a page without the warmup payload yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Wartung</p></body></html>", URL), URL).shouldBeNull()
    }

    private companion object {
        const val URL = "https://www.colosseumberlin.com/details-registrierung/christoph-kramer"
    }
}
