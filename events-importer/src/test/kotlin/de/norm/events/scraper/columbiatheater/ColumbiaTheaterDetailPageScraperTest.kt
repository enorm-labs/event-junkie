package de.norm.events.scraper.columbiatheater

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ColumbiaTheaterDetailPageScraper].
 *
 * Uses real `/event/<slug>/` snapshots covering the page variants that matter: a plain concert
 * (with a doubled ticket `href`), a show with media presenters, a cancelled show, a relocated show
 * with no times and no ticket block, and a rescheduled show whose header carries both its old and
 * its new date.
 */
class ColumbiaTheaterDetailPageScraperTest {
    private val scraper = ColumbiaTheaterDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent? {
        val url = "https://columbia-theater.de/event/$slug/"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/columbiatheater/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    @Test
    fun `parses every field of a plain concert page`() {
        val soulfly = scrape("columbiatheater-detail-concert.html", "20260803-soulfly")
        soulfly.shouldNotBeNull()
        soulfly.title shouldBe "Soulfly"
        soulfly.subtitle shouldBe "Tribal Technology Tour 2026 | Support: Botulism"
        soulfly.eventType shouldBe "CONCERT"
        soulfly.eventDate shouldBe LocalDate.of(2026, 8, 3)
        soulfly.startTime shouldBe LocalTime.of(20, 0)
        soulfly.doorsTime shouldBe LocalTime.of(19, 0)
        soulfly.status shouldBe "SCHEDULED"
        soulfly.sourceId shouldBe "columbia_theater:20260803-soulfly"
        soulfly.imageUrl shouldBe "https://columbia-theater.de/wp-content/uploads/2026/05/image-1024x683.webp"
        soulfly.description.shouldNotBeNull() shouldStartWith "Mit der Tribal Technology Tour 2026"
        soulfly.artists shouldContainExactly
            listOf(
                ScrapedArtist("Soulfly", "HEADLINER", titleDerived = true),
                ScrapedArtist("Botulism", "SUPPORT")
            )
        soulfly.promoters.shouldBeEmpty()
    }

    @Test
    fun `keeps only the first URL of a doubled ticket href`() {
        val soulfly = scrape("columbiatheater-detail-concert.html", "20260803-soulfly")
        soulfly.shouldNotBeNull().ticketUrl shouldBe
            "https://www.eventim.de/noapp/event/soulfly-tribal-technology-tour-2026-columbia-theater-21730842/" +
            "?affiliate=SZ3&utm_campaign=SzeneTickets+GmbH&utm_source=SZ3&utm_medium=dp"
    }

    @Test
    fun `reads the media presenters from the praesentiert-von credit`() {
        val temples = scrape("columbiatheater-detail-presenters.html", "20261029-temples")
        temples.shouldNotBeNull()
        temples.promoters shouldContainExactly listOf("DIFFUS", "Bedroomdisco", "MusikBlog", "FluxFM", "Musikexpress")
        temples.ticketUrl shouldBe
            "https://www.eventim.de/event/temples-2026-uk-eu-bliss-tour-columbia-theater-21537121/?affiliate=SZ3"
        temples.artists shouldContainExactly
            listOf(
                ScrapedArtist("Temples", "HEADLINER", titleDerived = true),
                ScrapedArtist("Jewls", "SUPPORT")
            )
    }

    @Test
    fun `reads a cancelled show and its Special Guest billing row`() {
        val oreillys = scrape("columbiatheater-detail-cancelled.html", "20261002-the-oreillys-and-the-paddyhats")
        oreillys.shouldNotBeNull()
        oreillys.status shouldBe "CANCELLED"
        oreillys.ticketUrl.shouldBeNull()
        oreillys.startTime shouldBe LocalTime.of(20, 0)
        oreillys.artists shouldContainExactly
            listOf(
                ScrapedArtist("The O'Reillys and The Paddyhats", "HEADLINER", titleDerived = true),
                ScrapedArtist("Harpyie", "SUPPORT")
            )
    }

    @Test
    fun `reads a relocated show whose header line carries no times`() {
        val turbopaolo = scrape("columbiatheater-detail-relocated.html", "20260928-turbopaolo")
        turbopaolo.shouldNotBeNull()
        turbopaolo.status shouldBe "RELOCATED"
        turbopaolo.eventDate shouldBe LocalDate.of(2026, 9, 28)
        turbopaolo.startTime.shouldBeNull()
        turbopaolo.doorsTime.shouldBeNull()
        turbopaolo.description.shouldNotBeNull()
    }

    @Test
    fun `takes the new date, not the header's previous date, for a rescheduled show`() {
        val kmfdm = scrape("columbiatheater-detail-postponed.html", "20270325-kmfdm")
        kmfdm.shouldNotBeNull()
        kmfdm.status shouldBe "POSTPONED"
        kmfdm.eventDate shouldBe LocalDate.of(2027, 3, 25)
        kmfdm.startTime shouldBe LocalTime.of(20, 0)
        kmfdm.doorsTime shouldBe LocalTime.of(19, 0)
        // The page's ticket href concatenates the old and the new shop link.
        kmfdm.ticketUrl shouldBe "https://www.eventim.de/event/kmfdm-europe-20262027-columbia-theater-20909224/"
        // This page's blurb is empty, so no description is stored.
        kmfdm.description.shouldBeNull()
        kmfdm.subtitle.shouldBeNull()
    }

    @Test
    fun `returns null for a page without an event content block`() {
        val url = "https://columbia-theater.de/event/20260803-soulfly/"
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", url), url).shouldBeNull()
    }
}
