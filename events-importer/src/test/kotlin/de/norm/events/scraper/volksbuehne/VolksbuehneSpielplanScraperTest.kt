package de.norm.events.scraper.volksbuehne

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class VolksbuehneSpielplanScraperTest {
    private val url = "https://www.volksbuehne-berlin.de/"
    private val document =
        Jsoup.parse(
            requireNotNull(javaClass.classLoader.getResourceAsStream("scraper/volksbuehne/volksbuehne-spielplan.html")).bufferedReader().readText(),
            url
        )

    private val main = VolksbuehneSpielplanScraper(VolksbuehneHouse.ROSA_LUXEMBURG_PLATZ).scrape(document, url)
    private val prater = VolksbuehneSpielplanScraper(VolksbuehneHouse.PRATER).scrape(document, url)

    @Test
    fun `splits the rows between the two houses and drops the guest performance, workshops and tours`() {
        main shouldHaveSize 82
        prater shouldHaveSize 16
        (main + prater).map { it.title } shouldNotContain "Ich weiß nicht, was ein Ort ist, ich kenne nur seinen Preis (Manzini-Studien)"
        (main + prater).map { it.title } shouldNotContain "Theaterführung"
        (main + prater).map { it.title } shouldNotContain "Raw Voice Lab"
        (main + prater).map { it.sourceId }.toSet() shouldHaveSize 98
    }

    @Test
    fun `reads a staged work on the main stage with its surtitles and sold-out tickets`() {
        val event = main.first { it.title == "A Year without Summer" }

        event.eventDate shouldBe LocalDate.of(2026, 10, 17)
        event.startTime shouldBe LocalTime.of(19, 30)
        event.room.shouldBeNull()
        event.subtitle shouldBe "Florentina Holzinger · mit dt. und engl. ÜT"
        event.eventType shouldBe EventType.SHOW.name
        event.typeIsFallback shouldBe true
        event.soldOut shouldBe true
        event.sourceUrl shouldBe "https://www.volksbuehne-berlin.de/produktionen/a-year-without-summer/20261017-1730/"
        event.sourceId shouldBe "volksbuehne:a-year-without-summer-20261017-1730"
        event.artists.shouldBeEmpty()
    }

    @Test
    fun `bills the acts of a concert from its mit line and keeps the salon as the room`() {
        val event = main.first { it.title == "Space Afrika present: Quiet Storm" }

        event.room shouldBe "Vorbühne"
        event.eventType shouldBe EventType.CONCERT.name
        event.typeIsFallback shouldBe false
        event.ticketUrl shouldBe "https://ticket.volksbuehne-berlin.de/eventim.webshop/webticket/shop?event=23750"
        event.artists.map { it.name } shouldContainExactly
            listOf("Tony Njoku", "Any", "Pike", "Gabriel", "Vanessa Bedoret", "Nardus Williams", "Bapari")
        event.artists.map { it.role }.toSet() shouldBe setOf("HEADLINER")
    }

    @Test
    fun `bills the DJs of a party after von, and no workshop group`() {
        val party = main.first { it.title == "Danse Macabre" }
        val showcase = main.first { it.title == "Raw Voice Lab Showcase" }

        party.eventType shouldBe EventType.PARTY.name
        party.artists.first() shouldBe ScrapedArtist(name = "Leslie Garcia", role = "DJ")
        showcase.artists.map { it.name } shouldContainExactly listOf("Sarmen Almond", "Anna Clementi")
    }

    @Test
    fun `types a talk and a screening from the format label, and a series evening keys on its page`() {
        val talk = main.first { it.title == "Mapping the Right: Folge 1" }
        val screening = main.first { it.title == "A Friend in Berlin" }

        talk.eventType shouldBe EventType.READING.name
        talk.room shouldBe "Roter Salon"
        talk.sourceId shouldBe "volksbuehne:afd-monitoring-at-mapping-the-right-folge-1"
        screening.eventType shouldBe EventType.SCREENING.name
    }

    @Test
    fun `keeps the Prater's foyer as a room and marks free entry`() {
        val reading = prater.first { it.title.startsWith("Poethical Readings") }
        val perfection = prater.first { it.title == "Perfection" }

        reading.room shouldBe "Foyer"
        reading.sourceId shouldBe "volksbuehne_prater:the-school-of-self-defense-20261031-1300"
        perfection.room.shouldBeNull()
        main.count { it.free } shouldBe 7
    }

    @Test
    fun `types by the first keyword of a compound label and drops nothing it does not know`() {
        VolksbuehneSpielplanScraper.eventType("Konzert und Künstler*innengespräch") shouldBe EventType.CONCERT
        VolksbuehneSpielplanScraper.eventType("Screening und Gespräch") shouldBe EventType.SCREENING
        VolksbuehneSpielplanScraper.eventType("Lesungen und Performances") shouldBe EventType.READING
        VolksbuehneSpielplanScraper.eventType("Sound meditation and album release").shouldBeNull()
        VolksbuehneSpielplanScraper.isOutOfScope("Theaterführung", null) shouldBe true
        VolksbuehneSpielplanScraper.isOutOfScope("Joy Is Not a Metaphor", "Workshop") shouldBe true
        VolksbuehneSpielplanScraper.isOutOfScope("Mononoke", null) shouldBe false
    }

    @Test
    fun `drops a children's show`() {
        val html =
            """
            <section id="performances"><ul><li class="activity">
              <p class="activity__time"><time datetime="2026-12-06T11:00:00+01:00">11:00</time></p>
              <p class="activity__stage">Roter Salon</p>
              <h3 class="activity__title"><a href="/produktionen/kasper/20261206-1000/">Kasper und das Krokodil</a></h3>
              <p class="activity__title_addition">Ein Puppenspiel für Kinder ab 4 Jahren</p>
            </li></ul></section>
            """.trimIndent()

        VolksbuehneSpielplanScraper(VolksbuehneHouse.ROSA_LUXEMBURG_PLATZ).scrape(Jsoup.parse(html, url), url).shouldBeEmpty()
    }
}
