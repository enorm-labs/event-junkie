package de.norm.events.scraper.gaertenderwelt

import de.norm.events.event.EventType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [GaertenDerWeltOverviewPageScraper], parsing saved snapshots of the park's
 * `/events/veranstaltungen/` listing (captured 6 August 2026).
 *
 * The first-page snapshot is the breadth filter's regression guard: of its five rows, four are
 * guided tours and workshops and only the games night is programme.
 */
class GaertenDerWeltOverviewPageScraperTest {
    private val scraper = GaertenDerWeltOverviewPageScraper()

    private fun fixture(name: String): Document =
        Jsoup.parse(
            javaClass.classLoader
                .getResourceAsStream("scraper/gaertenderwelt/$name")!!
                .bufferedReader()
                .readText(),
            LISTING_URL
        )

    @Test
    fun `keeps only the staged programme on a listing page`() {
        val events = scraper.scrape(fixture("gaertenderwelt-overview.html"), LISTING_URL)

        // Five rows: two Führungen, two Workshops, and the uncategorised games night.
        events shouldHaveSize 1
        events.single().title shouldBe "Spieleabend"
    }

    @Test
    fun `parses a concert row in full`() {
        val events = scraper.scrape(fixture("gaertenderwelt-overview-page2.html"), LISTING_URL)
        val concert = events.single { it.title == "Agnes Obel" }

        concert.eventType shouldBe EventType.CONCERT.name
        concert.eventDate shouldBe LocalDate.of(2026, 8, 15)
        concert.startTime shouldBe LocalTime.of(19, 0)
        concert.sourceUrl shouldBe "$LISTING_URL/detail/2026-08-15_1900/agnes-obel/"
        concert.sourceId shouldBe "gaerten_der_welt:2026-08-15_1900/agnes-obel"
        concert.imageUrl shouldBe
            "https://www.gaertenderwelt.de/fileadmin/_processed_/f/0/csm_gdw_events_AgnesObel_2025__Alex_Bruel_Flagstad___1__a0332c56eb.png"
        concert.ticketUrl?.startsWith("https://www.eventim.de/event/agnes-obel-gaerten-der-welt-21055531/") shouldBe true
        concert.soldOut shouldBe false
    }

    @Test
    fun `types the open-air cinema from the park's category rather than the title`() {
        val events = scraper.scrape(fixture("gaertenderwelt-overview-page2.html"), LISTING_URL)
        val screening = events.single { it.title.startsWith("Wanderkino") }

        screening.eventType shouldBe EventType.SCREENING.name
        screening.eventDate shouldBe LocalDate.of(2026, 8, 20)
        screening.startTime shouldBe LocalTime.of(21, 0)
        // The park sells no ticket for this one.
        screening.ticketUrl.shouldBeNull()
    }

    @Test
    fun `infers a type from the title when the park files a row under no category`() {
        val gamesNight = scraper.scrape(fixture("gaertenderwelt-overview.html"), LISTING_URL).single()

        gamesNight.eventType shouldBe EventType.OTHER.name
        gamesNight.eventDate shouldBe LocalDate.of(2026, 8, 14)
        gamesNight.startTime shouldBe LocalTime.of(17, 30)
        // "17.30 – 21 Uhr": the stamp gives the start, the cell the end (#2970).
        gamesNight.endDate shouldBe LocalDate.of(2026, 8, 14)
        gamesNight.endTime shouldBe LocalTime.of(21, 0)
        gamesNight.subtitle shouldBe "Jeden 2. Freitag im Monat"
    }

    @Test
    fun `keeps no end for a row whose time cell is a single clock`() {
        val screening = scraper.scrape(fixture("gaertenderwelt-overview-page2.html"), LISTING_URL).single { it.title.startsWith("Wanderkino") }

        // "21.00 Uhr".
        screening.endDate.shouldBeNull()
        screening.endTime.shouldBeNull()
    }

    @Test
    fun `rolls an end before the start to the next morning`() {
        val lateNight = scraper.scrape(row(stamp = "2026-12-31_2200", time = "22 – 2 Uhr"), LISTING_URL).single()

        lateNight.eventDate shouldBe LocalDate.of(2026, 12, 31)
        lateNight.endDate shouldBe LocalDate.of(2027, 1, 1)
        lateNight.endTime shouldBe LocalTime.of(2, 0)
    }

    private fun row(
        stamp: String,
        time: String
    ): Document =
        Jsoup.parse(
            """
            <html><body><div class="tx-events2"><div class="list">
              <div class="eventWrapper">
                <div class="category">Konzerte</div>
                <div class="eventInner">
                  <h3 class="media-heading"><a href="/events/veranstaltungen/detail/$stamp/silvester/">Silvester</a></h3>
                  <div class="time">$time</div>
                </div>
              </div>
            </div></div></body></html>
            """.trimIndent(),
            LISTING_URL
        )

    @Test
    fun `stores the row teaser as the subtitle`() {
        val events = scraper.scrape(fixture("gaertenderwelt-overview-last.html"), LISTING_URL)

        events.single().subtitle shouldBe "Treibende Beats, legendäre Hits und euphorische Partystimmung!"
    }

    @Test
    fun `follows the paginator's next link`() {
        val page1 = fixture("gaertenderwelt-overview.html")

        scraper.nextPageUrl(page1, LISTING_URL) shouldBe "https://www.gaertenderwelt.de/events/veranstaltungen/page2/"
    }

    @Test
    fun `reports no next page on the last one`() {
        val last = fixture("gaertenderwelt-overview-last.html")

        scraper.nextPageUrl(last, LISTING_URL).shouldBeNull()
        last.select(".paginationWrapper").isEmpty() shouldBe false
    }

    @Test
    fun `returns no events for a listing with no rows`() {
        val empty = Jsoup.parse("""<html><body><div class="tx-events2"><div class="list"></div></div></body></html>""", LISTING_URL)

        scraper.scrape(empty, LISTING_URL) shouldHaveSize 0
        scraper.nextPageUrl(empty, LISTING_URL).shouldBeNull()
    }

    @Test
    fun `skips a row whose detail link carries no date stamp`() {
        val unstamped =
            Jsoup.parse(
                """
                <html><body><div class="tx-events2"><div class="list">
                  <div class="eventWrapper">
                    <div class="category">Konzerte</div>
                    <div class="eventInner">
                      <h3 class="media-heading"><a href="/events/veranstaltungen/detail/some-band/">Some Band</a></h3>
                    </div>
                  </div>
                </div></div></body></html>
                """.trimIndent(),
                LISTING_URL
            )

        scraper.scrape(unstamped, LISTING_URL) shouldHaveSize 0
    }

    @Test
    fun `stores an exhibition listed with its whole run as one event from the first day to the last`() {
        // Captured 4 October 2026: the row's href stamps the next open day, its date cell the run.
        val run = scraper.scrape(fixture("gaertenderwelt-overview-runs.html"), LISTING_URL).single { it.eventType == EventType.EXHIBITION.name }

        run.title shouldBe "Zwischen Himmel und Erde: Ausstellung"
        run.eventDate shouldBe LocalDate.of(2026, 9, 1)
        run.endDate shouldBe LocalDate.of(2026, 11, 1)
        run.startTime shouldBe LocalTime.of(9, 0)
        run.endTime.shouldBeNull()
        run.sourceId shouldBe "gaerten_der_welt:zwischen-himmel-und-erde-ausstellung"
        run.sourceUrl shouldBe "$LISTING_URL/detail/2026-10-04_0900/zwischen-himmel-und-erde-ausstellung/"
    }

    @Test
    fun `keeps a one-day row on the same page dated and keyed by its stamp`() {
        val gamesNight = scraper.scrape(fixture("gaertenderwelt-overview-runs.html"), LISTING_URL).single { it.title == "Spieleabend" }

        gamesNight.eventDate shouldBe LocalDate.of(2026, 10, 9)
        gamesNight.endDate shouldBe LocalDate.of(2026, 10, 9)
        gamesNight.sourceId shouldBe "gaerten_der_welt:2026-10-09_1730/spieleabend-1-1"
    }

    @Test
    fun `keeps the stamped night of a show listed over several nights`() {
        val droneShow = scraper.scrape(fixture("gaertenderwelt-overview-runs-page2.html"), LISTING_URL).single { it.title.startsWith("Drone Art Show") }

        // "22.10.2026 - 24.10.2026" under "Konzerte": each night is its own show.
        droneShow.eventType shouldBe EventType.CONCERT.name
        droneShow.eventDate shouldBe LocalDate.of(2026, 10, 22)
        droneShow.endDate shouldBe LocalDate.of(2026, 10, 22)
        droneShow.endTime shouldBe LocalTime.of(21, 0)
        droneShow.sourceId shouldBe "gaerten_der_welt:2026-10-22_2000/drone-art-show-harry-potter"
    }

    private companion object {
        private const val LISTING_URL = "https://www.gaertenderwelt.de/events/veranstaltungen"
    }
}
