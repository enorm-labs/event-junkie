package de.norm.events.scraper.surprise

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Unit tests for [SurpriseOverviewPageScraper], against a saved copy of the real events page. */
class SurpriseOverviewPageScraperTest {
    private val scraper = SurpriseOverviewPageScraper()
    private val baseUrl = "https://surprise-berlin.de/events-programs.php"

    private val events: List<ScrapedEvent> by lazy {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/surprise/surprise-overview.html")!!
                .bufferedReader()
                .readText()
        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl)
    }

    private fun night(date: String) = events.single { it.eventDate == LocalDate.parse(date) }

    @Test
    fun `scrape reads every night of the weekend`() {
        events shouldHaveSize 2
        events.map { it.eventDate } shouldContainExactly listOf(LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-17"))
    }

    @Test
    fun `scrape reads one night in full`() {
        val night = night("2026-10-17")

        night.title shouldBe "We Love Afrobeats"
        night.eventType shouldBe "PARTY"
        night.startTime.shouldBeNull()
        night.sourceUrl shouldBe baseUrl
        night.sourceId shouldBe "surprise:2026-10-17"
        night.imageUrl shouldBe "https://surprise-berlin.de/images/events/sat1710.jpg"
        night.genre shouldBe "Dancehall, Salsa, Hip Hop, Afrobeat, RnB"
        night.artists.map { it.name } shouldContainExactly listOf("DJ Rolex", "DJ Judex")
        night.artists.map { it.role }.distinct() shouldContainExactly listOf("DJ")
    }

    @Test
    fun `the ladies' door price is the box-office price and both gates go in the note`() {
        val night = night("2026-10-17")

        night.priceBoxOffice shouldBe BigDecimal("15")
        night.pricePresale.shouldBeNull()
        night.priceNote shouldBe "Ladies: 15,-€ Entry (incl. 5,-€ for Drinks) · Gentlemen: 25,-€ Entry (incl. 10,-€ for Drinks)"
        night("2026-10-16").priceBoxOffice shouldBe BigDecimal("10")
    }

    @Test
    fun `the description keeps its line break and leaves the prices out`() {
        val description = night("2026-10-17").description!!.lines()

        description shouldHaveSize 2
        description.first() shouldStartWith "Afrobeats, Hip Hop, Party Berlin"
        description.last() shouldEndWith "the hottest sounds in:"
        night("2026-10-16").description!! shouldStartWith "Bring your friends"
    }

    @Test
    fun `the title loses its trailing dash`() {
        night("2026-10-16").title shouldBe "Afro + Black Party"
    }

    @Test
    fun `a night without a parseable date is skipped`() {
        val html =
            """
            <div class="every-event-displayed">
              <div class="events-date-displayed"><h2>Party -</h2>
                <div class="events-displayed-show-day"><h3>31</h3></div>
                <div class="events-displayed-month-yrs"><h6>Februar</h6><p>2026</p></div>
              </div>
            </div>
            """.trimIndent()

        scraper.scrape(Jsoup.parse(html, baseUrl), baseUrl).shouldBeEmpty()
    }
}
