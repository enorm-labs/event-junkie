package de.norm.events.scraper.amt

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Unit tests for [AmtOverviewPageScraper].
 *
 * Parses the saved June, July and October 2026 month snapshots. Dates come from each block's `data-date`
 * attribute (a full `MMMM d, yyyy`), so no clock is needed — every date is deterministic.
 */
class AmtOverviewPageScraperTest {
    private val scraper = AmtOverviewPageScraper()

    private val juneUrl = "https://www.club-amt.berlin/month/june"
    private val julyUrl = "https://www.club-amt.berlin/month/july"
    private val octoberUrl = "https://www.club-amt.berlin/month/october"

    private fun monthPage(
        name: String,
        baseUrl: String
    ) = Jsoup.parse(
        javaClass.classLoader
            .getResourceAsStream("scraper/amt/$name")!!
            .bufferedReader()
            .readText(),
        baseUrl
    )

    private val juneEvents by lazy { scraper.scrape(monthPage("amt-month-june.html", juneUrl), juneUrl) }
    private val julyEvents by lazy { scraper.scrape(monthPage("amt-month-july.html", julyUrl), julyUrl) }
    private val octoberEvents by lazy { scraper.scrape(monthPage("amt-month-october.html", octoberUrl), octoberUrl) }

    private fun octoberNight(day: Int) = octoberEvents.first { it.eventDate == LocalDate.of(2026, 10, day) }

    @Test
    fun `parses every dated night on a month page`() {
        juneEvents shouldHaveSize 6
        julyEvents shouldHaveSize 5
        octoberEvents shouldHaveSize 9
    }

    @Test
    fun `maps a door-only figure to box office and no presale`() {
        val event = octoberNight(25)

        event.sourceId shouldBe "amt:sektfruhstuck"
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice shouldBe BigDecimal("10")
        octoberNight(30).pricePresale.shouldBeNull()
        octoberNight(30).priceBoxOffice shouldBe BigDecimal("25")
        // `>15` is a floor; its figure is still the door price.
        octoberNight(9).pricePresale.shouldBeNull()
        octoberNight(9).priceBoxOffice shouldBe BigDecimal("15")
    }

    @Test
    fun `keeps a bare figure beside a ticket link as presale and a euro-marked range as both tiers`() {
        val bare = octoberNight(31)
        bare.ticketUrl shouldBe "https://ra.co/events/2535397"
        bare.pricePresale shouldBe BigDecimal("25")
        bare.priceBoxOffice.shouldBeNull()

        octoberNight(16).pricePresale shouldBe BigDecimal("18")
        octoberNight(16).priceBoxOffice shouldBe BigDecimal("27")
    }

    @Test
    fun `extracts all fields of a themed night with a ticket link and a tiered price`() {
        val event = juneEvents.first { it.eventDate == LocalDate.of(2026, 6, 19) }

        event.title shouldBe "SUBSTATION"
        event.subtitle shouldBe "[SEX POSITIV]"
        event.eventType shouldBe EventType.PARTY.name
        event.description!! shouldContain "Berenice Britney Speed"
        event.sourceUrl shouldBe "https://www.club-amt.berlin/event/substation-3"
        // The detail-page slug is the stable canonical identity.
        event.sourceId shouldBe "amt:substation-3"
        event.ticketUrl shouldBe "https://de.ra.co/events/2448915"
        // A "10 – 20" tiered price maps low → presale, high → box office.
        event.pricePresale shouldBe BigDecimal("10")
        event.priceBoxOffice shouldBe BigDecimal("20")
        // A space-separated DJ line cannot be split into names reliably, so no artists are extracted.
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `splits the DJ line into performers only when delimited with double-slash`() {
        val event = juneEvents.first { it.eventDate == LocalDate.of(2026, 6, 21) }

        event.title shouldBe "AMT + Jägermsiter Späti Rave"
        event.artists shouldContainExactly
            listOf(
                ScrapedArtist(name = "DJ BTM", role = "DJ"),
                ScrapedArtist(name = "DJ HIGHCLASS", role = "DJ"),
                ScrapedArtist(name = "MANI", role = "DJ")
            )
        // No ticket link and no price cell on this night.
        event.ticketUrl.shouldBeNull()
        event.pricePresale.shouldBeNull()
        event.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `maps a single flat price to presale only`() {
        val event = juneEvents.first { it.eventDate == LocalDate.of(2026, 6, 27) }

        event.title shouldBe "LIMAX"
        event.pricePresale shouldBe BigDecimal("25")
        event.priceBoxOffice.shouldBeNull()
        // A non-Resident-Advisor ticket shop is still captured as an absolute URL.
        event.ticketUrl!! shouldContain "eventjet.at"
    }

    @Test
    fun `handles a bare open-decks night with no lineup, price, or ticket`() {
        val event = juneEvents.first { it.eventDate == LocalDate.of(2026, 6, 23) }

        event.title shouldBe "AMT OPEN DECKS"
        event.sourceId shouldBe "amt:amt-open-decks"
        event.subtitle.shouldBeNull()
        event.description.shouldBeNull()
        event.artists.shouldBeEmpty()
        event.ticketUrl.shouldBeNull()
        event.pricePresale.shouldBeNull()
    }

    @Test
    fun `drops a tba lineup placeholder without minting artists or a description`() {
        val event = julyEvents.first { it.eventDate == LocalDate.of(2026, 7, 25) }

        event.title shouldBe "CSD STD - SUBSTATION Transmitted Disease"
        // "tba" is a placeholder, not a real lineup.
        event.description.shouldBeNull()
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `splits a multi-DJ bill on a July night`() {
        val event = julyEvents.first { it.eventDate == LocalDate.of(2026, 7, 18) }

        event.title shouldBe "TON:STEIN"
        event.artists.map { it.name } shouldContainExactly
            listOf("Voiski", "Polar Inertia", "Bardo", "Natif Orchestra", "Nocthiel")
        event.artists.forEach { it.role shouldBe "DJ" }
    }

    @Test
    fun `returns no events for a page without event blocks`() {
        val emptyDoc = Jsoup.parse("<html><body><p>Baustelle</p></body></html>", juneUrl)
        scraper.scrape(emptyDoc, juneUrl).shouldBeEmpty()
    }
}
