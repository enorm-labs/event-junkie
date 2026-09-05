package de.norm.events.scraper.huxleys

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [HuxleysDetailPageScraper].
 *
 * Uses real `/event/<slug>` snapshots covering a plain show, a sold-out one, a cancelled one, and
 * one that both states a price and is relocated. The taxonomy assertions matter most: genre and
 * promoter exist only as slugs on the `article` element, so they are what a theme change would
 * silently drop.
 */
class HuxleysDetailPageScraperTest {
    private val scraper = HuxleysDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent? {
        val url = "https://huxleysneuewelt.de/event/$slug"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/huxleys/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    @Test
    fun `parses every field of a detail page`() {
        val thievery = scrape("huxleys-detail-soldout.html", "2026-08-02-thievery-corporation")
        thievery.shouldNotBeNull()
        // The page renders no heading; the title comes from og:title minus the site suffix.
        thievery.title shouldBe "Thievery Corporation"
        thievery.subtitle shouldBe "30TH ANNIVERSARY TOUR"
        thievery.eventType shouldBe "CONCERT"
        thievery.eventDate shouldBe LocalDate.of(2026, 8, 2)
        thievery.startTime shouldBe LocalTime.of(20, 0)
        thievery.doorsTime shouldBe LocalTime.of(19, 0)
        thievery.soldOut shouldBe true
        thievery.sourceId shouldBe "huxleys:2026-08-02-thievery-corporation"
        thievery.description.shouldNotBeNull() shouldStartWith "Zeitgeistige Clubkultur"
        thievery.imageUrl.shouldNotBeNull() shouldStartWith "https://huxleysneuewelt.de/wp-content/uploads/"
        thievery.ticketUrl.shouldNotBeNull() shouldStartWith "https://www.eventim.de/"
    }

    @Test
    fun `reads genre and promoter from the article's taxonomy slugs`() {
        val thievery = scrape("huxleys-detail-soldout.html", "2026-08-02-thievery-corporation")
        thievery.shouldNotBeNull()
        thievery.genre shouldBe "Electronic, Fusion, Indietronica"
        thievery.promoters shouldContainExactly listOf("Trinity Music")
    }

    @Test
    fun `reads the promoter from the hero credit, keeping its own spelling`() {
        val kard = scrape("huxleys-detail-simple.html", "2026-09-01-kard")
        kard.shouldNotBeNull()
        kard.genre shouldBe "Kpop"
        kard.promoters shouldContainExactly listOf("Concert Concept Veranstaltungs-GmbH")
        kard.subtitle shouldBe "Europe Tour"
        kard.startTime shouldBe LocalTime.of(19, 30)
        kard.doorsTime shouldBe LocalTime.of(18, 30)
    }

    @Test
    fun `reads a cancelled show from its badge`() {
        val rockLegends = scrape("huxleys-detail-cancelled.html", "2026-11-06-rock-legends")
        rockLegends.shouldNotBeNull()
        rockLegends.status shouldBe "CANCELLED"
        rockLegends.soldOut shouldBe false
        rockLegends.promoters shouldContainExactly listOf("Manfred Hertlein Veranstaltungs GmbH")
        // This page carries no genre taxonomy at all.
        rockLegends.genre.shouldBeNull()
    }

    @Test
    fun `parses a stated price and keeps the fee qualifier as a note`() {
        val currentJoys = scrape("huxleys-detail-relocated.html", "2026-08-18-current-joys")
        currentJoys.shouldNotBeNull()
        currentJoys.pricePresale shouldBe BigDecimal("28")
        currentJoys.priceBoxOffice.shouldBeNull()
        currentJoys.priceNote shouldBe "VVK: 28 € (zzgl. Gebühr)"
        currentJoys.free shouldBe false
    }

    @Test
    fun `leaves prices null when the page states none`() {
        val kard = scrape("huxleys-detail-simple.html", "2026-09-01-kard")
        kard.shouldNotBeNull()
        kard.pricePresale.shouldBeNull()
        kard.priceBoxOffice.shouldBeNull()
        kard.priceNote.shouldBeNull()
    }

    @Test
    fun `reads the promoter's full name where the taxonomy slug lost its first word`() {
        // The Dresden Dolls: the hero credits "Konzertbüro Schoneberg presents", the slug is
        // `promoters-schoneberg` — a Berlin district, not a promoter (#1139).
        val dolls = scrape("huxleys-detail-schoneberg.html", "2026-09-05-the-dresden-dolls")
        dolls.shouldNotBeNull()
        dolls.promoters shouldContainExactly listOf("Konzertbüro Schoneberg")
    }

    @Test
    fun `falls back to the taxonomy slug when the hero shows no credit`() {
        val html =
            """
            <html><head><title>Some Act - Huxleys Neue Welt</title></head><body>
              <article class="post-1 event promoters-trinity-music"><div class="details"></div></article>
            </body></html>
            """.trimIndent()
        val event =
            scraper.scrape(
                Jsoup.parse(html, "https://huxleysneuewelt.de/event/2026-09-05-some-act"),
                "https://huxleysneuewelt.de/event/2026-09-05-some-act"
            )
        event.shouldNotBeNull()
        event.promoters shouldContainExactly listOf("Trinity Music")
    }

    @Test
    fun `does not read the media presenters as promoters`() {
        // The page also carries presenters-bedroomdisco / -bytefm / -diffus / -rausgegangen.
        val currentJoys = scrape("huxleys-detail-relocated.html", "2026-08-18-current-joys")
        currentJoys.shouldNotBeNull()
        currentJoys.promoters shouldContainExactly listOf("Puschen")
    }

    @Test
    fun `returns null for a page without an event article`() {
        val url = "https://huxleysneuewelt.de/event/2026-08-02-thievery-corporation"
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", url), url).shouldBeNull()
    }

    @Test
    fun `derives no artists when the page has no support line`() {
        val kard = scrape("huxleys-detail-simple.html", "2026-09-01-kard")
        kard.shouldNotBeNull()
        // A CONCERT title is still the headliner; the tour name is not an act.
        kard.artists.map { it.name } shouldContainExactly listOf("KARD")
        kard.artists.filter { it.role == "SUPPORT" }.shouldBeEmpty()
    }
}
