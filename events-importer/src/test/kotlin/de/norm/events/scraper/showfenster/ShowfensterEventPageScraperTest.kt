package de.norm.events.scraper.showfenster

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ShowfensterEventPageScraperTest {
    private val scraper = ShowfensterEventPageScraper()

    private val listed =
        ScrapedEvent(
            title = "A Life In Colour - Konzert",
            eventType = "CONCERT",
            eventDate = LocalDate.of(2026, 11, 18),
            sourceUrl = "https://www.showfenster-show.de/event-details/a-life-in-colour-konzert",
            sourceId = "showfenster:119cd77f-e565-4df8-9a7f-385c37255174",
            artists = listOf(ScrapedArtist(name = "A Life In Colour", titleDerived = true))
        )

    @Test
    fun `bills the players under Es spielen in place of the work's title`() {
        // Event 12296: the title names a work, the life of a painter set to music, and the page lists who plays it.
        val document = fixtureDocument("showfenster/showfenster-event-a-life-in-colour.html", listed.sourceUrl)

        val event = scraper.enrich(listed, document)

        event?.artists?.map { it.name } shouldBe listOf("Jonathan Bratoëff", "Richard Maegraith", "Marcel Krömker")
        event?.artists?.none { it.titleDerived } shouldBe true
    }

    @Test
    fun `keeps the title's act when the page names no players`() {
        val document = Jsoup.parse(page("""{"type":"PARAGRAPH","nodes":[{"type":"TEXT","textData":{"text":"Jazz aus Berlin."}}]}"""))

        scraper.enrich(listed, document) shouldBe listed
    }

    @Test
    fun `a page without the Wix payload yields nothing, so the stored lineup stays`() {
        scraper.enrich(listed, Jsoup.parse("<html><body></body></html>")).shouldBeNull()
    }

    @Test
    fun `reads players up to the first line of another shape`() {
        val players =
            playersOf(
                listOf(
                    "Die Musik zeigt den Weg: durch ihr Leben.",
                    "",
                    "Es spielen:",
                    "Anna Beispiel: Gesang",
                    "Jean-Luc de la Test : Kontrabass",
                    "Karten an der Abendkasse.",
                    "Noch Jemand: Klavier"
                )
            )

        players shouldBe listOf("Anna Beispiel", "Jean-Luc de la Test")
    }

    @Test
    fun `reads no players without the heading`() {
        playersOf(listOf("Anna Beispiel: Gesang")).shouldBeEmpty()
    }

    private fun page(paragraphs: String): String =
        """<html><head><script type="application/json" id="wix-warmup-data">""" +
            """{"appsWarmupData":{"140603ad-af8d-84a5-2c80-a0f60cb47351":{"EventsPageInitialState":{"event":{"event":""" +
            """{"id":"e1","title":"Max Stramm Quintett - Konzert","longDescription":{"nodes":[$paragraphs]}}}}}}}""" +
            """</script></head><body></body></html>"""
}
