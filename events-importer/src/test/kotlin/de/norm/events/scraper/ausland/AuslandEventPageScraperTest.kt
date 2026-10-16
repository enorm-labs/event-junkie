package de.norm.events.scraper.ausland

import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalTime

class AuslandEventPageScraperTest {
    private val scraper = AuslandEventPageScraper()
    private val frictiveUrl = "https://ausland.berlin/event/lorena-izquierdo-mizuki-ishikawa-matthias-koole-frictive-frequencies"

    @Test
    fun `reads the doors, the start and the price note of a concert night`() {
        val event = scraper.scrape(document("ausland-event-frictive-frequencies.html", frictiveUrl), frictiveUrl).shouldNotBeNull()

        event.title shouldBe "Lorena Izquierdo / Mizuki Ishikawa / Matthias Koole / Frictive Frequencies"
        event.eventDate shouldBe UNRESOLVED_EVENT_DATE
        event.doorsTime shouldBe LocalTime.of(19, 0)
        event.startTime shouldBe LocalTime.of(20, 0)
        event.priceNote shouldBe "Tickets (only at the door) from 10+ €"
        event.sourceId shouldBe "ausland:lorena-izquierdo-mizuki-ishikawa-matthias-koole-frictive-frequencies"
    }

    @Test
    fun `keeps the text up to the house's accessibility notes`() {
        val description = scraper.scrape(document("ausland-event-frictive-frequencies.html", frictiveUrl), frictiveUrl)?.description.shouldNotBeNull()

        description shouldStartWith "Lorena Izquierdo (voice)\nMizuki Ishikawa (electronics, objects)\nMatthias Koole (Guitar)\nDoors open 19h"
        description shouldContain "The concert series Frictive Frequencies"
        description shouldNotContain "Accessibility"
        description shouldNotContain "Next and last date"
    }

    @Test
    fun `reads the German text without the line-up disclaimer`() {
        val url = "https://ausland.berlin/de/event/lorena-izquierdo-mizuki-ishikawa-matthias-koole-frictive-frequencies"
        val description = scraper.description(document("ausland-event-frictive-frequencies-de.html", url)).shouldNotBeNull()

        description shouldContain "Einlass 19 Uhr | Konzert+Gespräche 20 Uhr"
        description shouldContain "Die Konzertreihe Frictive Frequencies"
        description shouldNotContain "Änderungen der Line-ups"
    }

    @Test
    fun `reads free entry as the price note and no doors where none is stated`() {
        val url = "https://ausland.berlin/event/eclat-crew-music-making-meetup-1"
        val event = scraper.scrape(document("ausland-event-eclat-crew.html", url), url).shouldNotBeNull()

        event.priceNote shouldBe "Free entry, registration required."
        event.doorsTime.shouldBeNull()
    }

    @Test
    fun `reads a donation range as the price note`() {
        val url = "https://ausland.berlin/event/vinyl-hours-record-club"
        val event = scraper.scrape(document("ausland-event-vinyl-hours.html", url), url).shouldNotBeNull()

        event.priceNote shouldBe "Donation suggestion: € 5,00 - € 20"
    }

    @Test
    fun `returns null for a page without an event article`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Not found</p></body></html>", frictiveUrl), frictiveUrl).shouldBeNull()
    }

    private fun document(
        name: String,
        url: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/ausland/$name")!!
            .bufferedReader()
            .readText(),
        url
    )
}
