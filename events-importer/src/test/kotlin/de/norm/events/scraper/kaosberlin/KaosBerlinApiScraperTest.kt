package de.norm.events.scraper.kaosberlin

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class KaosBerlinApiScraperTest {
    private val scraper = KaosBerlinApiScraper()

    private val fixture by lazy {
        javaClass.classLoader
            .getResourceAsStream("scraper/kaosberlin/kaosberlin-events.json")!!
            .bufferedReader()
            .readText()
    }

    private val page by lazy { scraper.scrapePage(fixture) }

    private fun event(slug: String) = page.events.single { it.sourceId == "kaos_berlin:$slug" }

    private fun names(slug: String) = event(slug).artists.map { it.name }

    /** The fixture's first event alone, with [title], [categories] and [website] in place of its own; null when skipped. */
    private fun variant(
        title: String,
        categories: List<String> = emptyList(),
        website: String = ""
    ): ScrapedEvent? =
        JsonMapper().let { mapper ->
            val root = mapper.readTree(fixture) as ObjectNode
            val events = root.withArray("events")
            val night = events.first() as ObjectNode
            night.put("title", title)
            night.put("website", website)
            val categoryNodes = night.putArray("categories")
            categories.forEach { categoryNodes.addObject().put("name", it) }
            events.removeAll()
            events.add(night)
            scraper.scrapePage(mapper.writeValueAsString(root)).events.singleOrNull()
        }

    @Test
    fun `reads every event except the five queer meet-ups`() {
        page.events shouldHaveSize 27
        page.events.none { "queer" in it.sourceId } shouldBe true
        page.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a concert with its lineup, its price and its promoter`() {
        val concert = event("crashpad-psychrusader-slothtronaut-live-kaos-berlin")

        concert.title shouldBe "CRASHPAD + PSYCHRUSADER + SLOTHTRONAUT"
        concert.eventType shouldBe "CONCERT"
        concert.typeIsFallback shouldBe false
        concert.eventDate shouldBe LocalDate.of(2026, 10, 17)
        concert.startTime shouldBe LocalTime.of(19, 0)
        concert.endDate shouldBe LocalDate.of(2026, 10, 17)
        concert.endTime shouldBe LocalTime.of(22, 0)
        concert.sourceUrl shouldBe "https://kaosberlin.de/veranstaltung/crashpad-psychrusader-slothtronaut-live-kaos-berlin/"
        concert.ticketUrl.shouldNotBeNull() shouldStartWith "https://eventfrog.de/en/p/concerts/hard-rock-heavy-metal/crashpad-"
        concert.pricePresale shouldBe BigDecimal("15.52")
        concert.priceNote.shouldBeNull()
        concert.free shouldBe false
        concert.imageUrl shouldBe "https://kaosberlin.de/wp-content/uploads/2026/10/2026-09-20-kaos-konzert-17-10-2026-1920x915-tilt-w.webp"
        concert.description.shouldNotBeNull() shouldStartWith "On Saturday the 17th of October"
        concert.promoters shouldContainExactly listOf("Failsafe")
        names("crashpad-psychrusader-slothtronaut-live-kaos-berlin") shouldContainExactly
            listOf("CRASHPAD", "PSYCHRUSADER", "SLOTHTRONAUT")
    }

    @Test
    fun `reads a concert from the title marker when the event has no category`() {
        val prefixed = event("concert-octavio-papalini-cedrik-fermont-jacqnoise-bero-snaut")
        prefixed.title shouldBe "Octavio Papalini, Cedrik Fermont, Jacqnoise, Bero Snaut"
        prefixed.eventType shouldBe "CONCERT"
        names("concert-octavio-papalini-cedrik-fermont-jacqnoise-bero-snaut") shouldContainExactly
            listOf("Octavio Papalini", "Cedrik Fermont", "Jacqnoise", "Bero Snaut")

        val suffixed = event("data-animal-musseldog-the-darrancinis-concert")
        suffixed.title shouldBe "Data Animal, Musseldog, The Darrancinis"
        suffixed.eventType shouldBe "CONCERT"
        suffixed.typeIsFallback shouldBe false
    }

    @Test
    fun `splits a lineup joined by a bare plus`() {
        names("slava-bogublind-arrowheaddusk-saffronthe-darrancinis") shouldContainExactly
            listOf("slava bogu", "blind arrowhead", "dusk saffron", "the darrancinis")
        event("slava-bogublind-arrowheaddusk-saffronthe-darrancinis").priceNote shouldBe "15€ – 30€"
    }

    @Test
    fun `bills no act for a concert titled with a series name`() {
        val series = event("mmm-event")

        series.eventType shouldBe "CONCERT"
        series.artists.shouldBeEmpty()
    }

    @Test
    fun `types an uncategorised event by the house's format words`() {
        event("10-kom-bass-open-air-club").eventType shouldBe "PARTY"
        event("endless-groove-sessions-with-lefto-early-bird-7hr-set-open-air").eventType shouldBe "PARTY"
        event("save-the-date-kaos-summer-party").eventType shouldBe "PARTY"
        event("improtheater-playandwork").eventType shouldBe "COMEDY"
        event("lesung-kompass-fuer-eine-migrationspolitik-mit-herz-und-hirn").eventType shouldBe "READING"
        event("infoabend-save-kaos").eventType shouldBe "OTHER"
        event("infoabend-save-kaos").typeIsFallback shouldBe true
    }

    @Test
    fun `types an event by its category`() {
        event("never-now-open-air").eventType shouldBe "PARTY"
        event("out-of-order-openair-w-dj-rino-evin").eventType shouldBe "PARTY"
        event("out-of-order-openair-w-dj-rino-evin").artists.shouldBeEmpty()
        event("32841").eventType shouldBe "SCREENING"
        event("how-high-stand-up-comedy").eventType shouldBe "COMEDY"
    }

    @Test
    fun `reads free entry, a donation and an unknown price`() {
        event("32841").free shouldBe true
        event("lesung-kompass-fuer-eine-migrationspolitik-mit-herz-und-hirn").free shouldBe true

        val donation = event("impro-comedy-im-kaos")
        donation.priceNote shouldBe "Spende"
        donation.free shouldBe false
        donation.pricePresale.shouldBeNull()

        val unknown = event("never-now-open-air")
        unknown.priceNote.shouldBeNull()
        unknown.free shouldBe false
    }

    @Test
    fun `drops a social profile as the ticket link and cleans a tracked one`() {
        event("guido-rauch-the-smokers-concert").ticketUrl.shouldBeNull()
        event("impro-comedy-in-kaos").ticketUrl.shouldBeNull()
        event("pop-up-session-ride-the-twist").ticketUrl shouldBe
            "https://eventfrog.de/de/p/konzerte/indie-alternative/pop-up-session-ride-the-twist-7463334222394054172.html"
        variant("A + B", website = "https://tickets.example/a?fbclid=x&amp;id=7").shouldNotBeNull().ticketUrl shouldBe
            "https://tickets.example/a?id=7"
    }

    @Test
    fun `leaves the house out of the promoters`() {
        event("finale-der-futura-biennale-podiumsduiskussion").promoters shouldContainExactly listOf("Futura Biennale")
    }

    @Test
    fun `skips a meet-up by its category or its title`() {
        variant("Queerer Treff Schöneweide").shouldBeNull()
        variant("Offener Abend", categories = listOf("Meetup")).shouldBeNull()
        variant("Offener Abend").shouldNotBeNull().eventType shouldBe "OTHER"
    }

    @Test
    fun `cuts the live marker with the venue name from a title`() {
        val live = variant("BAND ONE + BAND TWO live @ Kaos Berlin").shouldNotBeNull()

        live.title shouldBe "BAND ONE + BAND TWO"
        live.eventType shouldBe "CONCERT"
        live.artists.map { it.name } shouldContainExactly listOf("BAND ONE", "BAND TWO")
    }

    @Test
    fun `returns an empty last page for a body that is not JSON`() {
        val broken = scraper.scrapePage("<html>")

        broken.events.shouldBeEmpty()
        broken.nextPageUrl.shouldBeNull()
    }
}
