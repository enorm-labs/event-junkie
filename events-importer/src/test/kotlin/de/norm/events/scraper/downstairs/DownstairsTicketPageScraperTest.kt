package de.norm.events.scraper.downstairs

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

class DownstairsTicketPageScraperTest {
    private val scraper = DownstairsTicketPageScraper()

    private fun document(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/downstairs/$name")!!
                .bufferedReader()
                .readText(),
            "https://www.downstairscomedy.shop/catalog/tickets/x"
        )

    @Test
    fun `reads the doors, the lowest tier and a note naming every tier`() {
        val show = scraper.scrape(document("downstairs-ticket-tiers.html")).shouldNotBeNull()

        show.doorsTime shouldBe LocalTime.of(19, 40)
        show.price shouldBe BigDecimal("15.5")
        show.priceNote shouldBe "Normalpreis: 27,50 € · Schüler:innen | Studierende | Azubis: 23,50 € · Social Club: 15,50 €"
    }

    @Test
    fun `reads the doors of a sold-out page, which lists no tier labels`() {
        val show = scraper.scrape(document("downstairs-ticket.html")).shouldNotBeNull()

        show.doorsTime shouldBe LocalTime.of(17, 40)
        show.price shouldBe BigDecimal("25.5")
        show.priceNote.shouldBeNull()
    }

    @Test
    fun `names no tier for a page with one price`() {
        val html =
            """
            <script type="application/ld+json">{"@type":"Event","name":"x","offers":[{"@type":"Offer","price":"24.9"}]}</script>
            <span class="ds-info-badge">Einlass: 23:40 Uhr / Beginn: 00:00 Uhr</span>
            <div class="variant-form"><div class="variant-form-body"><div class="row"><div class="col"><div class="row">
              <div class="col"><div>Normalpreis</div></div><div class="col-auto">24,90 €</div>
            </div></div></div></div></div>
            """.trimIndent()

        val show = scraper.scrape(Jsoup.parse(html)).shouldNotBeNull()

        show.doorsTime shouldBe LocalTime.of(23, 40)
        show.priceNote.shouldBeNull()
    }

    @Test
    fun `reads only the text of another seller's page that the shop redirects to`() {
        val html =
            """
            <script type="application/ld+json">{"@type":"Event","name":"Unfallkind","description":"Die neue Show.",
              "image":["https://fienta.com/cf/img/?file=x.jpg"],"offers":[{"@type":"Offer","price":"0.00"}]}</script>
            """.trimIndent()

        val show = scraper.scrape(Jsoup.parse(html)).shouldNotBeNull()

        show.soldElsewhere shouldBe true
        show.description shouldBe "Die neue Show."
        show.imageUrl.shouldBeNull()
        show.price.shouldBeNull()
        show.doorsTime.shouldBeNull()
    }

    @Test
    fun `reads nothing from another seller's page without a description`() {
        val html =
            """
            <script type="application/ld+json">{"@type":"Event","name":"Unfallkind","offers":[{"@type":"Offer","price":"0.00"}]}</script>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html)).shouldBeNull()
    }

    @Test
    fun `marks the shop's own page as not sold elsewhere`() {
        scraper.scrape(document("downstairs-ticket.html")).shouldNotBeNull().soldElsewhere shouldBe false
    }
}
