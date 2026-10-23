package de.norm.events.scraper.wienerblut

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** The live `/event-list` saved on 2026-10-23 held one gig; the other states come from `wienerblut-overview-edge-cases.html`. */
class WienerBlutOverviewPageScraperTest {
    private val baseUrl = "https://www.wienerblut.org/event-list"

    private fun parse(fixture: String): List<ScrapedEvent> =
        WienerBlutOverviewPageScraper().scrape(
            Jsoup.parse(
                javaClass.classLoader
                    .getResourceAsStream("scraper/wienerblut/$fixture")!!
                    .bufferedReader()
                    .readText(),
                baseUrl
            ),
            baseUrl
        )

    private val edgeCases by lazy { parse("wienerblut-overview-edge-cases.html") }

    private fun edgeCase(slug: String) = edgeCases.single { it.sourceId == "wiener_blut:$slug" }

    @Test
    fun `reads the listed gig fully, with the style before aus as its genre`() {
        val gig = parse("wienerblut-overview.html").single()

        gig.title shouldBe "BILLY ZACH"
        gig.eventType shouldBe EventType.CONCERT.name
        gig.typeIsFallback shouldBe true
        gig.eventDate shouldBe LocalDate.of(2026, 11, 21)
        gig.startTime shouldBe LocalTime.of(21, 0)
        gig.endTime shouldBe LocalTime.of(22, 0)
        gig.description shouldBe "Post-Punk aus Hamburg"
        gig.genre shouldBe "Post-Punk"
        gig.sourceUrl shouldBe "https://www.wienerblut.org/event-details/billy-zach"
        gig.sourceId shouldBe "wiener_blut:billy-zach"
        gig.imageUrl!! shouldEndWith "e9edd4aefd1e46bbba293e9515de2c7f~mv2.png"
        gig.artists.map { it.name } shouldContainExactly listOf("BILLY ZACH")
    }

    @Test
    fun `marks a cancelled gig and drops a hidden end`() {
        val cancelled = edgeCase("die-kerzen")

        cancelled.status shouldBe EventStatus.CANCELLED.name
        cancelled.genre shouldBe "Indie-Pop"
        cancelled.endTime.shouldBeNull()
    }

    @Test
    fun `types a quiz by its title and reads no genre without a blurb`() {
        val quiz = edgeCase("kneipenquiz")

        quiz.eventType shouldBe EventType.QUIZ.name
        quiz.genre.shouldBeNull()
        quiz.artists.shouldBeEmpty()
    }

    @Test
    fun `skips an event without a slug or with no date set`() {
        edgeCases.map { it.sourceId } shouldContainExactly listOf("wiener_blut:billy-zach", "wiener_blut:die-kerzen", "wiener_blut:kneipenquiz")
    }

    @Test
    fun `returns nothing for a page without the Wix payload`() {
        WienerBlutOverviewPageScraper().scrape(Jsoup.parse("<html><body></body></html>", baseUrl), baseUrl).shouldBeEmpty()
    }
}
