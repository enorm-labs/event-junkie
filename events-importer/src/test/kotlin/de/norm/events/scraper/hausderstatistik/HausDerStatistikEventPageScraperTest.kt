package de.norm.events.scraper.hausderstatistik

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class HausDerStatistikEventPageScraperTest {
    private val scraper = HausDerStatistikEventPageScraper()

    private fun listed(
        slug: String,
        date: String
    ) = ScrapedEvent(
        title = slug,
        description = "Teaser",
        eventDate = LocalDate.parse(date),
        startTime = LocalTime.parse("19:00"),
        sourceUrl = "https://hausderstatistik.org/event/$slug/",
        sourceId = "haus_der_statistik:$slug"
    )

    @Test
    fun `reads the text, the doors and show times and the picture of a concert`() {
        val listed = listed("lovetempo-nyc-mattie-safer-of-the-rapture-moriah-plaza", "2026-10-23")

        val event = scraper.scrape(listed, fixtureDocument("hausderstatistik/hausderstatistik-event-lovetempo.html", listed.sourceUrl)).shouldNotBeNull()

        val description = event.description.shouldNotBeNull()
        description shouldStartWith "lovetempo is the soul-drenched"
        description shouldContain "Support:"
        event.doorsTime shouldBe LocalTime.parse("19:00")
        event.startTime shouldBe LocalTime.parse("20:00")
        event.imageUrl.shouldNotBeNull() shouldStartWith "https://files.teamup.com/"
        event.priceNote.shouldBeNull()
    }

    @Test
    fun `reads the price note and keeps the listed start when no show time is stated`() {
        val listed = listed("matthias-mollner-fighters-in-the-crash-zone", "2026-10-16")

        val event = scraper.scrape(listed, fixtureDocument("hausderstatistik/hausderstatistik-event-mollner.html", listed.sourceUrl)).shouldNotBeNull()

        event.priceNote shouldBe "freier Eintritt"
        event.doorsTime.shouldBeNull()
        event.startTime shouldBe LocalTime.parse("19:00")
        event.description.shouldNotBeNull() shouldContain "Fotokunstbuch"
    }

    @Test
    fun `yields nothing from a page without an event article`() {
        val listed = listed("weg", "2026-10-16")

        scraper.scrape(listed, Jsoup.parse("<main><p>Seite nicht gefunden</p></main>", listed.sourceUrl)).shouldBeNull()
    }

    @Test
    fun `reads a price cell that is only a figure as the presale price`() {
        // Event 12931.
        val listed = listed("musotopia-fundraiser-for-the-burner-embassy-2", "2026-11-14")
        val page =
            Jsoup.parse(
                """<article class="event"><div class="entry-content"><div class="column is-8"><p>Live concerts</p></div>
                <div class="column is-4"><p><strong>Ticket/Kostenbeteiligung</strong><br>15</p></div></div></article>""",
                listed.sourceUrl
            )

        val event = scraper.scrape(listed, page).shouldNotBeNull()

        event.pricePresale?.compareTo(BigDecimal(15)) shouldBe 0
        event.priceNote.shouldBeNull()
    }
}
