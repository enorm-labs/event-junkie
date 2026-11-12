package de.norm.events.scraper.eightmm

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class EightMmEventPageScraperTest {
    private val scraper = EightMmEventPageScraper()

    private fun listed(title: String) =
        ScrapedEvent(
            title = title,
            eventDate = LocalDate.of(2026, 10, 17),
            startTime = LocalTime.of(19, 0),
            sourceUrl = "https://www.8mmbar.de/program/2026/10/17/x",
            sourceId = "eight_mm:x"
        )

    @Test
    fun `reads the blurb and the doors and show times`() {
        val event = scraper.enrich(listed("RMFC LIVE in 8MM"), fixtureDocument("eightmm/eightmm-event-rmfc.html", "https://www.8mmbar.de/program"))

        event.description!! shouldStartWith "ROUGH’n’ROLL Concerts presents:\nR.M.F.C. (Sydney, Australia)"
        event.description shouldContain "Aftershow DJ set Liam Carrol"
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `bills a festival day from the blurb's line-up list`() {
        // Event 12287: the title names the day, and the acts are only in the blurb.
        val html =
            """
            <article class="eventitem"><div class="eventitem-column-content"><div class="sqs-html-content">
            <p>8MM Fest returns for two nights.</p><p>Line up -</p>
            <p>ABIGAIL TOLL / ACTIVEMIRROR / BLACK BORDELLO /CALVIN LOVE / I WANT POETRY / YARD + MORE</p>
            </div></div></article>
            """.trimIndent()

        val event = scraper.enrich(listed("8MM FEST DAY 1"), Jsoup.parse(html))

        event.artists.map { it.name } shouldBe
            listOf("Abigail Toll", "Activemirror", "Black Bordello", "Calvin Love", "I Want Poetry", "Yard")
    }

    @Test
    fun `drops the bracket after an act in the line-up list`() {
        // The 8MM Fest warm-up show on 2026-11-12, whose blurb bills the Copenhagen act as `WEDDING (CPN)`.
        val html =
            """
            <article class="eventitem"><div class="eventitem-column-content"><div class="sqs-html-content">
            <p>Line up - WEDDING (CPN) / LYZZA / MAF</p>
            </div></div></article>
            """.trimIndent()

        val event = scraper.enrich(listed("8MM Fest WARM UP SHOW"), Jsoup.parse(html))

        event.artists.map { it.name } shouldBe listOf("Wedding", "Lyzza", "Maf")
    }

    @Test
    fun `keeps the acts a house-frame title bills over a line-up list`() {
        val html =
            """<article class="eventitem"><div class="eventitem-column-content"><div class="sqs-html-content"><p>Line up: SOMEONE ELSE</p></div></div></article>"""
        val listed = listed("8MM Live: SOFT PALMS").copy(artists = listOf(ScrapedArtist(name = "SOFT PALMS", role = ArtistRole.HEADLINER)))

        scraper.enrich(listed, Jsoup.parse(html)).artists.map { it.name } shouldBe listOf("SOFT PALMS")
    }

    @Test
    fun `a page without a blurb leaves the listed event`() {
        val listed = listed("JEFF CLARK (BLACK LIPS) SUNDAY'S IN OCTOBER DJ RESIDENCY")

        val event = scraper.enrich(listed, fixtureDocument("eightmm/eightmm-event-residency.html", "https://www.8mmbar.de/program"))

        event shouldBe listed
        event.description.shouldBeNull()
    }
}
