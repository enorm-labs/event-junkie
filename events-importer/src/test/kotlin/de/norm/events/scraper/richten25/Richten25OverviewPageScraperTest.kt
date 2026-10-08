package de.norm.events.scraper.richten25

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class Richten25OverviewPageScraperTest {
    private val baseUrl = "https://richten25.de/events"
    private val events = Richten25OverviewPageScraper().scrape(Jsoup.parse(fixture("richten25-events.html"), baseUrl), baseUrl)

    @Test
    fun `reads the upcoming nights from the embed in the island props and skips the past ones`() {
        events.size shouldBe 9
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 8)
        events.last().eventDate shouldBe LocalDate.of(2026, 10, 23)
        events.first().sourceId shouldBe "richten25:2026-10-08"
        events.forEach { it.eventType shouldBe EventType.CONCERT.name }
        events.first().title shouldBe "Mia Zabelka, Edith Steyer & Łukasz Marciniak // Zone Null & Réka Csiszér"
    }

    @Test
    fun `bills each musician of a set, and a band for a prefixed set`() {
        events.first().artists.map { it.name } shouldBe listOf("Mia Zabelka", "Edith Steyer", "Łukasz Marciniak", "Zone Null", "Réka Csiszér")
        events.first { it.eventDate == LocalDate.of(2026, 10, 12) }.artists.map { it.name } shouldBe listOf("Schrödinger or Boom Boom God", "SLAP")
        events.first { it.eventDate == LocalDate.of(2026, 10, 23) }.artists.map { it.name } shouldBe listOf("Jon Elbaz Sextet")
    }

    @Test
    fun `bills a series' members rather than its name`() {
        val html =
            """<astro-island props="{&quot;x&quot;:[0,&quot;&lt;section class=\&quot;event-list\&quot;&gt;&lt;h2&gt;Upcoming Events&lt;/h2&gt;""" +
                """&lt;ul&gt;&lt;li&gt;&lt;strong&gt;October 4, 2026: &lt;/strong&gt; Knistern Series: Otis Mensah &amp; Nour Sokhon // Jumoke Adeyanju""" +
                """&lt;/li&gt;&lt;/ul&gt;&lt;/section&gt;&quot;]}"></astro-island>"""

        val night = Richten25OverviewPageScraper().scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        night.artists.map { it.name } shouldBe listOf("Otis Mensah", "Nour Sokhon", "Jumoke Adeyanju")
    }

    @Test
    fun `yields nothing for a page without the embed`() {
        Richten25OverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }

    private fun fixture(name: String): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/richten25/$name")!!
            .bufferedReader()
            .readText()
}
