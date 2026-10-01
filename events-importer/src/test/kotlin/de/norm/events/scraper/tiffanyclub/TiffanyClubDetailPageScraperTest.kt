package de.norm.events.scraper.tiffanyclub

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Unit tests for [TiffanyClubDetailPageScraper]. */
class TiffanyClubDetailPageScraperTest {
    private val scraper = TiffanyClubDetailPageScraper()

    private fun page(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/tiffanyclub/$name")!!
                .bufferedReader()
                .readText(),
            "https://tiffany-berlin.de/event/x/"
        )

    @Test
    fun `reads the blurb and leaves the guest-list note out`() {
        val description = scraper.scrapeDescription(page("tiffanyclub-detail.html"))!!

        description shouldStartWith "🔥 LATIN HELL"
        description shouldNotContain "Hinweis zur Gästeliste"
        description shouldNotContain "Tischreservierung"
    }

    @Test
    fun `returns null for a page without a blurb`() {
        scraper.scrapeDescription(page("tiffanyclub-detail-private.html")).shouldBeNull()
    }
}
