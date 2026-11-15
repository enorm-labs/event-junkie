package de.norm.events.scraper.renate

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The description of a Renate row, on the homepage as it read on 2026-11-10 (#5104). */
class RenateOverviewPageScraperDescriptionTest {
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val baseUrl = "https://www.renate.cc/"
    private val events = RenateOverviewPageScraper(clock).scrape(fixtureDocument("renate/renate-overview-2026-11-10.html", baseUrl), baseUrl)

    @Test
    fun `takes a night's own prose as its description`() {
        events.first { it.title == "Renate NYE 2026/27" }.description.shouldNotBeNull() shouldStartWith "5 Floors, 68 Hours of Non-Stop Music!"
        events.first { it.title.startsWith("BENEFIT: PROTECT THE DOLLS") }.description.shouldNotBeNull() shouldContain
            "This is a benefit event in solidarity with The Dolls & Casa Kua."
    }

    @Test
    fun `leaves out the info block every row repeats`() {
        // The fixture holds the series text in eight `.info-text` blocks.
        events.mapNotNull { it.description }.forEach { it shouldNotContain "Our House is Your House for the winter" }
    }

    @Test
    fun `leaves the description empty for a night of names only`() {
        events.first { it.title == "Renate Klubnacht w/ Carly Zeng & CREAM" }.description.shouldBeNull()
    }
}
