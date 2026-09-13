package de.norm.events.scraper.sisyphos

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Parses a saved snapshot of the Sisyphos shop's `/collections/tickets/products.json`. The
 * fixture holds one dated `generationS` night and six undated Sauniphos sauna-weekend tickets.
 */
class SisyphosApiScraperTest {
    private val scraper = SisyphosApiScraper()
    private val baseUrl = "https://www.sisyphos-berlin.net/collections/tickets/products.json"

    private val events: List<ScrapedEvent> by lazy {
        val json =
            javaClass.classLoader
                .getResourceAsStream("scraper/sisyphos/sisyphos-tickets.json")!!
                .bufferedReader()
                .readText()
        scraper.scrape(json, baseUrl)
    }

    @Test
    fun `keeps only the tickets that name a date`() {
        events shouldHaveSize 1
        events.single().sourceId shouldBe "sisyphos:generations-10-okt-2026"
    }

    @Test
    fun `maps a ticketed night from its product`() {
        val night = events.single()
        night.title shouldBe "generationS"
        night.eventDate shouldBe LocalDate.of(2026, 10, 10)
        night.eventType shouldBe "PARTY"
        night.sourceUrl shouldBe "https://www.sisyphos-berlin.net/products/generations-10-okt-2026"
        night.ticketUrl shouldBe night.sourceUrl
        night.pricePresale shouldBe BigDecimal("25.00")
        night.priceBoxOffice.shouldBeNull()
        night.soldOut shouldBe false
        night.imageUrl shouldBe "https://cdn.shopify.com/s/files/1/0405/6131/1898/files/generations04.okt.jpg?v=1788184581"
        night.description shouldContain "Halligalli Hits & Disco Cocktail!"
        night.description shouldNotContain "<"
        night.doorsTime.shouldBeNull()
        night.startTime.shouldBeNull()
        night.artists.shouldBeEmpty()
    }

    @Test
    fun `reads a dotted date and marks a night whose variants are all unavailable as sold out`() {
        val night =
            scraper
                .scrape(
                    product(
                        handle = "generations-14-11-2026",
                        title = "generationS 14.11.2026",
                        variants = """[{"price": "30.00", "available": false}, {"price": "25.00", "available": false}]"""
                    ),
                    baseUrl
                ).single()
        night.eventDate shouldBe LocalDate.of(2026, 11, 14)
        night.soldOut shouldBe true
        night.pricePresale shouldBe BigDecimal("25.00")
    }

    @Test
    fun `reads a full month name and a date that is only in the blurb`() {
        val night =
            scraper
                .scrape(
                    product(
                        handle = "silvester",
                        title = "Silvester im Sisy",
                        body = "<p>Am 31. Dezember 2026 ab Mitternacht.</p>"
                    ),
                    baseUrl
                ).single()
        night.title shouldBe "Silvester im Sisy"
        night.eventDate shouldBe LocalDate.of(2026, 12, 31)
    }

    @Test
    fun `skips merch and a ticket with an impossible date`() {
        scraper.scrape(product(handle = "t-shirt", title = "T-Shirt 10. OKT 2026", productType = "Physical"), baseUrl).shouldBeEmpty()
        scraper.scrape(product(handle = "no-such-day", title = "generationS 31. FEB 2026"), baseUrl).shouldBeEmpty()
    }

    @Test
    fun `returns nothing for an empty, malformed or unexpected payload`() {
        scraper.scrape("""{"products": []}""", baseUrl).shouldBeEmpty()
        scraper.scrape("not json", baseUrl).shouldBeEmpty()
        scraper.scrape("""{"collections": []}""", baseUrl).shouldBeEmpty()
    }

    private fun product(
        handle: String,
        title: String,
        productType: String = "Ticket",
        body: String = "",
        variants: String = """[{"price": "25.00", "available": true}]"""
    ): String =
        """
        {"products": [{
            "handle": "$handle",
            "title": "$title",
            "product_type": "$productType",
            "body_html": "${body.replace("\"", "\\\"")}",
            "variants": $variants,
            "images": []
        }]}
        """.trimIndent()
}
