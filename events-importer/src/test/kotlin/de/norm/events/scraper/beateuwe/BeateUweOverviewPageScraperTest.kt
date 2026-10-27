package de.norm.events.scraper.beateuwe

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [BeateUweOverviewPageScraper], against a saved copy of the real homepage. */
class BeateUweOverviewPageScraperTest {
    private val scraper = BeateUweOverviewPageScraper()
    private val baseUrl = "https://beate-uwe.de/"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/beateuwe/beateuwe-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    private fun acts(date: String) = night(date).artists.map { it.name to it.role }

    @Test
    fun `scrape reads every night of the list and none of the weekly teasers`() {
        events shouldHaveSize 17
        events.first().eventDate shouldBe LocalDate.of(2026, 10, 9)
        events.last().eventDate shouldBe LocalDate.of(2026, 11, 8)
        events.map { it.sourceId }.distinct() shouldHaveSize 17
    }

    @Test
    fun `scrape maps a fully populated night`() {
        val event = night("2026-10-09")

        event.title shouldBe "Kompáz im Beate Uwe"
        event.subtitle.shouldBeNull()
        event.eventType shouldBe "PARTY"
        event.startTime shouldBe LocalTime.of(23, 0)
        event.doorsTime.shouldBeNull()
        event.sourceUrl shouldBe baseUrl
        event.sourceId shouldBe "beate_uwe:2026-10-09"
        event.ticketUrl shouldBe "https://ra.co/events/2534687"
        acts("2026-10-09") shouldContainExactly
            listOf("CHOMBA" to ArtistRole.DJ, "ZickZacken" to ArtistRole.DJ, "Adrija" to ArtistRole.DJ, "NEMOA" to ArtistRole.DJ)
    }

    @Test
    fun `a live act is billed LIVE without its marker`() {
        acts("2026-10-11") shouldContainExactly
            listOf(
                "Felío" to ArtistRole.DJ,
                "Kollektiv Sheesh" to ArtistRole.DJ,
                "Sebastian Wasner" to ArtistRole.LIVE,
                "Motip White" to ArtistRole.LIVE,
                "Just Emma" to ArtistRole.DJ
            )
    }

    @Test
    fun `a b2b pair bills both acts and a stray comma space is tolerated`() {
        acts("2026-10-18") shouldContainExactly
            listOf(
                "tatzentanz" to ArtistRole.DJ,
                "olibrii" to ArtistRole.DJ,
                "mimikri" to ArtistRole.LIVE,
                "coco loris" to ArtistRole.DJ,
                "ele" to ArtistRole.DJ,
                "pi:mentea" to ArtistRole.DJ
            )
    }

    @Test
    fun `a duo joined by an ampersand stays one act`() {
        acts("2026-10-17").map { it.first } shouldContainExactly
            listOf("Dompe", "Leeni & Danilo Kupfernagel", "Agent!", "Enduro Disco", "Rabella")
    }

    @Test
    fun `a set annotation comes off and a group's members in brackets stay with it`() {
        acts("2026-10-31").map { it.first } shouldContainExactly listOf("AKA AKA", "MALÏ", "Miss Kitchen")
        acts("2026-11-06").map { it.first } shouldContainExactly
            listOf("karete bu", "Bibsi Swift", "Bouncy Sally", "Spice Boys (Carpenter Effect & Zylenth )", "e.Kato")
    }

    @Test
    fun `a night without a lineup keeps its second line as the subtitle`() {
        val event = night("2026-11-03")

        event.title shouldBe "Tanzen3000"
        event.subtitle shouldBe "This Dancefloor is for Dancing! – movement workshop & DJ Set"
        event.startTime shouldBe LocalTime.of(18, 0)
        event.ticketUrl.shouldBeNull()
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `a night not yet linked has no ticket link but keeps its lineup`() {
        val event = night("2026-11-08")

        event.title shouldBe "Beate Barfuß /// Metanoia Showcase"
        event.ticketUrl.shouldBeNull()
        event.artists.map { it.name } shouldContainExactly listOf("Sukisu", "Cara Steel", "David Benjamin", "Zettka", "Coss")
    }

    @Test
    fun `a four-digit year and an impossible date are handled`() {
        val html =
            """
            <ul class="elementor-icon-list-items"><li><i class="far fa-calendar-alt"></i><span class="elementor-icon-list-text">FR, 01.01.2027</span></li>
            <li><i class="far fa-clock"></i><span class="elementor-icon-list-text">23:00</span></li></ul>
            <div class="elementor-widget-text-editor"><p>New Year</p><p>w/ DJ One</p></div>
            <ul class="elementor-icon-list-items"><li><i class="far fa-calendar-alt"></i><span class="elementor-icon-list-text">SA, 31.02.27</span></li></ul>
            <div class="elementor-widget-text-editor"><p>Nowhere</p></div>
            """.trimIndent()

        val event = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).single()

        event.eventDate shouldBe LocalDate.of(2027, 1, 1)
        event.artists.map { it.name } shouldContainExactly listOf("DJ One")
    }

    @Test
    fun `a page without a programme yields nothing`() {
        scraper.scrape(Jsoup.parse("<html><body><p>Closed</p></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
