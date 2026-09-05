package de.norm.events.scraper.hole44

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [Hole44DetailPageScraper].
 *
 * Uses real detail-page snapshots: a fully-populated concert (promoter, support,
 * multiple genres, doors, image, JSON-LD description), a relocated show, and a
 * single-genre concert with no support act and an ampersand in its promoter name.
 */
class Hole44DetailPageScraperTest {
    private val scraper = Hole44DetailPageScraper()

    private fun parse(
        fixture: String,
        url: String
    ): ScrapedEvent? {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/hole44/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    @Test
    fun `parses all detail fields for a fully populated concert`() {
        val url = "https://hole-berlin.de/event/2026-08-02-municipal-waste/"
        val event = parse("hole44-detail-standard.html", url).shouldNotBeNull()

        event.title shouldBe "Municipal Waste"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 8, 2)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.genre shouldBe "crossover trash, Hardcore-Punk, Trash Metal"
        event.status shouldBe "SCHEDULED"
        event.sourceUrl shouldBe url
        event.sourceId shouldBe "hole44:2026-08-02-municipal-waste"
        // Description and image come from the schema.org Event JSON-LD block.
        event.description.shouldNotBeNull() shouldStartWith "Crossover Thrash Metal"
        event.imageUrl shouldBe "https://hole-berlin.de/wp-content/uploads/2026/02/municipal-waste-berlin.webp"
        event.promoters shouldContainExactly listOf("Trinity Music")
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist("Municipal Waste", "HEADLINER"),
                ScrapedArtist("Battlecreek", "SUPPORT")
            )
    }

    @Test
    fun `reads the relocation status from the detail header`() {
        val url = "https://hole-berlin.de/event/2026-11-20-luke-combs-uk-tribute/"
        val event = parse("hole44-detail-relocated.html", url).shouldNotBeNull()

        event.status shouldBe "RELOCATED"
        event.promoters shouldContainExactly listOf("FKP Scorpio")
        event.genre shouldBe "Country"
    }

    @Test
    fun `strips the presents credit and keeps an ampersand in the promoter name`() {
        val url = "https://hole-berlin.de/event/2026-07-27-kang-yuchan/"
        val event = parse("hole44-detail-simple.html", url).shouldNotBeNull()

        // "Weird World Booking & Promotion GmbH presents" → the trailing credit is stripped.
        event.promoters shouldContainExactly listOf("Weird World Booking & Promotion GmbH")
        event.doorsTime shouldBe LocalTime.of(18, 0)
        event.startTime shouldBe LocalTime.of(19, 0)
        event.artists shouldContainExactly listOf(ScrapedArtist("KANG YUCHAN", "HEADLINER"))
        event.artists.filter { it.role == "SUPPORT" }.shouldBeEmpty()
    }

    @Test
    fun `reads the ticket shop link from the Tickets button`() {
        // Modern English, 5 September 2026: "Tickets / Eventim" is an `a.button.ticket` anchor (#1140).
        val url = "https://hole-berlin.de/event/2026-09-05-modern-english/"
        val event = parse("hole44-detail-ticket-button.html", url).shouldNotBeNull()

        event.title shouldBe "Modern English"
        event.ticketUrl.shouldNotBeNull() shouldStartWith "https://www.eventim.de/noapp/event/modern-english-hole-44-21500096/"
        event.status shouldBe "RELOCATED"
    }

    @Test
    fun `returns null for a page without an event title`() {
        val url = "https://hole-berlin.de/event/missing/"
        parse2(url) shouldBe null
    }

    private fun parse2(url: String): ScrapedEvent? = scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url)
}
