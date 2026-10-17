package de.norm.events.scraper.torhausberlin

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [TorhausBerlinOverviewPageScraper], against a saved copy of the German start page. */
class TorhausBerlinOverviewPageScraperTest {
    private val scraper = TorhausBerlinOverviewPageScraper()
    private val baseUrl = "https://torhausberlin.de/de"

    private fun fixture() =
        javaClass.classLoader
            .getResourceAsStream("scraper/torhausberlin/torhausberlin-overview.html")!!
            .bufferedReader()
            .readText()

    private fun scrapeFixture() = scraper.scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)

    /** A page of [cards] whose flight payload carries [keys], pushed through the same chunk path as the live page. */
    private fun scrapeCards(
        cards: String,
        keys: List<String>
    ) = JsonMapper
        .builder()
        .build()
        .writeValueAsString(keys.joinToString(",") { """["${'$'}","div","$it",{}]""" })
        .let { chunk ->
            val html = """<html><body>$cards<script>self.__next_f.push([1,$chunk])</script></body></html>"""
            scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
        }

    private fun card(
        title: String,
        times: String,
        badge: String? = null
    ) = """<article><div><p>OKT</p><p class="font-heading">23</p>${badge?.let { "<span>$it</span>" }.orEmpty()}</div>
        |<p class="line-clamp-4">$title</p><p class="font-mono">$times</p></article>
        """.trimMargin()

    @Test
    fun `scrape reads the radio nights and leaves out the Kuefa`() {
        val events = scrapeFixture()

        events.map { it.title } shouldContainExactly
            listOf("THF Presents: Love on the Air vol. 2 x MUCHA", "THF Presents: AM Morning finds Matters of Care")
    }

    @Test
    fun `scrape takes the date from the payload and the end day from the card`() {
        val event = scrapeFixture().first()

        event.eventDate shouldBe LocalDate.of(2026, 10, 23)
        event.startTime shouldBe LocalTime.of(18, 0)
        event.endDate shouldBe LocalDate.of(2026, 10, 24)
        event.endTime shouldBe LocalTime.MIDNIGHT
        event.eventType shouldBe EventType.PARTY.name
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "torhaus_berlin:2188836235-2026-10-23"
    }

    @Test
    fun `scrape keeps each DJ once and drops the repeated show name`() {
        val events = scrapeFixture()

        events[0].artists.map { it.name } shouldContainExactly listOf("jass:minute", "Arekipe", "RAW DJ", "Zoran", "elva", "Ephémera")
        events[0].artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
        events[1].artists.map { it.name } shouldContainExactly listOf("Johann Florin", "museum of no art", "Isa Schwarzenberg")
    }

    @Test
    fun `scrape ends a same-day range on its own day`() {
        val event = scrapeFixture()[1]

        event.eventDate shouldBe LocalDate.of(2026, 12, 11)
        event.startTime shouldBe LocalTime.of(18, 0)
        event.endDate shouldBe LocalDate.of(2026, 12, 11)
        event.endTime shouldBe LocalTime.of(22, 45)
    }

    @Test
    fun `scrape types an unbadged event by its title and bills no DJ`() {
        val events = scrapeCards(card("Ausstellung im Torhaus w/ Band A", "20:00 – 23:00"), listOf("TU42:1792771200000"))

        events shouldHaveSize 1
        events[0].title shouldBe "Ausstellung im Torhaus"
        events[0].eventType shouldBe EventType.EXHIBITION.name
        events[0].artists.map { it.role } shouldContainExactly listOf("HEADLINER")
    }

    @Test
    fun `scrape leaves the times empty for an all-day event`() {
        val event = scrapeCards(card("Ausstellung", "Ganztägig"), listOf("TU7:1792713600000")).single()

        event.eventDate shouldBe LocalDate.of(2026, 10, 23)
        event.startTime.shouldBeNull()
        event.endDate.shouldBeNull()
    }

    @Test
    fun `scrape skips the page when the cards and the payload keys disagree`() {
        scrapeCards(card("Ausstellung", "18:00 – 20:00"), emptyList()).shouldBeEmpty()
    }
}
