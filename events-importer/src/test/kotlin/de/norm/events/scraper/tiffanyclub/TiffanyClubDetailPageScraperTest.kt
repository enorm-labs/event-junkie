package de.norm.events.scraper.tiffanyclub

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** Unit tests for [TiffanyClubDetailPageScraper]. */
class TiffanyClubDetailPageScraperTest {
    private val scraper = TiffanyClubDetailPageScraper()

    private val eventUrl = "https://tiffany-berlin.de/event/x/"

    @Test
    fun `reads the blurb and leaves the guest-list note out`() {
        val description = scraper.scrapeDescription(fixtureDocument("tiffanyclub/tiffanyclub-detail.html", eventUrl))!!

        description shouldStartWith "🔥 LATIN HELL"
        description shouldNotContain "Hinweis zur Gästeliste"
        description shouldNotContain "Tischreservierung"
    }

    @Test
    fun `returns null for a page without a blurb`() {
        scraper.scrapeDescription(fixtureDocument("tiffanyclub/tiffanyclub-detail-private.html", eventUrl)).shouldBeNull()
    }
}
