package de.norm.events.scraper.hole44

import de.norm.events.event.EventType
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.fixtureDocument
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The detail page's own description decides a comma the title cannot (#1832): Hole 44 bills
 * `Myki, Darlene & Nini` and describes `Myki Meeks, Darlene Mitchell, and Nini Coco`, so the acts
 * are billed under names only the blurb carries. A tour title joined by an ampersand bills no act (#5195).
 */
class Hole44CoBillTest {
    private val scraper = Hole44DetailPageScraper()
    private val sourceUrl = "https://hole-berlin.de/event/2026-10-07-myki-darlene-nini/"

    @Test
    fun `bills the acts the description names in full`() {
        val event = scraper.scrape(fixtureDocument("hole44/hole44-detail-cobill.html", sourceUrl), sourceUrl)!!
        event.title shouldBe "Myki, Darlene & Nini"
        event.artists.map { it.name } shouldBe listOf("Myki Meeks", "Darlene Mitchell", "Nini Coco")
    }

    @Test
    fun `bills a duo the title joins with an ampersand as one act`() {
        // Event 14164: Caramella & YDRA tour as one act.
        buildArtistsForEventType("Caramella & YDRA", null, EventType.CONCERT.name).map { it.name } shouldBe listOf("Caramella & YDRA")
    }

    @Test
    fun `bills no act from a tour title joined with an ampersand`() {
        // Event 14512: a label's tour, not the acts `H1GHR Season In Europe` and `UK` (#5195).
        hole44Artists("H1GHR SEASON IN EUROPE & UK", null, EventType.CONCERT.name).shouldBeEmpty()
    }

    @Test
    fun `keeps the support line of a tour title`() {
        hole44Artists("Some Label Tour & Friends", "Support: Act One", EventType.CONCERT.name).map { it.name } shouldBe listOf("Act One")
    }

    @Test
    fun `keeps a tribute act whose name carries a place without an ampersand`() {
        // Event 628 bills its tribute band under the title.
        hole44Artists("Luke Combs UK Tribute", null, EventType.CONCERT.name).map { it.name } shouldBe
            buildArtistsForEventType("Luke Combs UK Tribute", null, EventType.CONCERT.name).map { it.name }
    }

    @Test
    fun `still bills two acts an ampersand joins`() {
        // Event 634.
        hole44Artists("DONNA SAVAGE & BRENK SINATRA", null, EventType.CONCERT.name).map { it.name } shouldBe
            listOf("DONNA SAVAGE", "BRENK SINATRA")
    }
}
