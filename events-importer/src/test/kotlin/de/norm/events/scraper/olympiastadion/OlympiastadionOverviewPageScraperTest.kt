package de.norm.events.scraper.olympiastadion

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate

class OlympiastadionOverviewPageScraperTest {
    private val baseUrl = "https://olympiastadion.berlin/termine-und-tickets/"
    private val scraper = OlympiastadionOverviewPageScraper()

    private fun fixture(): String =
        javaClass.classLoader
            .getResourceAsStream("scraper/olympiastadion/olympiastadion-overview.html")!!
            .bufferedReader()
            .readText()

    private val events = scraper.scrape(Jsoup.parse(fixture(), baseUrl), baseUrl)

    @Test
    fun `reads each event card once and skips Hertha's game cards`() {
        events.map { it.sourceId } shouldContainExactly
            listOf(
                "olympiastadion:deutschland-niederlande",
                "olympiastadion:s25-berlin-2027",
                "olympiastadion:u2-the-greatest-show-on-earth-world-tour"
            )
    }

    @Test
    fun `reads title, date, poster and act of a concert card`() {
        val u2 = events.single { it.sourceId.endsWith("u2-the-greatest-show-on-earth-world-tour") }
        u2.title shouldBe "U2 – The Greatest Show On Earth World Tour"
        u2.eventDate shouldBe LocalDate.of(2027, 6, 30)
        u2.eventType shouldBe EventType.CONCERT.name
        u2.sourceUrl shouldBe "https://olympiastadion.berlin/events/u2-the-greatest-show-on-earth-world-tour/"
        u2.imageUrl shouldBe "https://olympiastadion.berlin/wp-content/uploads/2026/11/U2_SocialIcon_01B.jpg"
        u2.artists.map { it.name to it.role } shouldContainExactly listOf("U2" to ArtistRole.HEADLINER)
    }

    @Test
    fun `skips a card without a date`() {
        val html =
            """
            <section class="termine"><a href="https://olympiastadion.berlin/events/x/" class="card-link">
            <div class="card__content"><h3>No date</h3></div></a></section>
            """.trimIndent()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }
}
