package de.norm.events.scraper.admiralspalast

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AdmiralspalastListingPageScraper] against a saved snapshot of the A–Z listing and
 * one category-filtered copy of it.
 */
class AdmiralspalastListingPageScraperTest {
    private val scraper = AdmiralspalastListingPageScraper()
    private val listingUrl = "https://www.admiralspalast.theater/veranstaltungsuebersicht.html"

    @Test
    fun `reads every production once, though each tile links to it several times`() {
        val urls = scraper.scrapeProductionUrls(fixtureDocument("admiralspalast/admiralspalast-overview.html", listingUrl), listingUrl)
        urls shouldHaveSize 100
        urls.distinct() shouldHaveSize 100
        urls shouldContain "https://www.admiralspalast.theater/veranstaltung/abba-gold-the-concert-show-emotion.html"
    }

    @Test
    fun `resolves the links to absolute URLs and drops the fragment`() {
        val urls = scraper.scrapeProductionUrls(fixtureDocument("admiralspalast/admiralspalast-overview.html", listingUrl), listingUrl)
        urls.all { it.startsWith("https://www.admiralspalast.theater/veranstaltung/") } shouldBe true
        urls.none { it.contains('#') } shouldBe true
    }

    @Test
    fun `reads the category filter pages the venue offers`() {
        val genres = scraper.scrapeGenreUrls(fixtureDocument("admiralspalast/admiralspalast-overview.html", listingUrl), listingUrl)
        genres.size shouldBe 20
        genres["https://www.admiralspalast.theater/veranstaltungsuebersicht/eventkategorie/comedy.html"] shouldBe "Comedy"
        genres["https://www.admiralspalast.theater/veranstaltungsuebersicht/eventkategorie/musical.html"] shouldBe "Musical"
    }

    @Test
    fun `a category page lists only its own productions`() {
        val comedyUrl = "https://www.admiralspalast.theater/veranstaltungsuebersicht/eventkategorie/comedy.html"
        val urls = scraper.scrapeProductionUrls(fixtureDocument("admiralspalast/admiralspalast-genre-comedy.html", listingUrl), comedyUrl)
        urls shouldHaveSize 21
        urls shouldContain "https://www.admiralspalast.theater/veranstaltung/buelent-ceylan-diktatuerk.html"
    }

    @Test
    fun `returns nothing for a page without a listing`() {
        val empty = Jsoup.parse("<html><body></body></html>", listingUrl)
        scraper.scrapeProductionUrls(empty, listingUrl).shouldBeEmpty()
        scraper.scrapeGenreUrls(empty, listingUrl).isEmpty() shouldBe true
    }
}
