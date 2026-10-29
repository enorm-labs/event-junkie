package de.norm.events.scraper.interkosmos

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** The two `wp/v2/events` answers saved on 2026-10-23, the one-off events from that day and the rules, plus row 2679 from 2026-10-29. */
fun interkosmosFixture(name: String): String =
    InterkosmosApiScraperTest::class.java.classLoader
        .getResourceAsStream("scraper/interkosmos/$name")!!
        .bufferedReader()
        .readText()

class InterkosmosApiScraperTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val events = InterkosmosApiScraper().scrape(interkosmosFixture("interkosmos-events.json"), interkosmosFixture("interkosmos-rules.json"), today)

    @Test
    fun `reads the one-off events and thirteen weeks of comedy nights, without the yoga`() {
        events shouldHaveSize 19
        events.filter { it.eventType == EventType.COMEDY.name } shouldHaveSize 11
        events.filter { "Yoga" in it.title }.shouldBeEmpty()
        events.filter { it.eventDate == LocalDate.of(2026, 11, 10) }.shouldBeEmpty()
        events.map { it.sourceId }.toSet().size shouldBe events.size
    }

    @Test
    fun `dates each comedy rule on its Thursday of the month and skips a fifth Thursday and one past the horizon`() {
        val universe = events.filter { it.title == "Comedy Universum" }.map { it.eventDate }
        val cactus = events.filter { it.title == "Kaktus Comedy" }.map { it.eventDate }

        universe.sorted() shouldBe listOf("2026-11-05", "2026-11-19", "2026-12-03", "2026-12-17", "2027-01-07", "2027-01-21").map(LocalDate::parse)
        cactus.sorted() shouldBe listOf("2026-11-12", "2026-11-26", "2026-12-10", "2026-12-24", "2027-01-14").map(LocalDate::parse)
        events.none { it.eventDate == LocalDate.of(2026, 10, 29) } shouldBe true
    }

    @Test
    fun `reads a comedy night's times and keys it by rule and date`() {
        val night = events.single { it.title == "Comedy Universum" && it.eventDate == LocalDate.of(2026, 11, 5) }

        night.startTime shouldBe LocalTime.of(19, 30)
        night.endTime shouldBe LocalTime.of(22, 0)
        night.endDate shouldBe night.eventDate
        night.sourceId shouldStartWith "interkosmos:"
        night.sourceId.endsWith("-2026-11-05") shouldBe true
        night.description!! shouldStartWith "Willkommen zum COMEDY UNIVERSUM"
    }

    @Test
    fun `reads a poetry slam with its price and ticket link`() {
        val slam = events.single { it.title == "flinta*kosmos" }

        slam.eventType shouldBe EventType.READING.name
        slam.eventDate shouldBe LocalDate.of(2026, 11, 17)
        slam.pricePresale shouldBe BigDecimal("10")
        slam.ticketUrl!! shouldStartWith "https://www.eventbrite.de/e/flintakosmos-tickets-"
        slam.sourceUrl shouldBe "https://www.interkosmos.cc/ik_event/flintakosmos/"
    }

    @Test
    fun `types a BuchBar reading by its title and bills the author, typed or not`() {
        val typed = events.single { it.title == "BuchBar mit Jana Glaese" }
        val untyped = events.single { it.title == "BuchBar mit Ulrike Almut Sandig" }

        typed.artists shouldContainExactly listOf(ScrapedArtist("Jana Glaese"))
        untyped.eventType shouldBe EventType.READING.name
        untyped.typeIsFallback shouldBe false
        untyped.artists shouldContainExactly listOf(ScrapedArtist("Ulrike Almut Sandig"))
    }

    @Test
    fun `types the games night, the party and the burlesque open stage`() {
        events.single { it.title.startsWith("Nerds of Beats") }.eventType shouldBe EventType.OTHER.name
        events.single { it.title.startsWith("QUEER*Kosmos") }.eventType shouldBe EventType.PARTY.name
        events.single { it.title == "PracTease Burlesque Open Stage" }.eventType shouldBe EventType.SHOW.name
        events.single { it.title.startsWith("Nerds of Beats") }.endTime.shouldBeNull()
    }

    @Test
    fun `drops a title that names a workshop or crafting, whatever its type`() {
        val titles =
            listOf(
                "Laternen basteln mit dem Kiezteam" to "Infoveranstaltung",
                "Bastelnachmittag für Groß und Klein" to "",
                "WORKSHOP: Siebdruck" to "Party",
                "Songwriting-Workshops ${"mit viel Zeit zum Ausprobieren ".repeat(8)}" to "Konzert",
                "Bastille Day Disco" to "Party",
                "Basteln" to "Yoga"
            )
        val json = titles.mapIndexed { i, (title, type) -> oneOffRow(9000 + i, title, type) }.joinToString(",", "[", "]")

        InterkosmosApiScraper().scrape(json, "[]", today).map { it.title } shouldContainExactly listOf("Bastille Day Disco")
    }

    private fun oneOffRow(
        id: Int,
        title: String,
        type: String
    ) =
        """{"id": $id, "title": {"rendered": "$title"}, "ik_event_type": "$type", "ik_date": "2026-11-10", "link": "https://www.interkosmos.cc/ik_event/$id/"}"""
}
