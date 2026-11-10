package de.norm.events.scraper.maxxim

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.AcceptedLimitations
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.fixtureDocument
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MaxximOverviewPageScraper].
 *
 * Parses a static snapshot of MAXXIM's `/partys` page (whose events live in the
 * embedded `wix-warmup-data` JSON) for deterministic, offline-safe testing
 * without HTTP fetching. The states the live programme did not happen to show —
 * cancellation, sold out, tiered prices, missing slug/date — come from the
 * hand-crafted `maxxim-overview-edge-cases.html` variant.
 */
class MaxximOverviewPageScraperTest {
    private val scraper = MaxximOverviewPageScraper()
    private val baseUrl = "https://www.maxxim-berlin.de/partys"

    private val events: List<ScrapedEvent> by lazy { parse("maxxim-overview.html") }
    private val edgeCaseEvents: List<ScrapedEvent> by lazy { parse("maxxim-overview-edge-cases.html") }

    private fun parse(fixture: String): List<ScrapedEvent> = scraper.scrape(fixtureDocument("maxxim/$fixture", baseUrl), baseUrl)

    private fun event(sourceId: String): ScrapedEvent = events.first { it.sourceId == sourceId }

    private fun edgeCaseEvent(sourceId: String): ScrapedEvent = edgeCaseEvents.first { it.sourceId == sourceId }

    @Test
    fun `stores every night Disco, Charts, the club's sound, since it names no style`() {
        AcceptedLimitations.withHouseGenre(EventSource.MAXXIM, events).map { it.genre }.distinct() shouldBe listOf("Disco, Charts")
    }

    @Test
    fun `discovers every event in the warmup payload`() {
        events shouldHaveSize 18
    }

    @Test
    fun `maps a fully populated event`() {
        val millenium = event("maxxim:millenium-memories-90s-2000er-4")
        millenium.title shouldBe "MILLENIUM MEMORIES - 90s/2000er"
        millenium.eventType shouldBe EventType.PARTY.name
        millenium.eventDate shouldBe LocalDate.of(2026, 8, 1)
        millenium.startTime shouldBe LocalTime.of(22, 0)
        // `endDate: 2026-08-02T03:00:00.000Z` is 05:00 Berlin, the club's real closing (ADR-029).
        millenium.endDate shouldBe LocalDate.of(2026, 8, 2)
        millenium.endTime shouldBe LocalTime.of(5, 0)
        millenium.sourceUrl shouldBe "https://www.maxxim-berlin.de/event-details/millenium-memories-90s-2000er-4"
        millenium.imageUrl shouldBe "https://static.wixstatic.com/media/8948df_f54d38631a5a4644bf9b6c0caa656be5~mv2.png"
        millenium.pricePresale shouldBe BigDecimal("12.00")
        millenium.soldOut shouldBe false
        millenium.status shouldBe EventStatus.SCHEDULED.name
        millenium.description?.startsWith("Bereit fuer die beste Zeitreise") shouldBe true
    }

    @Test
    fun `converts the UTC start instant to the Berlin wall-clock time`() {
        // startDate is 2026-08-04T17:00:00Z; in Europe/Berlin (summer time) that is 19:00 — an
        // after-work party, not the 17:00 UTC value.
        val afterWork = event("maxxim:far-out-after-work-11")
        afterWork.eventDate shouldBe LocalDate.of(2026, 8, 4)
        afterWork.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `types every night as a party`() {
        events.map { it.eventType }.toSet() shouldBe setOf(EventType.PARTY.name)
    }

    @Test
    fun `bills the DJs of the prose as DJs and the guest named without the prefix as nobody`() {
        val queensNight = event("maxxim:queens-night-shery-m-live")
        queensNight.artists.map { it.role }.toSet() shouldBe setOf(ArtistRole.DJ)
        // The line-up repeats `DJ ONE F` in capitals; the special guest Shery M has no `DJ` prefix.
        queensNight.artists.map { it.name } shouldBe listOf("DJ One F", "DJ TC", "DJ FERO", "DJ YG")
        event("maxxim:replay-back-to-the-90s-2000s-2010s-party-6").artists.shouldBeEmpty()
    }

    @Test
    fun `bills every name the prose prefixes DJ or DJane, and no unprefixed partner or host`() {
        mapOf(
            13027 to (
                "DJ Tiefton, DJ DeeNoRee und DJ Punjesh sorgen für den Sound, der den Abend unvergesslich macht." to
                    listOf("DJ Tiefton", "DJ DeeNoRee", "DJ Punjesh")
            ),
            13028 to
                (
                    "DJ Denny D plays Classics and House from 10:30 PM — and turns the night before Friday into the best night of the week." to
                        listOf("DJ Denny D")
                ),
            13029 to ("DJ Denny D, Party Classics, Disco House and Hip Hop from 10 PM." to listOf("DJ Denny D")),
            13030 to ("Hosted by Nico Muolo with DJ Denny D on the decks — the dancefloor fills up like it's Friday." to listOf("DJ Denny D")),
            12341 to ("DJ SIZMO & Laura Blue – Willkommen im wilden Westen." to listOf("DJ SIZMO")),
            10829 to ("DJane Hanna Kirsanova & DJ SIZMO – The Sound of Maxxim." to listOf("DJane Hanna Kirsanova", "DJ SIZMO")),
            12508 to ("Nach der Bescherung – DJ Storm mit Feel-Good Sounds & Christmas Classics." to listOf("DJ Storm")),
            10932 to ("La Nuit en Rose – Glamour, Glitzer und die besten Disco Classics mit DJ Isamel Nagera!" to listOf("DJ Isamel Nagera")),
            12211 to ("Vorher MAMA GEHT TANZEN (20–23 Uhr), ab 23 Uhr PURE DISCO mit DJ SIZMO & Laura Blue." to listOf("DJ SIZMO")),
            11791 to ("Das MAXXIM wird zum Disco-Zirkus – DJ SIZMO & Laura Blue live!" to listOf("DJ SIZMO"))
        ).forEach { (id, case) ->
            val (description, djs) = case
            withClue("event $id") { djsIn(description) shouldBe djs }
        }
    }

    @Test
    fun `builds a stable source id from the event slug`() {
        // The two Monday nights share a title, so only the slug keeps their sourceIds apart.
        val mondays = events.filter { it.title == "MONDAY NITE CLUB" }
        mondays.map { it.sourceId }.toSet() shouldHaveSize mondays.size
        mondays.map { it.sourceId } shouldBe
            listOf("maxxim:monday-nite-club-202", "maxxim:monday-nite-club-199", "maxxim:monday-nite-club-201")
    }

    @Test
    fun `leaves no price note for a single-tier night`() {
        event("maxxim:the-maxxim-sunday-club-7").priceNote.shouldBeNull()
    }

    @Test
    fun `reads a decimal ticket price`() {
        event("maxxim:replay-back-to-the-90s-2000s-2010s-party-6").pricePresale shouldBe BigDecimal("12.30")
    }

    @Test
    fun `sets no external ticket url because tickets are sold on the wix event page`() {
        events.forEach { it.ticketUrl.shouldBeNull() }
    }

    @Test
    fun `marks a cancelled night`() {
        val cancelled = edgeCaseEvent("maxxim:summer-closing-abgesagt")
        cancelled.status shouldBe EventStatus.CANCELLED.name
    }

    @Test
    fun `marks a sold-out night and notes its price range`() {
        val birthday = edgeCaseEvent("maxxim:maxxim-birthday")
        birthday.soldOut shouldBe true
        birthday.pricePresale shouldBe BigDecimal("10.00")
        birthday.priceNote shouldBe "€10 – €25"
    }

    @Test
    fun `keeps a zero-price night at zero so free entry is derived downstream`() {
        edgeCaseEvent("maxxim:open-house").pricePresale shouldBe BigDecimal("0.00")
    }

    @Test
    fun `skips entries without a slug or a resolvable date`() {
        edgeCaseEvents.map { it.title } shouldBe
            listOf("SUMMER CLOSING - ABGESAGT", "MAXXIM BIRTHDAY - EARLY BIRD & LATE ENTRY", "OPEN HOUSE")
    }

    @Test
    fun `returns an empty list when the page has no warmup payload`() {
        val document = Jsoup.parse("<html><body></body></html>", baseUrl)
        scraper.scrape(document, baseUrl).shouldBeEmpty()
    }
}
