package de.norm.events.scraper.columbiahalle

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [ColumbiahalleOverviewPageScraper].
 *
 * Uses a real `/veranstaltungen.html` snapshot covering the variants the listing produces: a fully
 * populated concert, a show with both `AK` and `VVK` prices plus a fee note, an `ab`-qualified
 * tiered price, a sold-out sticker, a cancelled sticker, an empty price block, a co-billed title,
 * and an event four years out — which only dates correctly if the month heading is carried across
 * the stream.
 */
class ColumbiahalleOverviewPageScraperTest {
    private val scraper = ColumbiahalleOverviewPageScraper()
    private val baseUrl = "https://www.columbiahalle.berlin/veranstaltungen.html"
    private lateinit var events: List<ScrapedEvent>

    @BeforeEach
    fun setUp() {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/columbiahalle/columbiahalle-overview.html")!!
                .bufferedReader()
                .readText()
        events = scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun event(eventId: String): ScrapedEvent = events.first { it.sourceId == "columbiahalle:$eventId" }

    @Test
    fun `extracts every event card from the fixture`() {
        events shouldHaveSize 87
    }

    @Test
    fun `assigns each event a unique sourceId`() {
        events.map { it.sourceId }.distinct() shouldHaveSize events.size
    }

    @Test
    fun `parses every field of a fully populated concert`() {
        val juju = event("9743")
        juju.title shouldBe "Juju"
        juju.subtitle shouldBe "Juju - CRASHOUT CLUBTOUR"
        juju.eventType shouldBe "CONCERT"
        // Day "07" from the card, month and year from the "August 2026" heading above it.
        juju.eventDate shouldBe LocalDate.of(2026, 8, 7)
        juju.doorsTime shouldBe LocalTime.of(18, 30)
        juju.startTime shouldBe LocalTime.of(20, 0)
        juju.pricePresale shouldBe BigDecimal("59.90")
        juju.priceBoxOffice.shouldBeNull()
        juju.priceNote.shouldBeNull()
        juju.status shouldBe "SCHEDULED"
        juju.soldOut shouldBe false
        juju.sourceUrl shouldBe "https://www.columbiahalle.berlin/veranstaltungen.html#event_9743"
        juju.ticketUrl shouldBe "https://store.juju44.net/products/album-2026-releaseshow-berlin-stehplatz-bundle"
        juju.promoters shouldContainExactly listOf("Boldt Berlin Konzertagentur GmbH")
        juju.description.shouldNotBeNull() shouldStartWith "Juju ist eine der prägendsten Stimmen im Deutschrap"
        juju.artists shouldContainExactly listOf(ScrapedArtist("Juju", "HEADLINER"))
    }

    @Test
    fun `resolves the site-relative poster path against the listing URL`() {
        event("9743").imageUrl shouldBe
            "https://www.columbiahalle.berlin/files/BilderCache/img_event_9743_files260324juju20angepisst2020" +
            "freestylefinalskorn260324juju20angepisst2020freestylefinalsimg3364c2a9woodywoodsnc2a9woodywoodsnjpg_640.jpg"
    }

    @Test
    fun `splits AK and VVK into box-office and presale, keeping the fee note`() {
        val deathCab = event("9728")
        deathCab.eventDate shouldBe LocalDate.of(2026, 10, 1)
        deathCab.priceBoxOffice shouldBe BigDecimal("48.00")
        deathCab.pricePresale shouldBe BigDecimal("38.00")
        deathCab.priceNote shouldBe "AK: 48,00 € VVK: 38,00 € zzgl. Gebühr"
    }

    @Test
    fun `keeps the raw text as a note when the price is only a from-price`() {
        val sido = event("8494")
        sido.pricePresale shouldBe BigDecimal("74.99")
        sido.priceNote shouldBe "VVK: ab 74,99 €"
    }

    @Test
    fun `extracts the support act from a Support subtitle`() {
        event("9728").artists shouldContainExactly
            listOf(
                ScrapedArtist("Death Cab For Cutie", "HEADLINER"),
                ScrapedArtist("Pool Kids", "SUPPORT")
            )
    }

    @Test
    fun `splits a co-billed title into one headliner per act`() {
        val metric = event("9729")
        metric.title shouldBe "METRIC / BROKEN SOCIAL SCENE / STARS"
        metric.artists shouldContainExactly
            listOf(
                ScrapedArtist("METRIC", "HEADLINER"),
                ScrapedArtist("BROKEN SOCIAL SCENE", "HEADLINER"),
                ScrapedArtist("STARS", "HEADLINER")
            )
    }

    @Test
    fun `mints no artist for a series billed under its own name`() {
        val kban = event("9720")
        kban.title shouldBe "KEIN BOCK AUF NAZIS"
        kban.eventType shouldBe "CONCERT"
        kban.artists.shouldBeEmpty()
    }

    @Test
    fun `flags a sold-out show from its sticker without changing status`() {
        val sido = event("8494")
        sido.soldOut shouldBe true
        sido.status shouldBe "SCHEDULED"
        sido.eventDate shouldBe LocalDate.of(2026, 12, 15)
    }

    @Test
    fun `reads a cancelled show from its sticker`() {
        val unity = event("9711")
        unity.title shouldBe "Unity"
        unity.status shouldBe "CANCELLED"
        unity.soldOut shouldBe false
        unity.eventDate shouldBe LocalDate.of(2026, 10, 2)
    }

    @Test
    fun `leaves all prices null when the price block is empty`() {
        val editors = event("9767")
        editors.pricePresale.shouldBeNull()
        editors.priceBoxOffice.shouldBeNull()
        editors.priceNote.shouldBeNull()
        editors.free shouldBe false
        editors.subtitle.shouldBeNull()
        editors.soldOut shouldBe true
    }

    @Test
    fun `carries the month heading across year boundaries to the far-future event`() {
        val tzk = event("9687")
        tzk.title shouldBe "TZK"
        tzk.eventDate shouldBe LocalDate.of(2030, 12, 28)
        tzk.promoters shouldContainExactly listOf("Loft Concerts")
    }

    @Test
    fun `imports the whole listing in chronological order`() {
        events.map { it.eventDate } shouldBe events.map { it.eventDate }.sorted()
    }
}
