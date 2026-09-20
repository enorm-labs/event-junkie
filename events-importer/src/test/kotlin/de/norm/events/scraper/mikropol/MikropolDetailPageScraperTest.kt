package de.norm.events.scraper.mikropol

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MikropolDetailPageScraper].
 *
 * Uses real detail-page snapshots: a fully-populated sold-out concert (support,
 * description, image, ticket link), a cancelled show, a relocated show whose title prefix
 * is stripped, and a plain concert with no support act.
 */
class MikropolDetailPageScraperTest {
    private val scraper = MikropolDetailPageScraper()

    private fun parse(
        fixture: String,
        url: String
    ): ScrapedEvent? {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/mikropol/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    @Test
    fun `parses all detail fields for a fully populated sold-out concert`() {
        val url = "https://mikropol-berlin.de/event/2026-07-14-house-of-protection/"
        val event = parse("mikropol-detail-soldout.html", url).shouldNotBeNull()

        event.title shouldBe "HOUSE OF PROTECTION"
        event.eventType shouldBe "CONCERT"
        event.eventDate shouldBe LocalDate.of(2026, 7, 14)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.status shouldBe "SCHEDULED"
        event.soldOut shouldBe true
        event.sourceUrl shouldBe url
        event.sourceId shouldBe "mikropol:2026-07-14-house-of-protection"
        event.subtitle shouldBe "support: noise of the voiceless"
        event.description.shouldNotBeNull() shouldStartWith "House of Protection"
        event.imageUrl shouldBe "https://mikropol-berlin.de/wp-content/uploads/2026/04/house-of-protection-berlin-333x500-1.webp"
        event.ticketUrl.shouldNotBeNull() shouldStartWith "https://www.eventim.de/"
        event.artists shouldBe
            listOf(
                ScrapedArtist("HOUSE OF PROTECTION", "HEADLINER", titleDerived = true),
                ScrapedArtist("noise of the voiceless", "SUPPORT")
            )
    }

    @Test
    fun `reads the cancelled status from the badge`() {
        val url = "https://mikropol-berlin.de/event/2026-07-25-vowws/"
        val event = parse("mikropol-detail-cancelled.html", url).shouldNotBeNull()

        event.title shouldBe "Vowws"
        event.status shouldBe "CANCELLED"
        event.soldOut shouldBe false
        event.artists shouldBe listOf(ScrapedArtist("Vowws", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `strips the relocation prefix from the title and reads it as a relocated status`() {
        val url = "https://mikropol-berlin.de/event/2026-07-16-verlegt-in-den-frannz-club-culture-wars/"
        val event = parse("mikropol-detail-relocated.html", url).shouldNotBeNull()

        event.title shouldBe "CULTURE WARS"
        event.status shouldBe "RELOCATED"
        event.artists shouldBe listOf(ScrapedArtist("CULTURE WARS", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `parses a plain concert with no support act`() {
        val url = "https://mikropol-berlin.de/event/2026-07-17-dueja/"
        val event = parse("mikropol-detail-simple.html", url).shouldNotBeNull()

        event.title shouldBe "DUEJA"
        event.subtitle shouldBe null
        event.startTime shouldBe LocalTime.of(20, 0)
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.description.shouldNotBeNull() shouldStartWith "Schwarz und Rosa"
        event.artists shouldBe listOf(ScrapedArtist("DUEJA", "HEADLINER", titleDerived = true))
    }

    // The credit sits above the title as "<promoter> presents:"; a page without one stores none (#1532).
    @Test
    fun `reads the promoter credit above the title`() {
        val url = "https://mikropol-berlin.de/event/2026-09-17-dice/"
        val event = parse("mikropol-detail-promoter.html", url).shouldNotBeNull()

        event.title shouldBe "Dice"
        event.promoters shouldBe listOf("Trinity Music")
        parse("mikropol-detail-simple.html", "https://mikropol-berlin.de/event/2026-07-14-house-of-protection/")
            .shouldNotBeNull()
            .promoters shouldBe emptyList()
    }

    @Test
    fun `returns null for a page without an event title`() {
        val url = "https://mikropol-berlin.de/event/missing/"
        scraper.scrape(Jsoup.parse("<html><body></body></html>", url), url) shouldBe null
    }
}
