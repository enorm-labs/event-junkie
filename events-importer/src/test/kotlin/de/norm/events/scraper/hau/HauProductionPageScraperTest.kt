package de.norm.events.scraper.hau

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class HauProductionPageScraperTest {
    private val scraper = HauProductionPageScraper()
    private val seedStoryUrl = pdetail("machina-ex-tadpole-repertory-a-seed-story")

    @Test
    fun `reads the text, poster and a single price with its reduction as the note`() {
        val production = scraper.scrape(fixtureDocument("hau/hau-production-soapskin.html", pdetail("soapskin-konzert"))).shouldNotBeNull()

        production.description.shouldNotBeNull() shouldStartWith "The Austrian Anja Plaschg, known as SOAP&SKIN"
        production.imageUrl.shouldNotBeNull() shouldStartWith "https://www.hebbel-am-ufer.de/fileadmin/_processed_/4/0/"
        production.pricePresale shouldBe BigDecimal("42")
        production.priceNote shouldBe "42 €, reduced 22 €"
    }

    @Test
    fun `takes the lowest full price of a range`() {
        val production = scraper.scrape(fixtureDocument("hau/hau-production-isabelle-schad.html", pdetail("isabelle-schad-piece-of-changes"))).shouldNotBeNull()

        production.pricePresale shouldBe BigDecimal("20")
        production.priceNote shouldBe "€20–25, reduced €12–15"
    }

    @Test
    fun `reads the price line alone, after a capacity note or before the surtitle dates`() {
        val surtitled = scraper.scrape(fixtureDocument("hau/hau-production-she-she-pop.html", pdetail("she-she-pop-alles-moegliche"))).shouldNotBeNull()
        val lowCapacity = scraper.scrape(fixtureDocument("hau/hau-production-seed-story.html", seedStoryUrl)).shouldNotBeNull()

        surtitled.pricePresale shouldBe BigDecimal("22")
        surtitled.priceNote shouldBe "22 €, reduced 12 €"
        lowCapacity.pricePresale shouldBe BigDecimal("22")
        lowCapacity.priceNote shouldBe "22 €, reduced 12 €"
    }

    @Test
    fun `ignores a combi-ticket paragraph and keeps a free-entry line`() {
        val html =
            """<div id="prodDetail"><div class="noteBox"><p>Free admission</p><p>Combi tickets:<br>3 events for 30–50 €</p></div></div>"""

        val production = scraper.scrape(Jsoup.parse(html)).shouldNotBeNull()

        production.pricePresale.shouldBeNull()
        production.priceNote shouldBe "Free admission"
    }

    @Test
    fun `leaves the price empty where the page names none`() {
        val production = scraper.scrape(fixtureDocument("hau/hau-production-break-wau.html", pdetail("break-dj-set-kidcat-broccoli"))).shouldNotBeNull()

        production.pricePresale.shouldBeNull()
        production.priceNote.shouldBeNull()
        production.description.shouldNotBeNull()
    }

    @Test
    fun `applies the page to a performance and keeps what the page lacks`() {
        val event =
            ScrapedEvent(
                title = "SOAP&SKIN",
                eventDate = LocalDate.of(2026, 10, 6),
                sourceUrl = "https://www.hebbel-am-ufer.de/en/programme/pdetail/soapskin-konzert/",
                sourceId = "hau1:soapskin-konzert-2026-10-06-2000",
                imageUrl = "https://example.org/listing.jpg"
            )

        val applied = HauProduction(description = "Text", imageUrl = null, pricePresale = BigDecimal("42"), priceNote = null).applyTo(event)

        applied.description shouldBe "Text"
        applied.imageUrl shouldBe "https://example.org/listing.jpg"
        applied.pricePresale shouldBe BigDecimal("42")
    }

    @Test
    fun `returns null for a page that is no production`() {
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>")).shouldBeNull()
    }

    private fun pdetail(slug: String) = "https://www.hebbel-am-ufer.de/en/programme/pdetail/$slug/"
}
