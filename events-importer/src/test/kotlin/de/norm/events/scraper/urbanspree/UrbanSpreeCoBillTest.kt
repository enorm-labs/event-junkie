package de.norm.events.scraper.urbanspree

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The page's own `Live:` block decides the comma the title carries (#1832): `FLAME PARADE, ALICE
 * GIFT + ANNA GROB` is three acts, and only the blurb separates the first two.
 */
class UrbanSpreeCoBillTest {
    private val scraper = UrbanSpreeDetailPageScraper()
    private val sourceUrl = "https://www.urbanspree.com/program/concerts/flame-parade,-alice-gift-anna-grob-29.10.2026-urban-spree-berlin.html"

    @Test
    fun `bills each act the Live block names`() {
        val event = scraper.scrape(fixtureDocument("urbanspree/urbanspree-detail-cobill.html", sourceUrl), sourceUrl)!!
        event.title shouldBe "FLAME PARADE, ALICE GIFT + ANNA GROB"
        event.artists.map { it.name } shouldBe listOf("FLAME PARADE", "ALICE GIFT", "ANNA GROB")
    }
}
