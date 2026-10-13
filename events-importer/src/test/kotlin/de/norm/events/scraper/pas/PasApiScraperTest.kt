package de.norm.events.scraper.pas

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.time.LocalTime

class PasApiScraperTest {
    private val scraper = PasApiScraper()

    private val fixture by lazy {
        javaClass.classLoader
            .getResourceAsStream("scraper/pas/pas-events.json")!!
            .bufferedReader()
            .readText()
    }

    private val page by lazy { scraper.scrapePage(fixture) }

    private fun event(slug: String) = page.events.single { it.sourceId == "pas:$slug" }

    private fun names(slug: String) = event(slug).artists.map { it.name }

    /** The fixture's first concert alone, with [title], [categories] and [venue] in place of its own; null when skipped. */
    private fun variant(
        title: String,
        categories: List<String> = listOf("Concert"),
        venue: String? = null
    ): ScrapedEvent? =
        JsonMapper().let { mapper ->
            val root = mapper.readTree(fixture) as ObjectNode
            val events = root.withArray("events")
            val night = events.first() as ObjectNode
            night.put("title", title)
            val categoryNodes = night.putArray("categories")
            categories.forEach { categoryNodes.addObject().put("name", it) }
            if (venue != null) night.putObject("venue").put("venue", venue)
            events.removeAll()
            events.add(night)
            scraper.scrapePage(mapper.writeValueAsString(root)).events.singleOrNull()
        }

    @Test
    fun `reads every event except the two held at other venues`() {
        page.events shouldHaveSize 21
        page.events.none { it.sourceId == "pas:pas-berlin-at-zk-u-openhaus-event" } shouldBe true
        page.events.none { it.sourceId == "pas:pas-event-at-kallasch-bar" } shouldBe true
        page.nextPageUrl.shouldBeNull()
    }

    @Test
    fun `maps a concert with its players, its doors and its styles`() {
        val concert = event("ernesto-rodrigues-eric-wong-guilherme-rodrigues-alexander-markvart")

        concert.title shouldBe "Ernesto Rodrigues, Eric Wong, Guilherme Rodrigues / Alexander Markvart"
        concert.eventType shouldBe "CONCERT"
        concert.typeIsFallback shouldBe false
        concert.genre shouldBe "Experimental, Free Improvisation"
        concert.eventDate shouldBe LocalDate.of(2026, 3, 17)
        concert.doorsTime shouldBe LocalTime.of(19, 30)
        concert.startTime.shouldBeNull()
        concert.endDate shouldBe LocalDate.of(2026, 3, 17)
        concert.endTime shouldBe LocalTime.of(23, 0)
        concert.sourceUrl shouldBe "https://pas-berlin.org/event/ernesto-rodrigues-eric-wong-guilherme-rodrigues-alexander-markvart/"
        concert.imageUrl.shouldNotBeNull() shouldStartWith "https://pas-berlin.org/wp-content/uploads/2026/03/"
        concert.ticketUrl.shouldBeNull()
        concert.pricePresale.shouldBeNull()
        concert.promoters.shouldBeEmpty()
        concert.artists.map { it.name } shouldContainExactly
            listOf("Ernesto Rodrigues", "Eric Wong", "Guilherme Rodrigues", "Alexander Markvart")
    }

    @Test
    fun `keeps the plugin's date and calendar-export blocks out of the description`() {
        val description = event("ernesto-rodrigues-eric-wong-guilherme-rodrigues-alexander-markvart").description.shouldNotBeNull()

        description shouldStartWith "Sudden concert in PAS: trio: Ernesto Rodrigues"
        description shouldNotContain "Google Calendar"
        description shouldNotContain "iCalendar"
    }

    @Test
    fun `reads a start after the doors from the description`() {
        val night = event("certain-lives-idol-worship-olive-ivy-beth-ellen-rosenbaum-piano-dingo-date-night")

        night.doorsTime shouldBe LocalTime.of(19, 0)
        night.startTime shouldBe LocalTime.of(19, 30)
    }

    @Test
    fun `keeps the api time as the start when the description names no doors`() {
        event("music-for-cosmos-rafgak").startTime shouldBe LocalTime.of(16, 0)
        event("music-for-cosmos-rafgak").doorsTime.shouldBeNull()
    }

    @Test
    fun `drops the series or label before a colon and bills an ensemble without its players`() {
        names("music-for-cosmos-rafgak") shouldContainExactly listOf("RAFGAK")
        names("music-for-cosmos-deeppopotamus-sic-logs") shouldContainExactly listOf("Deeppopotamus", "SIC!+", "Logs")
        names("akt-produkt-label-day-sic-martin-kuchen-joel-grip") shouldContainExactly listOf("SIC!", "Martin Küchen", "Joel Grip")
        names("sudden-concert-catherine-lamb-xavier-lopez-polivoks-trio-asnan-hall-markvart") shouldContainExactly
            listOf("Catherine Lamb", "Xavier Lopez", "Polivoks Trio")
        names("toshimaru-nakamura-kaffe-matthews-rafgak-bryan-eubanks-alexander-markvart") shouldContainExactly
            listOf("Toshimaru Nakamura", "Kaffe Matthews", "RAFGAK")
    }

    @Test
    fun `completes a surname from the credit lines, and keeps one no line credits`() {
        names("tuerlinckx-cusack-zabelka-hackel-strasser-markvart") shouldContainExactly
            listOf("Anaïs Tuerlinckx", "Peter Cusack", "Zabelka", "Teresa Hackel", "Stefan Strasser", "Alexander Markvart")
        names("cp-unit-with-lorena-izquierdo-reznichenko-denker-steidle") shouldContainExactly
            listOf("CP Unit with Lorena Izquierdo", "Olga Reznichenko", "Beat Denker", "Oliver Steidle")
    }

    @Test
    fun `drops a player's instrument from the billed name`() {
        names("certain-lives-idol-worship-olive-ivy-beth-ellen-rosenbaum-piano-dingo-date-night") shouldContainExactly
            listOf("Certain Lives", "Idol Worship", "Olive Ivy", "Beth Ellen Rosenbaum", "Dingo Date Night")
    }

    @Test
    fun `bills no act for a series night titled by its name`() {
        event("hard-to-talk-september-edition-3").artists.shouldBeEmpty()
        event("himmelsduft").artists.shouldBeEmpty()
        event("music-for-cosmos-meets-dissidents").artists.shouldBeEmpty()
    }

    @Test
    fun `keeps a price range as a note and infers the type of an uncategorised event`() {
        val songs = event("slightly-imperfect-songs-of-ivor-cutler")

        songs.priceNote shouldBe "10€ – 15€"
        songs.pricePresale.shouldBeNull()
        songs.typeIsFallback shouldBe true
        songs.endTime.shouldBeNull()
    }

    @Test
    fun `skips an event whose venue record names another place`() {
        variant("Kerbaj / Markvart", venue = "Kallasch&").shouldBeNull()
        variant("Kerbaj / Markvart", venue = "PAS Riverside").shouldNotBeNull()
    }

    @Test
    fun `skips an event whose title alone puts it at another venue`() {
        variant("PAS event at Kallasch&amp;!").shouldBeNull()
        variant("PAS Berlin at ZK/U OPENHAUS event").shouldBeNull()
    }

    @Test
    fun `keeps an event whose title puts it at the house, without that tail`() {
        variant("DANCE at PAS", listOf("Concert", "Performance")).shouldNotBeNull().title shouldBe "DANCE"
        variant("BANVIVAN at PAS").shouldNotBeNull().title shouldBe "BANVIVAN"
        variant("Party Perfect Label Showcase at PAS Berlin, Night 1").shouldNotBeNull().title shouldBe
            "Party Perfect Label Showcase at PAS Berlin, Night 1"
        variant("DISSIDENTS LXV at PAS Berlin").shouldNotBeNull().title shouldBe "DISSIDENTS LXV"
    }

    @Test
    fun `skips an event filed only under workshop or social`() {
        variant("Queer Life Drawing Session", listOf("workshop")).shouldBeNull()
        variant("Sprachcafé at PAS", listOf("sprachcafe / social")).shouldBeNull()
    }

    @Test
    fun `types an event by its first music, stage or art category`() {
        variant("Femmedecks", listOf("Club / Party", "Installation")).shouldNotBeNull().eventType shouldBe "PARTY"
        variant("Klub Demboh", listOf("Theatre", "Performance")).shouldNotBeNull().eventType shouldBe "SHOW"
        variant("Stills", listOf("Exhibition")).shouldNotBeNull().eventType shouldBe "EXHIBITION"
        variant("Film night", listOf("Film screening")).shouldNotBeNull().eventType shouldBe "SCREENING"
    }
}
