package de.norm.events.scraper.monsterronsons

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MonsterRonsonsDetailPageScraper].
 *
 * Parses static snapshots of two night pages captured on 2026-08-06: a hosted night with prose and
 * a door price, and the venue's "CLOSED" notice page; and of a night whose running time is a
 * paragraph of its own, captured on 2026-10-18.
 */
class MonsterRonsonsDetailPageScraperTest {
    private val scraper = MonsterRonsonsDetailPageScraper()

    private fun scrape(fixture: String): MonsterRonsonsNightDetail? {
        val url = "https://www.karaokemonster.de/posts/$fixture"
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/monsterronsons/monsterronsons-detail-$fixture.html")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, url), url)
    }

    @Test
    fun `reads the prose of a hosted night`() {
        val detail = scrape("hosted").shouldNotBeNull()
        detail.description shouldContain "IVANKA TRAMP"
        detail.description shouldContain "20:00"
    }

    @Test
    fun `reads a euro-sign-first door price`() {
        // The venue writes "€5", not "5€" — the shared parsePriceValue only matches the latter.
        scrape("hosted").shouldNotBeNull().priceBoxOffice shouldBe BigDecimal("5")
    }

    @Test
    fun `keeps a time-banded tariff as a note instead of picking one band as the price`() {
        // This night charges by arrival time: free before 19:00, €5, €10 overnight, €5, free again.
        // No single box-office price is true for it, so none is asserted.
        val detail = scrape("banded").shouldNotBeNull()
        detail.priceBoxOffice.shouldBeNull()
        detail.priceNote.shouldNotBeNull() shouldContain "€10"
        detail.priceNote.shouldNotBeNull() shouldContain "19:00"
    }

    @Test
    fun `does not read a following start time as part of the amount`() {
        // Webflow runs the paragraphs together, so "€5" is immediately followed by the next band's
        // "20:00". Reading the body whole produced a €520 door price before the paragraphs were split.
        val detail = scrape("banded").shouldNotBeNull()
        detail.priceNote.shouldNotBeNull() shouldNotContain "520"
        detail.description shouldNotContain "FREE19:00"
    }

    @Test
    fun `separates the paragraphs so the description stays readable`() {
        val detail = scrape("hosted").shouldNotBeNull()
        detail.description.lines().size shouldBeGreaterThan 1
        detail.description shouldNotContain "02:00€5"
    }

    @Test
    fun `reports no ticket url when webflow hides the empty ticket button`() {
        // The button is in the markup on every night, carrying w-condition-invisible when unused.
        scrape("hosted").shouldNotBeNull().ticketUrl.shouldBeNull()
    }

    @Test
    fun `still parses the closure page rather than failing on it`() {
        // The overview drops closure cards before any fetch, so this only guards the direct call.
        val detail = scrape("closed")
        detail.shouldNotBeNull().description shouldContain "closed"
    }

    @Test
    fun `returns null for a page with no rich-text body`() {
        val url = "https://www.karaokemonster.de/posts/shell"
        scraper.scrape(Jsoup.parse("<html><body><div class='date-container'></div></body></html>", url), url).shouldBeNull()
    }

    @Test
    fun `applies only the fields the listing card could not carry`() {
        val card =
            ScrapedEvent(
                title = "SING WITH IVANKA TRAMP",
                eventDate = LocalDate.of(2026, 8, 6),
                sourceUrl = "https://www.karaokemonster.de/posts/sing-with-fauxpas-2",
                sourceId = "monster_ronsons:2026-08-06-sing-with-fauxpas-2"
            )

        val enriched =
            MonsterRonsonsNightDetail(
                description = "Karaoke with IVANKA TRAMP",
                priceBoxOffice = BigDecimal("5"),
                ticketUrl = "https://tickets.example.test/night"
            ).applyTo(card)

        enriched.description shouldBe "Karaoke with IVANKA TRAMP"
        enriched.priceBoxOffice shouldBe BigDecimal("5")
        enriched.ticketUrl shouldBe "https://tickets.example.test/night"
        // The card stays authoritative for everything it stated itself.
        enriched.title shouldBe card.title
        enriched.eventDate shouldBe card.eventDate
        enriched.sourceId shouldBe card.sourceId
    }

    @Test
    fun `never overwrites a value the card already supplied`() {
        val card =
            ScrapedEvent(
                title = "SING WITH IVANKA TRAMP",
                description = "from the card",
                eventDate = LocalDate.of(2026, 8, 6),
                sourceUrl = "https://www.karaokemonster.de/posts/sing-with-fauxpas-2",
                sourceId = "monster_ronsons:2026-08-06-sing-with-fauxpas-2"
            )

        MonsterRonsonsNightDetail(description = "from the night page").applyTo(card).description shouldBe "from the card"
    }

    @Test
    fun `ends a night at its running-time paragraph, on the next day past midnight`() {
        // 18 October 2026: "<p>18:00 - 02:00</p><p>€5</p>" under a card that reads 18:00 (#3580).
        val detail = scrape("running-time").shouldNotBeNull()
        detail.runningTimes shouldBe listOf(MonsterRonsonsRunningTime(LocalTime.of(18, 0), LocalTime.of(2, 0)))
        detail.priceBoxOffice shouldBe BigDecimal("5")

        val night = detail.applyTo(card(LocalTime.of(18, 0)))
        night.endDate shouldBe LocalDate.of(2026, 10, 19)
        night.endTime shouldBe LocalTime.of(2, 0)
    }

    @Test
    fun `reads no end from a tariff band, a host's slot in prose, or a running time the card's start disagrees with`() {
        val banded = scrape("banded").shouldNotBeNull()
        banded.runningTimes shouldBe emptyList()
        banded.applyTo(card(LocalTime.of(18, 0))).endTime.shouldBeNull()
        scrape("hosted").shouldNotBeNull().runningTimes shouldBe emptyList()

        val other = scrape("running-time").shouldNotBeNull().applyTo(card(LocalTime.of(21, 0)))
        other.endDate.shouldBeNull()
        other.endTime.shouldBeNull()
    }

    @Test
    fun `reads a running time with a one-digit hour and no spaces`() {
        // "sing-with-soft-fantasy-4", 29 October 2026, prints "20:00-2:00".
        val url = "https://www.karaokemonster.de/posts/sing-with-soft-fantasy-4"
        val detail = scraper.scrape(Jsoup.parse("<div class='w-richtext'><p>Karaoke</p><p>20:00-2:00</p><p>€5</p></div>", url), url).shouldNotBeNull()

        detail.runningTimes shouldBe listOf(MonsterRonsonsRunningTime(LocalTime.of(20, 0), LocalTime.of(2, 0)))
    }

    private fun card(start: LocalTime) =
        ScrapedEvent(
            title = "SING WITH SCOTTY TECHNOLOGY",
            eventDate = LocalDate.of(2026, 10, 18),
            startTime = start,
            sourceUrl = "https://www.karaokemonster.de/posts/sing-with-scotty-technology-5",
            sourceId = "monster_ronsons:2026-10-18-sing-with-scotty-technology-5"
        )
}
