package de.norm.events.scraper.tempodrom

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Unit tests for [TempodromDetailPageScraper], against saved event pages. */
class TempodromDetailPageScraperTest {
    private val scraper = TempodromDetailPageScraper()

    private val listingRow =
        ScrapedEvent(
            title = "Jill Scott",
            eventDate = LocalDate.of(2026, 10, 6),
            sourceUrl = "https://www.tempodrom.de/event/jill_scott_2026-10-06_20/",
            sourceId = "tempodrom:jill_scott_2026-10-06_20"
        )

    private fun page(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/tempodrom/$name")!!
                .bufferedReader()
                .readText(),
            listingRow.sourceUrl
        )

    @Test
    fun `reads the promoter and its website from the Veranstalter credit`() {
        val event = scraper.addPromoter(listingRow, page("tempodrom-detail-trinity-music.html"))

        event.promoters shouldBe listOf("Trinity Music GmbH")
        event.promoterWebsites shouldBe mapOf("Trinity Music GmbH" to "http://www.trinitymusic.de")
    }

    @Test
    fun `reads a second promoter from another page`() {
        val event = scraper.addPromoter(listingRow, page("tempodrom-detail-d2mberlin.html"))

        event.promoters shouldBe listOf("d2mberlin GmbH")
        event.promoterWebsites shouldBe mapOf("d2mberlin GmbH" to "http://www.d2mberlin.de")
    }

    @Test
    fun `never reads the media partner the blurb names`() {
        // The Jill Scott page says "präsentiert von Radio Eins" in its blurb (#2653).
        scraper.addPromoter(listingRow, page("tempodrom-detail-trinity-music.html")).promoters shouldBe listOf("Trinity Music GmbH")
    }

    @Test
    fun `keeps a credit without a link and drops its label`() {
        val document = Jsoup.parse("<div class=copy>Veranstalter: Tempodrom GmbH</div>", listingRow.sourceUrl)

        val event = scraper.addPromoter(listingRow, document)

        event.promoters shouldBe listOf("Tempodrom GmbH")
        event.promoterWebsites shouldBe emptyMap()
    }

    @Test
    fun `repairs a name the venue stores double-encoded and drops an empty link`() {
        // elena_uhlig_und_fritz_karl_2027-04-01_20 credits it exactly so.
        val document = Jsoup.parse("<div class=copy>Veranstalter <a href=http:// target=_blank>KonzertbÃ¼ro Augsburg</a></div>", listingRow.sourceUrl)

        val event = scraper.addPromoter(listingRow, document)

        event.promoters shouldBe listOf("Konzertbüro Augsburg")
        event.promoterWebsites shouldBe emptyMap()
    }

    @Test
    fun `leaves a correctly encoded name alone`() {
        val document = Jsoup.parse("<div class=copy>Veranstalter <a>Concertbüro Zahlmann GmbH</a></div>", listingRow.sourceUrl)

        scraper.addPromoter(listingRow, document).promoters shouldBe listOf("Concertbüro Zahlmann GmbH")
    }

    @Test
    fun `leaves the event as the listing gave it when the page credits no promoter`() {
        val document = Jsoup.parse("<div class=copy>Event teilen</div><p>präsentiert von Radio Eins</p>", listingRow.sourceUrl)

        scraper.addPromoter(listingRow, document) shouldBeSameInstanceAs listingRow
    }
}
