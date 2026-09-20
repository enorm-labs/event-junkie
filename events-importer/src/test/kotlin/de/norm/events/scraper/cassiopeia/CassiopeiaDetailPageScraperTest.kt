package de.norm.events.scraper.cassiopeia

import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldNotBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [CassiopeiaDetailPageScraper].
 *
 * Tests the pure HTML parsing logic in isolation, without HTTP fetching
 * or overview page fallback. Focuses on artist extraction edge cases
 * that are most naturally tested against the detail page scraper directly.
 */
class CassiopeiaDetailPageScraperTest {
    private val scraper = CassiopeiaDetailPageScraper()

    /**
     * Builds a minimal Cassiopeia detail page HTML with the given [title],
     * [category], and optional [descriptionParagraphs].
     */
    private fun buildDetailHtml(
        title: String,
        category: String = "Konzert",
        descriptionParagraphs: List<String> = emptyList()
    ): String {
        val paragraphs =
            descriptionParagraphs.joinToString("\n") {
                """<div class="paragraph events">$it</div>"""
            }
        return """
            <html><body>
            <div class="modul-section events">
                <h1 class="event-date dark event">$title</h1>
                <div class="text-block-wrapper events">
                    <div class="date-wrapper">
                        <div class="subheading invert">01</div>
                        <div class="subheading invert">.</div>
                        <div class="subheading invert">06</div>
                        <div class="subheading invert">.</div>
                        <div class="subheading invert event-mobile opposite">2026</div>
                    </div>
                </div>
                <div class="text-block-wrapper events">
                    <div class="subheading invert gap">$category</div>
                </div>
                <div class="paragraph-wrapper">$paragraphs</div>
            </div>
            </body></html>
            """.trimIndent()
    }

    @Test
    fun `scrape extracts headliner and support from concert with support line`() {
        val html = buildDetailHtml("Pharmakon", descriptionParagraphs = listOf("Support: Aska", "Bio text."))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/pharmakon-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/pharmakon-123")

        event shouldNotBe null
        event!!.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Pharmakon", role = "HEADLINER", titleDerived = true),
                ScrapedArtist(name = "Aska", role = "SUPPORT")
            )
    }

    @Test
    fun `scrape extracts title as headliner from concert without support line`() {
        val html = buildDetailHtml("Pharmakon", descriptionParagraphs = listOf("Bio text, no support line."))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/pharmakon-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/pharmakon-123")

        event shouldNotBe null
        // CONCERT confirms the title is the headliner, even with no "Support:" line
        event!!.artists shouldContainExactly listOf(ScrapedArtist(name = "Pharmakon", role = "HEADLINER", titleDerived = true))
    }

    @Test
    fun `scrape filters an event-name title (festival slot) rather than minting a fake artist`() {
        val html = buildDetailHtml("Grey City Fest Opener", descriptionParagraphs = listOf("A festival event."))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/grey-city-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/grey-city-123")

        event shouldNotBe null
        // "Grey City Fest Opener" is a festival slot name, filtered by isNonArtistName
        event!!.artists.shouldBeEmpty()
    }

    @ParameterizedTest(name = "scrape filters out placeholder headliner: \"{0}\"")
    @ValueSource(strings = ["TBA", "tba", "TBD", "N.N.", "t.b.a."])
    fun `scrape filters out placeholder headliner names`(placeholder: String) {
        val html = buildDetailHtml(placeholder, descriptionParagraphs = listOf("Support: Real Artist"))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/tba-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/tba-123")

        event shouldNotBe null
        // Placeholder headliner is filtered, only the real support act remains
        event!!.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Real Artist", role = "SUPPORT")
            )
    }

    @ParameterizedTest(name = "scrape extracts headliner when support act is placeholder: \"{0}\"")
    @ValueSource(strings = ["TBA", "tba", "TBD", "N.N.", "t.b.a."])
    fun `scrape extracts headliner even when support act is a placeholder`(placeholder: String) {
        val html = buildDetailHtml("Döll", descriptionParagraphs = listOf("Support: $placeholder"))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/doell-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/doell-123")

        event shouldNotBe null
        // "Support: TBA" confirms the headliner pattern — title IS the artist.
        // The placeholder support act is filtered from the output,
        // but the headliner is still extracted.
        event!!.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Döll", role = "HEADLINER", titleDerived = true)
            )
    }

    @Test
    fun `scrape does not extract artists from party events`() {
        val html = buildDetailHtml("Super Tuesday", category = "Party", descriptionParagraphs = listOf("Party time!"))
        val doc = Jsoup.parse(html, "https://cassiopeia-berlin.de/event/super-tuesday-123")
        val event = scraper.scrape(doc, "https://cassiopeia-berlin.de/event/super-tuesday-123")

        event shouldNotBe null
        event!!.artists.shouldBeEmpty()
    }
}
