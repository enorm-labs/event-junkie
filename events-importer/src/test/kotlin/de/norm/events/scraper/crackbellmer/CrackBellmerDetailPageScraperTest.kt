package de.norm.events.scraper.crackbellmer

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/**
 * Unit tests for [CrackBellmerDetailPageScraper].
 *
 * The event page contributes only a blurb, so these cover the two shapes it comes in: filled, with
 * the Webflow rich-text editor's zero-width-joiner spacer paragraphs between the real ones, and
 * empty, which is what a bar night or a closed day renders.
 */
class CrackBellmerDetailPageScraperTest {
    private val scraper = CrackBellmerDetailPageScraper()
    private val pageUrl = "https://www.crackbellmer.de/events/bad-dad-yr8lk"

    @Test
    fun `joins the blurb's paragraphs and drops the spacer lines`() {
        val description = scraper.scrapeDescription(fixtureDocument("crackbellmer/crackbellmer-detail-blurb.html", pageUrl))

        description shouldBe
            "We're standing at the peak of summer and if not now, when? Join us on our terrace, in our garden or " +
            "preferably; on our dance floor! The night is neither young nor old, all that matters is our time here " +
            "together. Let's celebrate!\n" +
            "Crack Bellmer opens at 19:00, free entry until 22:00 when the DJs start.\n" +
            "Come early, stay late, tip the bar!"
        // The spacer paragraphs hold a zero-width joiner, which is not whitespace.
        description!!.shouldNotContain("‍")
    }

    @Test
    fun `returns null when the page carries no blurb`() {
        scraper.scrapeDescription(fixtureDocument("crackbellmer/crackbellmer-detail-no-blurb.html", pageUrl)).shouldBeNull()
    }

    @Test
    fun `returns null for a page without the event content wrapper`() {
        scraper.scrapeDescription(Jsoup.parse("<html><body><main></main></body></html>", pageUrl)).shouldBeNull()
    }
}
