package de.norm.events.scraper.matrix

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MatrixOverviewPageScraper].
 *
 * Parses the saved July (the current-month entry page), August, September and November 2026 month
 * snapshots. Every date comes from the block's own `DD-MM-YYYY` `id`, so no clock is needed.
 */
class MatrixOverviewPageScraperTest {
    private val scraper = MatrixOverviewPageScraper()

    private val entryUrl = "https://www.matrix-berlin.de/party-in-berlin/"
    private val augustUrl = "https://www.matrix-berlin.de/party-in-berlin/?get_month=8&get_year=2026"
    private val septemberUrl = "https://www.matrix-berlin.de/party-in-berlin/?get_month=9&get_year=2026"
    private val novemberUrl = "https://www.matrix-berlin.de/party-in-berlin/?get_month=11&get_year=2026"

    private fun monthPage(
        name: String,
        baseUrl: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/matrix/$name")!!
            .bufferedReader()
            .readText(),
        baseUrl
    )

    private val julyEvents by lazy { scraper.scrape(monthPage("matrix-month-july.html", entryUrl), entryUrl) }
    private val augustEvents by lazy { scraper.scrape(monthPage("matrix-month-august.html", augustUrl), augustUrl) }
    private val septemberEvents by lazy { scraper.scrape(monthPage("matrix-month-september.html", septemberUrl), septemberUrl) }

    @Test
    fun `parses every night on a month page`() {
        // The current-month page lists only the days still to come; a full month lists all of them.
        julyEvents shouldHaveSize 2
        augustEvents shouldHaveSize 31
        septemberEvents shouldHaveSize 30
    }

    @Test
    fun `extracts all fields of a night with a full lineup`() {
        val event = julyEvents.first { it.eventDate == LocalDate.of(2026, 7, 31) }

        event.title shouldBe "Matrix - Friday"
        event.eventType shouldBe EventType.PARTY.name
        event.startTime shouldBe LocalTime.of(22, 0)
        event.genre shouldBe "afrobeats, classics, Hip Hop, house, reggaeton, top40, electro, techno"
        event.imageUrl shouldBe
            "https://www.matrix-berlin.de/wp-content/uploads/2021/10/www.matrix-berlin.de-matrix-friday-v2-facebook-570x320.jpg"
        // Pinned to the explicit month-page form even though it was scraped from the bare entry URL,
        // so the URL survives the month rollover unchanged.
        event.sourceUrl shouldBe "https://www.matrix-berlin.de/party-in-berlin/?get_month=7&get_year=2026#31-07-2026"
        event.sourceId shouldBe "matrix:2026-07-31-matrix-friday"
        // The blurb keeps the venue's own line breaks.
        event.description!! shouldContain "Mainhall:\nDJ SIZE"
        // No starred promo and no entry block on this night.
        event.subtitle.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
        event.priceNote.shouldBeNull()
        // Matrix sells no advance tickets — only table reservations, which are not a ticket shop.
        event.ticketUrl.shouldBeNull()
        event.pricePresale.shouldBeNull()
    }

    @Test
    fun `reads the DJs list as DJs and the specials list as support`() {
        val event = augustEvents.first { it.eventDate == LocalDate.of(2026, 8, 1) }

        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "Kevin Miller", role = "DJ"),
                // "DJ JC & DJ GUS" is a duo billing of two residents…
                ScrapedArtist(name = "DJ JC", role = "DJ"),
                ScrapedArtist(name = "DJ GUS", role = "DJ"),
                // …while a parenthesized b2b alias stays one act.
                ScrapedArtist(name = "KORE (Eg0 B2B Kopolookoo)", role = "DJ"),
                // The "Specials:" list bills a guest on top of the residents.
                ScrapedArtist(name = "MC Caramel", role = "SUPPORT")
            )
    }

    @Test
    fun `maps the entry block to the lowest door price plus a tier breakdown`() {
        val event = augustEvents.first { it.eventDate == LocalDate.of(2026, 8, 3) }

        // "► Entry : / 10,00 € Ladies / 12,00 € Gents" — the lowest tier is the "from" price…
        event.priceBoxOffice shouldBe BigDecimal("10.00")
        // …both tiers are kept verbatim, and the starred promo names a € amount, so it follows them.
        event.priceNote shouldBe "10,00 € Ladies, 12,00 € Gents; Nur 5€ Eintritt für Ladies & Studenten bis 0 Uhr!"
        // The promo repeats on nearly every night: it is no subtitle, and its 5 € is no door price.
        event.subtitle.shouldBeNull()
    }

    @Test
    fun `keeps a ladies-free-entry night off the free flag`() {
        val event = augustEvents.first { it.eventDate == LocalDate.of(2026, 8, 5) }

        // "Freier Eintritt für Ladies bis 0 Uhr!" names no € amount, so it goes nowhere. Ladies get in
        // free until midnight; everyone else pays. In priceNote, detectFree() would mark the night free.
        event.subtitle.shouldBeNull()
        event.priceNote!! shouldNotContain "Freier"
        event.free shouldBe false
        event.toEventEntity(venueId = 1L, venueSlug = "matrix", eventSourceId = 1L).free shouldBe false
    }

    @Test
    fun `stores no night's promo banner as its subtitle`() {
        (augustEvents + septemberEvents).map { it.subtitle }.distinct() shouldContainExactly listOf(null)
    }

    @Test
    fun `yields no lineup or genre for a night the venue has not announced yet`() {
        val event = septemberEvents.first { it.eventDate == LocalDate.of(2026, 9, 1) }

        // The blurb still says "DJ TBA" — there is no DJs list and no genre list on the block.
        event.artists.shouldBeEmpty()
        event.genre.shouldBeNull()
        // The rest of the night is announced as usual.
        event.title shouldBe "Matrix - Tuesday"
        event.startTime shouldBe LocalTime.of(22, 0)
        event.priceBoxOffice shouldBe BigDecimal("10.00")
    }

    @Test
    fun `returns no events for a month the venue has no programme for`() {
        val november = monthPage("matrix-month-november.html", novemberUrl)

        scraper.scrape(november, novemberUrl).shouldBeEmpty()
    }

    @Test
    fun `returns no events for a page without event blocks`() {
        val emptyDoc = Jsoup.parse("<html><body><p>Geschlossen</p></body></html>", entryUrl)

        scraper.scrape(emptyDoc, entryUrl).shouldBeEmpty()
    }
}
