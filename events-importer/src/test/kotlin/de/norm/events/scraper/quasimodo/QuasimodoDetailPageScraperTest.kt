package de.norm.events.scraper.quasimodo

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Unit tests for [QuasimodoDetailPageScraper].
 *
 * Parses static snapshots of Quasimodo `/events/<slug>-<postId>` pages for deterministic,
 * offline-safe testing without HTTP fetching.
 */
class QuasimodoDetailPageScraperTest {
    private val scraper = QuasimodoDetailPageScraper()

    private fun scrape(
        fixture: String,
        slug: String
    ): ScrapedEvent {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/quasimodo/quasimodo-detail-$fixture.html")!!
                .bufferedReader()
                .readText()
        val sourceUrl = "https://quasimodo.club/events/$slug"
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!
    }

    @Test
    fun `maps a fully populated detail page`() {
        val otisKane = scrape("otis-kane", "otis-kane-7410")
        otisKane.title shouldBe "Otis Kane"
        otisKane.doorsTime.toString() shouldBe "21:00"
        otisKane.startTime.toString() shouldBe "22:00"
        otisKane.pricePresale shouldBe BigDecimal("30")
        otisKane.priceNote shouldBe "ab 30€ (zzgl. Gebühr)"
        otisKane.promoters shouldBe listOf("FKP Scorpio")
        otisKane.genre shouldBe "Neo-Soul, Rhythm and Blues"
        otisKane.sourceId shouldBe "quasimodo:otis-kane-7410"
        otisKane.ticketUrl!! shouldStartWith "https://www.eventim.de/"
        otisKane.imageUrl!! shouldStartWith "https://quasimodo.club/wp-content/uploads/"
        otisKane.description!! shouldContain "Los Angeles"
    }

    @Test
    fun `reads a cancellation from the page's alert`() {
        val cancelled = scrape("otis-kane-cancelled", "otis-kane-7410")
        cancelled.status shouldBe EventStatus.CANCELLED.name
        cancelled.title shouldBe "Otis Kane"
    }

    @Test
    fun `keeps a page without an alert scheduled`() {
        listOf("otis-kane", "we-love-80s", "disco-inferno", "berlin-beat-invasion").forEach { fixture ->
            scrape(fixture, "$fixture-1").status shouldBe EventStatus.SCHEDULED.name
        }
    }

    @Test
    fun `ignores a cancellation that only the description mentions`() {
        val html =
            """<article class="type-event"><h1>Otis Kane</h1>
            <div class="panel-collapse description"><p>Die Show vom März war abgesagt.</p></div></article>"""
        val sourceUrl = "https://quasimodo.club/events/otis-kane-7410"
        scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl)!!.status shouldBe EventStatus.SCHEDULED.name
    }

    @Test
    fun `leaves the date to the listing`() {
        // The detail page renders day/month/year as separate spans; the listing's mobile block
        // already carries a complete date, so this page deliberately does not parse one.
        scrape("otis-kane", "otis-kane-7410").eventDate shouldBe UNRESOLVED_EVENT_DATE
    }

    @Test
    fun `types an event the venue tagged as a party`() {
        val weLove80s = scrape("we-love-80s", "we-love-80s-37-7300")
        weLove80s.eventType shouldBe EventType.PARTY.name
        // A party's title is an event name, not an act.
        weLove80s.artists.shouldBeEmpty()
    }

    @Test
    fun `prefers party over concerts when the venue tagged both`() {
        // Disco Inferno carries "event-categories-concerts event-categories-party"; the party
        // reading is what keeps its event name out of the artist list.
        val discoInferno = scrape("disco-inferno", "disco-inferno-65-7289")
        discoInferno.eventType shouldBe EventType.PARTY.name
        discoInferno.artists.shouldBeEmpty()
    }

    @Test
    fun `falls back to title inference when the venue tagged no category`() {
        val otisKane = scrape("otis-kane", "otis-kane-7410")
        otisKane.eventType shouldBe EventType.CONCERT.name
        otisKane.artists.map { it.name } shouldBe listOf("Otis Kane")
    }

    @Test
    fun `reads both the presale and the box-office price`() {
        val weLove80s = scrape("we-love-80s", "we-love-80s-37-7300")
        weLove80s.pricePresale shouldBe BigDecimal("12.80")
        weLove80s.priceBoxOffice shouldBe BigDecimal("15")
        weLove80s.priceNote shouldBe "ab 12,80€ (zzgl. Gebühr)"
    }

    @Test
    fun `leaves the box-office price empty when the venue lists no Tageskasse row`() {
        scrape("otis-kane", "otis-kane-7410").priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `leaves the promoter empty for an in-house night`() {
        val berlinBeat = scrape("berlin-beat-invasion", "berlin-beat-invasion-no-8-7316")
        berlinBeat.promoters.shouldBeEmpty()
        berlinBeat.genre.shouldBeNull()
        berlinBeat.pricePresale shouldBe BigDecimal("31")
    }

    @Test
    fun `returns null for a page without a title`() {
        val sourceUrl = "https://quasimodo.club/events/otis-kane-7410"
        val document = Jsoup.parse("<html><body><main></main></body></html>", sourceUrl)
        scraper.scrape(document, sourceUrl).shouldBeNull()
    }
}
