package de.norm.events.scraper.mikropol

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [MikropolOverviewPageScraper].
 *
 * Uses a real `/events/` snapshot with a representative mix of `a.event` cards: a plain
 * concert, a concert with a `support:` line, a sold-out show (`Ausverkauft` class), a
 * cancelled show (`Abgesagt` class), and a relocated show whose "verlegt in den … –" title
 * prefix must be stripped and read as a `RELOCATED` status.
 */
class MikropolOverviewPageScraperTest {
    private val scraper = MikropolOverviewPageScraper()
    private val baseUrl = "https://mikropol-berlin.de/events/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/mikropol/mikropol-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceIdSuffix: String): ScrapedEvent = events.first { it.sourceId == "mikropol:$sourceIdSuffix" }

    @Test
    fun `extracts every event card from the fixture`() {
        events shouldHaveSize 60
    }

    @Test
    fun `parses date, times and detail URL for a plain concert`() {
        val dueja = event("2026-07-17-dueja")
        dueja.title shouldBe "DUEJA"
        dueja.eventType shouldBe "CONCERT"
        dueja.eventDate shouldBe LocalDate.of(2026, 7, 17)
        dueja.startTime shouldBe LocalTime.of(20, 0)
        dueja.doorsTime shouldBe LocalTime.of(19, 0)
        dueja.status shouldBe "SCHEDULED"
        dueja.soldOut shouldBe false
        dueja.sourceUrl shouldBe "https://mikropol-berlin.de/event/2026-07-17-dueja/"
        dueja.artists shouldContainExactly listOf(ScrapedArtist("DUEJA", "HEADLINER", titleDerived = true))
    }

    @Test
    fun `extracts the headliner and support act from the support line`() {
        val house = event("2026-07-14-house-of-protection")
        house.subtitle shouldBe "support: noise of the voiceless"
        house.artists shouldContainExactly
            listOf(
                ScrapedArtist("HOUSE OF PROTECTION", "HEADLINER", titleDerived = true),
                ScrapedArtist("noise of the voiceless", "SUPPORT")
            )
    }

    @Test
    fun `flags a sold-out show from its Ausverkauft class without changing status`() {
        val house = event("2026-07-14-house-of-protection")
        house.soldOut shouldBe true
        house.status shouldBe "SCHEDULED"
    }

    @Test
    fun `flags a cancelled show from its Abgesagt class`() {
        val vowws = event("2026-07-25-vowws")
        vowws.title shouldBe "Vowws"
        vowws.status shouldBe "CANCELLED"
        vowws.soldOut shouldBe false
    }

    @Test
    fun `strips the relocation prefix from the title and reads it as a relocated status`() {
        val cultureWars = event("2026-07-16-verlegt-in-den-frannz-club-culture-wars")
        cultureWars.title shouldBe "CULTURE WARS"
        cultureWars.status shouldBe "RELOCATED"
        cultureWars.eventDate shouldBe LocalDate.of(2026, 7, 16)
        cultureWars.artists shouldContainExactly listOf(ScrapedArtist("CULTURE WARS", "HEADLINER", titleDerived = true))
    }
}
