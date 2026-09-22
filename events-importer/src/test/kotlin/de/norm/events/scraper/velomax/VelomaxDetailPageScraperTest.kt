package de.norm.events.scraper.velomax

import de.norm.events.scraper.ScrapedEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for [VelomaxDetailPageScraper].
 *
 * Uses real detail-page snapshots from two of the three hall domains. The point of these tests is
 * that everything comes from the page's schema.org Microdata rather than its rendered markup — and
 * that it stays scoped to the single-event block, since each page also renders a teaser strip
 * carrying *other* events' dates, titles and images.
 */
class VelomaxDetailPageScraperTest {
    private val scraper = VelomaxDetailPageScraper()

    private fun scrape(
        fixture: String,
        sourceUrl: String,
        hall: VelomaxHall
    ): ScrapedEvent? {
        val html =
            javaClass.classLoader
                .getResourceAsStream("scraper/velomax/$fixture")!!
                .bufferedReader()
                .readText()
        return scraper.scrape(Jsoup.parse(html, sourceUrl), sourceUrl, hall)
    }

    @Test
    fun `reads every field from the schema-org Microdata`() {
        val joji =
            scrape(
                "velomax-detail-concert.html",
                "https://www.velodrom.de/events/event/joji-velodrom-2026-08-29",
                VelomaxHall.VELODROM
            )
        joji.shouldNotBeNull()
        joji.title shouldBe "Joji"
        // The `<br>` splits the block: the tour name is the subtitle, the line below it a billing (#1680).
        joji.subtitle shouldBe "SOLARIS"
        joji.artists.map { it.name to it.role } shouldContainExactly listOf("Joji" to "HEADLINER", "Tommy Richmann" to "SUPPORT")
        // startDate / doorTime carry machine-readable `datetime` attributes; the rendered text is
        // only "20:00 Uhr", with no date at all.
        joji.eventDate shouldBe LocalDate.of(2026, 8, 29)
        joji.startTime shouldBe LocalTime.of(20, 0)
        joji.doorsTime shouldBe LocalTime.of(18, 0)
        joji.status shouldBe "SCHEDULED"
        joji.sourceId shouldBe "velodrom:joji-velodrom-2026-08-29"
        joji.promoters shouldContainExactly listOf("Live Nation GmbH")
        joji.description.shouldNotBeNull() shouldStartWith "Der gefeierte Sänger und Produzent Joji"
        joji.ticketUrl.shouldNotBeNull() shouldStartWith "https://www.eventim.de/"
        joji.imageUrl.shouldNotBeNull() shouldStartWith "https://www.velodrom.de/fileadmin/"
    }

    @Test
    fun `does not take the teaser strip's data for the event's own`() {
        // The page's "Wir empfehlen" strip leads with Evanescence on 25 September; reading the page
        // unscoped would take that date, title or image.
        val joji =
            scrape(
                "velomax-detail-concert.html",
                "https://www.velodrom.de/events/event/joji-velodrom-2026-08-29",
                VelomaxHall.VELODROM
            )
        joji.shouldNotBeNull()
        joji.title shouldBe "Joji"
        joji.eventDate shouldBe LocalDate.of(2026, 8, 29)
        joji.imageUrl.shouldNotBeNull().contains("joji", ignoreCase = true) shouldBe true
    }

    @Test
    fun `parses a page from the Max-Schmeling-Halle domain`() {
        val show =
            scrape(
                "velomax-detail-show.html",
                "https://www.max-schmeling-halle.de/events/event/die-nervigen-max-schmeling-halle-2026-10-23",
                VelomaxHall.MAX_SCHMELING_HALLE
            )
        show.shouldNotBeNull()
        show.eventDate shouldBe LocalDate.of(2026, 10, 23)
        show.sourceId shouldBe "max_schmeling_halle:die-nervigen-max-schmeling-halle-2026-10-23"
        show.startTime.shouldNotBeNull()
    }

    @Test
    fun `parses a page from the UFO domain`() {
        val reezy =
            scrape(
                "velomax-detail-ufo.html",
                "https://www.ufo-velodrom.de/events/event/reezy-ufo-2026-09-12",
                VelomaxHall.UFO_IM_VELODROM
            )
        reezy.shouldNotBeNull()
        reezy.eventDate shouldBe LocalDate.of(2026, 9, 12)
        reezy.sourceId shouldBe "ufo_im_velodrom:reezy-ufo-2026-09-12"
        reezy.imageUrl.shouldNotBeNull() shouldStartWith "https://www.ufo-velodrom.de/"
    }

    @Test
    fun `returns null for a page without a schema-org Event block`() {
        val url = "https://www.velodrom.de/events/event/joji-velodrom-2026-08-29"
        scraper.scrape(Jsoup.parse("<html><body><main></main></body></html>", url), url, VelomaxHall.VELODROM).shouldBeNull()
    }

    @Test
    fun `maps the schema-org status vocabulary rather than German prose`() {
        val url = "https://www.velodrom.de/events/event/x-2026-01-01"

        fun statusOf(status: String): String? {
            val html =
                """
                <html><body><div itemtype="https://schema.org/Event" itemscope>
                  <h1 itemprop="name">Act</h1>
                  <meta itemprop="eventStatus" content="https://schema.org/$status">
                  <time itemprop="startDate" datetime="2026-01-01 20:00:00">20:00 Uhr</time>
                </div></body></html>
                """.trimIndent()
            return scraper.scrape(Jsoup.parse(html, url), url, VelomaxHall.VELODROM)?.status
        }
        statusOf("EventScheduled") shouldBe "SCHEDULED"
        statusOf("EventCancelled") shouldBe "CANCELLED"
        statusOf("EventPostponed") shouldBe "POSTPONED"
        statusOf("EventRescheduled") shouldBe "POSTPONED"
    }
}
