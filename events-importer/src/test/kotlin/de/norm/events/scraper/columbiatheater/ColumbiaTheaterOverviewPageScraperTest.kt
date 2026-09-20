package de.norm.events.scraper.columbiatheater

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Unit tests for [ColumbiaTheaterOverviewPageScraper].
 *
 * Uses a real homepage snapshot with a representative mix of `a.item` cards: plain concerts, a
 * co-billed show, a card whose billing rows split a support act from a DJ, a cancelled and a
 * relocated show, and the duplicate pair a rescheduled show renders (an `X`-prefixed placeholder
 * at its old date plus the real entry at its new one).
 */
class ColumbiaTheaterOverviewPageScraperTest {
    private val scraper = ColumbiaTheaterOverviewPageScraper()
    private val baseUrl = "https://columbia-theater.de/"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/columbiatheater/columbiatheater-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(sourceIdSuffix: String): ScrapedEvent = events.first { it.sourceId == "columbia_theater:$sourceIdSuffix" }

    @Test
    fun `extracts every event card from the fixture`() {
        events shouldHaveSize 98
    }

    @Test
    fun `assigns each event a unique sourceId`() {
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
    }

    @Test
    fun `parses date, image and lineup for a plain concert`() {
        val soulfly = event("20260803-soulfly")
        soulfly.title shouldBe "Soulfly"
        soulfly.subtitle shouldBe "Tribal Technology Tour 2026 | Support: Botulism"
        soulfly.eventType shouldBe "CONCERT"
        // The permalink's YYYYMMDD prefix, not the year-less "03 Aug" date block on the card.
        soulfly.eventDate shouldBe LocalDate.of(2026, 8, 3)
        soulfly.status shouldBe "SCHEDULED"
        soulfly.sourceUrl shouldBe "https://columbia-theater.de/event/20260803-soulfly/"
        soulfly.imageUrl shouldBe "https://columbia-theater.de/wp-content/uploads/2026/05/image-1024x683.webp"
        soulfly.artists shouldContainExactly
            listOf(
                ScrapedArtist("Soulfly", "HEADLINER", titleDerived = true),
                ScrapedArtist("Botulism", "SUPPORT")
            )
    }

    @Test
    fun `leaves the subtitle null when an event has neither a tour name nor a billing row`() {
        event("20270325-kmfdm").subtitle.shouldBeNull()
    }

    @Test
    fun `splits a co-billed title into one headliner per act`() {
        val despisedIcon = event("20261118-despised-icon-carnifex-suffocation")
        despisedIcon.title shouldBe "Despised Icon / Carnifex / Suffocation"
        despisedIcon.artists shouldContainExactly
            listOf(
                ScrapedArtist("Despised Icon", "HEADLINER", titleDerived = true),
                ScrapedArtist("Carnifex", "HEADLINER", titleDerived = true),
                ScrapedArtist("Suffocation", "HEADLINER", titleDerived = true),
                ScrapedArtist("Gates To Hell", "SUPPORT")
            )
    }

    @Test
    fun `types a DJ billing row as a DJ and a support row as a support act`() {
        event("20261017-sixpence-none-the-richer").artists shouldContainExactly
            listOf(
                ScrapedArtist("Sixpence None The Richer", "HEADLINER", titleDerived = true),
                ScrapedArtist("Robert Post", "SUPPORT"),
                ScrapedArtist("The 33rd Assassin", "DJ")
            )
    }

    @Test
    fun `drops a guest's parenthesised band affiliations, keeping a single name that may be an alias`() {
        // The comma list and the `ex-` opener are affiliations (#1561); `(PENETRATION)` alone could be an alias and stays.
        val johnRobb = event("20260926-john-robb-mark-reeder")
        johnRobb.artists shouldContainExactly
            listOf(
                ScrapedArtist("John Robb", "HEADLINER", titleDerived = true),
                ScrapedArtist("Mark Reeder", "HEADLINER", titleDerived = true),
                ScrapedArtist("Pauline Murray (PENETRATION)", "SUPPORT"),
                ScrapedArtist("Colin Newman", "SUPPORT"),
                ScrapedArtist("Budgie", "SUPPORT"),
                ScrapedArtist("Annette Benjamin", "SUPPORT"),
                ScrapedArtist("Alexander Hacke", "SUPPORT"),
                ScrapedArtist("Malka Spigel", "SUPPORT")
            )
    }

    @Test
    fun `reads a cancelled show from its data-c flag`() {
        val oreillys = event("20261002-the-oreillys-and-the-paddyhats")
        oreillys.title shouldBe "The O'Reillys and The Paddyhats"
        oreillys.status shouldBe "CANCELLED"
        oreillys.artists shouldContainExactly
            listOf(
                ScrapedArtist("The O'Reillys and The Paddyhats", "HEADLINER", titleDerived = true),
                ScrapedArtist("Harpyie", "SUPPORT")
            )
    }

    @Test
    fun `reads a relocated show from its data-m flag`() {
        event("20260928-turbopaolo").status shouldBe "RELOCATED"
    }

    @Test
    fun `imports a rescheduled show once, at its new date, without its stale-date placeholder`() {
        events.filter { it.sourceId == "columbia_theater:20270325-kmfdm" } shouldHaveSize 1
        val kmfdm = event("20270325-kmfdm")
        kmfdm.eventDate shouldBe LocalDate.of(2027, 3, 25)
        kmfdm.status shouldBe "POSTPONED"
    }

    @Test
    fun `skips the venue's off-site campaign banner`() {
        events.map { it.sourceUrl }.none { it.contains("columbiahalle") } shouldBe true
    }
}
