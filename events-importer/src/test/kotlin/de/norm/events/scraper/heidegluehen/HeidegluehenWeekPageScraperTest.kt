package de.norm.events.scraper.heidegluehen

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Unit tests for [HeidegluehenWeekPageScraper].
 *
 * Parses static snapshots of Heideglühen's `/aktuell/` page for deterministic, offline-safe testing
 * without HTTP fetching — one taken while the lineup was still unannounced, one after it went up.
 */
class HeidegluehenWeekPageScraperTest {
    private val scraper = HeidegluehenWeekPageScraper()
    private val sourceUrl = "https://heidegluehen.berlin/aktuell/"

    private fun scrape(fixture: String): HeidegluehenLineup? {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/heidegluehen/heidegluehen-aktuell-$fixture.html")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, sourceUrl))
    }

    @Test
    fun `reads the announced lineup in the order the venue bills it`() {
        val lineup = scrape("mit-lineup")!!
        lineup.date shouldBe LocalDate.of(2026, 6, 6)
        lineup.artists.map { it.name } shouldBe
            listOf(
                "Antal",
                "Marcel Dettmann",
                "Shonky",
                "Roman Flügel",
                "Dr Banana",
                "Isaac Carter",
                "Thilini",
                "Forsberg",
                "Violetta & Lucas Depta",
                "Woody"
            )
        lineup.artists.map { it.role }.toSet() shouldBe setOf("DJ")
    }

    @Test
    fun `drops the label or city a DJ is billed under`() {
        // "Antal // Rush Hour, NL" — the tail is a label, not part of the name.
        val lineup = scrape("mit-lineup")!!
        lineup.artists.none { it.name.contains("//") } shouldBe true
        lineup.artists.none { it.name.contains("Rush Hour") } shouldBe true
    }

    @Test
    fun `reads no name twice from the running order`() {
        // The set times below the billing repeat every name; none may be added again.
        val lineup = scrape("mit-lineup")!!
        lineup.artists shouldHaveSize 10
        lineup.artists.map { it.name }.toSet() shouldHaveSize 10
        lineup.artists.none { it.name.contains(":") } shouldBe true
        // "Finale b2b" closes the running order and is not a billed act.
        lineup.artists.none { it.name.contains("Finale") } shouldBe true
    }

    @Test
    fun `times every billed DJ from the running order, from Saturday noon into Sunday night`() {
        val sets = scrape("mit-lineup")!!.artists.associate { it.name to (it.setStart to it.setEnd) }

        sets.values.none { it.first == null } shouldBe true
        sets.getValue("Forsberg") shouldBe (Instant.parse("2026-06-06T10:00:00Z") to Instant.parse("2026-06-06T14:00:00Z"))
        // "22:00-01:00" ends on Sunday, and "01:00-04:00" after it starts there.
        sets.getValue("Roman Flügel") shouldBe (Instant.parse("2026-06-06T20:00:00Z") to Instant.parse("2026-06-06T23:00:00Z"))
        sets.getValue("Isaac Carter") shouldBe (Instant.parse("2026-06-06T23:00:00Z") to Instant.parse("2026-06-07T02:00:00Z"))
        // The second "12:00" is Sunday's; a name wrapped over two source lines still matches its billing.
        sets.getValue("Shonky").first shouldBe Instant.parse("2026-06-07T10:00:00Z")
        sets.getValue("Marcel Dettmann") shouldBe (Instant.parse("2026-06-07T16:00:00Z") to Instant.parse("2026-06-07T19:00:00Z"))
        sets.getValue("Violetta & Lucas Depta").first shouldBe Instant.parse("2026-06-07T06:00:00Z")
    }

    @Test
    fun `carries the party's own flyer, which supersedes the month's artwork`() {
        scrape("mit-lineup")!!.imageUrl!! shouldEndWith "2600606_Heide18.gif"
    }

    @Test
    fun `states nothing while the lineup is still unannounced`() {
        // "Das Programm folgt am Dienstag…" — the page names the date but no DJ.
        scrape("ohne-lineup").shouldBeNull()
    }

    @Test
    fun `applies its lineup and flyer to the matching event, changing nothing else`() {
        val event =
            ScrapedEvent(
                title = "Heideglühen #18",
                eventDate = LocalDate.of(2026, 6, 6),
                imageUrl = "https://heidegluehen.berlin/month.gif",
                sourceUrl = "https://heidegluehen.berlin/monatsvorschau/",
                sourceId = "heidegluehen:2026-06-06"
            )
        val enriched = scrape("mit-lineup")!!.applyTo(event)

        enriched.artists shouldHaveSize 10
        enriched.imageUrl!! shouldEndWith "2600606_Heide18.gif"
        enriched.title shouldBe event.title
        enriched.eventDate shouldBe event.eventDate
        enriched.sourceId shouldBe event.sourceId
    }

    @Test
    fun `keeps the month artwork when the week page publishes no flyer of its own`() {
        val event =
            ScrapedEvent(
                title = "Heideglühen #18",
                eventDate = LocalDate.of(2026, 6, 6),
                imageUrl = "https://heidegluehen.berlin/month.gif",
                sourceUrl = "https://heidegluehen.berlin/monatsvorschau/",
                sourceId = "heidegluehen:2026-06-06"
            )
        val enriched = HeidegluehenLineup(event.eventDate, emptyList(), imageUrl = null).applyTo(event)

        enriched.imageUrl shouldBe "https://heidegluehen.berlin/month.gif"
        enriched.artists.shouldBeEmpty()
    }

    @Test
    fun `returns null for a page with no date at all`() {
        val document = Jsoup.parse("<html><body><div class='fl-rich-text'><p>Happyhappyheide!</p></div></body></html>", sourceUrl)
        scraper.scrape(document).shouldBeNull()
    }
}
