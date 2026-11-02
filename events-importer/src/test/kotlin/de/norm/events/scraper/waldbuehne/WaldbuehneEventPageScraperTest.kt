package de.norm.events.scraper.waldbuehne

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class WaldbuehneEventPageScraperTest {
    private val scraper = WaldbuehneEventPageScraper()

    private fun page(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/waldbuehne/$name")!!
                .bufferedReader()
                .readText(),
            "https://www.waldbuehne-berlin.de/event/x/"
        )

    private fun listed(
        title: String,
        subtitle: String
    ) = ScrapedEvent(
        title = title,
        subtitle = subtitle,
        eventType = "CONCERT",
        eventDate = LocalDate.of(2027, 6, 8),
        sourceUrl = "https://www.waldbuehne-berlin.de/event/x/",
        sourceId = "waldbuehne:x",
        artists = listOf(ScrapedArtist(title, ArtistRole.HEADLINER))
    )

    @Test
    fun `bills the support act and keeps the bare tour line`() {
        val event = scraper.enrich(listed("blink-182", "Live 2027 - Billy Talent"), page("waldbuehne-event-support.html"))

        event.subtitle shouldBe "Live 2027"
        event.artists shouldContainExactly
            listOf(ScrapedArtist("blink-182", ArtistRole.HEADLINER), ScrapedArtist("Billy Talent", ArtistRole.SUPPORT))
        event.ticketUrl.shouldNotBeNull() shouldStartWith "https://www.eventim.de/"
        event.promoters shouldContainExactly listOf("PRK DreamHaus GmbH")
        event.promoterWebsites shouldBe mapOf("PRK DreamHaus GmbH" to "https://www.dreamhaus.com/")
        event.description.shouldNotBeNull() shouldStartWith "blink-182 kehren nach Deutschland zurück!"
        event.description.shouldNotBeNull() shouldNotContain "Einlasskontrolle"
    }

    @Test
    fun `reads a tbd clock as none`() {
        val event = scraper.enrich(listed("blink-182", "Live 2027"), page("waldbuehne-event-support.html"))

        event.doorsTime.shouldBeNull()
        event.startTime.shouldBeNull()
    }

    @Test
    fun `reads the doors and start clocks`() {
        val event = scraper.enrich(listed("DAS GIPFELTREFFEN", "x"), page("waldbuehne-event-cast-line.html"))

        event.doorsTime shouldBe LocalTime.of(17, 0)
        event.startTime shouldBe LocalTime.of(19, 0)
        event.promoters shouldContainExactly listOf("d2m Berlin GmbH")
    }

    @Test
    fun `marks a sold-out date, which links no shop`() {
        val event = scraper.enrich(listed("Herbert Grönemeyer", "Arena-Tour 2027"), page("waldbuehne-event-sold-out.html"))

        event.soldOut shouldBe true
        event.ticketUrl.shouldBeNull()
        event.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `links no shop for a date not yet on sale`() {
        val event = scraper.enrich(listed("Noah Kahan", "The Last Of The Bugs World Tour"), page("waldbuehne-event-no-presale.html"))

        event.ticketUrl.shouldBeNull()
        event.soldOut shouldBe false
        event.promoters shouldContainExactly listOf("Live Nation GmbH")
    }
}
