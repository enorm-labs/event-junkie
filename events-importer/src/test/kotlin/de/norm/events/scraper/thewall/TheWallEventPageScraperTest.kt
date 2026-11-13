package de.norm.events.scraper.thewall

import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

class TheWallEventPageScraperTest {
    private val scraper = TheWallEventPageScraper()
    private val url = "https://thewallcomedy.com/events/2026-11-14-the-wall-comedy-saturday-night-live-standup-early-comedy-showcase/"

    @Test
    fun `reads the description with a blank line between its paragraphs, and the spacer paragraphs add none`() {
        val description = scraper.description(fixtureDocument("thewall/thewall-event.html", url)).shouldNotBeNull()

        description shouldStartWith
            "The perfect pre dinner, pre drinks plan! Don't miss the comedy pregame Saturday Night Live Standup! " +
            "Quality comedy from 6:30 -7:30pm at Boxi\n\nJoin us for a night of laughter in Friedrichshain"
        description shouldNotContain "\n\n\n"
        description shouldNotContain "About This Event"
    }

    @Test
    fun `a page without the description block gives none`() {
        scraper.description(Jsoup.parse("<html><body><div class=\"card-body\"></div></body></html>", url)).shouldBeNull()
    }
}
