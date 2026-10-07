package de.norm.events.scraper.wuehlmaeuse

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

class WuehlmaeuseApiScraperTest {
    private val scraper = WuehlmaeuseApiScraper()

    private fun readFixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/wuehlmaeuse/$name")!!
            .bufferedReader()
            .readText()

    private val pageOne by lazy { scraper.scrapePage(readFixture("wuehlmaeuse-shop-page1.json")) }
    private val picked by lazy { scraper.toEvents(scraper.scrapePage(readFixture("wuehlmaeuse-shop-last.json")).tickets) }

    @Test
    fun `reads a full page of tickets and says another may follow`() {
        pageOne.tickets shouldHaveSize 100
        pageOne.full shouldBe true
        scraper.scrapePage(readFixture("wuehlmaeuse-shop-last.json")).full shouldBe false
    }

    @Test
    fun `folds the price categories of one performance into one event at the lowest price`() {
        val events = scraper.toEvents(pageOne.tickets)
        val show = events.first { it.title == "Florian Schroeder" && it.eventDate == LocalDate.of(2027, 10, 3) }

        events.size shouldBe events.map { it.sourceId }.distinct().size
        show.subtitle shouldBe "Endlich glücklich"
        show.eventType shouldBe "COMEDY"
        show.startTime shouldBe LocalTime.of(20, 0)
        show.doorsTime shouldBe LocalTime.of(19, 0)
        show.sourceUrl shouldBe "https://wuehlmaeuse.de/veranstaltung/florian-schroeder-endlich"
        show.sourceId shouldBe "wuehlmaeuse:florian-schroeder-endlich-2027-10-03-2000"
        show.pricePresale.shouldNotBeNull() shouldBe
            pageOne.tickets
                .filter { it.eventUrl == show.sourceUrl && it.date == show.eventDate }
                .mapNotNull { it.price }
                .min()
        show.imageUrl.shouldNotBeNull()
        show.artists.map { it.name } shouldContainExactly listOf("Florian Schroeder")
    }

    @Test
    fun `reads the price in euros from the minor units`() {
        pageOne.tickets.first().price shouldBe BigDecimal("40.30")
    }

    @Test
    fun `marks a cancelled show and strips the marker from its title and programme`() {
        val cancelled = picked.first { it.title == "Voices of Cinema" }

        cancelled.status shouldBe "CANCELLED"
        cancelled.subtitle.shouldNotBeNull() shouldNotContain "ABGESAGT"
        picked.first { it.title == "Florian Schroeder" }.status shouldBe "SCHEDULED"
    }

    @Test
    fun `bills no act for a series or a singalong`() {
        picked.first { it.title.startsWith("Lach-Stoff") }.artists.shouldBeEmpty()
        picked.first { it.title == "Rudelsingen 2027" }.artists.shouldBeEmpty()
    }

    @Test
    fun `degrades a body that is not JSON to an empty last page`() {
        val broken = scraper.scrapePage("not json")

        broken.tickets.shouldBeEmpty()
        broken.full shouldBe false
    }

    @Test
    fun `drops a family show for small children`() {
        fun ticket(
            act: String,
            programme: String
        ) = WuehlmaeuseTicket(
            act = act,
            programme = programme,
            date = LocalDate.of(2026, 12, 22),
            start = LocalTime.of(14, 0),
            doors = null,
            price = BigDecimal("18.00"),
            eventUrl = "https://wuehlmaeuse.de/veranstaltung/${act.lowercase().replace(' ', '-')}",
            ticketUrl = "https://wuehlmaeuse.de/shop/",
            imageUrl = null,
            cancelled = false
        )

        scraper
            .toEvents(
                listOf(
                    ticket("Linus Faber", "Die große Familien-Zaubershow für Kinder ab 4 Jahre"),
                    ticket("Florian Schroeder", "Endlich glücklich")
                )
            ).map { it.title } shouldContainExactly listOf("Florian Schroeder")
    }
}
