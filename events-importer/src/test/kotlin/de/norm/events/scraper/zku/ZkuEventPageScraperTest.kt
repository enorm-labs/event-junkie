package de.norm.events.scraper.zku

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ZkuEventPageScraperTest {
    private val scraper = ZkuEventPageScraper()

    private fun page(slug: String) =
        "https://www.zku-berlin.org/timeline/$slug/".let { url ->
            Jsoup.parse(javaClass.classLoader.getResource("scraper/zku/zku-event-$slug.html")!!.readText(), url)
        }

    private fun row(
        title: String,
        date: LocalDate,
        eventType: String = EventType.OTHER.name,
        subtitle: String? = null
    ) = ScrapedEvent(
        title = title,
        subtitle = subtitle,
        eventType = eventType,
        typeIsFallback = eventType == EventType.OTHER.name,
        eventDate = date,
        sourceUrl = "https://www.zku-berlin.org/timeline/x/",
        sourceId = "zku:x",
        imageUrl = "https://www.zku-berlin.org/crop.png"
    )

    @Test
    fun `types an untyped night with a DJ line-up as a party and times each set`() {
        val night = scraper.enrich(row("UNRUH & friends", LocalDate.of(2026, 10, 24)), page("unruh-friends")).shouldNotBeNull()

        night.eventType shouldBe EventType.PARTY.name
        night.typeIsFallback shouldBe false
        night.artists.map { it.name } shouldContainExactly listOf("Lezza", "Fanny Champuis", "Talia Door", "SHEZAY")
        night.artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
        night.artists.first().setStart shouldBe Instant.parse("2026-10-24T19:00:00Z")
        night.artists.last().setEnd shouldBe Instant.parse("2026-10-25T02:00:00Z")
        night.promoters shouldContainExactly listOf("UNRUH Kollektiv")
        night.description.shouldNotBeNull() shouldStartWith "Four years of UNRUH."
        night.description.shouldNotBeNull() shouldEndWith "dance until 3."
        night.imageUrl shouldBe "https://www.zku-berlin.org/fileadmin/_processed_/1/1/csm_UNRUH_friends_Fyler_V5_0a5e73a7ad.png"
    }

    @Test
    fun `splits a back-to-back line and reads a Tickets button`() {
        val night = scraper.enrich(row("AROMA DAY+NITE", LocalDate.of(2026, 8, 8)), page("aroma-day-nite")).shouldNotBeNull()

        night.eventType shouldBe EventType.PARTY.name
        night.artists.map { it.name } shouldContainExactly
            listOf("elliephunk", "asphalt angel", "blade", "DJ Gurk", "LG JASPER <3", "Sinan Varol", "Juicy Lucie", "Gigitòn")
        night.ticketUrl shouldBe "https://de.ra.co/events/2474237"
        night.promoters shouldContainExactly listOf("Aroma Kollektiv Berlin")
    }

    @Test
    fun `types a band night as a concert and keeps the donation line`() {
        val night =
            scraper.enrich(row("HOSE presents: Gorz, Hassan K. & Restless", LocalDate.of(2026, 9, 11)), page("hose")).shouldNotBeNull()

        night.eventType shouldBe EventType.CONCERT.name
        night.priceNote shouldBe "Entry by donation at the door. No pre-sale."
        night.priceBoxOffice.shouldBeNull()
        night.promoters shouldContainExactly listOf("HOSE shows")
        night.artists.map { it.name } shouldContainExactly listOf("Gorz", "Hassan K.", "Restless")
    }

    @Test
    fun `leaves an art evening untyped and its billing empty`() {
        val evening = scraper.enrich(row("Diffusion; a meddling.", LocalDate.of(2026, 10, 17)), page("diffusion-a-meddling")).shouldNotBeNull()

        evening.eventType shouldBe EventType.OTHER.name
        evening.typeIsFallback shouldBe true
        evening.artists.map { it.name } shouldContainExactly emptyList()
        evening.promoters shouldContainExactly listOf("Martin Roth Initiative")
        evening.ticketUrl.shouldBeNull()
    }

    @Test
    fun `keeps a talk's type and speaker`() {
        val talk = row("Cornelia Sollfrank: Revisiting Feminist Art", LocalDate.of(2026, 10, 23), EventType.READING.name)
        val enriched =
            scraper.enrich(
                talk.copy(
                    artists =
                        listOf(
                            de.norm.events.scraper
                                .ScrapedArtist("Cornelia Sollfrank")
                        )
                ),
                page("cornelia-sollfrank")
            )

        enriched.shouldNotBeNull().eventType shouldBe EventType.READING.name
        enriched.artists.map { it.name } shouldContainExactly listOf("Cornelia Sollfrank")
        enriched.description.shouldNotBeNull() shouldStartWith "In her exhibition Feminist Rage"
    }

    @Test
    fun `reads a screening's blurb`() {
        val screening = scraper.enrich(row("FREIPLATZ presents", LocalDate.of(2026, 10, 21), EventType.SCREENING.name), page("freiplatz")).shouldNotBeNull()

        screening.eventType shouldBe EventType.SCREENING.name
        screening.description.shouldNotBeNull() shouldStartWith "Freiplatz serves as the foundation"
        screening.priceBoxOffice shouldBe null
        screening.free shouldBe false
    }

    @Test
    fun `a page without the event article gives nothing`() {
        scraper.enrich(row("X", LocalDate.of(2026, 10, 1)), Jsoup.parse("<html><body><p>Gone</p></body></html>")).shouldBeNull()
    }

    @Test
    fun `reads a lone entry figure as the door price`() {
        val html =
            """<main><article><div class="block"><div class="block-padding"><p class="date">x</p>
            <p>Rooftop night.</p></div></div><section><div class="frame"><div class="ce-bodytext"><p>Entry is 5 € at the door.</p></div></div></section></article></main>"""
        val night = scraper.enrich(row("Lost in ZK/U", LocalDate.of(2026, 7, 10)), Jsoup.parse(html)).shouldNotBeNull()

        night.priceBoxOffice shouldBe BigDecimal("5")
        night.priceNote shouldBe "Entry is 5 € at the door."
    }
}
