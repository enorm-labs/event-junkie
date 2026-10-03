package de.norm.events.scraper.downstairs

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class DownstairsTicketListScraperTest {
    private val scraper = DownstairsTicketListScraper()
    private val url = "https://www.downstairscomedy.shop/catalog/tickets?limit=100&use_sold=true&use_pagy=true&view_type=list"

    private fun document(name: String) =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/downstairs/$name")!!
                .bufferedReader()
                .readText(),
            url
        )

    @Test
    fun `reads the hundred performances of the first page and skips the workshops`() {
        val events = scraper.scrape(document("downstairs-tickets-page1.html"), url)

        events shouldHaveSize 98
        events.none { it.title.startsWith("Stand-Up Comedy Workshop") } shouldBe true
        scraper.nextPage(document("downstairs-tickets-page1.html")) shouldBe 2
        scraper.nextPage(document("downstairs-tickets-page2.html")) shouldBe null
    }

    @Test
    fun `reads a performance with the year from its ticket link`() {
        val show = scraper.scrape(document("downstairs-tickets-page1.html"), url).first()

        show.title shouldBe "Downstairs Allstars - Stand Up Comedy Show"
        show.eventType shouldBe "COMEDY"
        show.eventDate shouldBe LocalDate.of(2026, 10, 3)
        show.startTime shouldBe LocalTime.of(18, 0)
        show.sourceId shouldBe "downstairs:98305"
        show.sourceUrl shouldBe
            "https://www.downstairscomedy.shop/catalog/tickets/98305-tickets-downstairs-allstars-stand-up-comedy-show-downstairs-comedy-club-berlin-am-03-10-2026"
        show.soldOut shouldBe true
        show.artists.shouldBeEmpty()
    }

    @Test
    fun `bills a guest's solo show`() {
        val events = scraper.scrape(document("downstairs-tickets-page1.html"), url) + scraper.scrape(document("downstairs-tickets-page2.html"), url)

        events.first { it.title.startsWith("Moritz Hohl - Premiere") }.artists.map { it.name } shouldContainExactly listOf("Moritz Hohl")
    }

    @Test
    fun `reads nothing from an empty frame`() {
        scraper.scrape(Jsoup.parse("<turbo-frame id=\"tickets\"></turbo-frame>", url), url) shouldHaveSize 0
    }
}
