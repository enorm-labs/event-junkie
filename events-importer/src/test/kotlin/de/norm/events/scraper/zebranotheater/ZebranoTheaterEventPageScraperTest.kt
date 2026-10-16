package de.norm.events.scraper.zebranotheater

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ZebranoTheaterEventPageScraperTest {
    private val scraper = ZebranoTheaterEventPageScraper()

    private fun page(date: String): Pair<ScrapedEvent, org.jsoup.nodes.Document> {
        val url = "https://www.zebrano-theater.de/programm/$date.shtml"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/zebranotheater/zebranotheater-$date.html")!!
                .bufferedReader()
                .readText()
        val event = ScrapedEvent(title = "t", eventDate = LocalDate.of(2026, 10, 4), sourceUrl = url, sourceId = "zebrano_theater:$date")
        return event to Jsoup.parse(html, url)
    }

    @Test
    fun `reads the text and the photo`() {
        val (event, document) = page("20261125")
        val enriched = scraper.scrape(event, document).shouldNotBeNull()
        enriched.imageUrl shouldBe "https://www.zebrano-theater.de/programm/beppe_gambetta_terra_madre.jpg"
        val description = enriched.description.shouldNotBeNull()
        description shouldStartWith "Beppe Gambetta hat neben seiner Figur als Akustikgitarrist"
        description shouldContain "\n\nFelix Meyer \"Kaum einem zweiten"
        description shouldNotContain "Mehr über"
        description shouldNotContain "Kartenbestellung"
        description shouldNotContain "Spezialgast"
        description shouldNotContain "Programmübersicht"
    }

    @Test
    fun `leaves the headings out of the text`() {
        val (event, document) = page("20261004")
        val description = scraper.scrape(event, document)?.description.shouldNotBeNull()
        description shouldStartWith "An jedem ersten Sonntag im Monat lädt Sebastian Krämer"
        description shouldNotContain "Club Genie und Wahnsinn\n"
        description shouldNotContain "Antragsteller"
    }

    @Test
    fun `yields nothing for a page without the programme column`() {
        val (event, _) = page("20261004")
        scraper.scrape(event, Jsoup.parse("<html><body><p>Seite nicht gefunden</p></body></html>")).shouldBeNull()
    }
}
