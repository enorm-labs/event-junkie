package de.norm.events.scraper.altemuenze

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class AlteMuenzeApiScraperTest {
    private val scraper = AlteMuenzeApiScraper()

    private val fixture by lazy {
        javaClass.classLoader
            .getResourceAsStream("scraper/altemuenze/altemuenze-events.json")!!
            .bufferedReader()
            .readText()
    }

    private val page by lazy { scraper.scrapePage(fixture) }

    private fun event(slug: String) = page.events.single { it.sourceId == "alte_muenze:$slug" }

    /** The fixture's `opera-days-at-alte-muenze` event alone, priced at [cost] with [amounts] as its `cost_details`. */
    private fun pricedAs(
        cost: String,
        vararg amounts: String
    ): ScrapedEvent =
        JsonMapper().let { mapper ->
            val root = mapper.readTree(fixture) as ObjectNode
            val events = root.withArray("events")
            val night = events.first { it.path("slug").asString() == "opera-days-at-alte-muenze" } as ObjectNode
            night.put("cost", cost)
            night.withObject("cost_details").putArray("values").apply { amounts.forEach(::add) }
            events.removeAll()
            events.add(night)
            scraper.scrapePage(mapper.writeValueAsString(root)).events.single()
        }

    @Test
    fun `keeps the programme and drops tours, workshops, runs, brunches, the conference and uncategorised rows`() {
        page.events shouldHaveSize 29
        page.nextPageUrl.shouldBeNull()
        page.events.map { it.sourceId.removePrefix("alte_muenze:") } shouldNotContainAnyOf
            listOf(
                "ink-against-borders",
                "cancelled-historische-fuehrung",
                "historische-fuehrung-06-06",
                "canoko",
                "kurdishwomenculturedays2026",
                "boozy-drag-brunch",
                "bagels-beats",
                "fleamarket",
                "ceramic-painting-at-the-greens",
                "contemp-movement-an-art-run-by-alte-muenze-hosted-by-claud"
            )
    }

    @Test
    fun `maps a festival with its times, image, link and organizer`() {
        val festival = event("eden-cinema-a-festival-for-iris-ter-schiphorst")

        festival.title shouldBe "EDEN CINEMA – A Festival for Iris ter Schiphorst"
        festival.eventType shouldBe "FESTIVAL"
        festival.eventDate shouldBe LocalDate.of(2026, 10, 18)
        festival.startTime shouldBe LocalTime.of(15, 0)
        festival.endDate shouldBe LocalDate.of(2026, 10, 18)
        festival.endTime shouldBe LocalTime.of(22, 30)
        festival.sourceUrl shouldBe "https://www.alte-muenze-berlin.de/event/eden-cinema-a-festival-for-iris-ter-schiphorst/"
        festival.ticketUrl shouldBe "https://zafraanensemble.com/archive/news/eden-cinema-ein-festival-fuer-iris-ter-schiphorst"
        festival.imageUrl shouldBe "https://www.alte-muenze-berlin.de/wp-content/uploads/2026/09/Bildschirmfoto-2026-09-24-um-13.00.43.png"
        festival.promoters shouldContainExactly listOf("Zafraan Ensemble")
        festival.description.shouldNotBeNull()
    }

    @Test
    fun `gives each occurrence of a recurring event its own identity`() {
        val opera = page.events.filter { it.title == "Opera Days at Alte Münze" }

        opera shouldHaveSize 6
        opera.first().sourceId shouldBe "alte_muenze:opera-days-at-alte-muenze-2026-08-25"
        opera.map { it.sourceId }.distinct() shouldHaveSize 6
        event("deep-fake-a-mockumentary-performance-2026-05-30-2").startTime shouldBe LocalTime.of(20, 30)
    }

    @Test
    fun `types staged opera as a show and reads its one price`() {
        val opera = event("opera-days-at-alte-muenze-2026-08-25")

        opera.eventType shouldBe "SHOW"
        opera.pricePresale shouldBe BigDecimal("27.82")
        opera.promoters shouldContainExactly listOf("Opernfest")
        opera.ticketUrl shouldBe "https://www.opernfest.com"
    }

    @Test
    fun `reads a sold-out marker before the slashes and drops it from the title`() {
        val show = event("sold-out-das-land-das-ich-liebe-voices-berlin-festival")

        show.title shouldBe "Das Land, das ich liebe – Voices Berlin Festival"
        show.soldOut shouldBe true
        show.status shouldBe "SCHEDULED"
        show.eventType shouldBe "SHOW"
    }

    @Test
    fun `stores an all-day exhibition as a run without times`() {
        val exhibition = event("gate-to-nancy-goerlach")

        exhibition.title shouldBe "GATE TO // Nancy Görlach"
        exhibition.eventType shouldBe "EXHIBITION"
        exhibition.eventDate shouldBe LocalDate.of(2026, 10, 8)
        exhibition.startTime.shouldBeNull()
        exhibition.endDate shouldBe LocalDate.of(2026, 10, 25)
        exhibition.endTime.shouldBeNull()
        exhibition.ticketUrl.shouldBeNull()
        exhibition.promoters shouldBe emptyList()
    }

    @Test
    fun `types club nights as parties and concerts by their category`() {
        event("heisss-x-home-again-nye").eventType shouldBe "PARTY"
        event("aixploration-2-0").free shouldBe true
        event("avers-ed-3").eventType shouldBe "PARTY"
        event("reversavers-ed-3").eventType shouldBe "CONCERT"
        event("reversavers-ed-3").ticketUrl.shouldBeNull()
    }

    @Test
    fun `reads a cancelled marker as the status`() {
        val json =
            """{"events":[{"url":"https://www.alte-muenze-berlin.de/event/x/","title":"CANCELLED // Night",""" +
                """"start_date":"2026-11-01 20:00:00","categories":[{"name":"Konzert"}]}]}"""

        val night = scraper.scrapePage(json).events.single()

        night.title shouldBe "Night"
        night.status shouldBe "CANCELLED"
        night.soldOut shouldBe false
    }

    @Test
    fun `stores the lowest tier as a from price and keeps the tiers as the note`() {
        pricedAs("€15", "15").run {
            pricePresale shouldBe BigDecimal("15")
            priceNote.shouldBeNull()
            free shouldBe false
        }
        pricedAs("€10 – €15", "10", "15").run {
            pricePresale shouldBe BigDecimal("10")
            priceNote shouldBe "€10 – €15"
            free shouldBe false
        }
        pricedAs("Free", "0").run {
            pricePresale.shouldBeNull()
            priceNote.shouldBeNull()
            free shouldBe true
        }
        pricedAs("€8 – €20", "12", "8", "20").run {
            pricePresale shouldBe BigDecimal("8")
            priceNote shouldBe "€8 – €20"
        }
        pricedAs("€0 – €10", "0", "10").run {
            pricePresale.shouldBeNull()
            priceNote shouldBe "€0 – €10"
            free shouldBe false
        }
    }
}
